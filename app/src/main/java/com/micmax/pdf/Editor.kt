package com.micmax.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

// ======================= Firma, sigla, testo, segni, timbri, disegno, evidenziatore, oscura, moduli =======================

/** Ink colours for pen, text, signature: name and colour. */
val INKS = listOf("nero" to 0xFF111111.toInt(), "blu" to 0xFF1D3A8A.toInt(), "rosso" to 0xFFC62828.toInt(), "verde" to 0xFF2E7D32.toInt())

/** Un riquadro per firmare con il dito. */
class SignaturePad(c: Context) : View(c) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INKS[1].second; style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bounds = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -1f, -1f)
    var ink: Int
        get() = paint.color
        set(v) { paint.color = v; invalidate() }
    val isEmpty get() = bounds.right < 0
    fun clear() { path.reset(); bounds.set(Float.MAX_VALUE, Float.MAX_VALUE, -1f, -1f); invalidate() }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> path.moveTo(e.x, e.y)
            MotionEvent.ACTION_MOVE -> path.lineTo(e.x, e.y)
        }
        bounds.union(e.x, e.y); invalidate(); return true
    }
    override fun onDraw(canvas: Canvas) { canvas.drawPath(path, paint) }
    /** Solo la firma, trasparente intorno. */
    fun bitmap(): Bitmap? {
        if (isEmpty) return null
        val m = 12f
        val w = (bounds.width() + 2 * m).toInt().coerceAtLeast(10); val h = (bounds.height() + 2 * m).toInt().coerceAtLeast(10)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { translate(-bounds.left + m, -bounds.top + m); drawPath(path, paint) }
        return b
    }
}

/** [kind]: "firma" or "sigla" (the initials), each remembered on its own. */
private fun MainActivity.signatureFile(kind: String) = File(filesDir, "$kind.png")

/** Chiede la firma o la sigla (quella salvata o una nuova) e la consegna a [then]. */
fun MainActivity.askSignature(kind: String = "firma", then: (Bitmap) -> Unit) {
    val word = if (kind == "sigla") "sigla" else "firma"
    val saved = signatureFile(kind).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    if (saved != null) {
        MaterialAlertDialogBuilder(this).setTitle(if (kind == "sigla") "✒️ La tua sigla" else "✍️ La tua firma")
            .setView(ImageView(this).apply { setImageBitmap(saved); adjustViewBounds = true; setPadding(dp(24), dp(12), dp(24), 0) })
            .setPositiveButton("Usa questa") { _, _ -> then(saved) }
            .setNeutralButton("Rifalla") { _, _ -> signatureFile(kind).delete(); askSignature(kind, then) }
            .setNegativeButton("Annulla", null).show()
        return
    }
    val pad = SignaturePad(this).apply { background = roundBg(0xFFFAFBFE.toInt(), 14, 0xFFB9C2D6.toInt()) }
    val colours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
    fun paintColours() {
        for (i in 0 until colours.childCount) (colours.getChildAt(i) as TextView).alpha = if (INKS[i].second == pad.ink) 1f else 0.45f
    }
    INKS.take(2).forEach { (name, c) ->
        colours.addView(TextView(this).apply {
            text = "● ${name.replaceFirstChar { it.uppercase() }}"; textSize = 14f; setTextColor(c); setPadding(dp(4), dp(4), dp(16), dp(4))
            setOnClickListener { pad.ink = c; paintColours() }
        })
    }
    paintColours()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
        addView(pad, LinearLayout.LayoutParams(-1, dp(if (kind == "sigla") 140 else 190)))
        addView(colours)
        addView(TextView(this@askSignature).apply { text = "Fai la $word qui con il dito: la ricordo per la prossima volta."; textSize = 13f; alpha = 0.7f; setPadding(0, dp(6), 0, 0) })
    }
    val dlg = MaterialAlertDialogBuilder(this).setTitle(if (kind == "sigla") "✒️ Sigla (le iniziali)" else "✍️ Firma").setView(box)
        .setPositiveButton("Metti sul foglio", null).setNeutralButton("Cancella", null).setNegativeButton("Annulla", null).create()
    dlg.setOnShowListener {
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val b = pad.bitmap() ?: run { toast("Prima fai la $word nel riquadro"); return@setOnClickListener }
            runCatching { signatureFile(kind).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            dlg.dismiss(); then(b)
        }
    }
    dlg.show()
}

