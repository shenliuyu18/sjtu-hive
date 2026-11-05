package com.sjtuhive.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min

/**
 * 轻量 PPTX 幻灯片渲染：按 OOXML 解析版式与页面，把形状/文本/图片/表格画到位图上，
 * 由界面按 PDF 预览的方式纵向翻页。
 */
internal class PptxDocument(private val file: File, private val cacheDir: File) : Closeable {

    private val zip = ZipFile(file)
    private val theme = ThemeColors()
    private val slides: List<SlideRef>
    private val slideVideos: List<String?>
    private val framesDir = File(cacheDir, "pptx-frames")
    private val videosDir = File(cacheDir, "pptx-videos")
    private val videoFrames = mutableMapOf<String, Bitmap?>()
    private val cache = object : LinkedHashMap<Long, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean = size > 6
    }

    val slideWidthEmu: Float
    val slideHeightEmu: Float

    init {
        val presentation = xml("ppt/presentation.xml") ?: error("无法解析演示文稿")
        val size = presentation.child("p:sldSz")
        slideWidthEmu = size?.attr("cx")?.toFloatOrNull() ?: 9_144_000f
        slideHeightEmu = size?.attr("cy")?.toFloatOrNull() ?: 5_143_500f
        theme.load(xml("ppt/theme/theme1.xml"))
        val rels = relationships("ppt/_rels/presentation.xml.rels")
        val ids = presentation.child("p:sldIdLst")?.children("p:sldId").orEmpty()
        val ordered = ids.mapNotNull { rels[it.attr("r:id")]?.first }
        slides = if (ordered.isNotEmpty()) {
            ordered.map { SlideRef(it, layoutOf(it)) }
        } else {
            zip.entries().toList()
                .map { it.name }
                .filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
                .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: 0 }
                .map { SlideRef(it, layoutOf(it)) }
        }
        slideVideos = slides.map { videoPartOf(it.path) }
    }

    fun videoOf(index: Int): String? = slideVideos.getOrNull(index)

    @Synchronized
    fun videoFile(part: String): File? = runCatching {
        val name = part.substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(videosDir, name)
        if (target.exists() && target.length() > 0) return target
        videosDir.mkdirs()
        val entry = zip.getEntry(part) ?: return null
        val temp = File(videosDir, "$name.part")
        zip.getInputStream(entry).use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target
    }.getOrNull()

    private fun videoPartOf(slidePath: String): String? {
        val slide = xml(slidePath) ?: return null
        val rels = relationships(relsName(slidePath))
        val pictures = slide.getElementsByTagName("p:pic")
        for (index in 0 until pictures.length) {
            val picture = pictures.item(index) as? Element ?: continue
            val link = (picture.getElementsByTagName("a:videoFile").item(0) as? Element)?.attr("r:link")
            val embed = (picture.getElementsByTagName("p14:media").item(0) as? Element)?.attr("r:embed")
            val part = rels[link]?.first ?: rels[embed]?.first ?: continue
            return part
        }
        return null
    }

    val slideCount: Int get() = slides.size
    val aspectRatio: Float get() = slideHeightEmu / slideWidthEmu

    @Synchronized
    fun render(index: Int, targetWidth: Int): Bitmap {
        val key = index * 100_000L + targetWidth
        cache[key]?.let { return it }
        val ref = slides[index]
        val scale = targetWidth / slideWidthEmu
        val height = (slideHeightEmu * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val painter = SlidePainter(canvas, scale, theme, zip) { part -> videoFrame(part) }

        val layoutPath = ref.layoutPath
        val layout = layoutPath?.let { xml(it) }
        val masterPath = layoutPath?.let { masterOf(it) }
        val master = masterPath?.let { xml(it) }
        val background = colorOf(ref.path, "p:cSld/p:bg/p:bgPr/a:solidFill")
            ?: layoutPath?.let { colorOf(it, "p:cSld/p:bg/p:bgPr/a:solidFill") }
            ?: masterPath?.let { colorOf(it, "p:cSld/p:bg/p:bgPr/a:solidFill") }
        if (background != null) canvas.drawColor(background)

        val root = Xf(0f, 0f, 1f, 1f)
        if (layout?.attr("showMasterSp") != "0" && masterPath != null) {
            painter.drawTree(
                master?.child("p:cSld")?.child("p:spTree"),
                relationships(relsName(masterPath)),
                root,
                true,
            )
        }
        if (layoutPath != null) {
            painter.drawTree(
                layout?.child("p:cSld")?.child("p:spTree"),
                relationships(relsName(layoutPath)),
                root,
                true,
            )
        }
        painter.drawTree(xml(ref.path)?.child("p:cSld")?.child("p:spTree"), relationships(relsName(ref.path)), root, false)

        cache[key] = bitmap
        return bitmap
    }

    private fun videoFrame(part: String): Bitmap? {
        if (videoFrames.containsKey(part)) return videoFrames[part]
        val frame = runCatching {
            val entry = zip.getEntry(part) ?: return null
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            framesDir.mkdirs()
            val temp = File.createTempFile("frame", ".mp4", framesDir)
            try {
                temp.writeBytes(bytes)
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(temp.absolutePath)
                    retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } finally {
                temp.delete()
            }
        }.getOrNull()
        videoFrames[part] = frame
        return frame
    }

    override fun close() {
        cache.clear()
        videoFrames.clear()
        zip.close()
    }

    private fun layoutOf(slidePath: String): String? =
        relationships(relsName(slidePath)).values.firstOrNull {
            it.second.endsWith("/slideLayout")
        }?.first

    private fun masterOf(layoutPath: String): String? =
        relationships(relsName(layoutPath)).values.firstOrNull {
            it.second.endsWith("/slideMaster")
        }?.first

    private fun relsName(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        val name = part.substringAfterLast('/')
        return "$dir/_rels/$name.rels"
    }

    private fun colorOf(part: String, path: String): Int? {
        var node: Element? = xml(part) ?: return null
        for (step in path.split('/')) {
            node = node?.child(step)
        }
        return theme.fillColor(node?.parentNode as? Element)
    }

    private fun xml(part: String): Element? = runCatching {
        val entry = zip.getEntry(part) ?: return null
        zip.getInputStream(entry).use { stream ->
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            factory.isExpandEntityReferences = false
            factory.newDocumentBuilder().parse(stream).documentElement
        }
    }.getOrNull()

    /** id -> (部件绝对路径, 关系类型) */
    private fun relationships(relsPath: String): Map<String, Pair<String, String>> {
        val root = xml(relsPath) ?: return emptyMap()
        val base = relsPath.substringBefore("/_rels/").ifEmpty { "" }
        return root.children("Relationship").mapNotNull { node ->
            val id = node.attr("Id") ?: return@mapNotNull null
            val target = node.attr("Target") ?: return@mapNotNull null
            id to (resolve(base, target) to node.attr("Type").orEmpty())
        }.toMap()
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val parts = base.split('/').filter { it.isNotEmpty() }.toMutableList()
        target.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    private data class SlideRef(val path: String, val layoutPath: String?)
}

private data class Xf(val ox: Float, val oy: Float, val sx: Float, val sy: Float) {
    fun x(value: Float) = ox + sx * value
    fun y(value: Float) = oy + sy * value
}

private data class ParagraphLine(val layout: StaticLayout, val before: Float, val indent: Float)

private class ThemeColors {
    private val colors = mutableMapOf<String, Int>()

    fun load(root: Element?) {
        val scheme = root?.child("a:themeElements")?.child("a:clrScheme") ?: return
        for (child in scheme.children()) {
            val value = child.child("a:srgbClr")?.attr("val")
                ?: child.child("a:sysClr")?.attr("lastClr")
                ?: continue
            runCatching { colors[child.tagName.substringAfter(':')] = Color.parseColor("#$value") }
        }
    }

    fun scheme(name: String): Int? = when (name) {
        "tx1" -> colors["dk1"]
        "tx2" -> colors["dk2"]
        "bg1" -> colors["lt1"]
        "bg2" -> colors["lt2"]
        "phClr" -> colors["lt1"]
        else -> colors[name]
    }

    fun fillColor(fill: Element?): Int? {
        if (fill == null) return null
        val solid = fill.child("a:solidFill")
        if (solid != null) return paint(solid)
        val gradient = fill.child("a:gradFill")
        if (gradient != null) {
            return gradient.getElementsByTagName("a:gs").item(0)?.let { paint(it as Element) }
        }
        return null
    }

    fun paint(container: Element?): Int? {
        if (container == null) return null
        val srgb = container.child("a:srgbClr")
        val scheme = container.child("a:schemeClr")
        if (srgb == null && scheme == null) return null
        var color = srgb?.let { runCatching { Color.parseColor("#${it.attr("val")}") }.getOrNull() }
            ?: scheme?.let { schemeColor(it) }
            ?: return null
        val holder = srgb ?: scheme ?: return color
        val alpha = holder.child("a:alpha")?.attr("val")?.toFloatOrNull()?.div(100_000f)
        if (alpha != null) color = ColorUtils.applyAlpha(color, alpha)
        return color
    }

    private fun schemeColor(node: Element): Int? {
        var color = scheme(node.attr("val").orEmpty()) ?: return null
        node.child("a:lumMod")?.attr("val")?.toFloatOrNull()?.let { color = ColorUtils.scaleLum(color, it / 100_000f) }
        node.child("a:lumOff")?.attr("val")?.toFloatOrNull()?.let { color = ColorUtils.offsetLum(color, it / 100_000f) }
        return color
    }
}

private object ColorUtils {
    fun applyAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun scaleLum(color: Int, factor: Float): Int = withLum(color) { (it * factor).coerceIn(0f, 1f) }
    fun offsetLum(color: Int, offset: Float): Int = withLum(color) { (it + offset).coerceIn(0f, 1f) }

    private fun withLum(color: Int, change: (Float) -> Float): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] = change(hsv[2])
        return Color.HSVToColor(Color.alpha(color), hsv)
    }
}

