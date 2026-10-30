package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLDecoder

data class ShuiyuanTopic(
    val id: Long,
    val title: String,
    val excerpt: String,
    val postsCount: Int,
    val views: Int,
    val likeCount: Int,
    val lastPostedAt: String,
    val pinned: Boolean,
)

data class ShuiyuanPost(
    val id: Long,
    val username: String,
    val cooked: String,
    val createdAt: String,
    val postNumber: Int,
)

data class ShuiyuanTopicDetail(
    val id: Long,
    val title: String,
    val posts: List<ShuiyuanPost>,
)

data class ShuiyuanProfile(
    val username: String,
    val name: String,
    val bio: String,
    val joinedAt: String,
    val topics: Int,
    val posts: Int,
    val likesReceived: Int,
)

data class ShuiyuanCategory(val id: Long, val name: String)

data class ShuiyuanPage(val topics: List<ShuiyuanTopic>, val hasMore: Boolean)

/** 水源社区（Discourse）。用登录 WebView 留下的会话 cookie 读取公开板块与帖子。 */
class ShuiyuanApi(private val context: Context) {

    fun isConnected(): Boolean {
        val bucket = HiveSession.store(context)["shuiyuan.sjtu.edu.cn"].orEmpty()
        // Discourse 匿名访问也会拿到 _forum_session，只有登录用户才有 _t。
        return bucket.keys.any { it.equals("_t", ignoreCase = true) }
    }

    suspend fun topics(page: Int = 0): ShuiyuanPage = withContext(Dispatchers.IO) {
        val body = HiveHttp.get(
            context,
            "$ORIGIN/latest.json?page=$page",
            "$ORIGIN/latest",
        )
        val root = JSONObject(body)
        val topicList = root.optJSONObject("topic_list") ?: return@withContext ShuiyuanPage(emptyList(), false)
        val list = topicList.optJSONArray("topics")
            ?: return@withContext ShuiyuanPage(emptyList(), false)
        val hasMore = topicList.text("more_topics_url").isNotEmpty()
        val topics = mutableListOf<ShuiyuanTopic>()
        for (index in 0 until list.length()) {
            list.optJSONObject(index)?.let { item -> mapTopic(item)?.let(topics::add) }
        }
        ShuiyuanPage(topics, hasMore)
    }

