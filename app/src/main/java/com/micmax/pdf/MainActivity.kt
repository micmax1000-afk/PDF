package com.micmax.pdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    val io = Executors.newSingleThreadExecutor()
    private lateinit var host: FrameLayout
    private val stack = ArrayList<Pair<View, (() -> Unit)?>>()
    private lateinit var homeBody: LinearLayout

    // ---------- selettori e fotocamera ----------
    private var pdfCb: ((List<Uri>) -> Unit)? = null
    private val pdfOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) pdfCb?.invoke(listOf(u)) }
    private val pdfMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l -> if (l.isNotEmpty()) pdfCb?.invoke(l) }
    fun pickPdf(multi: Boolean, cb: (List<Uri>) -> Unit) {
        pdfCb = cb
        runCatching { if (multi) pdfMany.launch(arrayOf("application/pdf")) else pdfOne.launch(arrayOf("application/pdf")) }.onFailure { toast("Non riesco ad aprire i file") }
    }

    private var imageCb: ((Uri) -> Unit)? = null
    private val imageOne = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) imageCb?.invoke(u) }
    fun pickImage(cb: (Uri) -> Unit) { imageCb = cb; runCatching { imageOne.launch("image/*") }.onFailure { toast("Non riesco ad aprire la galleria") } }

    private var photoCb: ((Boolean) -> Unit)? = null
    private val photoOne = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> photoCb?.invoke(ok) }
    fun takePhoto(f: File, cb: (Boolean) -> Unit) {
        photoCb = cb
        runCatching { photoOne.launch(FileProvider.getUriForFile(this, "$packageName.files", f)) }.onFailure { toast("Non riesco ad aprire la fotocamera") }
    }

    private var pendingSave: File? = null
    private val saveAsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pendingSave ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        val ok = runCatching { contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } } }.isSuccess
        toast(if (ok) "✓ PDF salvato" else "Non riesco a salvare il PDF")
    }
    fun saveAs(doc: Store.Doc) { pendingSave = doc.file(this); runCatching { saveAsLauncher.launch(Store.fileName(doc.name)) }.onFailure { toast("Non riesco a salvare") } }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val res = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@registerForActivityResult
        val uri = res.pdf?.uri ?: return@registerForActivityResult
        io.execute {
            val tmp = File(cacheDir, "scan_${System.currentTimeMillis()}.pdf")
            val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } } }.isSuccess
            runOnUiThread {
                if (!ok) { toast("Scansione non salvata"); return@runOnUiThread }
                val name = "Scansione " + java.text.SimpleDateFormat("d MMM HH.mm", java.util.Locale.ITALIAN).format(java.util.Date())
                io.execute { val d = Store.add(this, tmp, name); runOnUiThread { refreshHome(); openViewer(d) } }
            }
        }
    }

    fun scan() {
        try {
            val o = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true).setPageLimit(30)
                .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
            com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(o).getStartScanIntent(this)
                .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { toast("Scanner non disponibile: servono i Google Play Services aggiornati") }
        } catch (_: Throwable) { toast("Scanner non disponibile su questo telefono") }
    }

    // ---------- schermate ----------
    fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    fun push(v: View, onClose: (() -> Unit)? = null) {
        stack += v to onClose
        host.removeAllViews(); host.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeLast().second?.invoke()
        host.removeAllViews(); host.addView(stack.last().first, FrameLayout.LayoutParams(-1, -1))
        if (stack.size == 1) refreshHome()
    }

    private fun header(title: String, action: String?, onAction: (() -> Unit)?, back: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(12), dp(8))
        if (back) addView(TextView(this@MainActivity).apply { text = "‹"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); contentDescription = "Indietro"; setOnClickListener { pop() } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(col(R.color.text)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            if (!back) setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) addView(TextView(this@MainActivity).apply {
            text = action; textSize = 15f; setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundBg(col(R.color.accent), 18); setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { onAction?.invoke() }
        })
    }

    /** Una pagina con titolo e freccia indietro; il contenuto sta in [build]. */
    fun openScreen(title: String, action: String? = null, onAction: (() -> Unit)? = null, build: (LinearLayout) -> Unit): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(title, action, onAction))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        build(body)
        push(root)
        return root
    }

    /** Tema: 0 come il telefono, 1 chiaro, 2 scuro. */
    fun themeChoice() = getSharedPreferences("pdf", MODE_PRIVATE).getInt("theme", 0)
    private fun themeMode() = when (themeChoice()) { 1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
    fun setThemeChoice(v: Int) {
        getSharedPreferences("pdf", MODE_PRIVATE).edit().putInt("theme", v).apply()
        AppCompatDelegate.setDefaultNightMode(themeMode())
        recreate()
    }
    private fun themeDialog() {
        val names = arrayOf("Come il telefono", "Chiaro", "Scuro")
        MaterialAlertDialogBuilder(this).setTitle("🎨 Tema").setSingleChoiceItems(names, themeChoice()) { dl, w -> dl.dismiss(); setThemeChoice(w) }.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(themeMode())
        super.onCreate(savedInstanceState)
        PdfEdit.init(this)
        host = FrameLayout(this).apply { setBackgroundColor(col(R.color.bg)) }
        setContentView(host)
        push(buildHome())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (stack.size > 1) pop() else finish() }
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIntent(intent) }

    // ---------- home ----------
    private fun buildHome(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header("PDF e fogli", "🎨", { themeDialog() }, back = false))
        homeBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
        root.addView(ScrollView(this).apply { addView(homeBody) }, LinearLayout.LayoutParams(-1, 0, 1f))
        refreshHome()
        return root
    }

    fun refreshHome() {
        homeBody.removeAllViews()
        val grid = android.widget.GridLayout(this).apply { columnCount = 2 }
        fun cell(v: View) = grid.addView(v, android.widget.GridLayout.LayoutParams().apply {
            width = 0; columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4))
        })
        cell(bigButton("📂", "Apri un PDF", true) { pickPdf(false) { importUri(it.first()) { d -> openViewer(d) } } })
        cell(bigButton("📷", "Scansiona", true) { scan() })
        cell(bigButton("✍️", "Firma", false) { pickPdf(false) { importUri(it.first()) { d -> openEditor(d, "sign") } } })
        cell(bigButton("📑", "Unisci / Dividi", false) {
            MaterialAlertDialogBuilder(this).setItems(arrayOf("➕ Unisci più PDF in uno", "✂️ Dividi un PDF (tieni solo alcune pagine)")) { _, w ->
                if (w == 0) pickPdf(true) { mergePdfs(it) } else pickPdf(false) { importUri(it.first()) { d -> openPages(d, true) } }
            }.show()
        })
        homeBody.addView(grid)
        val search = EditText(this).apply {
            hint = "🔍 Cerca in tutti i miei PDF…"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
            background = roundBg(col(R.color.card), 14, col(R.color.stroke)); setPadding(dp(14), dp(12), dp(14), dp(12)); setTextColor(col(R.color.text))
            setOnEditorActionListener { v, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { searchAll(v.text.toString()); true } else false }
        }
        homeBody.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val docs = Store.list(this)
        sectionLabel(homeBody, "RECENTI")
        if (docs.isEmpty()) hintLabel(homeBody, "Qui compaiono i PDF che apri, scansioni o modifichi. Ogni modifica crea un PDF nuovo: l'originale non viene toccato.")
        docs.forEach { d ->
            listRow(homeBody, "📑", d.name, Store.sub(d), { docMenu(d) }) { openViewer(d) }
        }
    }

    private fun docMenu(d: Store.Doc) {
        MaterialAlertDialogBuilder(this).setTitle(d.name).setItems(arrayOf("Apri", "Rinomina", "Condividi", "Salva nei file", "Togli dall'elenco")) { _, w ->
            when (w) {
                0 -> openViewer(d)
                1 -> { val et = EditText(this).apply { setText(d.name); setSelectAllOnFocus(true); setPadding(dp(20), dp(14), dp(20), dp(14)) }
                    MaterialAlertDialogBuilder(this).setTitle("Nome").setView(et).setPositiveButton("Salva") { _, _ -> Store.rename(this, d, et.text.toString()); refreshHome() }.setNegativeButton("Annulla", null).show() }
                2 -> sharePdf(d)
                3 -> saveAs(d)
                4 -> MaterialAlertDialogBuilder(this).setTitle("Toglierlo dall'elenco?").setMessage("La copia dentro l'app viene eliminata. Il file originale, se c'è, non cambia.")
                    .setPositiveButton("Togli") { _, _ -> Store.remove(this, d); refreshHome() }.setNegativeButton("Annulla", null).show()
            }
        }.show()
    }

    private fun searchAll(q: String) {
        if (q.trim().length < 2) { toast("Scrivi almeno due lettere"); return }
        toast("Cerco…")
        io.execute {
            val found = ArrayList<Triple<Store.Doc, PdfEdit.Hit, Int>>()
            for (d in Store.list(this)) PdfEdit.search(this, d.file(this), q, 4).forEach { found += Triple(d, it, 0) }
            runOnUiThread {
                if (found.isEmpty()) { toast("Non trovo «$q» nei tuoi PDF (le scansioni non hanno testo da cercare)"); return@runOnUiThread }
                MaterialAlertDialogBuilder(this).setTitle("«$q» · ${found.size} risultati")
                    .setItems(found.take(40).map { (d, h, _) -> "${d.name} · pag. ${h.page + 1}\n${h.snippet}" }.toTypedArray()) { _, w -> found[w].let { (d, h, _) -> openViewer(d, h.page) } }
                    .setNegativeButton("Chiudi", null).show()
            }
        }
    }

    // ---------- importare un PDF ----------
    fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Documento"

    /** Copia il PDF nell'app; se ha una password la chiede. */
    fun importUri(uri: Uri, then: (Store.Doc) -> Unit) {
        toast("Apro il PDF…")
        io.execute {
            val tmp = File(cacheDir, "in_${System.currentTimeMillis()}.pdf")
            val name = displayName(uri)
            val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } } }.isSuccess
            val st = if (ok) PdfEdit.checkStrict(tmp) else PdfEdit.BAD
            runOnUiThread {
                when (st) {
                    PdfEdit.OK -> io.execute { val d = Store.add(this, tmp, name); runOnUiThread { refreshHome(); then(d) } }
                    PdfEdit.PASSWORD -> askPassword(tmp, name, then)
                    else -> toast("Non riesco ad aprire questo file come PDF")
                }
            }
        }
    }

    private fun askPassword(tmp: File, name: String, then: (Store.Doc) -> Unit) {
        val et = EditText(this).apply { hint = "Password del PDF"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setPadding(dp(20), dp(14), dp(20), dp(14)) }
        MaterialAlertDialogBuilder(this).setTitle("🔒 PDF con password").setMessage("Dentro quest'app lo apro senza password. Il file originale resta protetto.").setView(et)
            .setPositiveButton("Apri") { _, _ ->
                io.execute {
                    val out = File(cacheDir, "unlocked_${System.currentTimeMillis()}.pdf")
                    val ok = PdfEdit.unlock(this, tmp, out, et.text.toString()) && PdfEdit.checkStrict(out) == PdfEdit.OK
                    if (ok) { val d = Store.add(this, out, name); runOnUiThread { refreshHome(); then(d) } }
                    else runOnUiThread { toast("Password sbagliata"); askPassword(tmp, name, then) }
                }
            }.setNegativeButton("Annulla") { _, _ -> tmp.delete() }.show()
    }

    // ---------- risultato di una modifica ----------
    /** Il nuovo PDF [tmp] entra nell'elenco come «base (suffisso)»; poi si può aprire, condividere, salvare. */
    fun saveResult(tmp: File, baseName: String, suffix: String, closeTop: Boolean = true) {
        io.execute {
            val doc = Store.add(this, tmp, "$baseName ($suffix)")
            runOnUiThread {
                if (closeTop) pop()
                refreshHome()
                MaterialAlertDialogBuilder(this).setTitle("✓ PDF pronto")
                    .setMessage("«${doc.name}» · ${doc.pages} ${if (doc.pages == 1) "pagina" else "pagine"} · ${Store.size(doc.size)}\nL'originale non è stato toccato.")
                    .setPositiveButton("Apri") { _, _ -> openViewer(doc) }
                    .setNeutralButton("Salva nei file") { _, _ -> saveAs(doc) }
                    .setNegativeButton("Condividi") { _, _ -> sharePdf(doc) }.show()
            }
        }
    }

    fun sharePdf(doc: Store.Doc, onlyPackage: String? = null) {
        runCatching {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val f = File(dir, Store.fileName(doc.name))
            doc.file(this).copyTo(f, true)
            val u = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (onlyPackage != null) startActivity(i.setPackage(onlyPackage)) else startActivity(Intent.createChooser(i, "Condividi il PDF"))
        }.onFailure { toast(if (onlyPackage != null) "Web Launcher non è installato su questo telefono" else "Non riesco a condividere il PDF") }
    }

    // ---------- il PDF ----------
    fun openViewer(doc: Store.Doc, page: Int = 0) {
        val file = doc.file(this)
        val pr = runCatching { PageRenderer(file) }.getOrNull() ?: run { toast("Non riesco ad aprire questo PDF"); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        val head = header(doc.name, null, null)
        val counter = TextView(this).apply { textSize = 13f; setTextColor(col(R.color.muted)); setPadding(dp(8), 0, 0, 0) }
        head.addView(counter)
        root.addView(head)
        val rv = ZoomRecycler(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity); setBackgroundColor(0xFF8A93A6.toInt()); setPadding(dp(6), dp(6), dp(6), dp(6)); clipToPadding = false
        }
        rv.adapter = PageAdapter(pr) { rv.width - dp(12) }
        fun updateCounter() { counter.text = "${rv.currentPage() + 1} / ${pr.count}" }
        rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(r: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) = updateCounter()
        })
        fun cur() = rv.currentPage()
        root.addView(chipBar(listOf(
            "🔍 Cerca" to { searchInDoc(doc) { rv.goToPage(it) } },
            "✍️ Firma" to { openEditor(doc, "sign", cur()) },
            "🖍 Evidenzia" to { openEditor(doc, "highlight", cur()) },
            "🔤 Testo" to { openEditor(doc, "text", cur()) },
            "📌 Nota" to { openEditor(doc, "note", cur()) },
            "📝 Compila" to { openFillForm(doc) },
            "📑 Pagine" to { openPages(doc, false) },
            "✂️ Dividi" to { openPages(doc, true) },
            "🗜 Comprimi" to { compressDoc(doc) },
            "🔒 Password" to { protectDoc(doc) },
            "📋 Copia testo" to { copyText(doc) },
            "📤 Condividi" to { sharePdf(doc) },
            "✨ Con l'AI" to { aiHelp(doc) },
            "💾 Salva nei file" to { saveAs(doc) }
        )))
        root.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
        push(root) { pr.close() }
        rv.post { updateCounter(); if (page > 0) { rv.goToPage(page); updateCounter() } }
    }

    private fun searchInDoc(doc: Store.Doc, go: (Int) -> Unit) {
        val et = EditText(this).apply { hint = "Cosa cerchi?"; setSingleLine(); setPadding(dp(20), dp(14), dp(20), dp(14)) }
        MaterialAlertDialogBuilder(this).setTitle("🔍 Cerca nel PDF").setView(et).setPositiveButton("Cerca") { _, _ ->
            val q = et.text.toString()
            io.execute {
                val hits = PdfEdit.search(this, doc.file(this), q)
                runOnUiThread {
                    if (hits.isEmpty()) toast("Non trovo «${q.trim()}» (le scansioni non hanno testo da cercare)")
                    else MaterialAlertDialogBuilder(this).setTitle("«${q.trim()}» · ${hits.size} risultati")
                        .setItems(hits.map { "pag. ${it.page + 1} · ${it.snippet}" }.toTypedArray()) { _, w -> go(hits[w].page) }.setNegativeButton("Chiudi", null).show()
                }
            }
        }.setNegativeButton("Annulla", null).show()
    }

    private fun compressDoc(doc: Store.Doc) {
        MaterialAlertDialogBuilder(this).setTitle("🗜 Comprimi").setItems(arrayOf("Un po' (la qualità resta buona)", "Molto (piccolo, per WhatsApp o email)")) { _, w ->
            val (px, q) = if (w == 0) 1800 to 0.75f else 1200 to 0.5f
            toast("Comprimo…")
            io.execute {
                val out = File(cacheDir, "cmp_${System.currentTimeMillis()}.pdf")
                val r = runCatching { PdfEdit.compress(this, doc.file(this), out, px, q) }
                val before = doc.file(this).length(); val after = out.length()
                runOnUiThread {
                    when {
                        r.isFailure -> { out.delete(); toast("Non riuscito: ${r.exceptionOrNull()?.message}".take(120)) }
                        after >= before * 0.95 -> { out.delete(); toast("Questo PDF è già leggero: non cambia quasi niente") }
                        else -> { toast("Da ${Store.size(before)} a ${Store.size(after)}"); saveResult(out, doc.name, "compresso", closeTop = false) }
                    }
                }
            }
        }.setNegativeButton("Annulla", null).show()
    }

    private fun protectDoc(doc: Store.Doc) {
        val et = EditText(this).apply { hint = "Scegli una password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setPadding(dp(20), dp(14), dp(20), dp(14)) }
        MaterialAlertDialogBuilder(this).setTitle("🔒 Proteggi con password").setMessage("Crea una copia che si apre solo con la password. Se la dimentichi non c'è modo di recuperarla.").setView(et)
            .setPositiveButton("Proteggi") { _, _ ->
                val pw = et.text.toString()
                if (pw.length < 4) { toast("Almeno 4 caratteri"); return@setPositiveButton }
                val out = File(cacheDir, "pw_${System.currentTimeMillis()}.pdf")
                io.execute {
                    val r = runCatching { PdfEdit.protect(this, doc.file(this), out, pw) }
                    runOnUiThread { r.onSuccess { saveResult(out, doc.name, "con password", closeTop = false) }.onFailure { toast("Non riuscito: ${it.message}".take(120)) } }
                }
            }.setNegativeButton("Annulla", null).show()
    }

    private fun copyText(doc: Store.Doc) {
        toast("Leggo il testo…")
        io.execute {
            val t = PdfEdit.allText(this, doc.file(this))
            runOnUiThread {
                if (t == null) { MaterialAlertDialogBuilder(this).setTitle("Nessun testo").setMessage("Questo PDF sembra una scansione: contiene immagini, non testo da copiare.").setPositiveButton("Ok", null).show(); return@runOnUiThread }
                val tv = TextView(this).apply { text = t.take(20_000); setTextIsSelectable(true); textSize = 14f; setTextColor(col(R.color.text)); setPadding(dp(20), dp(8), dp(20), dp(8)) }
                MaterialAlertDialogBuilder(this).setTitle("📋 Testo del PDF").setView(ScrollView(this).apply { addView(tv) })
                    .setPositiveButton("Copia tutto") { _, _ ->
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("PDF", t)); toast("Copiato")
                    }.setNegativeButton("Chiudi", null).show()
            }
        }
    }

    private fun aiHelp(doc: Store.Doc) {
        MaterialAlertDialogBuilder(this).setTitle("✨ Con l'AI").setMessage("Quest'app non usa l'AI e non manda i tuoi file da nessuna parte. Se vuoi, puoi mandare questo PDF a Web Launcher, che ha «Spiegami un foglio» e le domande sul documento.")
            .setPositiveButton("Manda a Web Launcher") { _, _ -> sharePdf(doc, "com.weblauncher.app") }.setNegativeButton("No", null).show()
    }

    // ---------- da altre app e prove automatiche ----------
    private fun handleIntent(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) { i.action = null; importUri(uri) { openViewer(it) }; return }
        val action = i.getStringExtra("action") ?: return
        i.removeExtra("action")
        if (action == "selftest") { io.execute { android.util.Log.i("PDF_SELFTEST", PdfEdit.selfTest(this, cacheDir)) }; return }
        io.execute {
            val f = Selftest.samplePdf(this)
            val d = Store.add(this, f, "Contratto di prova")
            runOnUiThread {
                refreshHome()
                when (action) {
                    "viewer" -> openViewer(d)
                    "editor" -> openEditor(d, "highlight")
                    "pages" -> openPages(d, false)
                    "split" -> openPages(d, true)
                    "search" -> { openViewer(d); io.execute { android.util.Log.i("PDF_SELFTEST", "SEARCH=" + PdfEdit.search(this, d.file(this), "canone").size) } }
                    else -> {}
                }
            }
        }
    }
}
