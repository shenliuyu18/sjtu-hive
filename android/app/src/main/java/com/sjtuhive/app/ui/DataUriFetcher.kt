package com.sjtuhive.app.ui

import android.net.Uri
import android.util.Base64
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer

/** Coil 2 默认不处理 data: URI；办事图标接口返回的正是 base64 data URI。 */
class DataUriFetcher(private val uri: Uri, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val raw = uri.toString()
        val comma = raw.indexOf(',')
        require(comma > 0) { "无效的 data URI" }
        val header = raw.substring(5, comma)
        val payload = raw.substring(comma + 1)
        val bytes = if (header.endsWith(";base64")) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray()
        }
        return SourceResult(
            source = ImageSource(Buffer().apply { write(bytes) }, options.context),
            mimeType = header.substringBefore(';'),
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == "data") DataUriFetcher(data, options) else null
    }
}
