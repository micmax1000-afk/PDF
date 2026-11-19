package com.micmax.pdf

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/** Reads the PDF aloud with the phone's own voice (it works without internet), page after page. */
class ReadAloud(private val act: MainActivity, private val file: File, private val count: Int, private val onPage: (Int) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var page = 0
    private var spoke = false
    private var blanks = 0
    var active = false
        private set

    fun start(from: Int) {
        active = true; page = from.coerceIn(0, count - 1); spoke = false; blanks = 0
        if (ready) { speakPage(); return }
        if (tts == null) tts = TextToSpeech(act.applicationContext) { st ->
            act.runOnUiThread {
                if (st != TextToSpeech.SUCCESS) { act.toast("La voce del telefono non è disponibile"); active = false; return@runOnUiThread }
                val t = tts ?: return@runOnUiThread
                val r = t.setLanguage(Locale.ITALIAN)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) act.toast("Manca la voce italiana: si installa da Impostazioni › Sintesi vocale")
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "page$page") act.runOnUiThread { page++; speakPage() } }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { act.runOnUiThread { stop() } }
                })
                ready = true
                if (active) speakPage()
            }
        }
    }

    private fun speakPage() {
        if (!active) return
        if (page >= count) {
            act.toast(if (spoke) "Fine del PDF" else "Questo PDF non ha testo da leggere (è una scansione)")
            stop(); return
        }
        val p = page
        act.io.execute {
            val text = PdfEdit.pageText(act, file, p)
            act.runOnUiThread {
                if (!active || p != page) return@runOnUiThread
                if (text.isBlank()) {
                    if (++blanks > 12 && !spoke) { act.toast("Non trovo testo da leggere (è una scansione?)"); stop(); return@runOnUiThread }
                    page++; speakPage(); return@runOnUiThread
                }
                blanks = 0; spoke = true
                onPage(p)
                val parts = chunks(text)
                parts.forEachIndexed { i, s -> tts?.speak(s, TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) "page$p" else "part$p-$i") }
            }
        }
    }

    fun stop() { active = false; tts?.stop() }

    fun shutdown() { stop(); tts?.shutdown(); tts = null; ready = false }

    companion object {
        /** Pieces short enough for the voice, cut at the end of a sentence when possible. */
        fun chunks(text: String, max: Int = 3000): List<String> {
            val out = ArrayList<String>()
            var rest = text.trim()
            while (rest.length > max) {
                val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', ';'), max).takeIf { it > max / 3 } ?: rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
                out += rest.substring(0, cut + 1).trim(); rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }
}
