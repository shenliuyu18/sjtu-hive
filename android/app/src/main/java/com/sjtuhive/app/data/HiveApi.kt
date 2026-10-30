package com.sjtuhive.app.data

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 会话存储：登录 WebView 里各个交大站点（my.sjtu / jaccount / calendar / i.sjtu / pan ...）
 * 的 cookie 按域落盘，之后 OkHttp 用同一份会话访问各站接口。
 */
object HiveSession {
    private const val FILE = "hive_session"
    private const val KEY_HOSTS = "host_cookies"
    private const val KEY_LEGACY = "cookies"

    /** 登录一次后可复用的交大站点。 */
    val campusHosts = listOf(
        "my.sjtu.edu.cn",
        "jaccount.sjtu.edu.cn",
        "calendar.sjtu.edu.cn",
        "i.sjtu.edu.cn",
        "pan.sjtu.edu.cn",
        "oc.sjtu.edu.cn",
        "canvas.sjtu.edu.cn",
        "shuiyuan.sjtu.edu.cn",
        "v.sjtu.edu.cn",
    )

    fun store(context: Context): MutableMap<String, MutableMap<String, String>> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val result = linkedMapOf<String, MutableMap<String, String>>()
        val raw = prefs.getString(KEY_HOSTS, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(raw)
                root.keys().forEach { host ->
                    val bucket = root.optJSONObject(host) ?: return@forEach
                    val cookies = linkedMapOf<String, String>()
                    bucket.keys().forEach { name -> cookies[name] = bucket.optString(name) }
                    if (cookies.isNotEmpty()) result[host] = cookies
                }
            }
        }
        // 旧版只把 my.sjtu 的 cookie 存成一条字符串，读到时补成按域格式。
        if (result.isEmpty()) {
            val legacy = parseCookieHeader(prefs.getString(KEY_LEGACY, null)).toMutableMap()
            if (legacy.isNotEmpty()) result["my.sjtu.edu.cn"] = legacy
        }
        return result
    }

    fun saveStore(context: Context, store: Map<String, Map<String, String>>) {
        val root = JSONObject()
        store.forEach { (host, cookies) ->
            val bucket = JSONObject()
            cookies.forEach { (name, value) -> bucket.put(name, value) }
            root.put(host, bucket)
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOSTS, root.toString())
            .remove(KEY_LEGACY)
            .apply()
    }

    /** jAccount 登录成功才会下发 JAAuthCookie；匿名访问也会拿到 JSESSIONID，不能据此判登录。 */
    fun isLoggedIn(context: Context): Boolean =
        store(context).values.any { bucket ->
            bucket.keys.any { it.equals("JAAuthCookie", ignoreCase = true) }
        }

    /** 把 WebView 里已登录的 cookie 全部搬到按域存储，供 OkHttp 复用会话。 */
    fun refreshFromWebView(context: Context) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        val current = store(context)
        var changed = false
        campusHosts.forEach { host ->
            val header = runCatching { manager.getCookie("https://$host") }.getOrNull() ?: return@forEach
            val cookies = parseCookieHeader(header)
            if (cookies.isEmpty()) return@forEach
            val bucket = current.getOrPut(host) { mutableMapOf() }
            cookies.forEach { (name, value) ->
                if (bucket[name] != value) {
                    bucket[name] = value
                    changed = true
                }
            }
        }
        if (changed) saveStore(context, current)
        // 让 WebView 的 cookie 立刻落盘，进程被杀后静默 SSO 还能接着用。
        runCatching { manager.flush() }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun removeHost(context: Context, host: String) {
        val current = store(context)
        if (current.remove(host) != null) saveStore(context, current)
    }
}

internal fun parseCookieHeader(header: String?): Map<String, String> =
    header.orEmpty().split(";").mapNotNull { part ->
        val trimmed = part.trim()
        val index = trimmed.indexOf('=')
        if (index <= 0) null else trimmed.substring(0, index) to trimmed.substring(index + 1)
    }.toMap()

/** 按域读取、并把响应 Set-Cookie 合并回存储的 CookieJar。 */
class HiveCookieJar(context: Context) : CookieJar {
    private val appContext = context.applicationContext

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val store = HiveSession.store(appContext)
        val host = url.host
        val match = store.keys
            .filter { host == it || host.endsWith(".$it") }
            .maxByOrNull { it.length } ?: return emptyList()
        return store[match].orEmpty().mapNotNull { (name, value) ->
            runCatching {
                Cookie.Builder().name(name).value(value).hostOnlyDomain(host).path("/").build()
            }.getOrNull()
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val store = HiveSession.store(appContext)
        val bucket = store.getOrPut(url.host) { mutableMapOf() }
        cookies.forEach { bucket[it.name] = it.value }
        HiveSession.saveStore(appContext, store)
    }
}

/** 共用 OkHttp 客户端：带上校园会话 cookie。 */
object HiveHttp {
    private val clients = mutableMapOf<String, OkHttpClient>()

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    data class HttpResult(val code: Int, val url: String, val body: String)

