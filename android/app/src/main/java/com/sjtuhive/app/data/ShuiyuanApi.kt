package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    val likeCount: Int = 0,
    val liked: Boolean = false,
)

data class ShuiyuanTopicDetail(
    val id: Long,
    val title: String,
    val posts: List<ShuiyuanPost>,
    // Discourse 返回的完整楼层 id 序列，首屏只带前 20 层，其余按需拉取。
    val stream: List<Long> = emptyList(),
    val bookmarked: Boolean = false,
)

data class ShuiyuanNotification(
    val id: Long,
    val type: Int,
    val read: Boolean,
    val createdAt: String,
    val topicId: Long?,
    val postNumber: Int?,
    val title: String,
    val username: String?,
)

data class ShuiyuanBookmark(
    val id: Long,
    val topicId: Long,
    val title: String,
    val excerpt: String,
    val createdAt: String,
)

data class ShuiyuanCounts(
    val notifications: Int = 0,
    val messages: Int = 0,
)

data class ShuiyuanBadge(
    val id: Long,
    val name: String,
    val description: String,
    val imageUrl: String,
    val grantedAt: String,
    val count: Int,
    val badgeType: String,
)

data class ShuiyuanProfile(
    val username: String,
    val name: String,
    val bio: String,
    val joinedAt: String,
    val topics: Int,
    val posts: Int,
    val likesReceived: Int,
    val badgeCount: Int = 0,
    val badges: List<ShuiyuanBadge> = emptyList(),
)

data class ShuiyuanActivity(
    val postNumber: Int,
    val topicId: Long,
    val topicTitle: String,
    val blurb: String,
    val createdAt: String,
)

data class ShuiyuanCategory(
    val id: Long,
    val name: String,
    val slug: String = "",
    val color: String = "808281",
    val parentId: Long? = null,
    val topicCount: Int = 0,
)

data class ShuiyuanTag(val name: String, val topicCount: Int)

data class ShuiyuanSite(
    val categories: List<ShuiyuanCategory>,
    val tags: List<ShuiyuanTag>,
)

data class ShuiyuanPage(val topics: List<ShuiyuanTopic>, val hasMore: Boolean)

/** 水源社区（Discourse）。用登录 WebView 留下的会话 cookie 读取公开板块与帖子。 */
class ShuiyuanApi(private val context: Context) {

    fun isConnected(): Boolean {
        val bucket = HiveSession.store(context)["shuiyuan.sjtu.edu.cn"].orEmpty()
        // Discourse 匿名访问也会拿到 _forum_session，只有登录用户才有 _t。
        return bucket.keys.any { it.equals("_t", ignoreCase = true) }
    }

