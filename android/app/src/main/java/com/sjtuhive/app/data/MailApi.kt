package com.sjtuhive.app.data

import android.content.Context
import android.text.Html
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress

data class MailAccount(
    val address: String,
    val password: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
)

data class MailSummary(
    val index: Int,
    val subject: String,
    val from: String,
    val date: String,
    val seen: Boolean,
)

data class MailFolder(val name: String, val label: String)

data class MailAttachment(val name: String, val size: Long)

/** 邮箱：IMAP over SSL 收信。账号手动配置，密码用 Android Keystore 加密后落盘。 */
class MailApi(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun account(): MailAccount? {
        val address = prefs.getString("address", "").orEmpty()
        if (address.isBlank()) return null
        val password = SecureStore.decrypt(context, prefs.getString("password", "").orEmpty())
        if (password.isBlank()) return null
        return MailAccount(
            address = address,
            password = password,
            imapHost = prefs.getString("imap_host", "mail.sjtu.edu.cn").orEmpty(),
            imapPort = prefs.getInt("imap_port", 993),
            smtpHost = prefs.getString("smtp_host", "mail.sjtu.edu.cn").orEmpty(),
            smtpPort = prefs.getInt("smtp_port", 465),
        )
    }

    fun save(account: MailAccount) {
        prefs.edit()
            .putString("address", account.address)
            .putString("password", SecureStore.encrypt(context, account.password))
            .putString("imap_host", account.imapHost)
            .putInt("imap_port", account.imapPort)
            .putString("smtp_host", account.smtpHost)
            .putInt("smtp_port", account.smtpPort)
            .apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    suspend fun folders(): List<MailFolder> = withContext(Dispatchers.IO) {
        val account = account() ?: throw CampusAuthException("请先配置邮箱账号")
        withStore(account) { store ->
            val folders = store.defaultFolder.list("*")
            val result = mutableListOf(MailFolder("INBOX", "收件箱"))
            folders.forEach { folder ->
                if (folder.name == "INBOX") return@forEach
                result += MailFolder(folder.name, folderLabel(folder.name))
            }
            result
        }
    }

    suspend fun inbox(folderName: String = "INBOX", limit: Int = 30): List<MailSummary> =
        withContext(Dispatchers.IO) {
            val account = account() ?: throw CampusAuthException("请先配置邮箱账号")
            withFolder(account, folderName) { folder ->
                val count = folder.messageCount
                val start = (count - limit + 1).coerceAtLeast(1)
                val result = mutableListOf<MailSummary>()
                for (number in count downTo start) {
                    val message = folder.getMessage(number) ?: continue
                    val from = (message.from?.firstOrNull() as? InternetAddress)?.let {
                        it.personal?.takeIf { name -> name.isNotBlank() } ?: it.address
                    }.orEmpty()
                    result += MailSummary(
                        index = number,
                        subject = message.subject.orEmpty().ifBlank { "（无主题）" },
                        from = from,
                        date = formatDate(message.sentDate ?: message.receivedDate),
                        seen = message.flags?.contains(javax.mail.Flags.Flag.SEEN) == true,
                    )
                }
                result
            }
        }

    suspend fun body(folderName: String, index: Int): String = withContext(Dispatchers.IO) {
        val account = account() ?: throw CampusAuthException("请先配置邮箱账号")
        withFolder(account, folderName) { folder ->
            val message = folder.getMessage(index) ?: return@withFolder ""
            extractText(message).ifBlank { "（没有可显示的正文）" }
        }
    }

    suspend fun attachments(folderName: String, index: Int): List<MailAttachment> =
        withContext(Dispatchers.IO) {
            val account = account() ?: throw CampusAuthException("请先配置邮箱账号")
            withFolder(account, folderName) { folder ->
                val message = folder.getMessage(index) ?: return@withFolder emptyList()
                val result = mutableListOf<MailAttachment>()
                collectAttachments(message, result)
                result
            }
        }

    /** 把指定附件写入缓存，供内置预览打开。 */
    suspend fun downloadAttachment(folderName: String, index: Int, fileName: String): java.io.File =
        withContext(Dispatchers.IO) {
            val account = account() ?: throw CampusAuthException("请先配置邮箱账号")
            withFolder(account, folderName) { folder ->
                val message = folder.getMessage(index) ?: error("邮件不存在")
                val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "attachment.bin" }
                val target = java.io.File(java.io.File(context.cacheDir, "preview"), safe)
                if (!writeAttachment(message, fileName, target)) error("附件读取失败")
                target
            }
        }

    private fun writeAttachment(part: Part, name: String, target: java.io.File): Boolean {
        if (runCatching { part.fileName }.getOrNull() == name) {
            val input = runCatching { part.inputStream }.getOrNull() ?: return false
            target.parentFile?.mkdirs()
            input.use { stream ->
                target.outputStream().use { output -> stream.copyTo(output) }
            }
            return true
        }
        if (part.isMimeType("multipart/*")) {
            val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return false
            for (child in 0 until multipart.count) {
                if (writeAttachment(multipart.getBodyPart(child), name, target)) return true
            }
        }
        return false
    }

    private fun collectAttachments(part: Part, result: MutableList<MailAttachment>) {        val name = runCatching { part.fileName }.getOrNull()
        val disposition = runCatching { part.disposition }.getOrNull()
        if (!name.isNullOrBlank() && disposition.equals(Part.ATTACHMENT, ignoreCase = true)) {
            result += MailAttachment(name, runCatching { part.size.toLong() }.getOrDefault(0L))
        }
        if (part.isMimeType("multipart/*")) {
            val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return
            for (index in 0 until multipart.count) {
                collectAttachments(multipart.getBodyPart(index), result)
            }
        }
    }

    suspend fun send(to: String, subject: String, body: String) = withContext(Dispatchers.IO) {
        val account = account() ?: throw CampusAuthException("请先配置邮箱账号")
        if (to.isBlank()) throw CampusAuthException("请填写收件人")
        val properties = Properties().apply {
            put("mail.smtp.host", account.smtpHost)
            put("mail.smtp.port", account.smtpPort.toString())
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "25000")
        }
        val session = Session.getInstance(properties, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication(): javax.mail.PasswordAuthentication =
                javax.mail.PasswordAuthentication(account.address, account.password)
        })
        val message = javax.mail.internet.MimeMessage(session).apply {
            setFrom(javax.mail.internet.InternetAddress(account.address))
            setRecipients(
                javax.mail.Message.RecipientType.TO,
                javax.mail.internet.InternetAddress.parse(to),
            )
            setSubject(subject)
            setText(body)
        }
        try {
            javax.mail.Transport.send(message)
        } catch (error: javax.mail.AuthenticationFailedException) {
            throw CampusAuthException("邮箱账号或密码不正确")
        } catch (error: Exception) {
            throw IllegalStateException(error.message ?: "发送失败", error)
        }
    }

    private fun <T> withStore(account: MailAccount, block: (javax.mail.Store) -> T): T {
        val properties = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "25000")
        }
        val session = Session.getInstance(properties)
        val store = session.getStore("imaps")
        try {
            store.connect(account.imapHost, account.imapPort, account.address, account.password)
            return block(store)
        } catch (error: javax.mail.AuthenticationFailedException) {
            throw CampusAuthException("邮箱账号或密码不正确")
        } catch (error: Exception) {
            throw IllegalStateException(error.message ?: "无法连接邮箱服务器", error)
        } finally {
            runCatching { store.close() }
        }
    }

    private fun <T> withFolder(account: MailAccount, folderName: String, block: (Folder) -> T): T =
        withStore(account) { store ->
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            try {
                block(folder)
            } finally {
                runCatching { folder.close(false) }
            }
        }

    private fun folderLabel(name: String): String = when (name.lowercase()) {
        "sent", "sent items", "sent messages", "已发送" -> "已发送"
        "drafts", "草稿箱" -> "草稿"
        "trash", "deleted", "deleted items", "已删除" -> "已删除"
        "junk", "spam", "junk e-mail", "垃圾邮件" -> "垃圾邮件"
        "archive", "archives", "归档" -> "归档"
        else -> name.substringAfterLast('/')
    }

    private fun extractText(part: Part): String {
        if (part.isMimeType("text/plain")) {
            return part.content?.toString().orEmpty()
        }
        if (part.isMimeType("text/html")) {
            val html = part.content?.toString().orEmpty()
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        }
        if (part.isMimeType("multipart/*")) {
            val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return ""
            val plain = StringBuilder()
            val other = StringBuilder()
            for (index in 0 until multipart.count) {
                val child = multipart.getBodyPart(index)
                val text = extractText(child)
                if (text.isBlank()) continue
                if (child.isMimeType("text/plain")) plain.append(text) else other.append(text)
            }
            return if (plain.isNotEmpty()) plain.toString() else other.toString()
        }
        return ""
    }

    private fun formatDate(date: Date?): String {
        if (date == null) return ""
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date)
    }

    companion object {
        private const val PREFS = "hive_mail_account"
    }
}

/** 用 Android Keystore 的 AES/GCM 加密邮箱密码，避免明文落盘。 */
object SecureStore {
    private const val ALIAS = "hive_mail_secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(context: Context, value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun decrypt(context: Context, value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            val data = Base64.decode(value, Base64.NO_WRAP)
            val iv = data.copyOfRange(0, 12)
            val encrypted = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
