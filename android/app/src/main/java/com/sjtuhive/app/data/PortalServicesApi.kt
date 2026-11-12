package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

data class ServiceApp(
    val id: String,
    val name: String,
    val department: String,
    val uri: String,
    val categoryName: String,
    val topCategoryName: String,
    val iconId: Int,
    val favorite: Boolean,
    val ready: Boolean,
)

data class ServiceCategory(val title: String, val count: Int)

data class ServiceRecent(val itemId: String, val itemName: String, val url: String)

data class ServiceProcess(
    val id: String,
    val name: String,
    val appName: String,
    val stepName: String,
    val entry: String,
    val percent: Int,
    val updatedAt: Long?,
)

data class ServiceCatalog(
    val apps: List<ServiceApp>,
    val categories: List<ServiceCategory>,
    val favorites: Set<String>,
    val recent: List<ServiceRecent>,
)

data class ServiceProcessPage(val items: List<ServiceProcess>, val total: Int)

/** 交我办办事大厅（my.sjtu.edu.cn /api/task/me）。 */
class PortalServicesApi(private val context: Context) {

    suspend fun catalog(): ServiceCatalog = withContext(Dispatchers.IO) {
        val appsJson = fetchJson("/api/task/me/apps")
        val categoryJson = fetchJson("/api/task/me/category")
        val favorites = runCatching { fetchJson("/api/task/me/preference") }
            .map { entities(it).firstOrNull()?.optJSONArray("FavoriteApps") ?: JSONArray() }
            .getOrDefault(JSONArray())
        val recentJson = runCatching { fetchJson("/api/task/me/recentlyApp?limit=12") }
            .getOrNull()
        val favoriteIds = buildSet {
            for (index in 0 until favorites.length()) {
                favorites.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val apps = entities(appsJson).mapNotNull(::parseApp)
        if (apps.isEmpty()) {
            throw CampusAuthException("交我办没有返回办事应用，请重新登录 jAccount")
        }
        val counts = apps.groupingBy { it.topCategoryName }.eachCount()
        val categories = entities(categoryJson)
            .mapNotNull { item ->
                val title = item.text("title")
                if (title.isEmpty()) null
                else ServiceCategory(title, counts[title] ?: 0)
            }
            .distinctBy { it.title }
            .sortedByDescending { it.count }
        val recent = recentJson?.let(::entities).orEmpty().mapNotNull { item ->
            val id = item.text("itemId")
            val name = item.text("itemName")
            val url = item.text("url")
            if (id.isEmpty() || name.isEmpty()) null else ServiceRecent(id, name, url)
        }
        ServiceCatalog(
            apps = apps.map { it.copy(favorite = favoriteIds.contains(it.id)) }
                .sortedWith(compareBy({ !it.favorite }, { it.name })),
            categories = categories,
            favorites = favoriteIds,
            recent = recent,
        )
    }

    suspend fun processes(kind: String, limit: Int = 20, start: Int = 0): ServiceProcessPage =
        withContext(Dispatchers.IO) {
            val path = when (kind) {
                "todo" -> "/api/task/me/processes/todo?thing=false"
                "completed" -> "/api/task/me/processes/completed?limit=${limit.coerceIn(1, 50)}" +
                    "&start=$start&order=auto&keyword="
                "cc" -> "/api/task/me/processes/cc?limit=${limit.coerceIn(1, 50)}" +
                    "&start=$start&order=auto&keyword="
                else -> error("未知的流程类型")
            }
            val json = fetchJson(path)
            ServiceProcessPage(
                items = entities(json).mapNotNull { parseProcess(it, kind) },
                total = json.optInt("total", -1),
            )
        }

    suspend fun setFavorite(appId: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        val result = HiveHttp.raw(
            context,
            "$ORIGIN/api/task/me/preference/FavoriteApps/${enc(appId)}",
            "$ORIGIN/ui/app/",
            method = if (favorite) "PUT" else "DELETE",
            jsonBody = "{}",
        )
        if (result.code == 401 || result.code == 403) throw CampusAuthException("交我办登录已失效")
        if (result.code !in 200..299) error("收藏失败：HTTP ${result.code}")
        runCatching { JSONObject(result.body) }.getOrNull()?.let { body ->
            val errno = body.opt("errno")
            val code = when (errno) {
                is Number -> errno.toInt()
                is String -> errno.toIntOrNull()
                else -> 0
            }
            if (code != null && code != 0) error(body.text("error").ifBlank { "收藏失败" })
        }
        Unit
    }

    suspend fun touchRecent(itemId: String, itemName: String, url: String) =
        withContext(Dispatchers.IO) {
            val query = "itemId=${enc(itemId)}&itemName=${enc(itemName)}&url=${enc(url)}"
            val result = HiveHttp.raw(
                context,
                "$ORIGIN/api/task/me/recentlyApp?$query",
                "$ORIGIN/ui/app/",
                method = "PUT",
                jsonBody = "{}",
            )
            if (result.code !in 200..299) error("记录最近使用失败")
        }

    suspend fun icons(ids: Collection<Int>): Map<Int, String> = withContext(Dispatchers.IO) {
        val missing = ids.filter { it > 0 }.distinct()
        if (missing.isEmpty()) return@withContext emptyMap()
        val payload = JSONObject().put("ids", JSONArray(missing)).toString()
        val result = HiveHttp.raw(
            context,
            "https://jwb.sjtu.edu.cn/jwbMaintenance/unlimited/icon/list",
            "$ORIGIN/ui/app/",
            method = "POST",
            jsonBody = payload,
            headers = mapOf("Content-Type" to "application/json;charset=UTF-8"),
        )
        if (result.code !in 200..299) return@withContext emptyMap()
        val map = mutableMapOf<Int, String>()
        runCatching { JSONObject(result.body) }.getOrNull()?.let { body ->
            entities(body).forEach { item ->
                val id = item.optInt("id", 0)
                val data = item.text("data")
                if (id <= 0 || data.isEmpty()) return@forEach
                val source = iconSource(data) ?: return@forEach
                map[id] = source
            }
        }
        map
    }

    private suspend fun fetchJson(path: String): JSONObject {
        val result = HiveHttp.raw(context, "$ORIGIN$path", "$ORIGIN/ui/app/")
        if (result.code == 401 || result.code == 403) throw CampusAuthException("交我办登录已失效，请重新登录")
        if (result.code !in 200..299) error("交我办返回 HTTP ${result.code}")
        return runCatching { JSONObject(result.body) }
            .getOrElse { throw IllegalStateException("交我办响应无法识别") }
    }

    private fun entities(json: JSONObject): List<JSONObject> {
        val array = json.optJSONArray("entities") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun parseApp(raw: JSONObject): ServiceApp? {
        val id = raw.text("id")
        val name = raw.text("name")
        if (id.isEmpty() || name.isEmpty()) return null
        val tag = Regex("#CategoryName:([^,#]+)").find(raw.text("tags"))?.groupValues?.getOrNull(1)
            ?.trim().orEmpty()
        var topName = ""
        var iconId = 0
        raw.optJSONArray("categoryIcon")?.let { categories ->
            for (index in 0 until categories.length()) {
                val item = categories.optJSONObject(index) ?: continue
                for (key in item.keys()) {
                    if (key == "应用分类" || key == "sort") continue
                    val value = item.optInt(key, 0)
                    if (value > 0) {
                        topName = key
                        iconId = value
                        break
                    }
                }
                if (iconId > 0) break
            }
        }
        return ServiceApp(
            id = id,
            name = name,
            department = raw.text("department"),
            uri = raw.text("uri"),
            categoryName = tag,
            topCategoryName = topName,
            iconId = iconId,
            favorite = false,
            ready = raw.optBoolean("ready", true),
        )
    }

    private fun parseProcess(raw: JSONObject, kind: String): ServiceProcess? {
        val process = raw.optJSONObject("process")
        val id = raw.text("id").ifBlank { process?.text("id").orEmpty() }
        val name = process?.text("name").orEmpty().ifBlank { raw.text("name") }
        if (id.isEmpty() || name.isEmpty()) return null
        val percent = process?.optJSONObject("milestone")?.let { milestone ->
            milestone.opt("percent")?.toString()?.toFloatOrNull()?.toInt() ?: 0
        } ?: 0
        val stamp = when {
            raw.opt("assignTime") is Number -> raw.optLong("assignTime")
            process?.opt("update") is Number -> process.optLong("update")
            raw.opt("update") is Number -> raw.optLong("update")
            else -> 0L
        }
        val entry = process?.text("entry").orEmpty().ifBlank { raw.text("uri") }
        return ServiceProcess(
            id = id,
            name = name,
            appName = if (kind == "todo") process?.optJSONObject("app")?.text("name").orEmpty() else "",
            stepName = if (kind == "todo") raw.text("name").takeIf { it != name }.orEmpty() else "",
            entry = entry,
            percent = percent,
            updatedAt = stamp.takeIf { it > 0 }?.times(1000),
        )
    }

    private fun iconSource(data: String): String? {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return null
        val head = trimmed.take(100).lowercase()
        if (head.startsWith("<svg") || head.startsWith("<?xml") || head.startsWith("<")) {
            val encoded = Base64.getEncoder().encodeToString(trimmed.toByteArray())
            return "data:image/svg+xml;base64,$encoded"
        }
        if (head.startsWith("https://") || head.startsWith("http://") || head.startsWith("data:image/")) {
            return trimmed
        }
        return "data:image/png;base64,${trimmed.replace(Regex("\\s+"), "")}"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val ORIGIN = "https://my.sjtu.edu.cn"
    }
}
