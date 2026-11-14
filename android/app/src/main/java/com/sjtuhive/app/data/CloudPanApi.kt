package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

data class PanShareItem(val path: String, val isFolder: Boolean)

data class PanShareOptions(
    val name: String,
    val permanent: Boolean = false,
    val expireDays: Int = 30,
    val extractionCode: String = "",
    val canPreview: Boolean = true,
    val canDownload: Boolean = true,
    val canSaveToNetDisc: Boolean = false,
)

data class PanShare(
    val id: String,
    val name: String,
    val code: String,
    val url: String,
    val expireTime: String,
    val extractionCode: String,
)

data class RecycledItem(
    val id: String,
    val name: String,
    val path: String,
    val spaceId: String,
    val size: Long,
    val removalTime: String,
    val remainingTime: String,
    val directory: Boolean,
    val canRestore: Boolean,
    val canDelete: Boolean,
    val restoreExpired: Boolean,
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

    /** 下载到缓存目录，供内置预览使用。 */
    suspend fun downloadToCache(path: String, name: String, expectedSize: Long = -1): java.io.File = withContext(Dispatchers.IO) {
        val url = fileUrl(path, download = true)
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "preview.bin" }
        val target = java.io.File(java.io.File(context.cacheDir, "preview"), safe)
        HiveHttp.download(context, url, target, expectedSize = expectedSize)
    }

    /** 云盘组织 ID（分享接口需要）；取当前登录的组织。 */
    suspend fun organizationId(): Int = withContext(Dispatchers.IO) {
        val state = ensureSession()
        val result = HiveHttp.raw(
            context,
            "$ORIGIN/user/v1/organization?user_token=${enc(state.userToken)}" +
                "&device_id=${enc(deviceId())}",
            "$ORIGIN/",
        )
        if (result.code !in 200..299) error("读取云盘组织失败：HTTP ${result.code}")
        val body = result.body.trim()
        val list: org.json.JSONArray = when {
            body.startsWith("[") -> org.json.JSONArray(body)
            else -> {
                val json = JSONObject(body)
                json.optJSONArray("items") ?: json.optJSONArray("organizations")
                    ?: org.json.JSONArray()
            }
        }
        var fallback = 1
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val id = item.optInt("id", 0).takeIf { it > 0 }
                ?: item.optJSONObject("organization")?.optInt("id", 0)?.takeIf { it > 0 }
                ?: 0
            if (id <= 0) continue
            if (item.optBoolean("isLastSignedIn", false)) return@withContext id
            if (fallback == 1) fallback = id
        }
        fallback
    }

    suspend fun fileInfo(path: String): Pair<String, Long> = withContext(Dispatchers.IO) {
        val state = ensureSession()
        val access = accessToken(state)
        val clean = path.trim('/')
        val endpoint = "$ORIGIN/api/v1/file/${enc(access.libraryId)}/${enc(access.spaceId)}/" +
            "${escapePath(clean)}?info&access_token=${enc(access.token)}&content_disposition=attachment"
        val result = HiveHttp.raw(context, endpoint, "$ORIGIN/")
        if (result.code !in 200..299) error("交大云盘返回 HTTP ${result.code}")
        val payload = JSONObject(result.body)
        val url = payload.text("cosUrl")
        if (url.isEmpty()) error("交大云盘没有返回文件地址")
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        if (!host.endsWith(".sjtu.edu.cn") && !host.endsWith(".cofile.net")) {
            error("文件地址不在交大域名下")
        }
        url to longValue(payload, "size")
    }

    suspend fun createShare(items: List<PanShareItem>, options: PanShareOptions): PanShare =
        withContext(Dispatchers.IO) {
            val state = ensureSession()
            val access = accessToken(state)
            val orgId = organizationId()
            val body = JSONObject().apply {
                put("name", options.name)
                put("directoryInfoList", org.json.JSONArray().apply {
                    items.forEach { item ->
                        put(JSONObject().apply {
                            put("spaceOrgId", orgId)
                            put("spaceId", access.spaceId)
                            put("path", item.path)
                            put("type", if (item.isFolder) "dir" else "file")
                        })
                    }
                })
                val expire = if (options.permanent) {
                    java.time.LocalDate.now().plusYears(100)
                } else {
                    java.time.LocalDate.now().plusDays(options.expireDays.coerceAtLeast(1).toLong())
                }
                put("expireTime", expire.toString())
                put("linkToLatestVersion", true)
                put("canPreview", options.canPreview)
                put("canDownload", options.canDownload)
                put("canSaveToNetDisc", options.canSaveToNetDisc)
                options.extractionCode.trim().takeIf { it.isNotEmpty() }
                    ?.let { put("extractionCode", it) }
            }
            val result = HiveHttp.raw(
                context,
                "$ORIGIN/user/v1/share/$orgId/?user_token=${enc(state.userToken)}",
                "$ORIGIN/",
                method = "POST",
                jsonBody = body.toString(),
            )
            if (result.code !in 200..299) {
                val message = runCatching { JSONObject(result.body).text("message") }
                    .getOrNull().orEmpty()
                error(message.ifBlank { "创建分享失败：HTTP ${result.code}" })
            }
            val payload = JSONObject(result.body)
            val code = payload.text("code")
            if (code.isEmpty()) error("云盘没有返回分享码")
            val endpoint = payload.text("endpoint").trimEnd('/').ifBlank { ORIGIN }
            PanShare(
                id = payload.text("id"),
                name = options.name,
                code = code,
                url = "$endpoint/web/share/$code",
                expireTime = payload.text("expireTime"),
                extractionCode = options.extractionCode.trim(),
            )
        }

    suspend fun listShares(page: Int = 1, pageSize: Int = 50): List<PanShare> =
        withContext(Dispatchers.IO) {
            val state = ensureSession()
            val orgId = organizationId()
            val json = requestJson(
                "$ORIGIN/user/v1/share/$orgId/list?user_token=${enc(state.userToken)}" +
                    "&page=${page.coerceAtLeast(1)}&page_size=${pageSize.coerceIn(1, 100)}" +
                    "&order_by=creationTime&order_by_type=desc",
            )
            val contents = json.optJSONArray("contents") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until contents.length()) {
                    val item = contents.optJSONObject(index) ?: continue
                    val code = item.text("code")
                    add(
                        PanShare(
                            id = item.text("id"),
                            name = item.text("name"),
                            code = code,
                            url = if (code.isEmpty()) "" else "$ORIGIN/web/share/$code",
                            expireTime = item.text("expireTime"),
                            extractionCode = item.text("extractionCode"),
                        ),
                    )
                }
            }
        }

    suspend fun deleteShare(id: String) = withContext(Dispatchers.IO) {
        val state = ensureSession()
        val orgId = organizationId()
        val result = HiveHttp.raw(
            context,
            "$ORIGIN/user/v1/share/$orgId/delete?user_token=${enc(state.userToken)}",
            "$ORIGIN/",
            method = "POST",
            jsonBody = org.json.JSONArray().put(id).toString(),
        )
        if (result.code !in 200..204) error("取消分享失败：HTTP ${result.code}")
    }

    /** 移入回收站（permanent=false）或彻底删除。 */
    suspend fun delete(path: String, directory: Boolean, permanent: Boolean = false) =
        withContext(Dispatchers.IO) {
            val clean = path.trim('/')
            require(clean.isNotEmpty()) { "不能删除云盘根目录" }
            val state = ensureSession()
            val access = accessToken(state)
            val kind = if (directory) "directory" else "file"
            val endpoint = "$ORIGIN/api/v1/$kind/${enc(access.libraryId)}/${enc(access.spaceId)}/" +
                "${escapePath(clean)}?access_token=${enc(access.token)}&permanent=${if (permanent) 1 else 0}"
            val result = HiveHttp.raw(
                context,
                endpoint,
                "$ORIGIN/",
                method = "DELETE",
                jsonBody = "{}",
            )
            if (result.code == 401 || result.code == 403) throw CampusAuthException("云盘授权已失效，请重新授权")
            if (result.code !in 200..299) {
                val message = runCatching { JSONObject(result.body).text("message") }.getOrNull().orEmpty()
                error(message.ifBlank { "删除失败：HTTP ${result.code}" })
            }
        }

    suspend fun recycled(): List<RecycledItem> = withContext(Dispatchers.IO) {
        val state = ensureSession()
        val orgId = organizationId()
        val access = accessToken(state)
        val endpoint = "$ORIGIN/user/v1/recycled/$orgId/list?user_token=${enc(state.userToken)}" +
            "&limit=50&order_by=delete_time&order_by_type=desc"
        val spaceId = access.spaceId.toIntOrNull() ?: access.spaceId
        val body = JSONObject().apply {
            put(
                "spaceItems",
                org.json.JSONArray().put(
                    JSONObject().put("spaceId", spaceId).put("spaceOrgId", orgId),
                ),
            )
            put("withAllTeamSpace", false)
            put("withAllGroupSpace", false)
        }
        val result = HiveHttp.raw(
            context,
            endpoint,
            "$ORIGIN/",
            method = "POST",
            jsonBody = body.toString(),
        )
        if (result.code == 401 || result.code == 403) throw CampusAuthException("云盘授权已失效，请重新授权")
        if (result.code !in 200..299) error("读取回收站失败：HTTP ${result.code}")
        val payload = JSONObject(result.body)
        val contents = payload.optJSONArray("contents") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until contents.length()) {
                val item = contents.optJSONObject(index) ?: continue
                val id = item.text("recycledItemId")
                if (id.isEmpty()) continue
                val original = item.optJSONArray("originalPath")
                val segments = buildList {
                    if (original != null) {
                        for (part in 0 until original.length()) {
                            original.optString(part).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                val authority = item.optJSONObject("authorityList")
                add(
                    RecycledItem(
                        id = id,
                        name = item.text("name").ifBlank { segments.lastOrNull().orEmpty() },
                        path = segments.joinToString("/"),
                        spaceId = item.text("spaceId"),
                        size = longValue(item, "size"),
                        removalTime = item.text("removalTime"),
                        remainingTime = item.text("remainingTime"),
                        directory = item.text("type").equals("dir", ignoreCase = true),
                        canRestore = authority?.optBoolean("canRestore", true) ?: true,
                        canDelete = authority?.optBoolean("canDelete", true) ?: true,
                        restoreExpired = item.optBoolean("isOverRestoreTime", false),
                    ),
                )
            }
        }
    }

    suspend fun restoreRecycled(items: List<RecycledItem>) = recycledAction("restore", items)

    suspend fun deleteRecycled(items: List<RecycledItem>) = recycledAction("delete", items)

    private suspend fun recycledAction(action: String, items: List<RecycledItem>) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            val state = ensureSession()
            val orgId = organizationId()
            val body = JSONObject().put(
                "recycledItems",
                org.json.JSONArray().apply {
                    items.forEach { item ->
                        put(
                            JSONObject()
                                .put("spaceId", item.spaceId)
                                .put("recycledItemId", item.id),
                        )
                    }
                },
            )
            val endpoint = "$ORIGIN/user/v1/recycled/$orgId?$action&user_token=${enc(state.userToken)}"
            val result = HiveHttp.raw(
                context,
                endpoint,
                "$ORIGIN/",
                method = "POST",
                jsonBody = body.toString(),
            )
            if (result.code == 401 || result.code == 403) throw CampusAuthException("云盘授权已失效，请重新授权")
            if (result.code !in 200..299) {
                val message = runCatching { JSONObject(result.body).text("message") }.getOrNull().orEmpty()
                error(message.ifBlank { "回收站操作失败：HTTP ${result.code}" })
            }
        }

    /**
     * 分片上传。confirmKey 非空时沿用会话，只补传缺失分片；返回新的 confirmKey。
     */
    suspend fun uploadFile(
        local: java.io.File,
        remotePath: String,
        confirmKey: String?,
        onProgress: (Long, Long) -> Unit,
        onSession: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val state = ensureSession()
        val access = accessToken(state)
        val size = local.length()
        val chunkSize = 4 * 1024 * 1024
        val chunkCount = ((size + chunkSize - 1) / chunkSize).coerceAtLeast(1).toInt()
        val parent = remotePath.trim('/').substringBeforeLast('/', "")
        if (parent.isNotEmpty()) ensureDirectory(access, parent)

        var key = confirmKey.orEmpty()
        val uploaded = mutableSetOf<Int>()
        if (key.isNotEmpty()) {
            val info = runCatching { chunkUploadInfo(access, key) }.getOrNull()
            if (info != null && info.confirmed) return@withContext key
            info?.parts?.keys?.let(uploaded::addAll)
        }
        if (key.isEmpty()) {
            key = startChunkUpload(access, remotePath, chunkCount)
        }
        onSession(key)

        var session: ChunkSession? = null
        suspend fun renewFrom(part: Int) {
            val remaining = (part..chunkCount).filter { it !in uploaded }.take(50)
            if (remaining.isEmpty()) return
            session = renewChunkUpload(access, key, remaining)
        }

        var sent = uploaded.size.toLong() * chunkSize
        onProgress(sent.coerceAtMost(size), size)
        java.io.RandomAccessFile(local, "r").use { input ->
            for (part in 1..chunkCount) {
                if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) throw kotlinx.coroutines.CancellationException("传输已暂停")
                if (part in uploaded) continue
                val offset = (part - 1).toLong() * chunkSize
                val length = minOf(chunkSize.toLong(), size - offset).toInt()
                val buffer = ByteArray(length)
                input.seek(offset)
                input.readFully(buffer)
                val current = session
                if (current == null || current.expiringSoon() || current.parts[part] == null) {
                    renewFrom(part)
                }
                val active = session ?: error("上传凭证缺失")
                val partInfo = active.parts[part] ?: error("第 $part 片缺少签名头")
                val base = if (active.domain.startsWith("http://") ||
                    active.domain.startsWith("https://")
                ) {
                    active.domain
                } else {
                    "https://${active.domain}"
                }
                val url = "$base${active.path}?uploadId=${enc(active.uploadId)}&partNumber=$part"
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .put(buffer.toRequestBody("application/octet-stream".toMediaType()))
                    .header("User-Agent", HiveHttp.USER_AGENT)
                    .apply { partInfo.forEach { (name, value) -> header(name, value) } }
                    .build()
                HiveHttp.client(context).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("上传分片失败：HTTP ${response.code}")
                }
                uploaded.add(part)
                sent = uploaded.size.toLong() * chunkSize
                onProgress(sent.coerceAtMost(size), size)
            }
        }
        confirmChunkUpload(access, key)
        onProgress(size, size)
        key
    }

    private suspend fun ensureDirectory(access: PanToken, dir: String) {
        var prefix = ""
        for (segment in dir.split('/').filter { it.isNotEmpty() }) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            val endpoint = "$ORIGIN/api/v1/directory/${enc(access.libraryId)}/" +
                "${enc(access.spaceId)}/${escapePath(prefix)}" +
                "?access_token=${enc(access.token)}&conflict_resolution_strategy=ask"
            val result = HiveHttp.raw(context, endpoint, "$ORIGIN/", method = "PUT", jsonBody = "{}")
            if (result.code in 200..299) continue
            val code = runCatching { JSONObject(result.body).text("code") }.getOrNull().orEmpty()
            if (code != "SameNameDirectoryOrFileExists") error("创建目录失败：HTTP ${result.code}")
        }
    }

    private suspend fun startChunkUpload(
        access: PanToken,
        remotePath: String,
        chunkCount: Int,
    ): String {
        val range = (1..minOf(chunkCount, 50)).toList()
        val body = JSONObject().put("partNumberRange", org.json.JSONArray(range)).toString()
        val endpoint = fileEndpoint(access, remotePath) +
            "?multipart&conflict_resolution_strategy=rename&access_token=${enc(access.token)}"
        val result = HiveHttp.raw(context, endpoint, "$ORIGIN/", method = "POST", jsonBody = body)
        if (result.code !in 200..299) error("云盘没有接受分片上传：HTTP ${result.code}")
        val key = JSONObject(result.body).text("confirmKey")
        if (key.isEmpty()) error("云盘没有返回上传会话")
        return key
    }

    private suspend fun chunkUploadInfo(access: PanToken, key: String): ChunkSession {
        val endpoint = fileEndpoint(access, key) +
            "?upload&no_upload_part_info=1&access_token=${enc(access.token)}"
        val result = HiveHttp.raw(context, endpoint, "$ORIGIN/")
        if (result.code !in 200..299) error("读取上传会话失败：HTTP ${result.code}")
        return ChunkSession.parse(JSONObject(result.body))
    }

    private suspend fun renewChunkUpload(access: PanToken, key: String, parts: List<Int>): ChunkSession {
        val body = JSONObject().put("partNumberRange", org.json.JSONArray(parts)).toString()
        val endpoint = fileEndpoint(access, key) + "?renew&access_token=${enc(access.token)}"
        val result = HiveHttp.raw(context, endpoint, "$ORIGIN/", method = "POST", jsonBody = body)
        if (result.code !in 200..299) error("刷新上传凭证失败：HTTP ${result.code}")
        return ChunkSession.parse(JSONObject(result.body))
    }

    private suspend fun confirmChunkUpload(access: PanToken, key: String) {
        val endpoint = fileEndpoint(access, key) +
            "?confirm&conflict_resolution_strategy=overwrite&access_token=${enc(access.token)}"
        val result = HiveHttp.raw(context, endpoint, "$ORIGIN/", method = "POST", jsonBody = "{}")
        if (result.code !in 200..299) error("确认上传失败：HTTP ${result.code}")
    }

    private fun fileEndpoint(access: PanToken, path: String): String =
        "$ORIGIN/api/v1/file/${enc(access.libraryId)}/${enc(access.spaceId)}/" +
            escapePath(path.trim('/'))

    private data class ChunkSession(
        val domain: String,
        val path: String,
        val uploadId: String,
        val expiration: Long,
        val parts: Map<Int, Map<String, String>>,
        val confirmed: Boolean,
    ) {
        fun expiringSoon(): Boolean =
            expiration > 0 && expiration - System.currentTimeMillis() < 30_000

        companion object {
            fun parse(json: JSONObject): ChunkSession {
                val partsJson = json.optJSONObject("parts")
                val parts = mutableMapOf<Int, Map<String, String>>()
                if (partsJson != null) {
                    for (key in partsJson.keys()) {
                        val number = key.toIntOrNull() ?: continue
                        val item = partsJson.optJSONObject(key) ?: continue
                        val headers = mutableMapOf<String, String>()
                        item.optJSONObject("headers")?.let { headerJson ->
                            for (name in headerJson.keys()) headers[name] = headerJson.text(name)
                        }
                        parts[number] = headers
                    }
                }
                val rawExpiration = json.text("expiration")
                return ChunkSession(
                    domain = json.text("domain"),
                    path = json.text("path"),
                    uploadId = json.text("uploadId"),
                    expiration = runCatching {
                        java.time.Instant.parse(rawExpiration).toEpochMilli()
                    }.getOrDefault(0L),
                    parts = parts,
                    confirmed = json.optBoolean("confirmed", false),
                )
            }
        }
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
        return value.split("/").joinToString("/") { enc(it).replace("+", "%20") }
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