    /** 用设置里的 jAccount 静默完成水源社区 SSO。 */
    suspend fun ensureLogin(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected()) return@withContext true
        if (!HiveSession.isLoggedIn(context)) return@withContext false
        val result = HiveHttp.raw(context, "$ORIGIN/auth/jaccount", "$ORIGIN/")
        if (result.code !in 200..299) return@withContext false
        runCatching {
            HiveHttp.get(context, "$ORIGIN/latest.json?page=0", "$ORIGIN/latest")
        }.isSuccess && isConnected()
    }

    suspend fun topics(page: Int = 0): ShuiyuanPage = withContext(Dispatchers.IO) {
        topicPage("$ORIGIN/latest.json?page=$page", "$ORIGIN/latest")
    }

    suspend fun featured(page: Int = 0): ShuiyuanPage = withContext(Dispatchers.IO) {
        val tag = enc(FEATURED_TAG)
        topicPage("$ORIGIN/tag/$tag.json?page=$page", "$ORIGIN/tag/$tag")
    }

    suspend fun categoryTopics(slug: String, id: Long, page: Int = 0): ShuiyuanPage =
        withContext(Dispatchers.IO) {
            val safeSlug = enc(slug.ifBlank { id.toString() })
            topicPage("$ORIGIN/c/$safeSlug/$id.json?page=$page", "$ORIGIN/c/$safeSlug/$id")
        }

    suspend fun tagTopics(tag: String, page: Int = 0): ShuiyuanPage = withContext(Dispatchers.IO) {
        val safeTag = enc(tag)
        topicPage("$ORIGIN/tag/$safeTag.json?page=$page", "$ORIGIN/tag/$safeTag")
    }

    suspend fun site(): ShuiyuanSite = withContext(Dispatchers.IO) {
        val root = JSONObject(getJson("$ORIGIN/site.json", "$ORIGIN/"))
        val categories = mutableListOf<ShuiyuanCategory>()
        root.optJSONArray("categories")?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = item.optLong("id")
                val name = item.text("name")
                val slug = item.text("slug")
                if (id == 0L || name.isEmpty() || slug == "uncategorized") continue
                categories += ShuiyuanCategory(
                    id = id,
                    name = name,
                    slug = slug,
                    color = item.text("color").ifBlank { "808281" },
                    parentId = if (item.isNull("parent_category_id")) {
                        null
                    } else {
                        item.optLong("parent_category_id")
                    },
                    topicCount = item.optInt("topic_count"),
                )
            }
        }
        val tags = mutableListOf<ShuiyuanTag>()
        root.optJSONArray("top_tags")?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val name = item.text("name")
                if (name.isNotEmpty()) tags += ShuiyuanTag(name, item.optInt("topic_count"))
            }
        }
        ShuiyuanSite(categories, tags)
    }

    private suspend fun topicPage(url: String, referer: String): ShuiyuanPage {
        val body = getJson(url, referer)
        val root = JSONObject(body)
        val topicList = root.optJSONObject("topic_list") ?: return ShuiyuanPage(emptyList(), false)
        val list = topicList.optJSONArray("topics") ?: return ShuiyuanPage(emptyList(), false)
        val hasMore = topicList.text("more_topics_url").isNotEmpty()
        val topics = mutableListOf<ShuiyuanTopic>()
        for (index in 0 until list.length()) {
            list.optJSONObject(index)?.let { item -> mapTopic(item)?.let(topics::add) }
        }
        return ShuiyuanPage(topics, hasMore)
    }

    /** Discourse 全文搜索；page 从 1 开始，延续页由 grouped_search_result 判断。 */
    suspend fun search(query: String, page: Int = 1): ShuiyuanPage = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return@withContext ShuiyuanPage(emptyList(), false)
        val body = getJson(
            "$ORIGIN/search.json?q=${enc(keyword)}&page=$page",
            "$ORIGIN/search",
        )
        val root = JSONObject(body)
        val blurbs = mutableMapOf<Long, String>()
        root.optJSONArray("posts")?.let { posts ->
            for (index in 0 until posts.length()) {
                val post = posts.optJSONObject(index) ?: continue
                val topicId = post.optLong("topic_id")
                if (topicId == 0L || blurbs.containsKey(topicId)) continue
                val blurb = cleanExcerpt(post.text("blurb"))
                if (blurb.isNotEmpty()) blurbs[topicId] = blurb
            }
        }
        val topics = mutableListOf<ShuiyuanTopic>()
        root.optJSONArray("topics")?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val topic = mapTopic(item) ?: continue
                topics += blurbs[topic.id]?.let { topic.copy(excerpt = it) } ?: topic
            }
        }
        val hasMore = root.optJSONObject("grouped_search_result")
            ?.optBoolean("more_full_page_results", false) ?: false
        ShuiyuanPage(topics, hasMore)
    }

    suspend fun likePost(postId: Long, liked: Boolean): Unit = withContext(Dispatchers.IO) {
        if (postId <= 0) error("楼层 ID 无效")
        if (liked) {
            action(
                "POST",
                "/post_actions.json",
                "$ORIGIN/",
                JSONObject().put("id", postId).put("post_action_type_id", 2),
            )
        } else {
            action("DELETE", "/post_actions/$postId.json?post_action_type_id=2", "$ORIGIN/")
        }
    }

    suspend fun toggleBookmark(topicId: Long, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (topicId <= 0) error("主题 ID 无效")
        if (on) {
            action(
                "POST",
                "/bookmarks",
                "$ORIGIN/t/$topicId",
                JSONObject().put("bookmarkable_id", topicId).put("bookmarkable_type", "Topic"),
            )
            true
        } else {
            val existing = bookmarks().firstOrNull { it.topicId == topicId }
                ?: return@withContext false
            action("DELETE", "/bookmarks/${existing.id}", "$ORIGIN/t/$topicId")
            false
        }
    }

    suspend fun bookmarks(): List<ShuiyuanBookmark> = withContext(Dispatchers.IO) {
        val username = currentUsername() ?: error("水源社区登录已失效，请重新登录")
        val safe = enc(username)
        val root = JSONObject(
            getJson("$ORIGIN/u/$safe/bookmarks.json", "$ORIGIN/u/$safe"),
        )
        val list = root.optJSONObject("user_bookmark_list")?.optJSONArray("bookmarks")
            ?: return@withContext emptyList()
        val result = mutableListOf<ShuiyuanBookmark>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val topicId = if (item.isNull("topic_id")) {
                item.optLong("bookmarkable_id")
            } else {
                item.optLong("topic_id")
            }
            if (topicId <= 0) continue
            result += ShuiyuanBookmark(
                id = item.optLong("id"),
                topicId = topicId,
                title = item.text("title")
                    .ifBlank { item.text("fancy_title") }
                    .ifBlank { "(无标题)" },
                excerpt = cleanExcerpt(item.text("excerpt")),
                createdAt = item.text("created_at"),
            )
        }
        result
    }

    suspend fun notifications(): List<ShuiyuanNotification> = withContext(Dispatchers.IO) {
        val root = JSONObject(getJson("$ORIGIN/notifications.json?recent=true", "$ORIGIN/"))
        val list = root.optJSONArray("notifications") ?: return@withContext emptyList()
        val result = mutableListOf<ShuiyuanNotification>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val data = item.optJSONObject("data")
            val title = item.text("fancy_title")
                .ifBlank { data?.text("topic_title").orEmpty() }
            result += ShuiyuanNotification(
                id = item.optLong("id"),
                type = item.optInt("notification_type"),
                read = item.optBoolean("read"),
                createdAt = item.text("created_at"),
                topicId = if (item.isNull("topic_id")) null else item.optLong("topic_id"),
                postNumber = if (item.isNull("post_number")) null else item.optInt("post_number"),
                title = cleanExcerpt(title),
                username = data?.text("display_username")?.takeIf { it.isNotBlank() },
            )
        }
        result
    }

    suspend fun markNotificationsRead() = withContext(Dispatchers.IO) {
        action("PUT", "/notifications/mark-read", "$ORIGIN/", JSONObject())
    }

    suspend fun counts(): ShuiyuanCounts = withContext(Dispatchers.IO) {
        val current = JSONObject(getJson("$ORIGIN/session/current.json", "$ORIGIN/"))
            .optJSONObject("current_user") ?: return@withContext ShuiyuanCounts()
        ShuiyuanCounts(
            notifications = current.optInt("unread_notifications"),
            messages = current.optInt("unread_private_messages"),
        )
    }

    suspend fun sendMessage(targets: List<String>, title: String, raw: String): Long =
        withContext(Dispatchers.IO) {
            val recipients = targets.map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }
            if (recipients.isEmpty()) error("请填写收件人")
            val subject = title.trim()
            val body = raw.trim()
            if (subject.isEmpty() || body.isEmpty()) error("私信标题和内容不能为空")
            val payload = JSONObject()
                .put("raw", body)
                .put("title", subject)
                .put("archetype", "private_message")
                .put("target_usernames", org.json.JSONArray(recipients))
            val data = action("POST", "/posts.json", "$ORIGIN/", payload)
            val root = JSONObject(data)
            root.optLong("topic_id").takeIf { it > 0 } ?: root.optLong("id")
        }

    suspend fun uploadImage(fileName: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) error("图片为空")
            if (bytes.size > 20L * 1024 * 1024) error("图片超过 20 MB")
            val safeName = fileName.trim().ifBlank { "image.jpg" }
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("type", "composer")
                .addFormDataPart(
                    "files[]",
                    safeName,
                    bytes.toRequestBody("application/octet-stream".toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url("$ORIGIN/uploads.json")
                .header("Referer", "$ORIGIN/")
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", csrfToken())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json")
                .header("User-Agent", HiveHttp.USER_AGENT)
                .post(multipart)
                .build()
            HiveHttp.client(context).newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        JSONObject(text).optJSONArray("errors")?.optString(0)
                    }.getOrNull().orEmpty()
                    error(message.ifBlank { "上传失败：HTTP ${response.code}" })
                }
                val root = JSONObject(text)
                val short = root.optString("short_url").ifBlank { root.optString("url") }
                if (short.isEmpty()) error("水源没有返回图片地址")
                short
            }
        }

    /** 图片灯箱用：带水源会话 Cookie 取原图，兼容相对路径。 */
    suspend fun imageBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val absolute = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            ORIGIN + url
        }
        val request = Request.Builder()
            .url(absolute)
            .header("Referer", "$ORIGIN/")
            .header("User-Agent", HiveHttp.USER_AGENT)
            .build()
        HiveHttp.client(context).newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("图片加载失败：HTTP ${response.code}")
            val body = response.body ?: error("图片内容为空")
            if (body.contentLength() > 40L * 1024 * 1024) error("图片超过 40 MB")
            body.bytes()
        }
    }

    private suspend fun action(
        method: String,
        path: String,
        referer: String,
        payload: JSONObject? = null,
    ): String {
        val result = HiveHttp.raw(
            context,
            "$ORIGIN$path",
            referer,
            method = method,
            origin = ORIGIN,
            jsonBody = payload?.toString(),
            headers = mapOf("X-CSRF-Token" to csrfToken()),
        )
        if (result.code !in 200..299) {
            val message = runCatching {
                JSONObject(result.body).optJSONArray("errors")?.optString(0)
            }.getOrNull().orEmpty()
            error(message.ifBlank { "操作失败：HTTP ${result.code}" })
        }
        return result.body
    }

    suspend fun topic(id: Long): ShuiyuanTopicDetail = withContext(Dispatchers.IO) {        val body = getJson("$ORIGIN/t/$id.json", "$ORIGIN/t/$id")
        val root = JSONObject(body)
        val title = root.text("title")
        val postStream = root.optJSONObject("post_stream")
        val posts = mutableListOf<ShuiyuanPost>()
        postStream?.optJSONArray("posts")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { item -> posts += mapPost(item) }
            }
        }
        val stream = mutableListOf<Long>()
        postStream?.optJSONArray("stream")?.let { array ->
            for (index in 0 until array.length()) {
                stream += array.optLong(index)
            }
        }
        ShuiyuanTopicDetail(
            id = id,
            title = title,
            posts = posts,
            stream = stream.ifEmpty { posts.map { it.id } },
            bookmarked = root.optBoolean("bookmarked"),
        )
    }


    /** 带静默重登的读取：401/403 先走一次 jAccount SSO，成功后重试原请求。 */
    private suspend fun getJson(url: String, referer: String, acceptJson: Boolean = true): String =
        withContext(Dispatchers.IO) {
            try {
                HiveHttp.get(context, url, referer, acceptJson)
            } catch (cause: IllegalStateException) {
                if (cause.message?.contains("登录已失效") == true && ensureLogin()) {
                    HiveHttp.get(context, url, referer, acceptJson)
                } else {
                    throw cause
                }
            }
        }

    /** 按 id 批量补齐剩余楼层（Discourse 的 posts.json 支持 post_ids[]）。 */
    suspend fun posts(topicId: Long, ids: List<Long>): List<ShuiyuanPost> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val query = ids.joinToString("&") { "post_ids[]=$it" }
        val body = getJson("$ORIGIN/t/$topicId/posts.json?$query", "$ORIGIN/t/$topicId")
        val array = JSONObject(body).optJSONObject("post_stream")?.optJSONArray("posts")
            ?: return@withContext emptyList()
        val result = mutableListOf<ShuiyuanPost>()
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { item -> result += mapPost(item) }
        }
        result
    }

    private fun mapPost(item: JSONObject): ShuiyuanPost {
        var likeCount = 0
        var liked = false
        item.optJSONArray("actions_summary")?.let { actions ->
            for (index in 0 until actions.length()) {
                val action = actions.optJSONObject(index) ?: continue
                if (action.optInt("id") == 2) {
                    likeCount = action.optInt("count")
                    liked = action.optBoolean("acted")
                }
            }
        }
        return ShuiyuanPost(
            id = item.optLong("id"),
            username = item.text("username"),
            cooked = item.text("cooked"),
            createdAt = item.text("created_at"),
            postNumber = item.optInt("post_number"),
            likeCount = likeCount,
            liked = liked,
        )
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
        if (result.code == 403) throw CampusAuthException("没有回复权限，请重新登录水源社区")
        if (result.code !in 200..299) {
            val message = runCatching {
                JSONObject(result.body).optJSONArray("errors")?.optString(0)
            }.getOrNull().orEmpty()
            error(message.ifBlank { "回复失败：HTTP ${result.code}" })
        }
    }

    suspend fun categories(): List<ShuiyuanCategory> = withContext(Dispatchers.IO) {
        val root = JSONObject(getJson("$ORIGIN/categories.json", "$ORIGIN/categories"))
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
            if (result.code == 403) throw CampusAuthException("没有发帖权限，请重新登录水源社区")
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
            JSONObject(getJson("$ORIGIN/session/csrf.json", "$ORIGIN/")).text("csrf")
        }.getOrNull().orEmpty()
        if (csrf.isEmpty()) throw CampusAuthException("水源社区登录已失效，请重新登录")
        return csrf
    }

    suspend fun currentUsername(): String? = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(getJson("$ORIGIN/session/current.json", "$ORIGIN/"))
                .optJSONObject("current_user")?.text("username")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun profile(username: String): ShuiyuanProfile = withContext(Dispatchers.IO) {
        val name = enc(username)
        val root = JSONObject(getJson("$ORIGIN/u/$name.json", "$ORIGIN/u/$name"))
        val user = root.optJSONObject("user") ?: error("没有找到该用户")
        val summary = runCatching {
            JSONObject(
                getJson("$ORIGIN/u/$name/summary.json", "$ORIGIN/u/$name"),
            ).optJSONObject("user_summary")
        }.getOrNull()
        val infos = mutableMapOf<Long, JSONObject>()
        root.optJSONArray("badges")?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                infos[item.optLong("id")] = item
            }
        }
        val typeNames = mutableMapOf<Long, String>()
        root.optJSONArray("badge_types")?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                typeNames[item.optLong("id")] = item.text("name")
            }
        }
        val badges = mutableListOf<ShuiyuanBadge>()
        root.optJSONArray("user_badges")?.let { list ->
            for (index in 0 until list.length()) {
                val granted = list.optJSONObject(index) ?: continue
                val badgeId = granted.optLong("badge_id")
                val info = infos[badgeId] ?: continue
                badges += ShuiyuanBadge(
                    id = badgeId,
                    name = info.text("name"),
                    description = cleanExcerpt(info.text("description")),
                    imageUrl = info.text("image_url"),
                    grantedAt = granted.text("granted_at"),
                    count = granted.optInt("count"),
                    badgeType = typeNames[info.optLong("badge_type_id")].orEmpty(),
                )
            }
        }
        ShuiyuanProfile(
            username = user.text("username").ifBlank { username },
            name = user.text("name"),
            bio = user.text("bio_excerpt").ifBlank { user.text("bio_raw") },
            joinedAt = user.text("created_at").replace("T", " ").take(10),
            topics = summary?.optInt("topic_count", 0) ?: 0,
            posts = summary?.optInt("post_count", 0) ?: 0,
            likesReceived = summary?.optInt("likes_received", 0) ?: 0,
            badgeCount = user.optInt("badge_count", badges.size),
            badges = badges,
        )
    }

    suspend fun activity(username: String): List<ShuiyuanActivity> = withContext(Dispatchers.IO) {
        val name = enc(username)
        val root = JSONObject(
            getJson("$ORIGIN/u/$name/activity/newest.json?page=0", "$ORIGIN/u/$name"),
        )
        val posts = mutableMapOf<Long, JSONObject>()
        root.optJSONArray("posts")?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                posts[item.optLong("id")] = item
            }
        }
        val titles = mutableMapOf<Long, String>()
        root.optJSONArray("topics")?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                titles[item.optLong("id")] = item.text("title")
            }
        }
        val stream = root.optJSONArray("stream") ?: return@withContext emptyList()
        val seen = mutableSetOf<Long>()
        val result = mutableListOf<ShuiyuanActivity>()
        for (index in 0 until stream.length()) {
            val entry = stream.opt(index)
            val postId = when (entry) {
                is Number -> entry.toLong()
                is JSONObject -> entry.optLong("id")
                else -> 0L
            }
            if (postId <= 0 || !seen.add(postId)) continue
            val post = posts[postId] ?: continue
            val topicId = post.optLong("topic_id")
            if (topicId <= 0) continue
            val blurb = cleanExcerpt(post.text("blurb"))
            if (blurb.isEmpty()) continue
            result += ShuiyuanActivity(
                postNumber = post.optInt("post_number"),
                topicId = topicId,
                topicTitle = titles[topicId].orEmpty(),
                blurb = blurb,
                createdAt = post.text("created_at"),
            )
        }
        result
    }

    suspend fun createdTopics(username: String): List<ShuiyuanTopic> = withContext(Dispatchers.IO) {
        val name = enc(username)
        val root = JSONObject(
            getJson("$ORIGIN/topics/created-by/$name.json?page=0", "$ORIGIN/u/$name"),
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
        const val FEATURED_TAG = "精品"

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
