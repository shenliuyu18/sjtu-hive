package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class CloudFile(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: String,
)

data class CloudListing(
    val path: String,
    val files: List<CloudFile>,
)

/**
 * 交大云盘（pan.sjtu.edu.cn）。用 jAccount SSO 换 user_token，
 * 再用 user_token 换每个空间的 access_token 读目录 / 取预签名文件地址。
 */
class CloudPanApi(private val context: Context) {

    private val prefs = context.getSharedPreferences("hive_cloudpan", Context.MODE_PRIVATE)
    private var session: PanSession? = loadSession()
    private var token: PanToken? = null

    suspend fun listing(path: String): CloudListing = withContext(Dispatchers.IO) {        val state = ensureSession()
        val access = accessToken(state)
        val clean = path.trim('/')
        val endpoint = "$ORIGIN/api/v1/directory/${enc(access.libraryId)}/${enc(access.spaceId)}/" +
            "${escapePath(clean)}?access_token=${enc(access.token)}&page=1&page_size=200" +
            "&order_by=name&order_by_type=asc"
        val result = HiveHttp.raw(context, endpoint, "$ORIGIN/")
        if (result.code == 401 || result.code == 403) {
            token = null
            throw CampusAuthException("云盘授权已失效，请重新授权")
        }
        if (result.code !in 200..299) error("交大云盘返回 HTTP ${result.code}")
        val payload = JSONObject(result.body)
        val contents = payload.optJSONArray("contents") ?: return@withContext CloudListing(clean, emptyList())
        val files = mutableListOf<CloudFile>()
        for (index in 0 until contents.length()) {
            val item = contents.optJSONObject(index) ?: continue
            val name = item.text("name").trim()
            if (name.isEmpty() || name == ".") continue
            val full = if (clean.isEmpty()) name else "$clean/$name"
            files += CloudFile(
                name = name,
                path = full,
                directory = item.text("type").equals("dir", ignoreCase = true),
                size = longValue(item, "size"),
                modifiedAt = item.text("modificationTime"),
            )
        }
        CloudListing(clean, files.sortedWith(compareBy({ !it.directory }, { it.name.lowercase() })))
    }

    /** 取文件的预签名地址（COS），可直接交给系统下载器或浏览器打开。 */
    suspend fun fileUrl(path: String, download: Boolean): String = withContext(Dispatchers.IO) {
        val state = ensureSession()
        val access = accessToken(state)
        val clean = path.trim('/')
        val disposition = if (download) "attachment" else "inline"
        val endpoint = "$ORIGIN/api/v1/file/${enc(access.libraryId)}/${enc(access.spaceId)}/" +
            "${escapePath(clean)}?info&access_token=${enc(access.token)}&content_disposition=$disposition"
        val result = HiveHttp.raw(context, endpoint, "$ORIGIN/")
        if (result.code !in 200..299) error("交大云盘返回 HTTP ${result.code}")
        val payload = JSONObject(result.body)
        val url = payload.text("cosUrl")
        if (url.isEmpty()) error("交大云盘没有返回文件地址")
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        if (!host.endsWith(".sjtu.edu.cn") && !host.endsWith(".cofile.net")) {
            error("文件地址不在交大域名下")
        }
        url
    }

    fun accountName(): String = session?.displayName.orEmpty()

    fun reset() {
        session = null
        token = null
        prefs.edit().remove("session").apply()
    }

    private suspend fun ensureSession(): PanSession {
        val current = session
        if (current != null && current.expiresAt > System.currentTimeMillis()) return current
        return login()
    }

    private suspend fun login(): PanSession {
        if (!HiveSession.isLoggedIn(context)) {
            throw CampusAuthException("请先登录 jAccount")
        }
        val orgs = requestJson("$ORIGIN/user/v1/organization/login-org-list")
        val list = orgs.optJSONArray("organizationList") ?: throw CampusAuthException("云盘没有返回可用组织")
        var corpId = ""
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val name = item.text("name")
            if (name.contains("交通") || name.contains("交大")) {
                corpId = item.text("corpId")
                break
            }
        }
        if (corpId.isEmpty()) {
            corpId = list.optJSONObject(0)?.text("corpId").orEmpty()
        }
        if (corpId.isEmpty()) throw CampusAuthException("云盘没有返回可用组织")

        val redirect = requestJson("$ORIGIN/user/v1/sign-in/sso-login-redirect/${enc(corpId)}?auto_redirect=false&from=web")
        val ssoUrl = redirect.text("url")
        if (ssoUrl.isEmpty()) throw CampusAuthException("云盘没有返回单点登录地址")
        val response = HiveHttp.raw(context, ssoUrl, "$ORIGIN/")
        val code = runCatching {
            java.net.URI(response.url).query.orEmpty()
                .split("&")
                .firstOrNull { it.startsWith("code=") }
                ?.substringAfter("code=")
        }.getOrNull().orEmpty()
        if (code.isEmpty()) throw CampusAuthException("云盘单点登录失败，请重新登录 jAccount")

