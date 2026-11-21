package com.micmax.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Pagine di un PDF: [split] = scegli le pagine da tenere; altrimenti ruota, sposta, togli, aggiungi. */
fun MainActivity.openPages(doc: Store.Doc, split: Boolean) {
    val file = doc.file(this)
    val total = PdfEdit.pageCount(file)
    if (total == 0) { toast("Non riesco a leggere questo PDF"); return }
    pagesScreen(if (split) "Dividi" else "Pagine", (0 until total).map { PdfEdit.PageOp.FromPdf(file, it) }, doc.name, split, if (split) null else "modificato")
}

/**
 * The page board: thumbnails you can drag to reorder (long press), tap to turn, copy or remove; buttons to add
 * a PDF, a photo or a blank page, page numbers and a watermark. With [split] a tap picks the pages to keep.
 * [baseName] and [suffix] name the new PDF.
 */
fun MainActivity.pagesScreen(title: String, start: List<PdfEdit.PageOp>, baseName: String, split: Boolean, suffix: String?, closeTop: Boolean = true) {
    val ops = start.toMutableList()
    val selected = HashSet<PdfEdit.PageOp>()
    var decor = PdfEdit.Decor()
    var changed = false
    val thumbs = HashMap<PdfEdit.PageOp, Bitmap?>()
    var save: () -> Unit = {}
    openScreen(title, "Salva", { save() }, dirty = { changed || selected.isNotEmpty() }, scroll = false) { body ->
        val status = TextView(this).apply { textSize = 13f; setTextColor(col(R.color.muted)); setPadding(dp(6), 0, dp(6), dp(6)) }
        lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
        fun refresh() {
            status.text = if (split) "Scelte ${selected.size} di ${ops.size} pagine"
            else listOfNotNull("${ops.size} ${if (ops.size == 1) "pagina" else "pagine"}", if (decor.numbers) "numeri di pagina" else null, decor.watermark?.let { "filigrana «$it»" }).joinToString(" · ")
            adapter.notifyDataSetChanged()
        }
        fun mark() { changed = true; refresh() }

        hintLabel(body, if (split) "Tocca le pagine che vuoi tenere: con «Salva» ne esce un PDF nuovo solo con quelle."
            else "Tieni premuta una pagina e trascinala per spostarla. Toccala per girarla, copiarla o toglierla.")
        if (split) body.addView(chipBar(listOf(
            "✓ Tutte" to { selected.clear(); selected += ops; refresh() },
            "✗ Nessuna" to { selected.clear(); refresh() },
            "½ Dispari" to { selected.clear(); ops.forEachIndexed { i, o -> if (i % 2 == 0) selected += o }; refresh() },
            "½ Pari" to { selected.clear(); ops.forEachIndexed { i, o -> if (i % 2 == 1) selected += o }; refresh() }
        )))
        else body.addView(chipBar(listOf(
            "➕ PDF" to {
                pickPdf(false) { uris ->
                    toast("Aggiungo…")
                    io.execute {
                        val f = copyIn(uris.first(), "add", ".pdf")
                        val n = if (f != null && PdfEdit.checkStrict(f) == PdfEdit.OK) PdfEdit.pageCount(f) else 0
                        runOnUiThread { if (n == 0) toast("Non riesco a leggere quel PDF (se ha una password, aprilo prima dalla home)") else { (0 until n).forEach { ops += PdfEdit.PageOp.FromPdf(f!!, it) }; mark(); toast("Aggiunte $n pagine") } }
                    }
                }
            },
            "📷 Foto" to {
                MaterialAlertDialogBuilder(this).setItems(arrayOf("📷 Scatta una foto", "🖼 Scegli dalla galleria (anche più di una)")) { _, w ->
                    if (w == 0) {
                        val f = File(cacheDir, "pagina_${System.currentTimeMillis()}.jpg")
                        takePhoto(f) { ok -> if (ok) { ops += PdfEdit.PageOp.Photo(f); mark() } }
                    } else pickImages { uris ->
                        io.execute {
                            val files = uris.mapNotNull { copyIn(it, "pagina", ".jpg") }
                            runOnUiThread { if (files.isEmpty()) toast("Non riesco a leggere le foto") else { files.forEach { ops += PdfEdit.PageOp.Photo(it) }; mark() } }
                        }
                    }
                }.show()
            },
            "📄 Pagina bianca" to { ops += PdfEdit.PageOp.Blank(); mark(); toast("Pagina bianca aggiunta in fondo") },
            "🔢 Numeri" to { decor = decor.copy(numbers = !decor.numbers); mark(); toast(if (decor.numbers) "Metto i numeri in fondo a ogni pagina" else "Niente numeri di pagina") },
            "💧 Filigrana" to {
                val et = EditText(this).apply { hint = "Es. COPIA, BOZZA, Solo per la banca"; setText(decor.watermark.orEmpty()); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; setPadding(dp(20), dp(14), dp(20), dp(14)) }
                MaterialAlertDialogBuilder(this).setTitle("💧 Filigrana").setMessage("Una scritta grande e chiara di traverso su ogni pagina.").setView(et)
                    .setPositiveButton("Metti") { _, _ -> decor = decor.copy(watermark = et.text.toString().trim().take(40).ifBlank { null }); mark() }
                    .setNeutralButton("Togli") { _, _ -> decor = decor.copy(watermark = null); mark() }
                    .setNegativeButton("Annulla", null).show()
            }
        )))
        body.addView(status)

        val rv = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@pagesScreen, 3); clipToPadding = false; setPadding(0, 0, 0, dp(24)) }
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = ops.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val iv = ImageView(this@pagesScreen).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE); elevation = dp(2).toFloat(); id = 1 }
                val tv = TextView(this@pagesScreen).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); id = 2 }
                val cell = LinearLayout(this@pagesScreen).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                    addView(iv, LinearLayout.LayoutParams(dp(92), dp(120)))
                    addView(tv)
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                }
                return object : RecyclerView.ViewHolder(cell) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val op = ops[pos]
                val cell = h.itemView as LinearLayout
                val iv = cell.findViewById<ImageView>(1); val tv = cell.findViewById<TextView>(2)
                val turn = ((op as? PdfEdit.PageOp.FromPdf)?.turn ?: 0) % 360
                iv.rotation = turn.toFloat()
                val on = split && op in selected
                cell.background = if (on) roundBg(col(R.color.icbg), 10, col(R.color.accent)) else null
                tv.text = (if (on) "✓ " else "") + "${pos + 1}" + (if (turn != 0) " ⟳" else "") + when (op) { is PdfEdit.PageOp.Photo -> " 📷"; is PdfEdit.PageOp.Blank -> " (bianca)"; else -> "" }
                iv.tag = op
                if (thumbs.containsKey(op)) iv.setImageBitmap(thumbs[op])
                else {
                    iv.setImageDrawable(null)
                    io.execute {
                        val b = when (op) {
                            is PdfEdit.PageOp.FromPdf -> PdfEdit.render(op.file, op.index, 220)
                            is PdfEdit.PageOp.Photo -> PdfEdit.loadPhoto(op.file.path, 300)
                            is PdfEdit.PageOp.Blank -> null
                        }
                        runOnUiThread { thumbs[op] = b; if (iv.tag == op) iv.setImageBitmap(b) }
                    }
                }
                cell.setOnClickListener {
                    val i = h.bindingAdapterPosition.takeIf { it >= 0 } ?: return@setOnClickListener
                    if (split) { if (!selected.add(ops[i])) selected.remove(ops[i]); refresh(); return@setOnClickListener }
                    MaterialAlertDialogBuilder(this@pagesScreen).setTitle("Pagina ${i + 1}")
                        .setItems(arrayOf("⟳ Gira", "⧉ Copia (la mette dopo)", "⬅ Sposta prima", "➡ Sposta dopo", "🗑 Togli")) { _, w ->
                            when (w) {
                                0 -> (ops[i] as? PdfEdit.PageOp.FromPdf)?.let { ops[i] = it.copy(turn = (it.turn + 90) % 360) } ?: toast("Solo le pagine dei PDF si girano")
                                1 -> ops.add(i + 1, when (val o = ops[i]) { is PdfEdit.PageOp.Blank -> PdfEdit.PageOp.Blank(); is PdfEdit.PageOp.FromPdf -> o.copy(turn = o.turn + 360); else -> o })
                                2 -> if (i > 0) ops.add(i - 1, ops.removeAt(i))
                                3 -> if (i < ops.lastIndex) ops.add(i + 1, ops.removeAt(i))
                                4 -> if (ops.size > 1) ops.removeAt(i) else toast("Serve almeno una pagina")
                            }
                            mark()
                        }.show()
                }
            }
        }
        rv.adapter = adapter
        if (!split) ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(r: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                val from = a.bindingAdapterPosition; val to = b.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                ops.add(to, ops.removeAt(from)); adapter.notifyItemMoved(from, to); changed = true
                return true
            }
            override fun onSwiped(v: RecyclerView.ViewHolder, d: Int) {}
            override fun clearView(r: RecyclerView, v: RecyclerView.ViewHolder) { super.clearView(r, v); r.post { refresh() } }
        }).attachToRecyclerView(rv)
        body.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))

        save = {
            val use = if (split) ops.filter { it in selected } else ops.map { if (it is PdfEdit.PageOp.FromPdf) it.copy(turn = it.turn % 360) else it }
            if (use.isEmpty()) toast("Scegli almeno una pagina")
            else {
                val out = File(cacheDir, "pages_${System.currentTimeMillis()}.pdf")
                toast("Creo il nuovo PDF…")
                val d = decor
                io.execute {
                    val r = runCatching { PdfEdit.rebuild(this, use, out, d) }
                    runOnUiThread { r.onSuccess { saveResult(out, baseName, suffix ?: "${use.size} ${if (use.size == 1) "pagina" else "pagine"}", closeTop) }.onFailure { toast("Non riuscito: ${it.message}".take(140)) } }
                }
            }
        }
        refresh()
    }
}

