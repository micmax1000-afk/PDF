package com.micmax.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A word on a page: its text and where it is, as fractions of the page as shown (0..1 from the top left); [line] counts the lines. */
data class Word(val text: String, val x: Float, val y: Float, val w: Float, val h: Float, val line: Int)

/** Choosing words (pure, unit-tested). */
object TextSel {
    /** The words from [a] to [b] (either order), one line of text per line of the page. */
    fun text(words: List<Word>, a: Int, b: Int): String {
        if (words.isEmpty()) return ""
        val lo = minOf(a, b).coerceIn(0, words.lastIndex); val hi = maxOf(a, b).coerceIn(0, words.lastIndex)
        val sb = StringBuilder()
        for (i in lo..hi) {
            if (i > lo) sb.append(if (words[i].line != words[i - 1].line) "\n" else " ")
            sb.append(words[i].text)
        }
        return sb.toString()
    }

    /** One box per line for the words from [a] to [b]: x, y, w, h as fractions. */
    fun lineBoxes(words: List<Word>, a: Int, b: Int): List<FloatArray> {
        if (words.isEmpty()) return emptyList()
        val lo = minOf(a, b).coerceIn(0, words.lastIndex); val hi = maxOf(a, b).coerceIn(0, words.lastIndex)
        return words.subList(lo, hi + 1).groupBy { it.line }.values.map { ws ->
            val l = ws.minOf { it.x }; val t = ws.minOf { it.y }; val r = ws.maxOf { it.x + it.w }; val btm = ws.maxOf { it.y + it.h }
            floatArrayOf(l, t, r - l, btm - t)
        }
    }

    /** The word under (fx, fy), or the closest one within [reach] (fractions of the page width); -1 if none. */
    fun at(words: List<Word>, fx: Float, fy: Float, ratio: Float, reach: Float = Float.MAX_VALUE): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        words.forEachIndexed { i, wd ->
            val dx = maxOf(wd.x - fx, 0f, fx - (wd.x + wd.w))
            val dy = (maxOf(wd.y - fy, 0f, fy - (wd.y + wd.h))) / ratio.coerceAtLeast(0.01f)
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && Math.sqrt(bestD.toDouble()) <= reach) best else -1
    }
}

/** The page with the words: drag a finger on the text to choose it. */
@SuppressLint("ViewConstructor")
class SelectView(c: Context, private val onChange: () -> Unit) : View(c) {
    var words: List<Word> = emptyList()
        set(v) { field = v; start = -1; end = -1; invalidate() }
    var start = -1
    var end = -1
    val hasSelection get() = start >= 0 && end >= 0
    private val fill = Paint().apply { color = 0x553B6CF5 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B6CF5.toInt(); style = Paint.Style.STROKE; strokeWidth = c.dp(1).toFloat() }
    private var dragging = false