private class SlidePainter(
    private val canvas: Canvas,
    private val scale: Float,
    private val theme: ThemeColors,
    private val zip: ZipFile,
    private val frameOf: (String) -> Bitmap?,
) {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val mediaScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 0, 0, 0)
    }
    private val mediaMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val pxPerEmu = scale

    fun drawTree(tree: Element?, rels: Map<String, Pair<String, String>>, parent: Xf, skipPlaceholders: Boolean) {
        tree ?: return
        for (node in tree.children()) {
            when (node.tagName) {
                "p:sp", "p:cxnSp" -> if (!(skipPlaceholders && node.isPlaceholder())) drawShape(node, rels, parent)
                "p:pic" -> if (!(skipPlaceholders && node.isPlaceholder())) drawPicture(node, rels, parent)
                "p:grpSp" -> drawGroup(node, rels, parent, skipPlaceholders)
                "p:graphicFrame" -> drawGraphicFrame(node, rels, parent)
            }
        }
    }

    private fun drawGroup(node: Element, rels: Map<String, Pair<String, String>>, parent: Xf, skipPlaceholders: Boolean) {
        val transform = node.child("p:grpSpPr")?.child("a:xfrm") ?: return
        val offset = transform.child("a:off") ?: return
        val extent = transform.child("a:ext") ?: return
        val childOffset = transform.child("a:chOff") ?: offset
        val childExtent = transform.child("a:chExt") ?: extent
        val sx = childExtent.num("cx").takeIf { it > 0 }?.let { extent.num("cx") / it } ?: 1f
        val sy = childExtent.num("cy").takeIf { it > 0 }?.let { extent.num("cy") / it } ?: 1f
        val child = Xf(
            parent.x(offset.num("x") - childOffset.num("x") * sx),
            parent.y(offset.num("y") - childOffset.num("y") * sy),
            parent.sx * sx,
            parent.sy * sy,
        )
        drawTree(node, rels, child, skipPlaceholders)
    }

    private fun drawShape(node: Element, rels: Map<String, Pair<String, String>>, space: Xf) {
        val spPr = node.child("p:spPr") ?: return
        val transform = spPr.child("a:xfrm") ?: return
        val rect = rect(transform, space)
        val rotation = transform.attr("rot")?.toFloatOrNull()?.div(60_000f) ?: 0f
        val geometry = spPr.child("a:prstGeom")?.attr("prst").orEmpty()
        val fill = theme.fillColor(spPr)
        val line = spPr.child("a:ln")
        val strokeWidth = line?.attr("w")?.toFloatOrNull()?.times(pxPerEmu) ?: 0f
        val strokeColor = line?.let { theme.paint(it.child("a:solidFill")) }?.takeIf { strokeWidth > 0f }
        val body = node.child("p:txBody")

        canvas.save()
        if (rotation != 0f) canvas.rotate(rotation, rect.centerX(), rect.centerY())
        if (fill != null) {
            fillPaint.color = fill
            when (geometry) {
                "ellipse" -> canvas.drawOval(rect, fillPaint)
                "roundRect" -> canvas.drawRoundRect(rect, min(rect.width(), rect.height()) * 0.1f, min(rect.width(), rect.height()) * 0.1f, fillPaint)
                "line" -> Unit
                else -> canvas.drawRect(rect, fillPaint)
            }
        }
        if (strokeColor != null) {
            linePaint.color = strokeColor
            linePaint.strokeWidth = strokeWidth
            when (geometry) {
                "ellipse" -> canvas.drawOval(rect, linePaint)
                "roundRect" -> canvas.drawRoundRect(rect, min(rect.width(), rect.height()) * 0.1f, min(rect.width(), rect.height()) * 0.1f, linePaint)
                "line" -> canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, linePaint)
                else -> canvas.drawRect(rect, linePaint)
            }
        }
        if (body != null && !body.isPromptText()) {
            canvas.save()
            canvas.clipRect(rect)
            drawText(body, rect)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawPicture(node: Element, rels: Map<String, Pair<String, String>>, space: Xf) {
        val transform = node.child("p:spPr")?.child("a:xfrm") ?: return
        val rect = rect(transform, space)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val blip = node.child("p:blipFill")?.child("a:blip") ?: return
        val part = rels[blip.attr("r:embed")]?.first ?: return
        val video = videoPart(node, rels)
        val frame = video?.let(frameOf)
        val bitmap = frame ?: decode(part, rect.width().toInt(), rect.height().toInt())
        val rotation = transform.attr("rot")?.toFloatOrNull()?.div(60_000f) ?: 0f
        val src = Rect(0, 0, bitmap?.width ?: 1, bitmap?.height ?: 1)
        if (bitmap != null) {
            node.child("p:blipFill")?.child("a:srcRect")?.let { crop ->
                val left = crop.attr("l")?.toFloatOrNull()?.div(100_000f) ?: 0f
                val top = crop.attr("t")?.toFloatOrNull()?.div(100_000f) ?: 0f
                val right = crop.attr("r")?.toFloatOrNull()?.div(100_000f) ?: 0f
                val bottom = crop.attr("b")?.toFloatOrNull()?.div(100_000f) ?: 0f
                src.set(
                    (bitmap.width * left).toInt(),
                    (bitmap.height * top).toInt(),
                    (bitmap.width * (1f - right)).toInt().coerceAtMost(bitmap.width),
                    (bitmap.height * (1f - bottom)).toInt().coerceAtMost(bitmap.height),
                )
                if (src.width() <= 0 || src.height() <= 0) src.set(0, 0, bitmap.width, bitmap.height)
            }
        }
        canvas.save()
        if (rotation != 0f) canvas.rotate(rotation, rect.centerX(), rect.centerY())
        if (video != null) {
            fillPaint.color = Color.rgb(18, 18, 20)
            canvas.drawRect(rect, fillPaint)
        }
        if (bitmap != null) canvas.drawBitmap(bitmap, src, rect, imagePaint)
        if (video != null) {
            val radius = min(rect.width(), rect.height()) * 0.075f
            canvas.drawCircle(rect.centerX(), rect.centerY(), radius, mediaScrim)
            val triangle = android.graphics.Path().apply {
                moveTo(rect.centerX() - radius * 0.25f, rect.centerY() - radius * 0.42f)
                lineTo(rect.centerX() + radius * 0.5f, rect.centerY())
                lineTo(rect.centerX() - radius * 0.25f, rect.centerY() + radius * 0.42f)
                close()
            }
            canvas.drawPath(triangle, mediaMark)
        }
        canvas.restore()
    }

    private fun videoPart(node: Element, rels: Map<String, Pair<String, String>>): String? {
        val holder = node.child("p:nvPicPr")?.child("p:nvPr") ?: return null
        val ids = mutableListOf<String>()
        val videos = holder.getElementsByTagName("a:videoFile")
        for (index in 0 until videos.length) {
            (videos.item(index) as? Element)?.attr("r:link")?.let { ids += it }
        }
        val media = holder.getElementsByTagName("p14:media")
        for (index in 0 until media.length) {
            (media.item(index) as? Element)?.attr("r:embed")?.let { ids += it }
        }
        return ids.firstNotNullOfOrNull { rels[it]?.first }
    }

    private fun drawGraphicFrame(node: Element, rels: Map<String, Pair<String, String>>, space: Xf) {
        val transform = node.child("p:xfrm") ?: return
        val rect = rect(transform, space)
        val table = node.child("a:graphic")?.child("a:graphicData")?.child("a:tbl") ?: return
        val columns = table.child("a:tblGrid")?.children("a:gridCol").orEmpty()
            .map { it.num("w") * pxPerEmu }
        if (columns.isEmpty()) return
        val total = columns.sum()
        val scaleX = if (total > 0f) rect.width() / total else 1f
        var y = rect.top
        for (row in table.children("a:tr")) {
            val rowHeight = row.num("h") * pxPerEmu
            var x = rect.left
            val cells = row.children("a:tc")
            for ((index, cell) in cells.withIndex()) {
                val width = (columns.getOrNull(index) ?: 0f) * scaleX
                val cellRect = RectF(x, y, x + width, y + rowHeight)
                val cellFill = theme.fillColor(cell.child("a:tcPr"))
                if (cellFill != null) {
                    fillPaint.color = cellFill
                    canvas.drawRect(cellRect, fillPaint)
                }
                linePaint.color = Color.argb(60, 0, 0, 0)
                linePaint.strokeWidth = max(1f, scale * 12_700f)
                canvas.drawRect(cellRect, linePaint)
                cell.child("a:txBody")?.let { body ->
                    canvas.save()
                    canvas.clipRect(cellRect)
                    drawText(body, cellRect, 45_720f)
                    canvas.restore()
                }
                x += width
            }
            y += rowHeight
        }
    }

    private fun drawText(body: Element, rect: RectF, defaultInset: Float = 91_440f) {
        val bodyPr = body.child("a:bodyPr")
        val leftInset = (bodyPr?.attr("lIns")?.toFloatOrNull() ?: defaultInset) * pxPerEmu
        val rightInset = (bodyPr?.attr("rIns")?.toFloatOrNull() ?: defaultInset) * pxPerEmu
        val topInset = (bodyPr?.attr("tIns")?.toFloatOrNull() ?: defaultInset / 2f) * pxPerEmu
        val bottomInset = (bodyPr?.attr("bIns")?.toFloatOrNull() ?: defaultInset / 2f) * pxPerEmu
        val autoFit = bodyPr?.child("a:normAutofit")
        val fontScale = autoFit?.attr("fontScale")?.toFloatOrNull()?.div(100_000f) ?: 1f
        val lineScale = (1f - (autoFit?.attr("lnSpcReduction")?.toFloatOrNull()?.div(100_000f) ?: 0f))
            .coerceAtLeast(0.5f)
        val width = (rect.width() - leftInset - rightInset).toInt().coerceAtLeast(1)
        val counter = IntArray(9)
        textPaint.textSize = 18f * 12_700f * pxPerEmu * fontScale

        val lines = mutableListOf<ParagraphLine>()
        for (paragraph in body.children("a:p")) {
            val pPr = paragraph.child("a:pPr")
            val level = pPr?.attr("lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
            val align = when (pPr?.attr("algn")) {
                "ctr" -> Layout.Alignment.ALIGN_CENTER
                "r" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            var bullet: String? = null
            when {
                pPr == null || pPr.child("a:buNone") != null -> Unit
                pPr.child("a:buAutoNum") != null -> {
                    counter[level] += 1
                    bullet = "${counter[level]}. "
                }
                pPr.child("a:buChar") != null -> bullet = (pPr.child("a:buChar")?.attr("char") ?: "•") + " "
            }
            val text = runs(paragraph, fontScale)
            val content: CharSequence = if (bullet.isNullOrEmpty()) {
                text
            } else {
                SpannableStringBuilder(bullet).append(text)
            }
            val layout = StaticLayout.Builder
                .obtain(content, 0, content.length, textPaint, width)
                .setAlignment(align)
                .setLineSpacing(0f, lineScale)
                .setIncludePad(false)
                .build()
            val before = pPr?.child("a:spcBef")?.child("a:spcPts")?.attr("val")?.toFloatOrNull()
                ?.div(100f)?.times(12_700f * pxPerEmu * fontScale) ?: 0f
            val marL = (pPr?.attr("marL")?.toFloatOrNull() ?: 0f) * pxPerEmu
            lines += ParagraphLine(layout, before, marL)
        }

        val totalHeight = lines.fold(0f) { sum, line -> sum + line.before + line.layout.height }
        var y = rect.top + topInset + when (bodyPr?.attr("anchor")) {
            "ctr" -> ((rect.height() - topInset - bottomInset - totalHeight) / 2f).coerceAtLeast(0f)
            "b" -> (rect.height() - topInset - bottomInset - totalHeight).coerceAtLeast(0f)
            else -> 0f
        }
        for (line in lines) {
            y += line.before
            canvas.save()
            canvas.translate(rect.left + leftInset + line.indent, y)
            line.layout.draw(canvas)
            canvas.restore()
            y += line.layout.height
        }
    }

    private fun runs(paragraph: Element, fontScale: Float): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        for (node in paragraph.children()) {
            when (node.tagName) {
                "a:r", "a:fld" -> {
                    val text = node.child("a:t")?.textContent ?: continue
                    val props = node.child("a:rPr")
                    val start = builder.length
                    builder.append(text)
                    val end = builder.length
                    val size = (props?.attr("sz")?.toFloatOrNull() ?: 1800f) / 100f * 12_700f * pxPerEmu * fontScale
                    builder.setSpan(AbsoluteSizeSpan(size.toInt().coerceAtLeast(1)), start, end, 0)
                    val bold = props?.attr("b") == "1"
                    val italic = props?.attr("i") == "1"
                    if (bold || italic) {
                        val style = when {
                            bold && italic -> Typeface.BOLD_ITALIC
                            bold -> Typeface.BOLD
                            else -> Typeface.ITALIC
                        }
                        builder.setSpan(StyleSpan(style), start, end, 0)
                    }
                    if (props?.attr("u")?.let { it != "none" } == true) {
                        builder.setSpan(UnderlineSpan(), start, end, 0)
                    }
                    theme.paint(props?.child("a:solidFill"))?.let {
                        builder.setSpan(ForegroundColorSpan(it), start, end, 0)
                    }
                }
                "a:br" -> builder.append('\n')
            }
        }
        return builder
    }

    private fun decode(part: String, targetWidth: Int, targetHeight: Int): Bitmap? = runCatching {
        val entry = zip.getEntry(part) ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    private fun rect(transform: Element, space: Xf): RectF {
        val offset = transform.child("a:off") ?: return RectF()
        val extent = transform.child("a:ext") ?: return RectF()
        val left = space.x(offset.num("x")) * pxPerEmu
        val top = space.y(offset.num("y")) * pxPerEmu
        val right = space.x(offset.num("x") + extent.num("cx")) * pxPerEmu
        val bottom = space.y(offset.num("y") + extent.num("cy")) * pxPerEmu
        return RectF(left, top, right, bottom)
    }
}

private fun Element.isPlaceholder(): Boolean =
    child("p:nvSpPr")?.child("p:nvPr")?.child("p:ph") != null ||
        child("p:nvPicPr")?.child("p:nvPr")?.child("p:ph") != null

private val masterPrompts = setOf(
    "单击此处编辑母版标题样式",
    "单击此处编辑母版副标题样式",
    "单击此处编辑母版文本样式",
    "Click to edit Master title style",
    "Click to edit Master subtitle style",
    "Click to edit Master text styles",
)

/** 母版里的提示文案在放映时不显示，渲染时同样跳过。 */
private fun Element.isPromptText(): Boolean {
    val nodes = getElementsByTagName("a:t")
    if (nodes.length == 0) return false
    val text = buildString {
        for (index in 0 until nodes.length) {
            append((nodes.item(index) as? Element)?.textContent.orEmpty())
        }
    }
    val compact = text.replace(Regex("\\s+"), "")
    return masterPrompts.any { it.replace(Regex("\\s+"), "") == compact }
}

private fun Element?.child(tag: String): Element? {
    val nodes = this?.childNodes ?: return null
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node is Element && node.tagName == tag) return node
    }
    return null
}

private fun Element.children(tag: String? = null): List<Element> {
    val nodes = childNodes
    val result = ArrayList<Element>(nodes.length)
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node is Element && (tag == null || node.tagName == tag)) result += node
    }
    return result
}

private fun Element.attr(name: String): String? =
    if (hasAttribute(name)) getAttribute(name).takeIf { it.isNotEmpty() } else null

private fun Element.num(name: String): Float = attr(name)?.toFloatOrNull() ?: 0f
