package com.micmax.pdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    val io = Executors.newSingleThreadExecutor()
    private lateinit var host: FrameLayout
    private lateinit var lockView: View

    /** A screen on the stack: its view, what to do when it closes, and whether it holds work not saved yet. */
    private class Screen(val view: View, val onClose: (() -> Unit)?, val dirty: (() -> Boolean)?)
    private val stack = ArrayList<Screen>()
    private lateinit var homeList: LinearLayout
    private var homeQuery = ""

    private fun prefs() = getSharedPreferences("pdf", MODE_PRIVATE)

    // ---------- selettori e fotocamera ----------
    private var pdfCb: ((List<Uri>) -> Unit)? = null
    private val pdfOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) pdfCb?.invoke(listOf(u)) }
    private val pdfMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l -> if (l.isNotEmpty()) pdfCb?.invoke(l) }
    fun pickPdf(multi: Boolean, cb: (List<Uri>) -> Unit) {
        pdfCb = cb
        runCatching { if (multi) pdfMany.launch(arrayOf("application/pdf")) else pdfOne.launch(arrayOf("application/pdf")) }.onFailure { toast("Non riesco ad aprire i file") }
    }

    private var imagesCb: ((List<Uri>) -> Unit)? = null
    private val imagesMany = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { l -> if (l.isNotEmpty()) imagesCb?.invoke(l) }
    fun pickImages(cb: (List<Uri>) -> Unit) { imagesCb = cb; runCatching { imagesMany.launch("image/*") }.onFailure { toast("Non riesco ad aprire la galleria") } }

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
            val tmp = copyIn(uri, "scan", ".pdf")
            runOnUiThread {
                if (tmp == null) { toast("Scansione non salvata"); return@runOnUiThread }
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

    fun push(v: View, onClose: (() -> Unit)? = null, dirty: (() -> Boolean)? = null) {
        stack += Screen(v, onClose, dirty)
        host.removeAllViews(); host.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    /** Back to the screen before. If this one has work not saved, asks first (unless [force]). */
    fun pop(force: Boolean = false) {
        if (stack.size <= 1) return
        if (!force && stack.last().dirty?.invoke() == true) {
            MaterialAlertDialogBuilder(this).setTitle("Uscire senza salvare?").setMessage("Quello che hai fatto qui andrà perso. Per tenerlo, tocca «Salva».")
                .setPositiveButton("Esci senza salvare") { _, _ -> pop(force = true) }.setNegativeButton("Resta", null).show()
            return
        }
        stack.removeAt(stack.lastIndex).onClose?.invoke()
        host.removeAllViews(); host.addView(stack.last().view, FrameLayout.LayoutParams(-1, -1))
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

    /**
     * Una pagina con titolo e freccia indietro; il contenuto sta in [build]. [dirty] says when there is work not saved
     * (going back then asks first); with [scroll] = false the body fills the screen (for lists that scroll on their own).
     */
    fun openScreen(title: String, action: String? = null, onAction: (() -> Unit)? = null, dirty: (() -> Boolean)? = null, scroll: Boolean = true, build: (LinearLayout) -> Unit): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(title, action, onAction))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), if (scroll) dp(24) else 0) }
        if (scroll) root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        else root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        build(body)
        push(root, null, dirty)
        return root
    }

    // ---------- impostazioni: tema, i miei dati, blocco ----------

    /** Tema: 0 come il telefono, 1 chiaro, 2 scuro. */
    fun themeChoice() = prefs().getInt("theme", 0)
    private fun themeMode() = when (themeChoice()) { 1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
    fun setThemeChoice(v: Int) {
        prefs().edit().putInt("theme", v).apply()
        AppCompatDelegate.setDefaultNightMode(themeMode())
        recreate()
    }
    private fun themeDialog() {
        val names = arrayOf("Come il telefono", "Chiaro", "Scuro")
        MaterialAlertDialogBuilder(this).setTitle("🎨 Tema").setSingleChoiceItems(names, themeChoice()) { dl, w -> dl.dismiss(); setThemeChoice(w) }.show()
    }

    private fun settings() {
        val lock = lockOn()
        MaterialAlertDialogBuilder(this).setTitle("⚙ Impostazioni").setItems(arrayOf(
            "🎨 Tema (chiaro, scuro, come il telefono)",
            "👤 I miei dati (per i moduli)",
            if (lock) "🔒 Blocco con impronta o PIN: attivo (tocca per toglierlo)" else "🔓 Blocco con impronta o PIN: spento (tocca per attivarlo)"
        )) { _, w ->
            when (w) {
                0 -> themeDialog()
                1 -> openMyData()
                2 -> if (lock) authenticate("Togli il blocco") { setLock(false); toast("Blocco tolto") } else enableLock()
            }
        }.show()
    }

    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private fun lockOn() = prefs().getBoolean("lock", false)
    private fun setLock(on: Boolean) {
        prefs().edit().putBoolean("lock", on).apply()
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun enableLock() {
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            MaterialAlertDialogBuilder(this).setTitle("🔒 Blocco").setMessage("Prima imposta un blocco schermo sul telefono (PIN, segno o impronta): l'app usa quello.").setPositiveButton("Ok", null).show()
            return
        }
        authenticate("Attiva il blocco") {
            setLock(true); unlocked = true
            toast("✓ Da ora l'app chiede l'impronta o il PIN quando la riapri")
        }
    }

    private fun authenticate(title: String, ok: () -> Unit) {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { ok() }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_CANCELED) toast(msg.toString())
            }
        })
        runCatching { prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("PDF e fogli").setAllowedAuthenticators(authenticators).build()) }
            .onFailure { toast("Non riesco a chiedere l'impronta o il PIN") }
    }

    private fun showLockIfNeeded() {
        val need = lockOn() && (!unlocked || System.currentTimeMillis() - leftAt > 60_000)
        if (!need) { lockView.visibility = View.GONE; return }
        unlocked = false
        lockView.visibility = View.VISIBLE
        lockView.post { authenticate("Sblocca") { unlocked = true; lockView.visibility = View.GONE } }
    }

    private fun buildLockView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(col(R.color.bg)); isClickable = true; visibility = View.GONE
        addView(TextView(this@MainActivity).apply { text = "🔒"; textSize = 56f; gravity = Gravity.CENTER })
        addView(TextView(this@MainActivity).apply { text = "PDF e fogli è bloccato"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(col(R.color.text)); setPadding(0, dp(12), 0, dp(20)) })
        addView(primaryButton("Sblocca") { authenticate("Sblocca") { unlocked = true; visibility = View.GONE } }.apply { layoutParams = LinearLayout.LayoutParams(dp(220), -2) })
    }

    override fun onStart() { super.onStart(); showLockIfNeeded() }
    override fun onStop() { super.onStop(); if (unlocked) leftAt = System.currentTimeMillis() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(themeMode())
        super.onCreate(savedInstanceState)
        PdfEdit.init(this)
        if (lockOn()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        host = FrameLayout(this).apply { setBackgroundColor(col(R.color.bg)) }
        lockView = buildLockView()
        setContentView(FrameLayout(this).apply { addView(host, FrameLayout.LayoutParams(-1, -1)); addView(lockView, FrameLayout.LayoutParams(-1, -1)) })
        if (savedInstanceState == null) io.execute { cleanCache() }
        push(buildHome())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (stack.size > 1) pop() else finish() }
        })
        handleIntent(intent)
    }

    override fun onDestroy() { super.onDestroy(); stack.forEach { runCatching { it.onClose?.invoke() } } }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIntent(intent) }

    /** Files left behind by earlier work (copies, shares, photos): gone after half an hour. */
    private fun cleanCache() {
        val old = System.currentTimeMillis() - 30 * 60_000
        cacheDir.listFiles()?.filter { it.isFile && it.lastModified() < old }?.forEach { it.delete() }
        File(cacheDir, "share").walkBottomUp().filter { it.lastModified() < old && it != File(cacheDir, "share") }.forEach { it.delete() }
    }

    // ---------- home ----------
    private fun buildHome(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header("PDF e fogli", "⚙", { settings() }, back = false))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val grid = android.widget.GridLayout(this).apply { columnCount = 2 }
        fun cell(v: View) = grid.addView(v, android.widget.GridLayout.LayoutParams().apply {
            width = 0; columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4))
        })
        cell(bigButton("📂", "Apri un PDF", true) { pickPdf(false) { importUri(it.first()) { d -> openViewer(d) } } })
        cell(bigButton("📷", "Scansiona", true) { scan() })
        cell(bigButton("✍️", "Firma", false) { pickPdf(false) { importUri(it.first()) { d -> openEditor(d, "sign") } } })
        cell(bigButton("📝", "Compila", false) { pickPdf(false) { importUri(it.first()) { d -> openFillForm(d) } } })
        cell(bigButton("🖼", "Foto in PDF", false) { pickImages { photosToPdf(it) } })
        cell(bigButton("📑", "Unisci / Dividi", false) {
            MaterialAlertDialogBuilder(this).setItems(arrayOf("➕ Unisci più PDF in uno", "✂️ Dividi un PDF (tieni solo alcune pagine)")) { _, w ->
                if (w == 0) pickPdf(true) { mergePdfs(it) } else pickPdf(false) { importUri(it.first()) { d -> openPages(d, true) } }
            }.show()
        })
        body.addView(grid)
        val search = EditText(this).apply {
            hint = "🔍 Cerca per nome · invio: cerca nel testo"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
            background = roundBg(col(R.color.card), 14, col(R.color.stroke)); setPadding(dp(14), dp(12), dp(14), dp(12)); setTextColor(col(R.color.text))
            setOnEditorActionListener { v, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { searchAll(v.text.toString()); true } else false }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { homeQuery = s.toString(); refreshHome() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        homeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(homeList)
        refreshHome()
        return root
    }

    private val sortNames = listOf("Recenti", "Nome", "Più grandi")
    private fun sort() = prefs().getInt("sort", Store.SORT_RECENT)

    fun refreshHome() {
        if (!::homeList.isInitialized) return
        homeList.removeAllViews()
        val all = Store.list(this)
        val shown = Store.arrange(all, homeQuery, sort())
        val favs = if (homeQuery.isBlank()) shown.filter { it.fav } else emptyList()
        if (favs.isNotEmpty()) {
            sectionLabel(homeList, "⭐ PREFERITI")
            favs.forEach { d -> listRow(homeList, "⭐", d.name, Store.sub(d), { docMenu(d) }) { openViewer(d) } }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        homeList.addView(head)
        sectionLabel(head, if (homeQuery.isBlank()) "I MIEI PDF" else "TROVATI: ${shown.size}")
        (head.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        if (all.size > 1) head.addView(TextView(this).apply {
            text = "Ordina: ${sortNames[sort()]} ▾"; textSize = 12.5f; setTextColor(col(R.color.accent)); setPadding(dp(8), dp(14), dp(6), dp(4))
            setOnClickListener {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("Ordina").setSingleChoiceItems(sortNames.toTypedArray(), sort()) { dl, w ->
                    prefs().edit().putInt("sort", w).apply(); dl.dismiss(); refreshHome()
                }.show()
            }
        })
        when {
            all.isEmpty() -> hintLabel(homeList, "Qui compaiono i PDF che apri, scansioni o modifichi. Ogni modifica crea un PDF nuovo: l'originale non viene toccato.")
            shown.isEmpty() -> hintLabel(homeList, "Nessun PDF con «${homeQuery.trim()}» nel nome. Premi invio sulla tastiera per cercarlo dentro i PDF.")
        }
        shown.filter { homeQuery.isNotBlank() || !it.fav }.forEach { d ->
            listRow(homeList, if (d.fav) "⭐" else "📑", d.name, Store.sub(d), { docMenu(d) }) { openViewer(d) }
        }
        if (homeQuery.isBlank() && all.size > 1) hintLabel(homeList, "Tieni premuto un PDF per rinominarlo, metterlo tra i preferiti, stamparlo o toglierlo.")
    }

    private fun docMenu(d: Store.Doc) {
        MaterialAlertDialogBuilder(this).setTitle(d.name).setItems(arrayOf("Apri", if (d.fav) "☆ Togli dai preferiti" else "⭐ Metti tra i preferiti", "Rinomina", "Condividi", "🖨 Stampa", "Salva nei file", "Togli dall'elenco")) { _, w ->
            when (w) {
                0 -> openViewer(d)
                1 -> { Store.setFav(this, d, !d.fav); refreshHome() }
                2 -> { val et = EditText(this).apply { setText(d.name); setSelectAllOnFocus(true); setPadding(dp(20), dp(14), dp(20), dp(14)) }
                    MaterialAlertDialogBuilder(this).setTitle("Nome").setView(et).setPositiveButton("Salva") { _, _ -> Store.rename(this, d, et.text.toString()); refreshHome() }.setNegativeButton("Annulla", null).show() }
                3 -> sharePdf(d)
                4 -> printPdf(d)
                5 -> saveAs(d)
                6 -> MaterialAlertDialogBuilder(this).setTitle("Toglierlo dall'elenco?").setMessage("La copia dentro l'app viene eliminata. Il file originale, se c'è, non cambia.")
                    .setPositiveButton("Togli") { _, _ -> Store.remove(this, d); refreshHome() }.setNegativeButton("Annulla", null).show()
            }
        }.show()
    }

    private fun searchAll(q: String) {
        if (q.trim().length < 2) { toast("Scrivi almeno due lettere"); return }
        toast("Cerco dentro i PDF…")
        io.execute {
            val found = ArrayList<Pair<Store.Doc, PdfEdit.Hit>>()
            for (d in Store.list(this)) PdfEdit.search(this, d.file(this), q, 4).forEach { found += d to it }
            runOnUiThread {
                if (found.isEmpty()) { toast("Non trovo «${q.trim()}» nei tuoi PDF (le scansioni non hanno testo da cercare)"); return@runOnUiThread }
                MaterialAlertDialogBuilder(this).setTitle("«${q.trim()}» · ${found.size} risultati")
                    .setItems(found.take(40).map { (d, h) -> "${d.name} · pag. ${h.page + 1}\n${h.snippet}" }.toTypedArray()) { _, w -> found[w].let { (d, h) -> openViewer(d, h.page) } }
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
            val name = displayName(uri)
            val tmp = copyIn(uri, "in", ".pdf")
            val st = if (tmp != null) PdfEdit.checkStrict(tmp) else PdfEdit.BAD
            runOnUiThread {
                when (st) {
                    PdfEdit.OK -> io.execute { val d = Store.add(this, tmp!!, name); runOnUiThread { refreshHome(); then(d) } }
                    PdfEdit.PASSWORD -> askPassword(tmp!!, name, then)
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
                    if (ok) { tmp.delete(); val d = Store.add(this, out, name); runOnUiThread { refreshHome(); then(d) } }
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
                if (closeTop) pop(force = true)
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

    /** Stampa con la stampa di Android (stampanti di casa, Wi-Fi, «Salva come PDF»). */
    fun printPdf(doc: Store.Doc) {
        val file = doc.file(this)
        val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
        runCatching {
            pm.print(doc.name, object : android.print.PrintDocumentAdapter() {
                override fun onLayout(old: android.print.PrintAttributes?, new: android.print.PrintAttributes?, cancel: android.os.CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                    if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                    cb.onLayoutFinished(android.print.PrintDocumentInfo.Builder(Store.fileName(doc.name)).setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(if (doc.pages > 0) doc.pages else android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true)
                }
                override fun onWrite(pages: Array<out android.print.PageRange>?, dest: android.os.ParcelFileDescriptor, cancel: android.os.CancellationSignal?, cb: WriteResultCallback) {
                    runCatching { file.inputStream().use { i -> java.io.FileOutputStream(dest.fileDescriptor).use { i.copyTo(it) } } }
                        .onSuccess { cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES)) }
                        .onFailure { cb.onWriteFailed(it.message) }
                }
            }, null)
        }.onFailure { toast("La stampa non è disponibile su questo telefono") }
    }

    /** Le pagine come foto JPG, da mandare su WhatsApp o salvare. */
    private fun shareAsImages(doc: Store.Doc, current: Int) {
        val choices = if (doc.pages > 1) arrayOf("Solo questa pagina (${current + 1})", "Tutte le pagine (${doc.pages})") else arrayOf("La pagina")
        MaterialAlertDialogBuilder(this).setTitle("🖼 PDF in foto").setItems(choices) { _, w ->
            val pages = if (w == 0) listOf(current) else (0 until doc.pages).toList()
            toast("Preparo ${pages.size} ${if (pages.size == 1) "foto" else "foto"}…")
            io.execute {
                val dir = File(File(cacheDir, "share"), "foto_${System.currentTimeMillis()}")
                val files = PdfEdit.toImages(doc.file(this), pages, dir, Store.cleanName(doc.name))
                runOnUiThread {
                    if (files.isEmpty()) { toast("Non riesco a fare le foto"); return@runOnUiThread }
                    runCatching {
                        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
                        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
                            else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
                        i.type = "image/jpeg"; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        i.clipData = android.content.ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
                        startActivity(Intent.createChooser(i, "Manda le foto"))
                    }.onFailure { toast("Non riesco a condividere le foto") }
                }
            }
        }.setNegativeButton("Annulla", null).show()
    }

    // ---------- il PDF ----------
    /** Apre il PDF; senza [page] riprende dall'ultima pagina letta. */
    fun openViewer(doc: Store.Doc, page: Int = -1) {
        val file = doc.file(this)
        val pr = runCatching { PageRenderer(file) }.getOrNull() ?: run { toast("Non riesco ad aprire questo PDF"); return }
        val startAt = if (page >= 0) page else Store.lastPage(this, doc).coerceIn(0, (pr.count - 1).coerceAtLeast(0))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        val head = header(doc.name, null, null)
        val counter = TextView(this).apply { textSize = 13f; setTextColor(col(R.color.accent)); setPadding(dp(8), dp(6), 0, dp(6)); contentDescription = "Vai alla pagina" }
        head.addView(counter)
        root.addView(head)
        val rv = ZoomRecycler(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity); setPadding(dp(6), dp(6), dp(6), dp(6)); clipToPadding = false; itemAnimator = null
        }
        val adapter = PageAdapter(pr) { rv.width - dp(12) }
        fun applyNight() { rv.setBackgroundColor(if (adapter.night) 0xFF22252B.toInt() else 0xFF8A93A6.toInt()) }
        adapter.night = prefs().getBoolean("night", false); applyNight()
        rv.adapter = adapter
        rv.onLongPage = { p, fx, fy -> rv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); openSelect(doc, p, fx, fy) }
        rv.onZoomed = { z -> val s = z > 1.4f; if (s && !adapter.sharp) { adapter.sharp = true; rv.refreshVisible() } else if (!s) adapter.sharp = false }
        fun cur() = rv.currentPage()
        fun updateCounter() { counter.text = "${cur() + 1} / ${pr.count}" + if (cur() in Store.marks(this, doc)) " 🔖" else "" }
        rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(r: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) = updateCounter()
            override fun onScrollStateChanged(r: androidx.recyclerview.widget.RecyclerView, st: Int) { if (st == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) Store.setLastPage(this@MainActivity, doc, cur()) }
        })
        fun go(p: Int) { rv.goToPage(p); rv.post { updateCounter() } }
        counter.setOnClickListener {
            val et = EditText(this).apply { hint = "1 – ${pr.count}"; inputType = InputType.TYPE_CLASS_NUMBER; setPadding(dp(20), dp(14), dp(20), dp(14)) }
            MaterialAlertDialogBuilder(this).setTitle("Vai alla pagina").setView(et).setPositiveButton("Vai") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n == null || n < 1 || n > pr.count) toast("Scrivi un numero da 1 a ${pr.count}") else go(n - 1)
            }.setNegativeButton("Annulla", null).show()
        }
        val reader = ReadAloud(this, file, pr.count) { p -> go(p) }
        root.addView(chipBar(listOf(
            "🔍 Cerca" to { searchInDoc(doc) { go(it) } },
            "✂ Seleziona testo" to { openSelect(doc, cur()) },
            "📖 Indice" to { showIndex(doc) { go(it) } },
            "🔖 Segnalibro" to { val on = Store.toggleMark(this, doc, cur()); updateCounter(); toast(if (on) "🔖 Segnalibro sulla pagina ${cur() + 1}" else "Segnalibro tolto") },
            "🔊 Leggi" to { if (reader.active) { reader.stop(); toast("Lettura fermata") } else { toast("Leggo da pagina ${cur() + 1} · tocca di nuovo «🔊 Leggi» per fermare"); reader.start(cur()) } },
            "🌙 Notte" to { adapter.night = !adapter.night; prefs().edit().putBoolean("night", adapter.night).apply(); applyNight(); adapter.notifyDataSetChanged() },
            "✍️ Firma" to { openEditor(doc, "sign", cur()) },
            "🖍 Evidenzia" to { openEditor(doc, "highlight", cur()) },
            "🔤 Testo" to { openEditor(doc, "text", cur()) },
            "📌 Nota" to { openEditor(doc, "note", cur()) },
            "⬛ Oscura" to { openEditor(doc, "redact", cur()) },
            "📝 Compila" to { openFillForm(doc) },
            "📑 Pagine" to { openPages(doc, false) },
            "✂️ Dividi" to { openPages(doc, true) },
            "🗜 Comprimi" to { compressDoc(doc) },
            "🔒 Password" to { protectDoc(doc) },
            "🖨 Stampa" to { printPdf(doc) },
            "🖼 In foto" to { shareAsImages(doc, cur()) },
            "📋 Copia testo" to { copyText(doc) },
            "📤 Condividi" to { sharePdf(doc) },
            "✨ Con l'AI" to { aiHelp(doc) },
            "💾 Salva nei file" to { saveAs(doc) }
        )))
        root.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
        push(root, { Store.setLastPage(this, doc, cur()); reader.shutdown(); pr.close() })
        rv.post {
            updateCounter()
            if (startAt > 0) { go(startAt); if (page < 0) toast("Riprendo da pagina ${startAt + 1}") }
        }
    }

    /** Segnalibri e capitoli del PDF. */
    private fun showIndex(doc: Store.Doc, go: (Int) -> Unit) {
        io.execute {
            val chapters = PdfEdit.outline(this, doc.file(this))
            runOnUiThread {
                val entries = Store.marks(this, doc).map { "🔖 Pagina ${it + 1}" to it } + chapters.map { (t, p) -> "$t · pag. ${p + 1}" to p }
                if (entries.isEmpty()) {
                    MaterialAlertDialogBuilder(this).setTitle("📖 Indice").setMessage("Questo PDF non ha un indice dei capitoli. Puoi mettere i tuoi segnalibri con «🔖 Segnalibro» sulla pagina che vuoi ritrovare.").setPositiveButton("Ok", null).show()
                    return@runOnUiThread
                }
                MaterialAlertDialogBuilder(this).setTitle("📖 Indice e segnalibri").setItems(entries.map { it.first }.toTypedArray()) { _, w -> go(entries[w].second) }.setNegativeButton("Chiudi", null).show()
            }
        }
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
                    "mydata" -> openMyData()
                    "select" -> { openSelect(d, 0, 0.1f, 0.12f); io.execute { android.util.Log.i("PDF_SELFTEST", "WORDS=" + PdfEdit.words(this, d.file(this), 0).size) } }
                    "search" -> { openViewer(d); io.execute { android.util.Log.i("PDF_SELFTEST", "SEARCH=" + PdfEdit.search(this, d.file(this), "canone").size) } }
                    else -> {}
                }
            }
        }
    }

    companion object {
        /** Unlocked in this run of the app (kept when the screen is rebuilt, e.g. after changing theme). */
        @JvmStatic var unlocked = false
        @JvmStatic var leftAt = 0L
    }
}
