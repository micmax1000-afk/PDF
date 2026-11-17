package com.micmax.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

// ======================= Firma, testo, note, evidenziatore, moduli =======================

/** Un riquadro per firmare con il dito. */
class SignaturePad(c: Context) : View(c) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1D3A8A.toInt(); style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bounds = android.graphics.RectF(Float.MAX_VALUE, Float.MAX_VALUE, -1f, -1f)
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

private fun MainActivity.signatureFile() = File(filesDir, "firma.png")

/** Chiede la firma (quella salvata o una nuova) e la consegna a [then]. */
fun MainActivity.askSignature(then: (Bitmap) -> Unit) {
    val saved = signatureFile().takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    if (saved != null) {
        MaterialAlertDialogBuilder(this).setTitle("✍️ La tua firma")
            .setView(ImageView(this).apply { setImageBitmap(saved); adjustViewBounds = true; setPadding(dp(24), dp(12), dp(24), 0) })
            .setPositiveButton("Usa questa") { _, _ -> then(saved) }
            .setNeutralButton("Firma di nuovo") { _, _ -> signatureFile().delete(); askSignature(then) }
            .setNegativeButton("Annulla", null).show()
        return
    }
    val pad = SignaturePad(this).apply { background = roundBg(0xFFFAFBFE.toInt(), 14, 0xFFB9C2D6.toInt()) }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
        addView(pad, LinearLayout.LayoutParams(-1, dp(190)))
        addView(TextView(this@askSignature).apply { text = "Firma qui con il dito: la ricordo per la prossima volta."; textSize = 13f; alpha = 0.7f; setPadding(0, dp(6), 0, 0) })
    }
    val dlg = MaterialAlertDialogBuilder(this).setTitle("✍️ Firma").setView(box)
        .setPositiveButton("Metti sul foglio", null).setNeutralButton("Cancella", null).setNegativeButton("Annulla", null).create()
    dlg.setOnShowListener {
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val b = pad.bitmap() ?: run { toast("Prima firma nel riquadro"); return@setOnClickListener }
            runCatching { signatureFile().outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            dlg.dismiss(); then(b)
        }
    }
    dlg.show()
}

/** Un testo come immagine da mettere sul foglio (nero su trasparente, oppure un foglietto giallo). */
fun textBitmap(text: String, note: Boolean): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (note) 0xFF3D2E00.toInt() else Color.BLACK; textSize = 44f }
    val lines = text.trim().lines().flatMap { l -> if (l.isEmpty()) listOf(" ") else l.chunked(36) }.ifEmpty { listOf(" ") }
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

