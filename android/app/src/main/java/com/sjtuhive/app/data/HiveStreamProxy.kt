package com.sjtuhive.app.data

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本地视频流代理：把带请求头的远端 MP4 转成 127.0.0.1 的普通 HTTP 流。
 *
 * 桌面端（Go）的 media 代理就是这么做的——远端 CDN 需要 Referer/Origin，
 * 且部分录像直连时会出现缓冲异常；统一走本地代理后：
 * - 请求头在代理侧注入，播放器只面对本地地址；
 * - Range 分段请求原样透传（拖动进度依赖它）；
 * - OkHttp 负责重定向与连接复用。
 */
object HiveStreamProxy {
    private data class Source(val url: String, val headers: Map<String, String>)

    private val sources = ConcurrentHashMap<String, Source>()
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile
    private var port: Int = 0

    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "hive-stream-proxy").apply { isDaemon = true }
    }

    /** 注册一个远端流，返回可交给播放器的本地地址。 */
    fun register(url: String, headers: Map<String, String>): String {
        startIfNeeded()
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        sources[token] = Source(url, headers)
        return "http://127.0.0.1:$port/v/$token"
    }

    private fun startIfNeeded() {
        if (port != 0) return
        synchronized(this) {
            if (port != 0) return
            val socket = ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"))
            port = socket.localPort
            val acceptor = socket
            executor.execute {
                while (!acceptor.isClosed) {
                    val client = runCatching { acceptor.accept() }.getOrNull() ?: break
                    executor.execute { serve(client) }
                }
            }
        }
    }

    private fun serve(client: Socket) {
        runCatching {
            client.use { socket ->
                socket.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]
                val headerMap = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headerMap[line.take(idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }

                val token = path.substringAfter("/v/", "").substringBefore('?')
                val source = sources[token]
                if (source == null) {
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        flush()
                    }
                    return
                }

                val builder = Request.Builder().url(source.url)
                source.headers.forEach { (name, value) -> builder.header(name, value) }
                headerMap["range"]?.let { builder.header("Range", it) }

                val upstream = httpClient.newCall(builder.build()).execute()
                upstream.use { response ->
                    val out = socket.getOutputStream()
                    val statusLine = "HTTP/1.1 ${response.code} ${response.message}\r\n"
                    val head = StringBuilder(statusLine)
                    val passthrough = listOf(
                        "Content-Length", "Content-Range", "Content-Type",
                        "Accept-Ranges", "Last-Modified", "ETag",
                    )
                    for (name in passthrough) {
                        response.header(name)?.let { head.append("$name: $it\r\n") }
                    }
                    head.append("Connection: close\r\n\r\n")
                    out.write(head.toString().toByteArray())
                    out.flush()

                    if (method.equals("HEAD", ignoreCase = true)) return

                    val body = response.body ?: return
                    val input = BufferedInputStream(body.byteStream(), 64 * 1024)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        out.flush()
                    }
                }
            }
        }
    }
}
