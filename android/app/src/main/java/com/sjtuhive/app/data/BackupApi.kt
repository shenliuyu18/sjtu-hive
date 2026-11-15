package com.sjtuhive.app.data

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份与恢复：把这台机器上的应用数据打成一个 zip（可选口令加密）。
 *
 * 与桌面端的备份格式不通用——两端数据模型不同（桌面是 SQLite，安卓是
 * SharedPreferences + JSON 快照），这里只保证安卓内部「导出→恢复」闭环。
 *
 * zip 结构：
 *   manifest.json            版本、时间、内容清单
 *   shared_prefs/<name>.xml  全部偏好（主题、账号、缓存开关等）
 *   files/hive_ui_cache.json 页面快照
 *   files/todo.json          本地待办
 *   files/cache/…            HiveCache 接口缓存
 *   secrets.json             邮箱密码等（已解密，受口令保护）
 *
 * 加密格式：magic "HIVEA1" + salt(16) + nonce(12) + AES-GCM(body)
 */
object BackupApi {
    private val MAGIC = "HIVEA1".toByteArray(Charsets.US_ASCII)
    private const val APP_TAG = "SJTU Hive Android"

    data class Result(val bytes: Int, val encrypted: Boolean)

    /** 生成备份字节（不落盘，调用方决定写入 SAF 或 WebDAV）。 */
    fun pack(context: Context, passphrase: String?): ByteArray {
        val plain = ByteArrayOutputStream()
        ZipOutputStream(plain).use { zip ->
            val manifest = JSONObject().apply {
                put("app", APP_TAG)
                put("createdAt", System.currentTimeMillis())
                put("format", 1)
                put("encrypted", !passphrase.isNullOrBlank())
            }
            addBytes(zip, "manifest.json", manifest.toString(2).toByteArray())

            // 全部 SharedPreferences（主题、邮箱账号、Canvas token、缓存开关等）
            val prefsDir = File(context.applicationContext.filesDir.parentFile, "shared_prefs")
            prefsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { file ->
                addBytes(zip, "shared_prefs/${file.name}", file.readBytes())
            }

            // 应用数据文件
            listOf("hive_ui_cache.json", "todo.json").forEach { name ->
                val file = File(context.filesDir, name)
                if (file.exists()) addBytes(zip, "files/$name", file.readBytes())
            }
            val cacheDir = File(context.filesDir, "cache")
            if (cacheDir.exists()) {
                cacheDir.walkBottomUp().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(cacheDir).path.replace(File.separatorChar, '/')
                    addBytes(zip, "files/cache/$rel", file.readBytes())
                }
            }

            // 凭据：邮箱密码（Keystore 加密态不可迁移，解密后放进来，靠口令保护）
            val secrets = JSONObject()
            runCatching {
                val account = MailApi(context).account()
                if (account != null) {
                    secrets.put(
                        "mail",
                        JSONObject().apply {
                            put("address", account.address)
                            put("password", account.password)
                            put("imapHost", account.imapHost)
                            put("imapPort", account.imapPort)
                            put("smtpHost", account.smtpHost)
                            put("smtpPort", account.smtpPort)
                        },
                    )
                }
            }
            addBytes(zip, "secrets.json", secrets.toString(2).toByteArray())
        }
        val body = plain.toByteArray()
        return if (passphrase.isNullOrBlank()) body else encrypt(body, passphrase)
    }

    /** 恢复备份：解包写回偏好与文件。返回恢复的条目数；需要重启应用后偏好才完全生效。 */
    fun restore(context: Context, data: ByteArray, passphrase: String?): Int {
        val body = if (isEncrypted(data)) {
            require(!passphrase.isNullOrEmpty()) { "这是加密备份，请输入口令" }
            decrypt(data, passphrase)
        } else {
            data
        }
        var restored = 0
        val prefsDir = File(context.applicationContext.filesDir.parentFile, "shared_prefs").apply { mkdirs() }
        ZipInputStream(ByteArrayInputStream(body)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val bytes = zip.readBytes()
                when {
                    name.startsWith("shared_prefs/") && name.endsWith(".xml") -> {
                        File(prefsDir, name.removePrefix("shared_prefs/")).writeBytes(bytes)
                        restored++
                    }
                    name.startsWith("files/cache/") -> {
                        val rel = name.removePrefix("files/cache/")
                        val target = File(File(context.filesDir, "cache"), rel)
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                        restored++
                    }
                    name.startsWith("files/") && !name.endsWith("manifest.json") -> {
                        File(context.filesDir, name.removePrefix("files/")).writeBytes(bytes)
                        restored++
                    }
                    name == "secrets.json" -> {
                        val secrets = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                        secrets?.optJSONObject("mail")?.let { mail ->
                            val address = mail.optString("address")
                            val password = mail.optString("password")
                            if (address.isNotBlank() && password.isNotBlank()) {
                                MailApi(context).save(
                                    MailAccount(
                                        address = address,
                                        password = password,
                                        imapHost = mail.optString("imapHost", "mail.sjtu.edu.cn"),
                                        imapPort = mail.optInt("imapPort", 993),
                                        smtpHost = mail.optString("smtpHost", "mail.sjtu.edu.cn"),
                                        smtpPort = mail.optInt("smtpPort", 465),
                                    ),
                                )
                                restored++
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return restored
    }

    fun isEncrypted(data: ByteArray): Boolean =
        data.size > MAGIC.size + 28 && MAGIC.indices.all { data[it] == MAGIC[it] }

    private fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val sealed = cipher.doFinal(plain)
        return ByteArrayOutputStream().apply {
            write(MAGIC)
            write(salt)
            write(cipher.iv)
            write(sealed)
        }.toByteArray()
    }

    private fun decrypt(data: ByteArray, passphrase: String): ByteArray {
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = data.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val body = data.copyOfRange(MAGIC.size + 28, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(deriveKey(passphrase, salt), "AES"),
            GCMParameterSpec(128, iv),
        )
        return cipher.doFinal(body)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun addBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
}

/**
 * WebDAV：备份上传/列表/下载。用 PUT / PROPFIND / GET 三个方法，
 * 兼容坚果云、Nextcloud 等常见实现。
 */
class WebDavClient(context: Context) {
    private val prefs = context.getSharedPreferences("hive_webdav", Context.MODE_PRIVATE)
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class Config(val url: String, val user: String, val password: String, val dir: String)

    fun config(): Config? {
        val url = prefs.getString("url", "").orEmpty()
        if (url.isBlank()) return null
        return Config(
            url = url.trimEnd('/'),
            user = prefs.getString("user", "").orEmpty(),
            password = prefs.getString("password", "").orEmpty(),
            dir = prefs.getString("dir", "SJTU-Hive").orEmpty().trim('/'),
        )
    }

    fun save(config: Config) {
        prefs.edit()
            .putString("url", config.url.trim().trimEnd('/'))
            .putString("user", config.user.trim())
            .putString("password", config.password)
            .putString("dir", config.dir.trim('/'))
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun authHeader(config: Config): String =
        okhttp3.Credentials.basic(config.user, config.password)

    suspend fun upload(config: Config, name: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url("${config.url}/${config.dir}/$name")
            .put(data.toRequestBody("application/octet-stream".toMediaType()))
            .header("Authorization", authHeader(config))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("上传失败：HTTP ${response.code}")
        }
        true
    }

    /** 列出目录内的备份文件（name -> 修改时间）。 */
    suspend fun list(config: Config): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified/></d:prop></d:propfind>"""
            .toRequestBody("application/xml".toMediaType())
        val request = okhttp3.Request.Builder()
            .url("${config.url}/${config.dir}/")
            .method("PROPFIND", body)
            .header("Depth", "1")
            .header("Authorization", authHeader(config))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("读取目录失败：HTTP ${response.code}")
            val xml = response.body?.string().orEmpty()
            Regex("<d:href>([^<]+)</d:href>|<href>([^<]+)</href>")
                .findAll(xml)
                .mapNotNull { (it.groupValues[1].ifBlank { it.groupValues[2] }) }
                .filter { it.contains('.') }
                .map { href ->
                    val name = href.trimEnd('/').substringAfterLast('/')
                    name to ""
                }
                .filter { (name, _) -> name.isNotBlank() }
                .toList()
        }
    }

    suspend fun download(config: Config, name: String): ByteArray = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url("${config.url}/${config.dir}/$name")
            .header("Authorization", authHeader(config))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            response.body?.bytes() ?: error("下载内容为空")
        }
    }
}
