package com.micmax.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Changes to PDFs, always written to a new file (the original stays as it is).
 * Positions are fractions of the page as it is shown (0..1, from the top left), whatever the page rotation.
 */
object PdfEdit {
    private var ready = false
    fun init(c: Context) { if (!ready) { PDFBoxResourceLoader.init(c.applicationContext); ready = true } }

    sealed class Edit(val page: Int) {
        /** A picture (signature, written text, note) in the rectangle x, y, w, h. */
        class Image(page: Int, val bitmap: Bitmap, val x: Float, val y: Float, val w: Float, val h: Float) : Edit(page)
        /** A see-through yellow mark. */
        class Highlight(page: Int, val x: Float, val y: Float, val w: Float, val h: Float) : Edit(page)
        /** A black box that really removes what is under it: the whole page becomes a picture. */
        class Redact(page: Int, val x: Float, val y: Float, val w: Float, val h: Float) : Edit(page)
    }

    data class Field(val name: String, val label: String, val kind: String, val value: String, val options: List<String>)

    // ---------- coordinates (pure, unit-tested) ----------

    /**
     * A point shown at (fx, fy) — fractions from the top left of the page as displayed — in PDF units, for a page
     * whose box starts at (llx, lly), is [w] × [h] wide when not rotated, and is shown rotated [rotation] degrees clockwise.
     */
    fun toPdf(fx: Float, fy: Float, llx: Float, lly: Float, w: Float, h: Float, rotation: Int): Pair<Float, Float> {
        val (u, v) = when (((rotation % 360) + 360) % 360) {
            90 -> fy to fx
            180 -> (1 - fx) to fy
            270 -> (1 - fy) to (1 - fx)
            else -> fx to (1 - fy)
        }
        return (llx + u * w) to (lly + v * h)
    }

    /**
     * The matrix (a, b, c, d, e, f) that turns points of the page as displayed — in PDF units, from the bottom left,
     * [w] × [h] being the size when not rotated — into PDF units: text drawn with it stands upright on the screen.
     */
    fun displayMatrix(llx: Float, lly: Float, w: Float, h: Float, rotation: Int): FloatArray {
        val r = ((rotation % 360) + 360) % 360
        val (dw, dh) = if (r == 90 || r == 270) h to w else w to h
        val o = toPdf(0f, 1f, llx, lly, w, h, r); val xe = toPdf(1f, 1f, llx, lly, w, h, r); val ye = toPdf(0f, 0f, llx, lly, w, h, r)
        return floatArrayOf((xe.first - o.first) / dw, (xe.second - o.second) / dw, (ye.first - o.first) / dh, (ye.second - o.second) / dh, o.first, o.second)
    }

    /** Width and height of the page as displayed (rotation applied), in PDF units. */
    private fun shownSize(page: PDPage): Pair<Float, Float> {
        val box = page.cropBox ?: page.mediaBox
        val r = ((page.rotation % 360) + 360) % 360
        return if (r == 90 || r == 270) box.height to box.width else box.width to box.height
    }

    private fun mapper(page: PDPage): (Float, Float) -> Pair<Float, Float> {
        val box = page.cropBox ?: page.mediaBox
        return { fx, fy -> toPdf(fx, fy, box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation) }
    }

    // ---------- reading ----------

    private fun load(file: File, password: String? = null): PDDocument =
        if (password.isNullOrEmpty()) PDDocument.load(file) else PDDocument.load(file, password)