    fun select(a: Int, b: Int) { start = a; end = b; invalidate(); onChange() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (words.isEmpty() || width == 0) return false
        val fx = e.x / width; val fy = e.y / height
        val ratio = width.toFloat() / height
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // only a touch on the text starts choosing: elsewhere the page scrolls as usual
                val i = TextSel.at(words, fx, fy, ratio, context.dp(20).toFloat() / width)
                if (i < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                if (hasSelection && (i == start || i == end)) { if (i == start) { start = end; end = i } } else { start = i; end = i }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val i = TextSel.at(words, fx, fy, ratio)
                if (i >= 0 && i != end) { end = i; invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; onChange() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (!hasSelection) return
        val W = width.toFloat(); val H = height.toFloat()
        TextSel.lineBoxes(words, start, end).forEach { b ->
            val r = RectF(b[0] * W - 2, b[1] * H - 2, (b[0] + b[2]) * W + 2, (b[1] + b[3]) * H + 2)
            c.drawRect(r, fill)
        }
        val a = words[minOf(start, end)]; val z = words[maxOf(start, end)]
        val k = context.dp(5).toFloat()
        c.drawCircle(a.x * W, (a.y + a.h) * H + k, k, edge.apply { style = Paint.Style.FILL })
        c.drawCircle((z.x + z.w) * W, (z.y + z.h) * H + k, k, edge)
    }
}

/** Selezionare il testo di una pagina: copia, condividi, evidenzia. [fx], [fy] (se dati) scelgono subito la parola toccata. */
fun MainActivity.openSelect(doc: Store.Doc, startPage: Int, fx: Float = -1f, fy: Float = -1f) {
    val file = doc.file(this)
    val total = PdfEdit.pageCount(file)
    if (total == 0) { toast("Non riesco a leggere questo PDF"); return }
    openScreen("Seleziona il testo", null, null) { body ->
        var page = startPage.coerceIn(0, total - 1)
        val info = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(col(R.color.muted)); setPadding(dp(4), dp(6), dp(4), dp(6)) }
        lateinit var sel: SelectView
        fun chosen() = if (sel.hasSelection) TextSel.text(sel.words, sel.start, sel.end) else ""
        fun showInfo() {
            info.text = "Pagina ${page + 1} di $total · " + when {
                sel.words.isEmpty() -> "nessun testo su questa pagina (è una scansione?)"
                sel.hasSelection -> { val n = Math.abs(sel.end - sel.start) + 1; "$n ${if (n == 1) "parola scelta" else "parole scelte"}" }
                else -> "trascina il dito sulle parole"
            }
        }
        sel = SelectView(this) { showInfo() }
        val img = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_XY }
        val frame = FrameLayout(this).apply {
            addView(img, FrameLayout.LayoutParams(-1, -2))
            addView(sel, FrameLayout.LayoutParams(-1, -1))
        }
        fun show(p: Int, tapX: Float = -1f, tapY: Float = -1f) {
            page = p.coerceIn(0, total - 1)
            sel.words = emptyList(); info.text = "Pagina ${page + 1} di $total · leggo il testo…"
            io.execute {
                val b = PdfEdit.render(file, page, resources.displayMetrics.widthPixels)
                val w = PdfEdit.words(this, file, page)
                runOnUiThread {
                    b?.let { img.setImageBitmap(it) }
                    sel.words = w
                    if (tapX >= 0 && w.isNotEmpty()) img.post {
                        val r = if (img.height > 0) img.width.toFloat() / img.height else 0.7f
                        val i = TextSel.at(w, tapX, tapY, r, 0.06f)
                        if (i >= 0) sel.select(i, i)
                    }
                    showInfo()
                    if (w.isEmpty()) toast("Questa pagina non ha testo da selezionare: è una scansione o un'immagine")
                }
            }
        }
        fun need(): String? = chosen().ifBlank { toast("Prima scegli del testo trascinando il dito"); null }
        body.addView(chipBar(listOf(
            "📋 Copia" to {
                need()?.let { t ->
                    (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("PDF", t))
                    toast("Copiato")
                }
            },
            "▣ Tutta la pagina" to { if (sel.words.isEmpty()) toast("Niente testo su questa pagina") else sel.select(0, sel.words.lastIndex) },
            "📤 Condividi" to {
                need()?.let { t -> runCatching { startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, t) }, "Condividi il testo")) } }
            },
            "🖍 Evidenzia" to {
                need()?.let {
                    val marks = TextSel.lineBoxes(sel.words, sel.start, sel.end).map { b -> EditItem(page, null, b[0], b[1], b[2], b[3] * 1.1f, EditItem.HIGHLIGHT) }
                    pop(force = true)
                    openEditor(doc, "", page, marks)
                    toast("Evidenziato: tocca «Salva» per tenerlo")
                }
            },
            "✗ Niente" to { sel.start = -1; sel.end = -1; sel.invalidate(); showInfo() }
        )))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(TextView(this).apply { text = "‹"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); contentDescription = "Pagina prima"; setOnClickListener { show(page - 1) } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        nav.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(TextView(this).apply { text = "›"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); contentDescription = "Pagina dopo"; setOnClickListener { show(page + 1) } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        body.addView(nav)
        body.addView(frame, LinearLayout.LayoutParams(-1, -2))
        hintLabel(body, "Tocca una parola e trascina fino all'ultima che ti serve. Trascina i pallini blu per allargare o stringere.")
        show(page, fx, fy)
    }
}