/** Un testo come immagine da mettere sul foglio (su trasparente, oppure un foglietto giallo), a capo tra le parole. */
fun textBitmap(text: String, note: Boolean, ink: Int = Color.BLACK): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (note) 0xFF3D2E00.toInt() else ink; textSize = 44f }
    val lines = Text.wrap(text, 880f) { p.measureText(it) }
    val w = (lines.maxOf { p.measureText(it) } + 40).toInt().coerceAtLeast(60)
    val lh = p.fontSpacing
    val h = (lines.size * lh + 30).toInt()
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    if (note) {
        c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFEF3C7.toInt() })
        c.drawRoundRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF59E0B.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f })
    }
    lines.forEachIndexed { i, l -> c.drawText(l, 20f, 15f + (i + 1) * lh - p.descent(), p) }
    return b
}

/** ✓ ✗ ● ◯ drawn with lines (the same on every phone). */
fun symbolBitmap(kind: String, ink: Int): Bitmap {
    val s = 120
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    when (kind) {
        "check" -> c.drawPath(Path().apply { moveTo(18f, 64f); lineTo(48f, 96f); lineTo(104f, 24f) }, p)
        "cross" -> { c.drawLine(24f, 24f, 96f, 96f, p); c.drawLine(96f, 24f, 24f, 96f, p) }
        "dot" -> c.drawCircle(60f, 60f, 34f, p.apply { style = Paint.Style.FILL })
        else -> c.drawOval(10f, 10f, 110f, 110f, p.apply { strokeWidth = 8f })
    }
    return b
}

/** A rubber stamp: «PAGATO» in a frame. */
fun stampBitmap(text: String, ink: Int): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 64f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); letterSpacing = 0.08f }
    val tw = p.measureText(text)
    val w = (tw + 80).toInt(); val h = (p.fontSpacing + 50).toInt()
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 7f }
    c.drawRoundRect(6f, 6f, w - 6f, h - 6f, 22f, 22f, st)
    c.drawRoundRect(16f, 16f, w - 16f, h - 16f, 16f, 16f, st.apply { strokeWidth = 2.5f })
    c.drawText(text, (w - tw) / 2, h / 2f - (p.ascent() + p.descent()) / 2, p)
    return b
}

/** Draws, under the finger, what the current tool is making (a stroke, an arrow, a frame, a line, a coloured box). */
class DrawLayer(c: Context) : View(c) {
    var tool: String? = null
    var ink = Color.BLACK
    var onDone: ((tool: String, path: Path, start: Pair<Float, Float>, end: Pair<Float, Float>) -> Unit)? = null
    private val path = Path()
    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f
    private var active = false
    val stroke get() = context.dp(3).toFloat()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = tool ?: return false
        parent?.requestDisallowInterceptTouchEvent(true)
        val x = e.x.coerceIn(0f, width.toFloat()); val y = e.y.coerceIn(0f, height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { active = true; sx = x; sy = y; ex = x; ey = y; path.reset(); path.moveTo(x, y) }
            MotionEvent.ACTION_MOVE -> { ex = x; ey = y; path.lineTo(x, y) }
            MotionEvent.ACTION_UP -> { active = false; ex = x; ey = y; path.lineTo(x, y); onDone?.invoke(t, Path(path), sx to sy, ex to ey); path.reset() }
            MotionEvent.ACTION_CANCEL -> { active = false; path.reset() }
        }
        invalidate(); return true
    }

    override fun onDraw(c: Canvas) {
        if (!active) return
        val t = tool ?: return
        p.color = ink; p.strokeWidth = stroke; p.style = Paint.Style.STROKE
        val l = minOf(sx, ex); val r = maxOf(sx, ex); val top = minOf(sy, ey); val bot = maxOf(sy, ey)
        when (t) {
            "pen" -> c.drawPath(path, p)
            "rect" -> c.drawRect(l, top, r, bot, p)
            "arrow" -> drawArrow(c, sx, sy, ex, ey, p)
            "under" -> c.drawLine(l, bot, r, bot, p)
            "strike" -> c.drawLine(l, (top + bot) / 2, r, (top + bot) / 2, p)
            "highlight" -> c.drawRect(l, top, r, maxOf(bot, top + context.dp(14)), Paint().apply { color = 0x66FDE047 })
            "redact" -> c.drawRect(l, top, r, maxOf(bot, top + context.dp(10)), Paint().apply { color = 0xCC000000.toInt() })
        }
    }

    companion object {
        fun drawArrow(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, p: Paint) {
            c.drawLine(x1, y1, x2, y2, p)
            val a = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            val len = p.strokeWidth * 5
            for (d in listOf(Math.PI * 0.82, -Math.PI * 0.82)) c.drawLine(x2, y2, (x2 + len * Math.cos(a + d)).toFloat(), (y2 + len * Math.sin(a + d)).toFloat(), p)
        }
    }
}