    suspend fun topic(id: Long): ShuiyuanTopicDetail = withContext(Dispatchers.IO) {
        val body = HiveHttp.get(context, "$ORIGIN/t/$id.json", "$ORIGIN/t/$id")
        val root = JSONObject(body)
        val title = root.text("title")
        val stream = root.optJSONObject("post_stream")
        val posts = mutableListOf<ShuiyuanPost>()
        stream?.optJSONArray("posts")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                posts += ShuiyuanPost(
                    id = item.optLong("id"),
                    username = item.text("username"),
                    cooked = item.text("cooked"),
                    createdAt = item.text("created_at"),
                    postNumber = item.optInt("post_number"),
                )
            }
        }
        ShuiyuanTopicDetail(id, title, posts)
    }

    suspend fun reply(topicId: Long, raw: String): Unit = withContext(Dispatchers.IO) {
        if (raw.isBlank()) error("回复内容不能为空")
        val payload = JSONObject()
            .put("topic_id", topicId)
            .put("raw", raw.trim())
        val result = HiveHttp.raw(
            context,
            "$ORIGIN/posts.json",
            "$ORIGIN/t/$topicId",
            method = "POST",
            origin = ORIGIN,
            jsonBody = payload.toString(),
            headers = mapOf("X-CSRF-Token" to csrfToken()),
        )
        if (result.code == 403) throw CampusAuthException("没有回复权限，请重新登录水源")
        if (result.code !in 200..299) {
            val message = runCatching {
                JSONObject(result.body).optJSONArray("errors")?.optString(0)
            }.getOrNull().orEmpty()
            error(message.ifBlank { "回复失败：HTTP ${result.code}" })
        }
    }

    suspend fun categories(): List<ShuiyuanCategory> = withContext(Dispatchers.IO) {
        val root = JSONObject(HiveHttp.get(context, "$ORIGIN/categories.json", "$ORIGIN/categories"))
        val list = root.optJSONObject("category_list")?.optJSONArray("categories")
            ?: return@withContext emptyList()
        val result = mutableListOf<ShuiyuanCategory>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val id = item.optLong("id")
            val name = item.text("name")
            if (id == 0L || name.isEmpty()) continue
            result += ShuiyuanCategory(id, name)
        }
        result
    }

    suspend fun createTopic(title: String, raw: String, categoryId: Long): Long =
        withContext(Dispatchers.IO) {
            if (title.isBlank() || raw.isBlank()) error("标题和正文不能为空")
            val payload = JSONObject()
                .put("title", title.trim())
                .put("raw", raw.trim())
                .put("category", categoryId)
            val result = HiveHttp.raw(
                context,
                "$ORIGIN/posts.json",
                "$ORIGIN/",
                method = "POST",
                origin = ORIGIN,
                jsonBody = payload.toString(),
                headers = mapOf("X-CSRF-Token" to csrfToken()),
            )
            if (result.code == 403) throw CampusAuthException("没有发帖权限，请重新登录水源")
            if (result.code !in 200..299) {
                val message = runCatching {
                    JSONObject(result.body).optJSONArray("errors")?.optString(0)
                }.getOrNull().orEmpty()
                error(message.ifBlank { "发帖失败：HTTP ${result.code}" })
            }
            val response = JSONObject(result.body)
            response.optLong("topic_id").takeIf { it > 0 }
                ?: error("发帖成功但没有返回话题编号")
        }

    private suspend fun csrfToken(): String {
        val csrf = runCatching {
            JSONObject(HiveHttp.get(context, "$ORIGIN/session/csrf.json", "$ORIGIN/")).text("csrf")
        }.getOrNull().orEmpty()
        if (csrf.isEmpty()) throw CampusAuthException("水源登录已失效，请重新登录")
        return csrf
    }

    suspend fun currentUsername(): String? = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(HiveHttp.get(context, "$ORIGIN/session/current.json", "$ORIGIN/"))
                .optJSONObject("current_user")?.text("username")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun profile(username: String): ShuiyuanProfile = withContext(Dispatchers.IO) {
        val name = enc(username)
        val user = JSONObject(
            HiveHttp.get(context, "$ORIGIN/u/$name.json", "$ORIGIN/u/$name"),
        ).optJSONObject("user") ?: error("没有找到该用户")
        val summary = runCatching {
            JSONObject(
                HiveHttp.get(context, "$ORIGIN/u/$name/summary.json", "$ORIGIN/u/$name"),
            ).optJSONObject("user_summary")
        }.getOrNull()
        ShuiyuanProfile(
            username = user.text("username").ifBlank { username },
            name = user.text("name"),
            bio = user.text("bio_excerpt").ifBlank { user.text("bio_raw") },
            joinedAt = user.text("created_at").replace("T", " ").take(10),
            topics = summary?.optInt("topic_count", 0) ?: 0,
            posts = summary?.optInt("post_count", 0) ?: 0,
            likesReceived = summary?.optInt("likes_received", 0) ?: 0,
        )
    }

    suspend fun createdTopics(username: String): List<ShuiyuanTopic> = withContext(Dispatchers.IO) {
        val name = enc(username)
        val root = JSONObject(
            HiveHttp.get(
                context,
                "$ORIGIN/topics/created-by/$name.json?page=0",
                "$ORIGIN/u/$name",
            ),
        )
        val list = root.optJSONObject("topic_list")?.optJSONArray("topics")
            ?: return@withContext emptyList()
        val topics = mutableListOf<ShuiyuanTopic>()
        for (index in 0 until list.length()) {
            list.optJSONObject(index)?.let { item -> mapTopic(item)?.let(topics::add) }
        }
        topics
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun mapTopic(item: JSONObject): ShuiyuanTopic? {
        val id = item.optLong("id")
        val title = renderEmoji(item.text("title"))
        if (id == 0L || title.isEmpty()) return null
        return ShuiyuanTopic(
            id = id,
            title = title,
            excerpt = cleanExcerpt(item.text("excerpt")),
            postsCount = item.optInt("posts_count"),
            views = item.optInt("views"),
            likeCount = item.optInt("like_count"),
            lastPostedAt = item.text("last_posted_at").ifBlank { item.text("created_at") },
            pinned = item.optBoolean("pinned"),
        )
    }

    private fun renderEmoji(value: String): String =
        EMOJI_PATTERN.replace(value) { match ->
            EMOJI[match.groupValues[1]] ?: ""
        }

    private fun cleanExcerpt(value: String): String {
        val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        return renderEmoji(decoded)
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        const val ORIGIN = "https://shuiyuan.sjtu.edu.cn"

        private val EMOJI_PATTERN = Regex(":([a-zA-Z0-9_+-]+):")
        private val EMOJI = mapOf(
            "pink_heart" to "💗",
            "pleading_face" to "🥺",
            "league_of_leagues" to "🏆",
            "dna" to "🧬",
            "heart" to "❤️",
            "sob" to "😭",
            "joy" to "😂",
            "smile" to "😄",
            "fire" to "🔥",
            "tada" to "🎉",
            "thinking" to "🤔",
            "thumbsup" to "👍",
            "eyes" to "👀",
            "star" to "⭐",
            "warning" to "⚠️",
            "white_check_mark" to "✅",
            "x" to "❌",
        )
    }
}