    @Synchronized
    fun client(context: Context): OkHttpClient {
        val key = context.applicationContext.packageName
        return clients.getOrPut(key) {
            OkHttpClient.Builder()
                .cookieJar(HiveCookieJar(context))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    suspend fun raw(
        context: Context,
        url: String,
        referer: String,
        method: String = "GET",
        origin: String? = null,
        jsonBody: String? = null,
        form: Map<String, String>? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult = withContext(Dispatchers.IO) {
        val requestBody = when {
            jsonBody != null -> jsonBody.toRequestBody("application/json".toMediaType())
            form != null -> okhttp3.FormBody.Builder().apply {
                form.forEach { (name, value) -> add(name, value) }
            }.build()
            else -> null
        }
        val builder = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", USER_AGENT)
        if (origin != null) builder.header("Origin", origin)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (requestBody == null) builder.get() else builder.method(method, requestBody)
        client(context).newCall(builder.build()).execute().use { response ->
            HttpResult(
                code = response.code,
                url = response.request.url.toString(),
                body = response.body?.string().orEmpty(),
            )
        }
    }

    suspend fun get(
        context: Context,
        url: String,
        referer: String,
        acceptJson: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", if (acceptJson) "application/json, text/plain, */*" else "*/*")
            .header("User-Agent", USER_AGENT)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
        client(context).newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) error("登录已失效，请重新登录")
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string() ?: error("空响应")
        }
    }

    suspend fun postForm(
        context: Context,
        url: String,
        referer: String,
        origin: String,
        fields: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val form = okhttp3.FormBody.Builder().apply {
            fields.forEach { (name, value) -> add(name, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(form)
            .header("Referer", referer)
            .header("Origin", origin)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", USER_AGENT)
            .build()
        client(context).newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) error("登录已失效，请重新登录")
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string() ?: error("空响应")
        }
    }
}

/** org.json 把 JSON null 读成字符串 "null"，这里统一成空串。 */
internal fun JSONObject.text(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return optString(key).trim().takeUnless { it == "null" }.orEmpty()
}

data class PortalGroup(val id: String, val name: String, val isGroup: Boolean, val unread: Int)

data class PortalMessage(
    val id: String,
    val title: String,
    val body: String,
    val source: String,
    val createdAt: String,
    val url: String?,
)

/** 交我办 / 教学网接口。接口形态与桌面端验证过的一致。 */
class HiveApi(private val context: Context) {

    suspend fun groups(): List<PortalGroup> {
        val body = HiveHttp.get(
            context,
            "https://my.sjtu.edu.cn/api/jwbmessage/group?key=&page=0&pageSize=30&read=false",
            "https://my.sjtu.edu.cn/ui/message/",
        )
        val root = JSONObject(body)
        if (!root.optBoolean("success")) error(root.optString("message", "交我办返回失败"))
        val entities = root.optJSONArray("entities") ?: return emptyList()
        return (0 until entities.length()).mapNotNull { index ->
            val item = entities.optJSONObject(index) ?: return@mapNotNull null
            PortalGroup(
                id = item.text("groupId"),
                name = item.text("groupName"),
                isGroup = item.optBoolean("isGroup"),
                unread = item.optInt("unreadNum"),
            )
        }
    }

    suspend fun messages(group: PortalGroup, limit: Int = 30): List<PortalMessage> {
        val path = "https://my.sjtu.edu.cn/api/jwbmessage/messagelist?groupId=${group.id}" +
            "&isGroup=${group.isGroup}&page=0&pageSize=$limit&key=&read=false"
        val root = JSONObject(HiveHttp.get(context, path, "https://my.sjtu.edu.cn/ui/message/"))
        if (!root.optBoolean("success")) error(root.optString("message", "交我办返回失败"))
        val entities = root.optJSONArray("entities") ?: return emptyList()
        return (0 until entities.length()).mapNotNull { index ->
            val item = entities.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("messageId")
            if (id.isEmpty()) return@mapNotNull null
            PortalMessage(
                id = id,
                title = item.text("title")
                    .ifBlank { item.text("pushTitle") }
                    .ifBlank { group.name },
                body = item.text("pushContent")
                    .ifBlank { item.text("description") }
                    .ifBlank { messageContext(item) },
                source = item.optJSONObject("authClient")?.text("name")
                    .orEmpty()
                    .ifBlank { group.name },
                createdAt = item.text("createTime"),
                url = firstLink(item),
            )
        }
    }

    private fun firstLink(item: JSONObject): String? {
        val urls = item.optJSONArray("urlList") ?: return null
        for (index in 0 until urls.length()) {
            val candidate = urls.optJSONObject(index)?.text("url").orEmpty()
            if (candidate.startsWith("http")) return candidate
        }
        return null
    }

    private fun messageContext(item: JSONObject): String {
        val context = item.optJSONArray("context") ?: return ""
        val parts = mutableListOf<String>()
        for (index in 0 until context.length()) {
            val value = context.optJSONObject(index)?.text("value").orEmpty()
            if (value.isNotEmpty()) parts += value
        }
        return parts.joinToString(" · ")
    }
}
