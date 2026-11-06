package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ShareCourse(val id: Long, val name: String, val subtitle: String)

data class ShareMaterial(
    val id: Long,
    val title: String,
    val subtitle: String,
    val description: String,
    val points: Int?,
    val fileName: String,
    val hasPurchased: Boolean,
    val isMine: Boolean,
)

private class ShareOAuthJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    fun seed(host: String, cookies: List<Cookie>) {
        store[host] = cookies.toMutableList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val current = store.getOrPut(url.host) { mutableListOf() }
        cookies.forEach { cookie ->
            current.removeAll { it.name == cookie.name }
            current += cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
}

/** 传承交大（share.dyweb.sjtu.cn）只读客户端；token 由 WebView 登录流程写入。 */
class ShareApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("hive_share", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val oauthJar = ShareOAuthJar()
    private val oauthClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(oauthJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun token(): String = SecureStore.decrypt(context, prefs.getString("token", "").orEmpty())

    fun loggedIn(): Boolean = token().isNotEmpty()

    fun saveToken(value: String) {
        prefs.edit().putString("token", SecureStore.encrypt(context, value.trim())).apply()
    }

    fun logout() {
        prefs.edit().remove("token").apply()
    }

    /** 用设置里保存的 jAccount Cookie 走一次 OAuth，完全静默地拿到站点令牌。 */
    suspend fun loginWithCampus(): String = withContext(Dispatchers.IO) {
        val jaccountCookies = HiveSession.store(context)["jaccount.sjtu.edu.cn"].orEmpty()
        if (jaccountCookies.isEmpty()) throw IllegalStateException("请先在设置中登录 jAccount")
        val seeded = jaccountCookies.mapNotNull { (name, value) ->
            runCatching {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain("jaccount.sjtu.edu.cn")
                    .path("/")
                    .secure()
                    .httpOnly()
                    .build()
            }.getOrNull()
        }
        oauthJar.seed("jaccount.sjtu.edu.cn", seeded)

        val callback = "https://share.dyweb.sjtu.cn/auth/jaccount/callback"
        val configUrl = "$BASE/auth/jaccount/config?redirect_uri=" + URLEncoder.encode(callback, "UTF-8")
        val config = oauthJson(configUrl)
        if (!config.optBoolean("success", false)) throw IllegalStateException("读取 jAccount 登录配置失败")
        val data = config.optJSONObject("data") ?: throw IllegalStateException("jAccount 登录配置不完整")
        val endpoint = data.optJSONObject("endpoint")?.optString("auth_url").orEmpty()
        val clientId = data.optString("client_id")
        val state = data.optString("state")
        val redirectUri = data.optString("redirect_uri").ifBlank { callback }
        if (endpoint.isBlank() || clientId.isBlank() || state.isBlank()) {
            throw IllegalStateException("jAccount 登录配置不完整")
        }
        val scopes = data.optJSONArray("scopes")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        val base = endpoint.toHttpUrlOrNull() ?: throw IllegalStateException("jAccount 地址无效")
        var current = base.newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", scopes.joinToString(" "))
            .addQueryParameter("state", state)
            .build()

        var code = ""
        for (step in 0 until 15) {
            val response = oauthClient.newCall(
                Request.Builder().url(current).header("User-Agent", USER_AGENT).build(),
            ).execute()
            response.use {
                val location = it.header("Location")
                    ?: throw IllegalStateException("设置中的 jAccount 登录已失效，请先在设置中重新登录")
                val next = current.resolve(location) ?: throw IllegalStateException("jAccount 返回了无效地址")
                if (next.scheme != "https" || next.host !in OAUTH_HOSTS) {
                    throw IllegalStateException("jAccount 跳转到了不受信任的地址")
                }
                current = next
                if (current.toString().startsWith(callback)) {
                    code = current.queryParameter("code").orEmpty()
                    val returnedState = current.queryParameter("state")
                    if (!returnedState.isNullOrBlank() && returnedState != state) {
                        throw IllegalStateException("jAccount 登录状态校验失败")
                    }
                    break
                }
            }
        }
        if (code.isBlank()) throw IllegalStateException("jAccount 没有返回授权码，请先在设置中重新登录")

        val authorizeUrl = "$BASE/auth/jaccount/authorize?code=" +
            URLEncoder.encode(code, "UTF-8") + "&state=" + URLEncoder.encode(state, "UTF-8")
        val authorize = oauthJson(authorizeUrl)
        if (!authorize.optBoolean("success", false)) throw IllegalStateException("jAccount 授权失败")
        val token = authorize.opt("data") as? String
        if (token.isNullOrBlank()) throw IllegalStateException("jAccount 授权没有返回有效登录信息")
        token.trim()
    }

    private fun oauthJson(url: String): JSONObject {
        val target = url.toHttpUrlOrNull() ?: throw IllegalStateException("jAccount 地址无效")
        oauthClient.newCall(Request.Builder().url(target).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("jAccount 请求失败：HTTP ${response.code}")
            return JSONObject(text)
        }
    }

    suspend fun searchCourses(keyword: String): List<ShareCourse> = withContext(Dispatchers.IO) {
        val data = post("/api/v1/course/search", JSONObject().put("keyword", keyword.trim()))
        asArray(data).mapNotNull { item ->
            val id = item.optLong("id", item.optLong("course_id"))
            if (id <= 0) return@mapNotNull null
            val name = text(item, "name", "title", "course_name").ifBlank { "课程 #$id" }
            val subtitle = listOf(
                text(item, "code"),
                text(item, "teacher_name", "teacher"),
            ).filter { it.isNotBlank() }.joinToString(" · ")
            ShareCourse(id, name, subtitle)
        }
    }

    suspend fun materials(courseId: Long): List<ShareMaterial> = withContext(Dispatchers.IO) {
        val data = get("/api/v1/material?course_id=$courseId")
        val result = mutableListOf<ShareMaterial>()
        fun collect(array: JSONArray?) {
            if (array == null) return
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toMaterial()?.let { result += it }
            }
        }
        if (data is JSONObject) {
            val classes = data.optJSONArray("classes")
            if (classes != null) {
                for (index in 0 until classes.length()) {
                    collect(classes.optJSONObject(index)?.optJSONArray("materials"))
                }
            }
            collect(data.optJSONArray("unarchived"))
        }
        if (result.isEmpty()) collect(asArray(data).let { JSONArray(it) })
        result
    }

    suspend fun material(id: Long): ShareMaterial? = withContext(Dispatchers.IO) {
        val data = get("/api/v1/material/get/$id")
        val first = if (data is JSONArray) data.optJSONObject(0) else data as? JSONObject
        first?.toMaterial()
    }

    suspend fun download(id: Long): String = withContext(Dispatchers.IO) {
        val data = get("/api/v1/material/download/$id")
        when (data) {
            is String -> data
            is JSONObject -> text(data, "url", "download_url", "link", "file_url")
            else -> ""
        }
    }

    suspend fun purchase(id: Long) = withContext(Dispatchers.IO) {
        get("/api/v1/material/purchase/$id")
    }

    suspend fun downloadToCache(id: Long, fileName: String): File = withContext(Dispatchers.IO) {
        val url = download(id)
        if (url.isBlank()) throw IllegalStateException("没有获取到下载链接")
        val safeName = safeShareFileName(fileName, "share-$id")
        val directory = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(directory, "${id}-$safeName")
        if (target.exists() && target.length() > 0) return@withContext target
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("下载资料失败：HTTP ${response.code}")
            response.body?.byteStream()?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("下载资料失败：没有响应内容")
        }
        target
    }