/** Copia un file scelto in un file dell'app (null se non si legge). */
fun MainActivity.copyIn(uri: Uri, prefix: String, ext: String): File? {
    val f = File(cacheDir, "${prefix}_${System.nanoTime()}$ext")
    return f.takeIf { runCatching { contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }.isSuccess && it.length() > 0 }
}

/** Unisce più PDF: prima si vedono tutte le pagine (si possono riordinare), poi «Salva». */
fun MainActivity.mergePdfs(uris: List<Uri>) {
    if (uris.size < 2) { toast("Scegli almeno due PDF"); return }
    toast("Preparo ${uris.size} PDF…")
    io.execute {
        val files = uris.mapNotNull { u -> copyIn(u, "merge", ".pdf")?.takeIf { PdfEdit.checkStrict(it) == PdfEdit.OK } }
        val ops = files.flatMap { f -> (0 until PdfEdit.pageCount(f)).map { PdfEdit.PageOp.FromPdf(f, it) } }
        runOnUiThread {
            if (files.size < uris.size) toast("Non riesco a leggere ${uris.size - files.size} PDF (hanno la password?)")
            if (files.size < 2) return@runOnUiThread
            pagesScreen("Unisci", ops, "PDF uniti", false, null)
        }
    }
}

/** Più foto diventano un PDF (una per pagina), in ordine; prima si possono riordinare. */
fun MainActivity.photosToPdf(uris: List<Uri>) {
    if (uris.isEmpty()) return
    toast("Preparo ${uris.size} foto…")
    io.execute {
        val files = uris.mapNotNull { copyIn(it, "foto", ".jpg") }
        runOnUiThread {
            if (files.isEmpty()) { toast("Non riesco a leggere le foto"); return@runOnUiThread }
            pagesScreen("Foto in PDF", files.map { PdfEdit.PageOp.Photo(it) }, "Foto " + Text.date().replace('/', '-'), false, null)
        }
    }
}
