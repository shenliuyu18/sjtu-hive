package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

/** 传承交大（share.dyweb.sjtu.cn）只读客户端；token 由 WebView 登录流程写入。 */
class ShareApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("hive_share", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
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
