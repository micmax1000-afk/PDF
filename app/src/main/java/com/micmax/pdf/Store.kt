package com.micmax.pdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The PDFs the app knows (copies in the app's own folder) and the list of recent ones. */
object Store {
    data class Doc(val id: String, val name: String, val time: Long, val pages: Int, val size: Long) {
        fun file(c: Context) = File(dir(c), "$id.pdf")
    }

    private const val MAX = 40

    fun dir(c: Context) = File(c.filesDir, "docs").apply { mkdirs() }
    private fun prefs(c: Context) = c.getSharedPreferences("pdf", Context.MODE_PRIVATE)

    fun list(c: Context): List<Doc> = runCatching {
        val a = JSONArray(prefs(c).getString("recents", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Doc(o.getString("id"), o.getString("name"), o.getLong("time"), o.optInt("pages"), o.optLong("size"))
        }.filter { it.file(c).exists() }
    }.getOrDefault(emptyList())

    private fun save(c: Context, docs: List<Doc>) {
        val a = JSONArray()
        docs.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("time", it.time).put("pages", it.pages).put("size", it.size)) }
        prefs(c).edit().putString("recents", a.toString()).apply()
    }

    /** Moves [src] into the app folder and puts it first in the recents. */
    fun add(c: Context, src: File, name: String): Doc {
        val id = UUID.randomUUID().toString().take(12)
        val doc = Doc(id, cleanName(name), System.currentTimeMillis(), 0, 0)
        val dest = doc.file(c)
        if (!src.renameTo(dest)) { src.copyTo(dest, true); src.delete() }
        val full = doc.copy(pages = PdfEdit.pageCount(dest), size = dest.length())
        val all = listOf(full) + list(c)
        all.drop(MAX).forEach { it.file(c).delete() }
        save(c, all.take(MAX))
        return full
    }

    fun remove(c: Context, doc: Doc) {
        doc.file(c).delete()
        save(c, list(c).filter { it.id != doc.id })
    }

    fun rename(c: Context, doc: Doc, name: String) = save(c, list(c).map { if (it.id == doc.id) it.copy(name = cleanName(name)) else it })

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