    fun pageCount(file: File): Int = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } } }.getOrDefault(0)

    /** width / height of every page, as shown. */
    fun ratios(file: File): List<Float> = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> (0 until r.pageCount).map { i -> r.openPage(i).use { it.width.toFloat() / it.height } } }
        }
    }.getOrDefault(emptyList())

    /** The page as a picture [width] pixels wide (white background), as it is shown. */
    fun render(file: File, page: Int, width: Int): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(page.coerceIn(0, r.pageCount - 1)).use { p ->
                    val hgt = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, hgt, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

    fun text(c: Context, file: File, maxChars: Int = 60_000): String? = runCatching {
        init(c)
        load(file).use { d -> PDFTextStripper().apply { sortByPosition = true }.getText(d) }
    }.getOrNull()?.replace(Regex("[ \\t]+"), " ")?.replace(Regex("\\n{3,}"), "\n\n")?.trim()?.take(maxChars)?.takeIf { it.length >= 40 }

    fun fields(c: Context, file: File): List<Field> {
        init(c)
        return load(file).use { d ->
            val form = d.documentCatalog.acroForm ?: return@use emptyList()
            form.fieldTree.mapNotNull { f ->
                val label = (f.alternateFieldName?.takeIf { it.isNotBlank() } ?: f.partialName ?: f.fullyQualifiedName).trim()
                when (f) {
                    is PDCheckBox -> Field(f.fullyQualifiedName, label, "check", if (f.isChecked) "true" else "false", emptyList())
                    is PDRadioButton -> Field(f.fullyQualifiedName, label, "radio", f.value.orEmpty(), f.onValues.toList())
                    is PDChoice -> Field(f.fullyQualifiedName, label, "choice", f.value.firstOrNull().orEmpty(), f.options.toList())
                    is PDTextField -> Field(f.fullyQualifiedName, label, "text", f.value.orEmpty(), emptyList())
                    else -> null
                }
            }
        }
    }

    // ---------- writing ----------

    fun fill(c: Context, src: File, out: File, values: Map<String, String>) {
        init(c)
        load(src).use { d ->
            val form = d.documentCatalog.acroForm ?: error("Questo PDF non ha campi da compilare")
            form.needAppearances = true
            values.forEach { (name, v) ->
                when (val f = form.getField(name)) {
                    is PDCheckBox -> if (v == "true") f.check() else f.unCheck()
                    is PDRadioButton -> if (v.isNotBlank()) runCatching { f.value = v }
                    is PDChoice -> if (v.isNotBlank()) runCatching { f.setValue(v) }
                    is PDTextField -> runCatching { f.value = v }.onFailure { form.needAppearances = true }
                    else -> {}
                }
            }
            d.save(out)
        }
    }

    fun apply(c: Context, src: File, out: File, edits: List<Edit>) {
        init(c)
        val (redact, rest) = edits.partition { it is Edit.Redact }
        val mid = if (redact.isEmpty()) out else File(out.path + ".tmp")
        load(src).use { d ->
            rest.groupBy { it.page }.forEach { (pi, list) ->
                val page = d.getPage(pi)
                val map = mapper(page)
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    list.forEach { e ->
                        when (e) {
                            is Edit.Image -> {
                                val img = LosslessFactory.createFromImage(d, e.bitmap)
                                val bl = map(e.x, e.y + e.h); val br = map(e.x + e.w, e.y + e.h); val tl = map(e.x, e.y)
                                cs.drawImage(img, Matrix(br.first - bl.first, br.second - bl.second, tl.first - bl.first, tl.second - bl.second, bl.first, bl.second))
                            }
                            is Edit.Highlight -> {
                                val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.35f }
                                cs.saveGraphicsState()
                                cs.setGraphicsStateParameters(gs)
                                cs.setNonStrokingColor(253, 224, 71)
                                val pts = listOf(map(e.x, e.y), map(e.x + e.w, e.y), map(e.x + e.w, e.y + e.h), map(e.x, e.y + e.h))
                                cs.moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { cs.lineTo(it.first, it.second) }
                                cs.closePath(); cs.fill()
                                cs.restoreGraphicsState()
                            }
                            is Edit.Redact -> {}
                        }
                    }
                }
            }
            d.save(mid)
        }
        if (redact.isNotEmpty()) try { flatten(mid, out, redact.filterIsInstance<Edit.Redact>()) } finally { mid.delete() }
    }

    /** The pages with black boxes become pictures (about 200 dpi) with the boxes burnt in: nothing under them is left. */
    private fun flatten(src: File, out: File, boxes: List<Edit.Redact>) {
        load(src).use { d ->
            boxes.groupBy { it.page }.forEach { (pi, list) ->
                val old = d.getPage(pi)
                val (dw, dh) = shownSize(old)
                val px = (dw * 200f / 72f).toInt().coerceIn(600, 2400)
                val bmp = render(src, pi, px) ?: error("Non riesco a disegnare la pagina ${pi + 1}")
                val black = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
                val cv = android.graphics.Canvas(bmp)
                val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
                list.forEach { cv.drawRect(it.x * bw, it.y * bh, (it.x + it.w) * bw, (it.y + it.h) * bh, black) }
                val np = PDPage(PDRectangle(dw, dh))
                val img = JPEGFactory.createFromImage(d, bmp, 0.9f)
                PDPageContentStream(d, np).use { it.drawImage(img, 0f, 0f, dw, dh) }
                d.pages.insertBefore(np, old)
                d.removePage(old)
            }
            d.save(out)
        }
    }

    /** One page of the new PDF: page [index] of [file] turned by [turn] degrees, or a photo. */
    sealed class PageOp {
        data class FromPdf(val file: File, val index: Int, val turn: Int = 0) : PageOp()
        data class Photo(val file: File) : PageOp()
        /** An empty white page (A4 unless told otherwise). */
        data class Blank(val id: Long = System.nanoTime(), val w: Float = PDRectangle.A4.width, val h: Float = PDRectangle.A4.height) : PageOp()
    }

    /** A photo ready to become a page: no larger than [maxPx], turned the right way (the camera writes the turn apart). */
    fun loadPhoto(path: String, maxPx: Int = 2000): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        var bmp = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, maxPx.toFloat() / maxOf(bmp.width, bmp.height))
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        val turn = runCatching {
            when (android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        if (turn != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(turn.toFloat()) }, true)
        bmp
    }.getOrNull()

    /** Page numbers («3 / 10») at the bottom and/or a big see-through word across every page. */
    data class Decor(val numbers: Boolean = false, val watermark: String? = null) {
        val any get() = numbers || !watermark.isNullOrBlank()
    }

    /** Only the letters the standard PDF font can write (no emoji). */
    private fun safe(font: PDFont, s: String) = s.filter { ch -> runCatching { font.encode(ch.toString()) }.isSuccess }

    private fun decorate(d: PDDocument, decor: Decor) {
        if (!decor.any) return
        val font = PDType1Font.HELVETICA_BOLD
        val total = d.numberOfPages
        val mark = decor.watermark?.let { safe(font, it.trim()) }?.takeIf { it.isNotBlank() }
        d.pages.forEachIndexed { i, page ->
            val box = page.cropBox ?: page.mediaBox
            val m = displayMatrix(box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation)
            val (dw, dh) = shownSize(page)
            PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                if (mark != null) {
                    cs.saveGraphicsState()
                    cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.16f })
                    cs.setNonStrokingColor(120, 120, 120)
                    val diag = Math.hypot(dw.toDouble(), dh.toDouble()).toFloat()
                    val unit = font.getStringWidth(mark) / 1000f
                    val size = (diag * 0.7f / unit.coerceAtLeast(0.1f)).coerceIn(12f, 140f)
                    cs.transform(Matrix.getRotateInstance(Math.atan2(dh.toDouble(), dw.toDouble()), dw / 2, dh / 2))
                    cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(-unit * size / 2, -size * 0.35f); cs.showText(mark); cs.endText()
                    cs.restoreGraphicsState()
                }
                if (decor.numbers) {
                    val label = pageLabel(i, total)
                    val size = 10f
                    val w = font.getStringWidth(label) / 1000f * size
                    cs.setNonStrokingColor(70, 70, 70)
                    cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset((dw - w) / 2, 16f); cs.showText(label); cs.endText()
                }
            }
        }
    }

    fun pageLabel(index: Int, total: Int) = "${index + 1} / $total"

    fun rebuild(c: Context, ops: List<PageOp>, out: File, decor: Decor = Decor()) {
        init(c)
        val opened = HashMap<String, PDDocument>()
        try {
            PDDocument().use { nd ->
                ops.forEach { op ->
                    when (op) {
                        is PageOp.FromPdf -> {
                            val src = opened.getOrPut(op.file.path) { load(op.file) }
                            val p = nd.importPage(src.getPage(op.index))
                            p.rotation = ((src.getPage(op.index).rotation + op.turn) % 360 + 360) % 360
                        }
                        is PageOp.Blank -> nd.addPage(PDPage(PDRectangle(op.w, op.h)))
                        is PageOp.Photo -> {
                            val bmp = loadPhoto(op.file.path) ?: return@forEach
                            val a4 = if (bmp.width > bmp.height) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                            val page = PDPage(a4); nd.addPage(page)
                            val img = JPEGFactory.createFromImage(nd, bmp, 0.85f)
                            val m = 18f
                            val fit = minOf((a4.width - 2 * m) / bmp.width, (a4.height - 2 * m) / bmp.height)
                            val w = bmp.width * fit; val h = bmp.height * fit
                            PDPageContentStream(nd, page).use { it.drawImage(img, (a4.width - w) / 2, (a4.height - h) / 2, w, h) }
                        }
                    }
                }
                if (nd.numberOfPages == 0) error("Nessuna pagina")
                decorate(nd, decor)
                nd.save(out)
            }
        } finally { opened.values.forEach { runCatching { it.close() } } }
    }


    // ---------- open, search, compress, password ----------

    const val OK = 0
    const val PASSWORD = 1
    const val BAD = 2

    /** Can the phone open this PDF? A PDF with an opening password is reported as [PASSWORD] (Android throws SecurityException). */
    fun checkStrict(file: File): Int = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> if (r.pageCount > 0) OK else BAD } }
    } catch (_: SecurityException) { PASSWORD } catch (_: Throwable) { BAD }

    /** The same PDF without its opening password, written to [out]; false if the password is wrong. */
    fun unlock(c: Context, src: File, out: File, password: String): Boolean {
        init(c)
        return try {
            load(src, password).use { d -> d.isAllSecurityToBeRemoved = true; d.save(out) }
            true
        } catch (_: Throwable) { false }
    }

    /** The same PDF protected by an opening password. */
    fun protect(c: Context, src: File, out: File, password: String) {
        init(c)
        load(src).use { d ->
            val pol = StandardProtectionPolicy(password, password, AccessPermission())
            pol.encryptionKeyLength = 128
            d.protect(pol)
            d.save(out)
        }
    }

    data class Hit(val page: Int, val snippet: String)

    /** Where [query] appears: page numbers (0-based) and a bit of text around it. */
    fun search(c: Context, file: File, query: String, max: Int = 60): List<Hit> {
        init(c)
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val hits = ArrayList<Hit>()
        runCatching {
            load(file).use { d ->
                val st = PDFTextStripper().apply { sortByPosition = true }
                for (i in 1..d.numberOfPages) {
                    if (hits.size >= max) break
                    st.startPage = i; st.endPage = i
                    val t = st.getText(d).replace(Regex("\\s+"), " ")
                    Text.findAll(t, q, minOf(3, max - hits.size)).forEach { idx -> hits += Hit(i - 1, snippetAround(t, idx, q.length)) }
                }
            }
        }
        return hits
    }

    fun snippetAround(t: String, idx: Int, len: Int, ctx: Int = 36): String {
        val a = (idx - ctx).coerceAtLeast(0); val b = (idx + len + ctx).coerceAtMost(t.length)
        return (if (a > 0) "…" else "") + t.substring(a, b).trim() + (if (b < t.length) "…" else "")
    }

    /** The chapters of the PDF (when it has them): title, indented by level, and page (0-based). */
    fun outline(c: Context, file: File): List<Pair<String, Int>> = runCatching {
        init(c)
        load(file).use { d ->
            val out = ArrayList<Pair<String, Int>>()
            fun walk(first: PDOutlineItem?, depth: Int) {
                var next = first
                while (next != null && out.size < 400 && depth < 6) {
                    val item: PDOutlineItem = next
                    val p = runCatching { item.findDestinationPage(d) }.getOrNull()
                    val idx = if (p != null) d.pages.indexOf(p) else -1
                    val title = item.title.orEmpty().trim()
                    if (idx >= 0 && title.isNotEmpty()) out += ("    ".repeat(depth) + title) to idx
                    walk(item.firstChild, depth + 1)
                    next = item.nextSibling
                }
            }
            walk(d.documentCatalog.documentOutline?.firstChild, 0)
            out
        }
    }.getOrDefault(emptyList())

    /** The text of one page (0-based), for reading aloud; empty for a scan. */
    fun pageText(c: Context, file: File, page: Int): String = runCatching {
        init(c)
        load(file).use { d -> PDFTextStripper().apply { sortByPosition = true; startPage = page + 1; endPage = page + 1 }.getText(d) }
    }.getOrDefault("").replace(Regex("-\\n(?=\\p{Ll})"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * The words of one page (0-based) and where they are, as fractions of the page as shown (rotation applied),
     * in reading order; empty for a scan.
     */
    fun words(c: Context, file: File, page: Int): List<Word> = runCatching {
        init(c)
        load(file).use { d ->
            val pg = d.getPage(page)
            val (dw, dh) = shownSize(pg)
            val out = ArrayList<Word>()
            var line = 0
            val st = object : PDFTextStripper() {
                override fun writeString(text: String?, pos: MutableList<com.tom_roush.pdfbox.text.TextPosition>?) {
                    val cur = ArrayList<com.tom_roush.pdfbox.text.TextPosition>()
                    fun flush() {
                        if (cur.isEmpty()) return
                        val t = cur.joinToString("") { it.unicode.orEmpty() }.trim()
                        if (t.isNotEmpty()) {
                            val l = cur.minOf { it.x }; val r = cur.maxOf { it.x + it.widthDirAdj }
                            val h = cur.maxOf { maxOf(it.heightDir, it.fontSizeInPt * 0.75f, 2f) }
                            val base = cur.maxOf { it.y }
                            out += Word(t, l / dw, (base - h) / dh, (r - l).coerceAtLeast(1f) / dw, (h * 1.25f) / dh, line)
                        }
                        cur.clear()
                    }
                    pos?.forEach { tp -> if (tp.unicode.isNullOrBlank()) flush() else cur += tp }
                    flush()
                }
                override fun writeLineSeparator() { line++ }
            }
            st.sortByPosition = true; st.startPage = page + 1; st.endPage = page + 1
            st.getText(d)
            out
        }
    }.getOrDefault(emptyList())

    /** The pages as JPEG pictures [width] pixels wide, in [dir]; the files in order. */
    fun toImages(file: File, pages: List<Int>, dir: File, baseName: String, width: Int = 1600): List<File> {
        dir.mkdirs()
        return pages.mapNotNull { p ->
            val b = render(file, p, width) ?: return@mapNotNull null
            val f = File(dir, "$baseName - pagina ${p + 1}.jpg")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            f
        }
    }

    /** Text of every page (for «Copia testo»), null when the PDF is a scan. */
    fun allText(c: Context, file: File): String? = text(c, file, 200_000)

    /** Smaller pictures: images larger than [maxPx] are scaled down and saved as JPEG at [quality]. */
    fun compress(c: Context, src: File, out: File, maxPx: Int, quality: Float) {
        init(c)
        load(src).use { d ->
            val done = java.util.IdentityHashMap<Any, Boolean>()
            for (page in d.pages) {
                val res = page.resources ?: continue
                for (name in res.xObjectNames.toList()) {
                    val x = runCatching { res.getXObject(name) }.getOrNull() as? PDImageXObject ?: continue
                    if (done.put(x.cosObject, true) != null) continue
                    if (x.width * x.height < 90_000 || x.isStencil) continue
                    if (x.cosObject.containsKey(COSName.SMASK) || x.cosObject.containsKey(COSName.MASK)) continue
                    val bmp = runCatching { x.image }.getOrNull() ?: continue
                    val sc = minOf(1f, maxPx.toFloat() / maxOf(bmp.width, bmp.height))
                    val small = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true) else bmp
                    val ni = JPEGFactory.createFromImage(d, small, quality)
                    res.put(name, ni)
                }
            }
            d.save(out)
        }
    }

    /** A small self-check for the automatic test: every kind of change on a PDF made on the spot. */
    fun selfTest(c: Context, dir: File): String = runCatching {
        init(c)
        val src = File(dir, "prova-modifica.pdf")
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle.A4)); d.addPage(PDPage(PDRectangle.A4).apply { rotation = 90 }); d.save(src) }
        val sig = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val a = File(dir, "prova-modifica-firmato.pdf")
        apply(c, src, a, listOf(Edit.Image(0, sig, 0.6f, 0.8f, 0.3f, 0.1f), Edit.Highlight(0, 0.1f, 0.1f, 0.5f, 0.03f), Edit.Image(1, sig, 0.1f, 0.1f, 0.2f, 0.1f)))
        val b = File(dir, "prova-modifica-pagine.pdf")
        rebuild(c, listOf(PageOp.FromPdf(a, 1, 90), PageOp.FromPdf(a, 0), PageOp.FromPdf(src, 0)), b)
        val pw = File(dir, "prova-modifica-password.pdf")
        protect(c, b, pw, "1234")
        val locked = checkStrict(pw)
        val un = File(dir, "prova-modifica-sbloccata.pdf")
        val unlocked = unlock(c, pw, un, "1234") && checkStrict(un) == OK && !unlock(c, pw, File(dir, "x.pdf"), "0000")
        val cmp = File(dir, "prova-modifica-compressa.pdf")
        compress(c, a, cmp, 800, 0.6f)
        val red = File(dir, "prova-modifica-oscurata.pdf")
        apply(c, src, red, listOf(Edit.Redact(1, 0.2f, 0.2f, 0.3f, 0.05f), Edit.Image(0, sig, 0.1f, 0.1f, 0.2f, 0.1f)))
        val oscura = pageCount(red) == 2 && checkStrict(red) == OK
        val deco = File(dir, "prova-modifica-decorata.pdf")
        rebuild(c, listOf(PageOp.FromPdf(src, 0), PageOp.Blank(), PageOp.FromPdf(src, 1)), deco, Decor(numbers = true, watermark = "COPIA è ✓"))
        val decora = pageCount(deco) == 3 && checkStrict(deco) == OK
        "PDFEDIT_OK pagine=${pageCount(b)} render=${render(b, 0, 300) != null} password=${locked == PASSWORD} sblocco=$unlocked compressa=${pageCount(cmp)} oscura=$oscura decora=$decora"
    }.getOrElse { "PDFEDIT_ERR ${it.javaClass.simpleName}: ${it.message}" }
}