/** [mode]: "sign", "highlight", "redact", "text", "note" say what to start with. */
@SuppressLint("ClickableViewAccessibility")
fun MainActivity.openEditor(doc: Store.Doc, mode: String, startPage: Int = 0) {
    val file = doc.file(this)
    val total = PdfEdit.pageCount(file)
    if (total == 0) { toast("Non riesco a leggere questo PDF"); return }
    val ratios = PdfEdit.ratios(file)
    val items = mutableListOf<EditItem>()
    var save: () -> Unit = {}
    openScreen("Firma, scrivi, segna", "Salva", { save() }, dirty = { items.isNotEmpty() }) { body ->
        val undo = ArrayDeque<List<EditItem>>(); val redo = ArrayDeque<List<EditItem>>()
        fun snapshot() = items.map { it.copy() }
        fun commit(before: List<EditItem>) { undo.addLast(before); if (undo.size > 60) undo.removeFirst(); redo.clear() }

        var page = startPage.coerceIn(0, total - 1)
        var ink = INKS[0].second
        val frame = FrameLayout(this)
        val img = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_XY }
        frame.addView(img, FrameLayout.LayoutParams(-1, -2))
        val overlay = FrameLayout(this)
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val layer = DrawLayer(this)
        frame.addView(layer, FrameLayout.LayoutParams(-1, -1))
        val info = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(col(R.color.muted)); setPadding(dp(4), dp(6), dp(4), dp(6)) }
        lateinit var drawItems: () -> Unit
        lateinit var showInfo: () -> Unit

        fun itemMenu(it: EditItem) {
            MaterialAlertDialogBuilder(this).setItems(arrayOf("📄 Copia su tutte le pagine", "🗑 Togli")) { _, w ->
                val before = snapshot()
                if (w == 0) {
                    if (total == 1) { toast("Il PDF ha una pagina sola"); return@setItems }
                    (0 until total).filter { p -> p != it.page }.forEach { p ->
                        val h = (it.h * ratios.getOrElse(p) { 1f } / ratios.getOrElse(it.page) { 1f }).coerceAtMost(1f)
                        items += it.copy(page = p, h = h, y = it.y.coerceAtMost(1f - h))
                    }
                    toast("Copiata su ${total - 1} pagine")
                } else items.remove(it)
                commit(before); drawItems()
            }.show()
        }

        fun addItemView(it: EditItem) {
            val W = overlay.width.toFloat().coerceAtLeast(1f); val H = overlay.height.toFloat().coerceAtLeast(1f)
            val lp = FrameLayout.LayoutParams((it.w * W).toInt().coerceAtLeast(1), (it.h * H).toInt().coerceAtLeast(1)).apply { leftMargin = (it.x * W).toInt(); topMargin = (it.y * H).toInt() }
            if (it.kind != EditItem.IMAGE) {
                overlay.addView(View(this).apply {
                    setBackgroundColor(if (it.kind == EditItem.REDACT) 0xFF000000.toInt() else 0x66FDE047)
                    setOnLongClickListener { _ -> itemMenu(it); true }
                    setOnClickListener { _ -> itemMenu(it) }
                }, lp)
                return
            }
            val holder = FrameLayout(this).apply { background = android.graphics.drawable.GradientDrawable().apply { setStroke(dp(1), 0x993B6CF5.toInt()); cornerRadius = dp(4).toFloat() } }
            holder.addView(ImageView(this).apply { setImageBitmap(it.bitmap); scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(-1, -1))
            val handle = View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF3B6CF5.toInt()) } }
            holder.addView(handle, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END))
            overlay.addView(holder, lp)
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var ow = 0; var oh = 0
            var before: List<EditItem> = emptyList()
            holder.setOnTouchListener { v, e ->
                frame.parent?.requestDisallowInterceptTouchEvent(true)
                val l = v.layoutParams as FrameLayout.LayoutParams
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = l.leftMargin; oy = l.topMargin; before = snapshot() }
                    MotionEvent.ACTION_MOVE -> { l.leftMargin = (ox + e.rawX - sx).toInt().coerceIn(0, (W - l.width).toInt().coerceAtLeast(0)); l.topMargin = (oy + e.rawY - sy).toInt().coerceIn(0, (H - l.height).toInt().coerceAtLeast(0)); v.layoutParams = l }
                    MotionEvent.ACTION_UP -> {
                        if (Math.abs(e.rawX - sx) < 8 && Math.abs(e.rawY - sy) < 8) itemMenu(it)
                        else { it.x = l.leftMargin / W; it.y = l.topMargin / H; commit(before) }
                    }
                }
                true
            }
            handle.setOnTouchListener { _, e ->
                frame.parent?.requestDisallowInterceptTouchEvent(true)
                val l = holder.layoutParams as FrameLayout.LayoutParams
                val ratio = it.h * H / (it.w * W).coerceAtLeast(1f)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; ow = l.width; oh = l.height; before = snapshot() }
                    MotionEvent.ACTION_MOVE -> { val nw = (ow + e.rawX - sx).toInt().coerceIn(dp(24), (W - l.leftMargin).toInt().coerceAtLeast(dp(24))); l.width = nw; l.height = (nw * ratio).toInt(); holder.layoutParams = l }
                    MotionEvent.ACTION_UP -> { it.w = l.width / W; it.h = l.height / H; commit(before) }
                }
                true
            }
        }
        drawItems = { overlay.removeAllViews(); items.filter { it.page == page }.forEach { addItemView(it) } }
        // girando il telefono la pagina cambia misura: i segni si rimettono al loro posto
        overlay.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) overlay.post { drawItems() } }

        fun place(b: Bitmap, widthFraction: Float, tag: String = "") {
            val W = overlay.width.toFloat().coerceAtLeast(1f); val H = overlay.height.toFloat().coerceAtLeast(1f)
            val h = ((widthFraction * W * b.height / b.width) / H).coerceAtMost(0.9f)
            val before = snapshot()
            items += EditItem(page, b, (1 - widthFraction) / 2, (0.5f - h / 2).coerceAtLeast(0f), widthFraction, h, EditItem.IMAGE, tag)
            commit(before); drawItems()
            toast("Trascinalo dove serve · il pallino blu lo ingrandisce")
        }
        fun placeAt(b: Bitmap, left: Float, top: Float) {
            val W = overlay.width.toFloat().coerceAtLeast(1f); val H = overlay.height.toFloat().coerceAtLeast(1f)
            val before = snapshot()
            items += EditItem(page, b, (left / W).coerceIn(0f, 1f), (top / H).coerceIn(0f, 1f), b.width / W, b.height / H, EditItem.IMAGE, "draw")
            commit(before); drawItems()
        }

        layer.onDone = { tool, path, (sx, sy), (ex, ey) ->
            val W = overlay.width.toFloat().coerceAtLeast(1f); val H = overlay.height.toFloat().coerceAtLeast(1f)
            val l = minOf(sx, ex); val r = maxOf(sx, ex); val t = minOf(sy, ey); val bt = maxOf(sy, ey)
            val sw = layer.stroke
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            when (tool) {
                "highlight", "redact" -> if (r - l > dp(8)) {
                    val minH = dp(if (tool == "highlight") 14 else 10)
                    val before = snapshot()
                    items += EditItem(page, null, l / W, t / H, (r - l) / W, (maxOf(bt, t + minH) - t) / H, if (tool == "highlight") EditItem.HIGHLIGHT else EditItem.REDACT)
                    commit(before); drawItems()
                }
                "pen" -> {
                    val bounds = RectF(); path.computeBounds(bounds, true)
                    val m = sw * 2
                    val bmp = Bitmap.createBitmap((bounds.width() + 2 * m).toInt().coerceAtLeast(4), (bounds.height() + 2 * m).toInt().coerceAtLeast(4), Bitmap.Config.ARGB_8888)
                    Canvas(bmp).apply { translate(-bounds.left + m, -bounds.top + m); drawPath(path, paint); if (bounds.width() < 2 && bounds.height() < 2) drawCircle(bounds.left, bounds.top, sw, paint) }
                    placeAt(bmp, bounds.left - m, bounds.top - m)
                }
                "rect" -> if (r - l > dp(6) && bt - t > dp(6)) {
                    val m = sw
                    val bmp = Bitmap.createBitmap((r - l + 2 * m).toInt(), (bt - t + 2 * m).toInt(), Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawRect(m, m, r - l + m, bt - t + m, paint)
                    placeAt(bmp, l - m, t - m)
                }
                "arrow" -> if (Math.hypot((ex - sx).toDouble(), (ey - sy).toDouble()) > dp(12)) {
                    val m = sw * 6
                    val bmp = Bitmap.createBitmap((r - l + 2 * m).toInt(), (bt - t + 2 * m).toInt(), Bitmap.Config.ARGB_8888)
                    DrawLayer.drawArrow(Canvas(bmp), sx - l + m, sy - t + m, ex - l + m, ey - t + m, paint)
                    placeAt(bmp, l - m, t - m)
                }
                "under", "strike" -> if (r - l > dp(8)) {
                    val y = if (tool == "under") bt else (t + bt) / 2
                    val m = sw
                    val bmp = Bitmap.createBitmap((r - l + 2 * m).toInt(), (2 * m).toInt().coerceAtLeast(2), Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawLine(m, m, r - l + m, m, paint.apply { strokeCap = Paint.Cap.BUTT })
                    placeAt(bmp, l - m, y - m)
                }
            }
        }

        val toolNames = mapOf("pen" to "✏️ Penna", "arrow" to "➚ Freccia", "rect" to "▭ Riquadro", "under" to "U̲ Sottolinea", "strike" to "S̶ Barra", "highlight" to "🖍 Evidenzia", "redact" to "⬛ Oscura")
        val toolHints = mapOf("pen" to "scrivi o disegna col dito", "arrow" to "trascina dall'inizio alla punta", "rect" to "trascina per fare il riquadro",
            "under" to "trascina sotto le parole", "strike" to "trascina sulle parole", "highlight" to "trascina il dito sul testo", "redact" to "copri quello che non deve vedersi")
        showInfo = {
            val t = layer.tool
            val colour = INKS.firstOrNull { it.second == ink }?.first ?: ""
            info.text = "Pagina ${page + 1} di $total" + if (t != null) " · ${toolNames[t]} ($colour): ${toolHints[t]}" else ""
        }
        fun setTool(t: String?) { layer.tool = if (layer.tool == t) null else t; layer.ink = ink; showInfo() }
        fun show(p: Int) {
            page = p.coerceIn(0, total - 1)
            showInfo()
            io.execute { val b = PdfEdit.render(file, page, resources.displayMetrics.widthPixels); runOnUiThread { b?.let { img.setImageBitmap(it) }; img.post { drawItems() } } }
        }
        fun askText(title: String, hint: String, note: Boolean, frac: Float) {
            layer.tool = null; showInfo()
            val et = EditText(this).apply { this.hint = hint; setPadding(dp(20), dp(14), dp(20), dp(14)) }
            MaterialAlertDialogBuilder(this).setTitle(title).setView(et)
                .setPositiveButton("Metti") { _, _ -> et.text.toString().takeIf { it.isNotBlank() }?.let { place(textBitmap(it, note, ink), frac) } }
                .setNegativeButton("Annulla", null).show()
        }
        fun textFrac(s: String) = (0.04f + s.length * 0.018f).coerceIn(0.08f, 0.6f)
        fun restore(from: ArrayDeque<List<EditItem>>, to: ArrayDeque<List<EditItem>>, word: String) {
            val prev = from.removeLastOrNull() ?: run { toast("Niente da $word"); return }
            val now = snapshot(); to.addLast(now)
            items.clear(); items += prev
            val changed = (prev + now).map { it.page }.toSet().filter { p -> prev.filter { it.page == p } != now.filter { it.page == p } }
            if (changed.isNotEmpty() && page !in changed) show(changed.first()) else drawItems()
        }

        fun myDataPicker() {
            val data = Store.myData(this)
            if (data.isEmpty()) { toast("Prima scrivi i tuoi dati: li ricordo per i prossimi moduli"); openMyData(); return }
            val keys = Store.MY_FIELDS.filter { data.containsKey(it.first) }
            val entries = listOfNotNull(Text.myDataValue("nomecompleto", data)?.let { "Nome e cognome" to it }) + keys.map { (k, label) -> label to data.getValue(k) }
            MaterialAlertDialogBuilder(this).setTitle("👤 I miei dati")
                .setItems(entries.map { "${it.first}: ${it.second}" }.toTypedArray()) { _, w -> entries[w].second.let { place(textBitmap(it, false, ink), textFrac(it)) } }
                .setNeutralButton("Modifica") { _, _ -> openMyData() }.setNegativeButton("Chiudi", null).show()
        }

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(TextView(this).apply { text = "‹"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); contentDescription = "Pagina prima"; setOnClickListener { show(page - 1) } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        nav.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(TextView(this).apply { text = "›"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); contentDescription = "Pagina dopo"; setOnClickListener { show(page + 1) } }, LinearLayout.LayoutParams(dp(48), dp(44)))

        body.addView(chipBar(listOf(
            "↶ Annulla" to { restore(undo, redo, "annullare") },
            "↷ Rifai" to { restore(redo, undo, "rifare") },
            "✍️ Firma" to { layer.tool = null; showInfo(); askSignature("firma") { place(it, 0.35f, "sign") } },
            "✒️ Sigla" to { layer.tool = null; showInfo(); askSignature("sigla") { place(it, 0.14f, "sign"); toast("Toccala per copiarla su tutte le pagine") } },
            "🔤 Testo" to { askText("Testo sul foglio", "Cosa scrivo sul foglio", false, 0.45f) },
            "📅 Data" to { layer.tool = null; showInfo(); place(textBitmap(Text.date(), false, ink), 0.22f) },
            "☑ Segni" to {
                MaterialAlertDialogBuilder(this).setTitle("Segni").setItems(arrayOf("✓ Spunta", "✗ Croce", "● Pallino", "◯ Cerchio (per cerchiare)")) { _, w ->
                    val k = listOf("check", "cross", "dot", "circle")[w]
                    place(symbolBitmap(k, ink), if (k == "circle") 0.14f else 0.05f)
                }.show()
            },
            "👤 I miei dati" to { myDataPicker() },
            "🏷 Timbro" to {
                val d = Text.date()
                val stamps = listOf("PAGATO", "RICEVUTO", "APPROVATO", "VISTO", "ANNULLATO", "COPIA", "PAGATO IL $d", "RICEVUTO IL $d")
                MaterialAlertDialogBuilder(this).setTitle("🏷 Timbro").setItems(stamps.toTypedArray()) { _, w ->
                    place(stampBitmap(stamps[w], if (ink == INKS[0].second) INKS[2].second else ink), if (stamps[w].length > 12) 0.42f else 0.3f)
                }.show()
            },
            "📌 Nota" to { askText("Nota", "La tua nota", true, 0.35f) },
            "🖍 Evidenzia" to { setTool("highlight") },
            "✏️ Disegna" to {
                val keys = listOf("pen", "arrow", "rect", "under", "strike")
                MaterialAlertDialogBuilder(this).setTitle("Disegna").setItems(keys.map { toolNames.getValue(it) }.toTypedArray()) { _, w -> layer.tool = null; setTool(keys[w]) }.show()
            },
            "⬛ Oscura" to { setTool("redact") },
            "🎨 Colore" to {
                MaterialAlertDialogBuilder(this).setTitle("Colore").setSingleChoiceItems(INKS.map { "● " + it.first.replaceFirstChar { c -> c.uppercase() } }.toTypedArray(), INKS.indexOfFirst { it.second == ink }) { dl, w ->
                    ink = INKS[w].second; layer.ink = ink; showInfo(); dl.dismiss()
                }.show()
            },
            "✋ Sposta" to { layer.tool = null; showInfo(); toast("Ora puoi spostare e toccare quello che hai messo") }
        )))
        body.addView(nav)
        body.addView(frame, LinearLayout.LayoutParams(-1, -2))
        hintLabel(body, "Tocca quello che hai messo per toglierlo o copiarlo su tutte le pagine. Con uno strumento attivo, toccalo di nuovo (o «✋ Sposta») per tornare a spostare.")
        save = save@{
            if (items.isEmpty()) { toast("Non hai aggiunto niente"); return@save }
            val edits = items.map {
                when (it.kind) {
                    EditItem.HIGHLIGHT -> PdfEdit.Edit.Highlight(it.page, it.x, it.y, it.w, it.h)
                    EditItem.REDACT -> PdfEdit.Edit.Redact(it.page, it.x, it.y, it.w, it.h)
                    else -> PdfEdit.Edit.Image(it.page, it.bitmap!!, it.x, it.y, it.w, it.h)
                }
            }
            val suffix = when {
                items.any { it.kind == EditItem.REDACT } -> "oscurato"
                items.any { it.tag == "sign" } -> "firmato"
                items.any { it.kind == EditItem.IMAGE } -> "modificato"
                else -> "evidenziato"
            }
            fun go() {
                val out = File(cacheDir, "edit_${System.currentTimeMillis()}.pdf")
                toast("Creo il nuovo PDF…")
                io.execute {
                    val r = runCatching { PdfEdit.apply(this, file, out, edits) }
                    runOnUiThread { r.onSuccess { saveResult(out, doc.name, suffix) }.onFailure { toast("Non riuscito: ${it.message}".take(140)) } }
                }
            }
            val pagesRedacted = items.filter { it.kind == EditItem.REDACT }.map { it.page + 1 }.distinct().sorted()
            if (pagesRedacted.isEmpty()) go()
            else MaterialAlertDialogBuilder(this).setTitle("⬛ Oscura per davvero")
                .setMessage("Per togliere davvero quello che hai coperto, ${if (pagesRedacted.size == 1) "la pagina ${pagesRedacted[0]} diventa un'immagine" else "le pagine ${pagesRedacted.joinToString(", ")} diventano immagini"}: il testo lì non si potrà più cercare o copiare. L'originale non cambia.")
                .setPositiveButton("Va bene") { _, _ -> go() }.setNegativeButton("Annulla", null).show()
        }
        show(page)
        when (mode) {
            "sign" -> frame.post { askSignature("firma") { place(it, 0.35f, "sign") } }
            "text" -> frame.post { askText("Testo sul foglio", "Cosa scrivo sul foglio", false, 0.45f) }
            "note" -> frame.post { askText("Nota", "La tua nota", true, 0.35f) }
            "highlight" -> setTool("highlight")
            "redact" -> setTool("redact")
        }
    }
}

/** Something put on a page: a picture (signature, text, stroke…), a yellow mark or a black box. Positions are fractions of the page. */
data class EditItem(val page: Int, val bitmap: Bitmap?, var x: Float, var y: Float, var w: Float, var h: Float, val kind: Int, val tag: String = "") {
    companion object { const val IMAGE = 0; const val HIGHLIGHT = 1; const val REDACT = 2 }
}

/** Compila i campi del modulo (se non ce ne sono propone di scrivere sul foglio). */
fun MainActivity.openFillForm(doc: Store.Doc) {
    val file = doc.file(this)
    toast("Cerco i campi del modulo…")
    io.execute {
        val fields = runCatching { PdfEdit.fields(this, file) }.getOrDefault(emptyList())
        runOnUiThread {
            if (fields.isEmpty()) {
                MaterialAlertDialogBuilder(this).setTitle("Nessun campo da compilare")
                    .setMessage("Questo PDF non ha campi da riempire (succede con i fogli scansionati). Puoi scrivere sul foglio, mettere ✓ nelle caselle e i tuoi dati dove servono.")
                    .setPositiveButton("Scrivi sul foglio") { _, _ -> openEditor(doc, "") }.setNegativeButton("Chiudi", null).show()
                return@runOnUiThread
            }
            var save: () -> Unit = {}
            val values = HashMap<String, String>()
            fields.forEach { values[it.name] = it.value }
            val initial = HashMap(values)
            openScreen("Compila", "Salva", { save() }, dirty = { values != initial }) { body ->
                val edits = HashMap<String, EditText>()
                hintLabel(body, "${fields.size} campi trovati. Lascia vuoti quelli che non ti servono.")
                listRow(body, "👤", "Riempi con i miei dati", "Nome, codice fiscale, indirizzo… nei campi vuoti") {
                    val data = Store.myData(this)
                    if (data.isEmpty()) { toast("Prima scrivi i tuoi dati: li ricordo per i prossimi moduli"); openMyData(); return@listRow }
                    var n = 0
                    fields.filter { it.kind == "text" && values[it.name].isNullOrBlank() }.forEach { f ->
                        val key = Text.myDataKey(f.label) ?: Text.myDataKey(f.name) ?: return@forEach
                        val v = Text.myDataValue(key, data) ?: return@forEach
                        edits[f.name]?.setText(v); n++
                    }
                    toast(if (n == 0) "Non ho trovato campi che chiedono i tuoi dati" else "Compilati $n campi: controllali prima di salvare")
                }
                fields.forEach { f ->
                    when (f.kind) {
                        "check" -> body.addView(android.widget.CheckBox(this).apply { text = f.label; isChecked = f.value == "true"; textSize = 15f; setOnCheckedChangeListener { _, on -> values[f.name] = on.toString() } })
                        "radio", "choice" -> {
                            val btn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { isAllCaps = false; text = f.label + ": " + f.value.ifBlank { "scegli" } }
                            btn.setOnClickListener { _ -> MaterialAlertDialogBuilder(this).setTitle(f.label).setItems(f.options.toTypedArray()) { _, w -> values[f.name] = f.options[w]; btn.text = f.label + ": " + f.options[w] }.show() }
                            body.addView(btn)
                        }
                        else -> {
                            body.addView(TextView(this).apply { text = f.label; textSize = 13f; setTextColor(col(R.color.muted)); setPadding(4, 16, 4, 0) })
                            body.addView(EditText(this).apply {
                                setText(f.value)
                                edits[f.name] = this
                                addTextChangedListener(object : android.text.TextWatcher {
                                    override fun afterTextChanged(s: android.text.Editable?) { values[f.name] = s.toString() }
                                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                })
                            })
                        }
                    }
                }
                listRow(body, "🔤", "Scrivi anche fuori dai campi", "Testo libero, firma, ✓ o note sul foglio") { openEditor(doc, "") }
                save = {
                    val out = File(cacheDir, "form_${System.currentTimeMillis()}.pdf")
                    toast("Creo il PDF compilato…")
                    io.execute {
                        val r = runCatching { PdfEdit.fill(this, file, out, values) }
                        runOnUiThread { r.onSuccess { saveResult(out, doc.name, "compilato") }.onFailure { toast("Non riuscito: ${it.message}".take(140)) } }
                    }
                }
            }
        }
    }
}

/** I miei dati: scritti una volta, poi si mettono sui moduli con un tocco. */
fun MainActivity.openMyData() {
    val data = Store.myData(this)
    val edits = LinkedHashMap<String, EditText>()
    openScreen("I miei dati", "Salva", {
        Store.setMyData(this, edits.mapValues { it.value.text.toString() })
        toast("✓ Dati salvati"); pop(force = true)
    }, dirty = { edits.any { (k, e) -> e.text.toString().trim() != data[k].orEmpty() } }) { body ->
        hintLabel(body, "Restano solo su questo telefono. Servono per «👤 I miei dati» quando firmi o compili un modulo.")
        Store.MY_FIELDS.forEach { (k, label) ->
            body.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(col(R.color.muted)); setPadding(4, 16, 4, 0) })
            body.addView(EditText(this).apply {
                setText(data[k].orEmpty()); setSingleLine()
                inputType = when (k) {
                    "email" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    "telefono" -> android.text.InputType.TYPE_CLASS_PHONE
                    "cap" -> android.text.InputType.TYPE_CLASS_NUMBER
                    "cf", "iban", "provincia" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    "nascita" -> android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_DATE
                    else -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                }
                edits[k] = this
            })
        }
    }
}