/** [mode]: "sign" parte chiedendo la firma, "highlight" parte con l'evidenziatore, "text"/"note" chiedono il testo. */
@SuppressLint("ClickableViewAccessibility")
fun MainActivity.openEditor(doc: Store.Doc, mode: String, startPage: Int = 0) {
    val file = doc.file(this)
    val total = PdfEdit.pageCount(file)
    if (total == 0) { toast("Non riesco a leggere questo PDF"); return }
    var save: () -> Unit = {}
    openScreen("Firma, testo e note", "Salva", { save() }) { body ->
        class Item(val page: Int, val bitmap: Bitmap?, var x: Float, var y: Float, var w: Float, var h: Float, val highlight: Boolean)
        val items = mutableListOf<Item>()
        var page = startPage.coerceIn(0, total - 1)
        var marker = mode == "highlight"
        val frame = FrameLayout(this)
        val img = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_XY }
        frame.addView(img, FrameLayout.LayoutParams(-1, -2))
        val overlay = FrameLayout(this)
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val info = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(col(R.color.muted)); setPadding(0, dp(6), 0, dp(6)) }
        lateinit var drawItems: () -> Unit

        fun addItemView(it: Item) {
            val W = overlay.width.toFloat().coerceAtLeast(1f); val H = overlay.height.toFloat().coerceAtLeast(1f)
            if (it.highlight) {
                overlay.addView(View(this).apply { setBackgroundColor(0x66FDE047); setOnLongClickListener { _ -> items.remove(it); drawItems(); true } },
                    FrameLayout.LayoutParams((it.w * W).toInt(), (it.h * H).toInt()).apply { leftMargin = (it.x * W).toInt(); topMargin = (it.y * H).toInt() })
                return
            }
            val holder = FrameLayout(this).apply { background = android.graphics.drawable.GradientDrawable().apply { setStroke(dp(2), 0xFF3B6CF5.toInt()); cornerRadius = dp(4).toFloat() } }
            holder.addView(ImageView(this).apply { setImageBitmap(it.bitmap); scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(-1, -1))
            val handle = View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF3B6CF5.toInt()) } }
            holder.addView(handle, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END))
            overlay.addView(holder, FrameLayout.LayoutParams((it.w * W).toInt(), (it.h * H).toInt()).apply { leftMargin = (it.x * W).toInt(); topMargin = (it.y * H).toInt() })
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var ow = 0; var oh = 0
            holder.setOnTouchListener { v, e ->
                frame.parent?.requestDisallowInterceptTouchEvent(true)
                val l = v.layoutParams as FrameLayout.LayoutParams
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = l.leftMargin; oy = l.topMargin }
                    MotionEvent.ACTION_MOVE -> { l.leftMargin = (ox + e.rawX - sx).toInt().coerceIn(0, (W - l.width).toInt().coerceAtLeast(0)); l.topMargin = (oy + e.rawY - sy).toInt().coerceIn(0, (H - l.height).toInt().coerceAtLeast(0)); v.layoutParams = l }
                    MotionEvent.ACTION_UP -> { it.x = l.leftMargin / W; it.y = l.topMargin / H; if (Math.abs(e.rawX - sx) < 8 && Math.abs(e.rawY - sy) < 8) MaterialAlertDialogBuilder(this).setItems(arrayOf("Togli")) { _, _ -> items.remove(it); drawItems() }.show() }
                }
                true
            }
            handle.setOnTouchListener { _, e ->
                frame.parent?.requestDisallowInterceptTouchEvent(true)
                val l = holder.layoutParams as FrameLayout.LayoutParams
                val ratio = it.h * H / (it.w * W).coerceAtLeast(1f)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; ow = l.width; oh = l.height }
                    MotionEvent.ACTION_MOVE -> { val nw = (ow + e.rawX - sx).toInt().coerceIn(dp(40), (W - l.leftMargin).toInt().coerceAtLeast(dp(40))); l.width = nw; l.height = (nw * ratio).toInt(); holder.layoutParams = l }
                    MotionEvent.ACTION_UP -> { it.w = l.width / W; it.h = l.height / H }
                }
                true
            }
        }
        drawItems = { overlay.removeAllViews(); items.filter { it.page == page }.forEach { addItemView(it) } }

        fun place(b: Bitmap, widthFraction: Float) {
            val W = overlay.width.toFloat().coerceAtLeast(1f); val H = overlay.height.toFloat().coerceAtLeast(1f)
            val h = (widthFraction * W * b.height / b.width) / H
            items += Item(page, b, (1 - widthFraction) / 2, (0.5f - h / 2).coerceAtLeast(0f), widthFraction, h, false); drawItems()
            toast("Trascinala dove serve · il pallino blu la ingrandisce")
        }
        var hx = 0f; var hy = 0f
        val draft = View(this).apply { setBackgroundColor(0x66FDE047); visibility = View.GONE }
        frame.addView(draft, FrameLayout.LayoutParams(0, 0))
        overlay.setOnTouchListener { _, e ->
            if (!marker) return@setOnTouchListener false
            frame.parent?.requestDisallowInterceptTouchEvent(true)
            val l = draft.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { hx = e.x; hy = e.y; draft.visibility = View.VISIBLE }
                MotionEvent.ACTION_MOVE -> { l.leftMargin = minOf(hx, e.x).toInt(); l.topMargin = minOf(hy, e.y).toInt(); l.width = Math.abs(e.x - hx).toInt(); l.height = Math.abs(e.y - hy).toInt().coerceAtLeast(dp(14)); draft.layoutParams = l }
                MotionEvent.ACTION_UP -> {
                    draft.visibility = View.GONE
                    val W = overlay.width.toFloat(); val H = overlay.height.toFloat()
                    if (l.width > dp(10)) { items += Item(page, null, l.leftMargin / W, l.topMargin / H, l.width / W, l.height / H, true); drawItems() }
                }
            }
            true
        }
        fun show(p: Int) {
            page = p.coerceIn(0, total - 1)
            info.text = "Pagina ${page + 1} di $total" + if (marker) " · trascina il dito sul testo per evidenziarlo" else ""
            io.execute { val b = PdfEdit.render(file, page, resources.displayMetrics.widthPixels); runOnUiThread { b?.let { img.setImageBitmap(it) }; img.post { drawItems() } } }
        }
        fun askText(title: String, hint: String, note: Boolean, frac: Float) {
            marker = false; show(page)
            val et = EditText(this).apply { this.hint = hint; setPadding(dp(20), dp(14), dp(20), dp(14)) }
            MaterialAlertDialogBuilder(this).setTitle(title).setView(et)
                .setPositiveButton("Metti") { _, _ -> et.text.toString().takeIf { it.isNotBlank() }?.let { place(textBitmap(it, note), frac) } }
                .setNegativeButton("Annulla", null).show()
        }
        body.addView(chipBar(listOf(
            "‹" to { show(page - 1) },
            "✍️ Firma" to { marker = false; show(page); askSignature { place(it, 0.35f) } },
            "🔤 Testo" to { askText("Testo sul foglio", "Cosa scrivo sul foglio", false, 0.45f) },
            "📅 Data" to { marker = false; show(page); place(textBitmap(java.text.SimpleDateFormat("d/M/yyyy", java.util.Locale.ITALIAN).format(java.util.Date()), false), 0.22f) },
            "🖍 Evidenzia" to { marker = true; show(page) },
            "📌 Nota" to { askText("Nota", "La tua nota", true, 0.35f) },
            "›" to { show(page + 1) }
        )))
        body.addView(info)
        body.addView(frame, LinearLayout.LayoutParams(-1, -2))
        hintLabel(body, "Tieni premuto un segno giallo per toglierlo. Tocca una firma, un testo o una nota per toglierli.")
        save = {
            if (items.isEmpty()) toast("Non hai aggiunto niente")
            else {
                val edits = items.map { if (it.highlight) PdfEdit.Edit.Highlight(it.page, it.x, it.y, it.w, it.h) else PdfEdit.Edit.Image(it.page, it.bitmap!!, it.x, it.y, it.w, it.h) }
                val suffix = if (items.any { !it.highlight }) "firmato" else "evidenziato"
                val out = File(cacheDir, "edit_${System.currentTimeMillis()}.pdf")
                toast("Creo il nuovo PDF…")
                io.execute {
                    val r = runCatching { PdfEdit.apply(this, file, out, edits) }
                    runOnUiThread { r.onSuccess { saveResult(out, doc.name, suffix) }.onFailure { toast("Non riuscito: ${it.message}".take(140)) } }
                }
            }
        }
        show(page)
        when (mode) {
            "sign" -> frame.post { askSignature { place(it, 0.35f) } }
            "text" -> frame.post { askText("Testo sul foglio", "Cosa scrivo sul foglio", false, 0.45f) }
            "note" -> frame.post { askText("Nota", "La tua nota", true, 0.35f) }
        }
    }
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
                    .setMessage("Questo PDF non ha campi da riempire (succede con i fogli scansionati). Puoi scrivere del testo dove vuoi sul foglio.")
                    .setPositiveButton("Scrivi sul foglio") { _, _ -> openEditor(doc, "text") }.setNegativeButton("Chiudi", null).show()
                return@runOnUiThread
            }
            var save: () -> Unit = {}
            openScreen("Compila", "Salva", { save() }) { body ->
                val values = HashMap<String, String>()
                hintLabel(body, "${fields.size} campi trovati. Lascia vuoti quelli che non ti servono.")
                fields.forEach { f ->
                    when (f.kind) {
                        "check" -> {
                            values[f.name] = f.value
                            body.addView(android.widget.CheckBox(this).apply { text = f.label; isChecked = f.value == "true"; textSize = 15f; setOnCheckedChangeListener { _, on -> values[f.name] = on.toString() } })
                        }
                        "radio", "choice" -> {
                            val btn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { isAllCaps = false; text = f.label + ": " + f.value.ifBlank { "scegli" } }
                            btn.setOnClickListener { _ -> MaterialAlertDialogBuilder(this).setTitle(f.label).setItems(f.options.toTypedArray()) { _, w -> values[f.name] = f.options[w]; btn.text = f.label + ": " + f.options[w] }.show() }
                            body.addView(btn)
                        }
                        else -> {
                            body.addView(TextView(this).apply { text = f.label; textSize = 13f; setTextColor(col(R.color.muted)); setPadding(4, 16, 4, 0) })
                            body.addView(EditText(this).apply {
                                setText(f.value)
                                addTextChangedListener(object : android.text.TextWatcher {
                                    override fun afterTextChanged(s: android.text.Editable?) { values[f.name] = s.toString() }
                                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                })
                            })
                        }
                    }
                }
                listRow(body, "🔤", "Scrivi anche fuori dai campi", "Testo libero, firma o note sul foglio") { openEditor(doc, "text") }
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