    private fun get(path: String): Any = request(path, null, "GET")

    private fun post(path: String, body: JSONObject): Any = request(path, body, "POST")

    private fun request(path: String, body: JSONObject?, method: String): Any {
        val token = token()
        if (token.isEmpty()) throw ShareAuthException("传承交大登录已失效，请重新登录")
        val builder = Request.Builder()
            .url("$BASE$path")
            .header("Auth", token)
            .header("Accept", "application/json")
            .header("User-Agent", "SJTU-Hive/Android")
        if (body != null) {
            builder.method(method, body.toString().toRequestBody(JSON))
        } else {
            builder.method(method, null)
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401) {
                logout()
                throw ShareAuthException("传承交大登录已失效，请重新登录")
            }
            val envelope = runCatching { JSONObject(text) }.getOrNull()
            if (envelope == null) {
                if (!response.isSuccessful) throw IllegalStateException("传承交大返回 HTTP ${response.code}")
                return text
            }
            if (!envelope.optBoolean("success", false)) {
                if (response.code == 401) {
                    logout()
                    throw ShareAuthException("传承交大登录已失效，请重新登录")
                }
                val message = shareErrorMessage(envelope.optString("message"))
                throw IllegalStateException(message.ifBlank { "传承交大返回 HTTP ${response.code}" })
            }
            if (!response.isSuccessful) throw IllegalStateException("传承交大返回 HTTP ${response.code}")
            return unwrap(envelope.opt("data"))
        }
    }

    private fun unwrap(value: Any?): Any {
        return when (value) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> {
                for (key in listOf("items", "list", "courses", "materials", "data")) {
                    val nested = value.opt(key)
                    if (nested is JSONArray) return nested
                }
                value
            }
            else -> value
        }
    }

    private fun asArray(value: Any): List<JSONObject> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> listOf(value)
        else -> emptyList()
    }

    private fun JSONObject.toMaterial(): ShareMaterial? {
        val id = optLong("id", optLong("material_id"))
        if (id <= 0) return null
        val title = text(this, "name", "file_name", "title").ifBlank { "资料 #$id" }
        val subtitle = listOf(
            text(this, "material_type", "material_type_name", "type"),
            text(this, "course_name", "course"),
            text(this, "created_at").take(10),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val points = runCatching {
            if (has("points") && !isNull("points")) optInt("points") else null
        }.getOrNull()
        val fileName = text(this, "file_name", "name", "title").ifBlank { "share-$id" }
        return ShareMaterial(
            id = id,
            title = title,
            subtitle = subtitle,
            description = text(this, "description", "content"),
            points = points,
            fileName = fileName,
            hasPurchased = optBoolean("has_purchased", false) || optBoolean("is_mine", false),
            isMine = optBoolean("is_mine", false),
        )
    }

    private fun text(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value.trim()
            if (value is Number) return value.toString()
            if (value is JSONObject) {
                val nested = text(value, "name", "title", "label", "file_name")
                if (nested.isNotBlank()) return nested
            }
        }
        return ""
    }

    private companion object {
        const val BASE = "https://api-v2.share.dyweb.sjtu.cn"
        const val USER_AGENT = "SJTU-Hive/Android"
        val OAUTH_HOSTS = setOf("jaccount.sjtu.edu.cn", "share.dyweb.sjtu.cn", "api-v2.share.dyweb.sjtu.cn")
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class ShareAuthException(message: String) : Exception(message)

internal fun safeShareFileName(name: String, fallback: String): String {
    val cleaned = name.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim('.', ' ')
    return cleaned.ifBlank { fallback }.take(140)
}

private fun shareErrorMessage(message: String): String = when (message.trim()) {
    "you have not purchase the material!" -> "该资料需要先兑换或领取后才能下载"
    else -> message.trim()
}
