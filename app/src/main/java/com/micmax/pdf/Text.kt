package com.micmax.pdf

import java.text.Normalizer

/** Small text helpers, pure Kotlin (unit-tested). */
object Text {
    /** The same letter without accents, lower case: «Perché» → «perche». Keeps the length, so positions stay the same. */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val base = if (ch.code < 128) ch else Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ch
            sb.append(base.lowercaseChar())
        }
        return sb.toString()
    }

    /** Where [query] appears in [text], ignoring accents and capitals. */
    fun findAll(text: String, query: String, max: Int = Int.MAX_VALUE): List<Int> {
        val t = fold(text); val q = fold(query)
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var from = 0
        while (out.size < max) {
            val i = t.indexOf(q, from)
            if (i < 0) break
            out += i; from = i + q.length
        }
        return out
    }

    /**
     * Lines no wider than [maxWidth] (as measured by [measure]), breaking between words;
     * only a single word longer than a whole line is cut.
     */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.trim().lines()) {
            if (para.isBlank()) { out += " "; continue }
            var line = ""
            for (word in para.trim().split(Regex("\\s+"))) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (measure(tryLine) <= maxWidth) { line = tryLine; continue }
                if (line.isNotEmpty()) out += line
                var w = word
                while (measure(w) > maxWidth && w.length > 1) {
                    var n = w.length - 1
                    while (n > 1 && measure(w.substring(0, n)) > maxWidth) n--
                    out += w.substring(0, n); w = w.substring(n)
                }
                line = w
            }
            if (line.isNotEmpty()) out += line
        }
        return out.ifEmpty { listOf(" ") }
    }

    /** Today as «05/03/2026». */
    fun date(time: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ITALIAN).format(java.util.Date(time))

    /** Which of «i miei dati» a form field asks for, from its name or label (null when it is not clear). */
    fun myDataKey(fieldName: String): String? {
        val spaced = fieldName.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        val t = " " + fold(spaced).replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        fun has(vararg w: String) = w.any { t.contains(" $it ") }
        return when {
            has("codice fiscale", "cod fiscale", "cod fisc", "cf", "fiscale", "codicefiscale") -> "cf"
            has("iban") -> "iban"
            has("email", "e mail", "mail", "pec") -> "email"
            has("telefono", "tel", "cellulare", "cell", "recapito telefonico", "phone") -> "telefono"
            has("data di nascita", "data nascita", "nato il", "nata il", "datanascita") -> "nascita"
            has("luogo di nascita", "luogo nascita", "nato a", "nata a", "comune di nascita", "luogonascita") -> "luogo"
            has("cap", "c a p", "codice postale") -> "cap"
            has("provincia", "prov") -> "provincia"
            has("nome e cognome", "cognome e nome", "nominativo") -> "nomecompleto"
            has("cognome") -> "cognome"
            has("nome") -> "nome"
            has("citta", "comune", "localita", "residente a") -> "citta"
            has("indirizzo", "via", "residente in via", "residenza", "domicilio") -> "indirizzo"
            else -> null
        }
    }

    /** The value of [key] from [data], with «nomecompleto» = nome + cognome. */
    fun myDataValue(key: String, data: Map<String, String>): String? =
        if (key == "nomecompleto") listOfNotNull(data["nome"], data["cognome"]).joinToString(" ").takeIf { it.isNotBlank() }
        else data[key]
}
