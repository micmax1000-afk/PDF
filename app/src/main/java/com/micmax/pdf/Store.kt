package com.micmax.pdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The PDFs the app knows (copies in the app's own folder), the list of recent ones and small per-PDF notes. */
object Store {
    data class Doc(val id: String, val name: String, val time: Long, val pages: Int, val size: Long, val fav: Boolean = false) {
        fun file(c: Context) = File(dir(c), "$id.pdf")
    }

    fun dir(c: Context) = File(c.filesDir, "docs").apply { mkdirs() }
    private fun prefs(c: Context) = c.getSharedPreferences("pdf", Context.MODE_PRIVATE)

    fun list(c: Context): List<Doc> = runCatching {
        val a = JSONArray(prefs(c).getString("recents", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Doc(o.getString("id"), o.getString("name"), o.getLong("time"), o.optInt("pages"), o.optLong("size"), o.optBoolean("fav"))
        }.filter { it.file(c).exists() }
    }.getOrDefault(emptyList())

    private fun save(c: Context, docs: List<Doc>) {
        val a = JSONArray()
        docs.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("time", it.time).put("pages", it.pages).put("size", it.size).put("fav", it.fav)) }
        prefs(c).edit().putString("recents", a.toString()).apply()
    }

    /** Moves [src] into the app folder and puts it first in the recents. Nothing is ever deleted on its own. */
    fun add(c: Context, src: File, name: String): Doc {
        val id = UUID.randomUUID().toString().take(12)
        val doc = Doc(id, cleanName(name), System.currentTimeMillis(), 0, 0)
        val dest = doc.file(c)
        if (!src.renameTo(dest)) { src.copyTo(dest, true); src.delete() }
        val full = doc.copy(pages = PdfEdit.pageCount(dest), size = dest.length())
        save(c, listOf(full) + list(c))
        return full
    }

    fun remove(c: Context, doc: Doc) {
        doc.file(c).delete()
        prefs(c).edit().remove("page_${doc.id}").remove("marks_${doc.id}").apply()
        save(c, list(c).filter { it.id != doc.id })
    }

    fun rename(c: Context, doc: Doc, name: String) = save(c, list(c).map { if (it.id == doc.id) it.copy(name = cleanName(name)) else it })

    fun setFav(c: Context, doc: Doc, fav: Boolean) = save(c, list(c).map { if (it.id == doc.id) it.copy(fav = fav) else it })

    // ---------- ordine e filtro dell'elenco (puri, provati) ----------

    const val SORT_RECENT = 0
    const val SORT_NAME = 1
    const val SORT_SIZE = 2

    /** Only the PDFs whose name contains [query] (accents and capitals don't matter), in the order [sort]. */
    fun arrange(docs: List<Doc>, query: String, sort: Int): List<Doc> {
        val q = Text.fold(query.trim())
        val f = if (q.isEmpty()) docs else docs.filter { Text.fold(it.name).contains(q) }
        return when (sort) {
            SORT_NAME -> f.sortedBy { Text.fold(it.name) }
            SORT_SIZE -> f.sortedByDescending { it.size }
            else -> f.sortedByDescending { it.time }
        }
    }

    // ---------- ultima pagina letta e segnalibri ----------

    fun lastPage(c: Context, doc: Doc) = prefs(c).getInt("page_${doc.id}", 0)
    fun setLastPage(c: Context, doc: Doc, page: Int) = prefs(c).edit().putInt("page_${doc.id}", page).apply()

    fun marks(c: Context, doc: Doc): List<Int> =
        prefs(c).getString("marks_${doc.id}", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.distinct().sorted()

    /** Adds or removes the bookmark on [page]; true when the page now has one. */
    fun toggleMark(c: Context, doc: Doc, page: Int): Boolean {
        val m = marks(c, doc).toMutableList()
        val on = if (page in m) { m.remove(page); false } else { m += page; true }
        prefs(c).edit().putString("marks_${doc.id}", m.sorted().joinToString(",")).apply()
        return on
    }

    // ---------- i miei dati (per i moduli) ----------

    /** key to label, in the order they are shown. */
    val MY_FIELDS = listOf(
        "nome" to "Nome", "cognome" to "Cognome", "cf" to "Codice fiscale", "nascita" to "Data di nascita",
        "luogo" to "Luogo di nascita", "indirizzo" to "Indirizzo (via e numero)", "cap" to "CAP", "citta" to "Città",
        "provincia" to "Provincia", "telefono" to "Telefono", "email" to "Email", "iban" to "IBAN"
    )

    fun myData(c: Context): Map<String, String> {
        val o = runCatching { JSONObject(prefs(c).getString("mydata", "{}")!!) }.getOrDefault(JSONObject())
        return MY_FIELDS.associate { (k, _) -> k to o.optString(k).trim() }.filterValues { it.isNotEmpty() }
    }

    fun setMyData(c: Context, data: Map<String, String>) {
        val o = JSONObject(); data.forEach { (k, v) -> if (v.isNotBlank()) o.put(k, v.trim()) }
        prefs(c).edit().putString("mydata", o.toString()).apply()
    }

    // ---------- nomi, dimensioni, date ----------

    /** «Contratto.PDF» → «Contratto» */
    fun cleanName(n: String): String = n.trim().replace(Regex("(?i)\\.pdf$"), "").replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Documento" }.take(80)

    fun fileName(name: String) = cleanName(name) + ".pdf"

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(java.util.Locale.ITALIAN, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
        val m = (now - time) / 60_000
        return when {
            m < 1 -> "adesso"
            m < 60 -> "$m min fa"
            m < 60 * 24 -> "${m / 60} ore fa"
            m < 60 * 24 * 2 -> "ieri"
            m < 60 * 24 * 14 -> "${m / (60 * 24)} giorni fa"
            else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ITALIAN).format(java.util.Date(time))
        }
    }

    fun sub(d: Doc) = listOf(ago(d.time), "${d.pages} ${if (d.pages == 1) "pagina" else "pagine"}", size(d.size)).joinToString(" · ")
}