        val query = "device_id=${enc(deviceId())}&type=sso&credential=${enc(code)}&state=oauth_v1_web_"
        val verified = requestJson(
            "$ORIGIN/user/v1/sign-in/verify-account-login/${enc(corpId)}?$query",
            post = true,
        )
        val userToken = verified.text("userToken")
        if (userToken.isEmpty()) throw CampusAuthException("云盘没有返回登录凭证")
        val expiresIn = verified.optLong("expiresIn", 3600)
        var libraryId = ""
        var displayName = ""
        verified.optJSONArray("organizations")?.let { organizations ->
            for (index in 0 until organizations.length()) {
                val org = organizations.optJSONObject(index) ?: continue
                if (libraryId.isEmpty()) {
                    libraryId = org.text("libraryId")
                    displayName = org.optJSONObject("orgUser")?.text("nickname").orEmpty()
                }
            }
        }
        if (displayName.isEmpty()) displayName = verified.optString("userId", "个人空间")
        val personal = requestJson(
            "$ORIGIN/user/v1/space/1/personal?user_token=${enc(userToken)}&device_id=${enc(deviceId())}",
            post = true,
        )
        val spaceId = personal.text("spaceId")
        if (spaceId.isEmpty()) throw CampusAuthException("云盘没有返回个人空间")
        if (personal.text("libraryId").isNotEmpty()) libraryId = personal.text("libraryId")
        val created = PanSession(
            userToken = userToken,
            libraryId = libraryId,
            spaceId = spaceId,
            displayName = displayName,
            expiresAt = System.currentTimeMillis() + expiresIn.coerceAtLeast(3600) * 1000,
        )
        session = created
        token = null
        prefs.edit().putString("session", created.toJson().toString()).apply()
        return created
    }

    private suspend fun accessToken(state: PanSession): PanToken {
        val cached = token
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached
        val issued = requestJson(
            "$ORIGIN/user/v1/space/1/personal?user_token=${enc(state.userToken)}&device_id=${enc(deviceId())}",
            post = true,
        )
        val value = issued.text("accessToken")
        if (value.isEmpty()) {
            session = null
            prefs.edit().remove("session").apply()
            throw CampusAuthException("云盘授权已失效，请重新授权")
        }
        val created = PanToken(
            token = value,
            libraryId = issued.text("libraryId").ifBlank { state.libraryId },
            spaceId = issued.text("spaceId").ifBlank { state.spaceId },
            expiresAt = System.currentTimeMillis() +
                (issued.optLong("expiresIn", 300L) - 60L).coerceAtLeast(60L) * 1000,
        )
        token = created
        return created
    }

    private suspend fun requestJson(url: String, post: Boolean = false): JSONObject {
        val result = HiveHttp.raw(
            context,
            url,
            "$ORIGIN/",
            method = if (post) "POST" else "GET",
            origin = ORIGIN,
            jsonBody = if (post) "{}" else null,
        )
        if (result.code == 401 || result.code == 403) throw CampusAuthException("请先登录 jAccount")
        if (result.code !in 200..299) {
            val message = runCatching { JSONObject(result.body).text("message") }.getOrNull().orEmpty()
            error(message.ifBlank { "交大云盘返回 HTTP ${result.code}" })
        }
        return runCatching { JSONObject(result.body) }.getOrElse {
            throw CampusAuthException("云盘登录状态异常，请重新登录")
        }
    }

    private fun loadSession(): PanSession? {
        val raw = prefs.getString("session", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PanSession(
                userToken = json.text("userToken"),
                libraryId = json.text("libraryId"),
                spaceId = json.text("spaceId"),
                displayName = json.text("displayName"),
                expiresAt = json.optLong("expiresAt"),
            ).takeIf { it.userToken.isNotEmpty() && it.expiresAt > System.currentTimeMillis() }
        }.getOrNull()
    }

    private fun deviceId(): String {
        val existing = prefs.getString("device_id", null)
        if (!existing.isNullOrBlank()) return existing
        val value = "sjtu-" + UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("device_id", value).apply()
        return value
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun escapePath(value: String): String {
        if (value.isEmpty()) return ""
        return value.split("/").joinToString("/") { enc(it) }
    }

    private fun longValue(item: JSONObject, key: String): Long {
        if (!item.has(key) || item.isNull(key)) return 0
        return when (val value = item.opt(key)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: 0
            else -> 0
        }
    }

    private data class PanSession(
        val userToken: String,
        val libraryId: String,
        val spaceId: String,
        val displayName: String,
        val expiresAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("userToken", userToken)
            put("libraryId", libraryId)
            put("spaceId", spaceId)
            put("displayName", displayName)
            put("expiresAt", expiresAt)
        }
    }

    private data class PanToken(
        val token: String,
        val libraryId: String,
        val spaceId: String,
        val expiresAt: Long,
    )

    companion object {
        private const val ORIGIN = "https://pan.sjtu.edu.cn"
    }
}
