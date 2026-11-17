package com.micmax.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Pagine di un PDF: [split] = scegli le pagine da tenere; altrimenti ruota, sposta, togli, aggiungi un PDF o una foto. */
fun MainActivity.openPages(doc: Store.Doc, split: Boolean) {
    val file = doc.file(this)
    val total = PdfEdit.pageCount(file)
    if (total == 0) { toast("Non riesco a leggere questo PDF"); return }
    var save: () -> Unit = {}
    openScreen(if (split) "Dividi" else "Pagine", "Salva", { save() }) { body ->
        val ops = (0 until total).map { PdfEdit.PageOp.FromPdf(file, it) as PdfEdit.PageOp }.toMutableList()
        val selected = HashSet<Int>()
        val thumbs = HashMap<String, Bitmap?>()
        val grid = GridLayout(this).apply { columnCount = 3 }
        lateinit var render: () -> Unit
        fun keyOf(op: PdfEdit.PageOp) = when (op) { is PdfEdit.PageOp.FromPdf -> op.file.path + "#" + op.index; is PdfEdit.PageOp.Photo -> op.file.path }
        render = {
            grid.removeAllViews()
            ops.forEachIndexed { i, op ->
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE); elevation = dp(2).toFloat() }
                val turn = (op as? PdfEdit.PageOp.FromPdf)?.turn ?: 0
                iv.rotation = turn.toFloat()
                val on = split && i in selected
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                    if (on) background = roundBg(col(R.color.icbg), 10, col(R.color.accent))
                    addView(iv, LinearLayout.LayoutParams(dp(92), dp(120)))
                    addView(TextView(this@openPages).apply {
                        text = (if (on) "✓ " else "") + "${i + 1}" + (if (turn != 0) " ⟳" else "") + (if (op is PdfEdit.PageOp.Photo) " 📷" else "")
                        textSize = 12f; gravity = Gravity.CENTER; setTextColor(col(R.color.text))
                    })
                    layoutParams = GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) }
                    setOnClickListener {
                        if (split) { if (!selected.add(i)) selected.remove(i); render(); return@setOnClickListener }
                        MaterialAlertDialogBuilder(this@openPages).setTitle("Pagina ${i + 1}")
                            .setItems(arrayOf("⟳ Ruota", "⬅ Sposta prima", "➡ Sposta dopo", "🗑 Togli")) { _, w ->
                                when (w) {
                                    0 -> (op as? PdfEdit.PageOp.FromPdf)?.let { ops[i] = it.copy(turn = (it.turn + 90) % 360) } ?: toast("Le foto si girano prima di aggiungerle")
                                    1 -> if (i > 0) ops.add(i - 1, ops.removeAt(i))
                                    2 -> if (i < ops.lastIndex) ops.add(i + 1, ops.removeAt(i))
                                    3 -> if (ops.size > 1) ops.removeAt(i) else toast("Serve almeno una pagina")
                                }
                                render()
                            }.show()
                    }
                }
                grid.addView(cell)
                val k = keyOf(op)
                if (thumbs.containsKey(k)) iv.setImageBitmap(thumbs[k])
                else io.execute {
                    val b = when (op) {
                        is PdfEdit.PageOp.FromPdf -> PdfEdit.render(op.file, op.index, 220)
                        is PdfEdit.PageOp.Photo -> runCatching { android.graphics.BitmapFactory.decodeFile(op.file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) }.getOrNull()
                    }
                    runOnUiThread { thumbs[k] = b; iv.setImageBitmap(b) }
                }
            }
        }
        hintLabel(body, if (split) "Tocca le pagine che vuoi tenere: con «Salva» ne esce un PDF nuovo solo con quelle." else "Tocca una pagina per ruotarla, spostarla o toglierla.")
        body.addView(grid)
        if (!split) {
            listRow(body, "➕", "Aggiungi un PDF", "Le sue pagine vanno in fondo") {
                pickPdf(false) { uris ->
                    toast("Aggiungo…")
                    io.execute {
                        val f = File(cacheDir, "add_" + System.currentTimeMillis() + ".pdf")
                        val ok = runCatching { contentResolver.openInputStream(uris.first())!!.use { i -> f.outputStream().use { i.copyTo(it) } } }.isSuccess
                        val n = if (ok && PdfEdit.checkStrict(f) == PdfEdit.OK) PdfEdit.pageCount(f) else 0
                        runOnUiThread { if (n == 0) toast("Non riesco a leggere quel PDF (se ha una password, aprilo prima dalla home)") else { (0 until n).forEach { ops += PdfEdit.PageOp.FromPdf(f, it) }; render(); toast("Aggiunte $n pagine") } }
                    }
                }
            }
            listRow(body, "📷", "Aggiungi una foto", "Scatta una foto o scegline una: diventa una pagina") {
                MaterialAlertDialogBuilder(this).setItems(arrayOf("📷 Scatta una foto", "🖼 Scegli dalla galleria")) { _, w ->
                    val f = File(cacheDir, "pagina_${System.currentTimeMillis()}.jpg")
                    if (w == 0) takePhoto(f) { ok -> if (ok) { ops += PdfEdit.PageOp.Photo(f); render() } }
                    else pickImage { uri ->
                        io.execute {
                            val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }.isSuccess
                            runOnUiThread { if (ok) { ops += PdfEdit.PageOp.Photo(f); render() } else toast("Non riesco a leggere la foto") }
                        }
                    }
                }.show()
            }
        }
        save = {
            val use = if (split) ops.filterIndexed { i, _ -> i in selected } else ops.toList()
            if (use.isEmpty()) toast("Scegli almeno una pagina")
            else {
                val out = File(cacheDir, "pages_${System.currentTimeMillis()}.pdf")
                toast("Creo il nuovo PDF…")
                io.execute {
                    val r = runCatching { PdfEdit.rebuild(this, use, out) }
                    runOnUiThread { r.onSuccess { saveResult(out, doc.name, if (split) "${use.size} pagine" else "modificato") }.onFailure { toast("Non riuscito: ${it.message}".take(140)) } }
                }
            }
        }
        render()
    }
}

/** Unisce più PDF (nell'ordine scelto) in uno solo. */
fun MainActivity.mergePdfs(uris: List<android.net.Uri>) {
    if (uris.size < 2) { toast("Scegli almeno due PDF"); return }
    toast("Unisco ${uris.size} PDF…")
    io.execute {
        val files = uris.mapIndexedNotNull { i, u ->
            val f = File(cacheDir, "merge_${System.currentTimeMillis()}_$i.pdf")
            val ok = runCatching { contentResolver.openInputStream(u)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } } }.isSuccess
            f.takeIf { ok && PdfEdit.checkStrict(it) == PdfEdit.OK }
        }
        if (files.size < 2) { runOnUiThread { toast("Non riesco a leggere alcuni PDF (uno ha la password?)") }; return@execute }
        val ops = files.flatMap { f -> (0 until PdfEdit.pageCount(f)).map { PdfEdit.PageOp.FromPdf(f, it) as PdfEdit.PageOp } }
        val out = File(cacheDir, "merged_${System.currentTimeMillis()}.pdf")
        val r = runCatching { PdfEdit.rebuild(this, ops, out) }
        runOnUiThread { r.onSuccess { saveResult(out, "PDF uniti", "${ops.size} pagine", closeTop = false) }.onFailure { toast("Non riuscito: ${it.message}".take(140)) } }
    }
}
