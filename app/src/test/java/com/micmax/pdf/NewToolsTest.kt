package com.micmax.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewToolsTest {
    @Test fun searchIgnoresAccentsAndCapitals() {
        assertEquals("perche e citta", Text.fold("Perché È città"))
        val t = "Il motivo è questo: perché la CITTÀ cresce. Perche no?"
        assertEquals(listOf(t.indexOf("perché"), t.indexOf("Perche")), Text.findAll(t, "perche"))
        assertEquals(listOf(t.indexOf("CITTÀ")), Text.findAll(t, "città"))
        assertEquals(1, Text.findAll(t, "perche", max = 1).size)
        assertTrue(Text.findAll(t, "").isEmpty())
    }

    @Test fun wrapBreaksBetweenWords() {
        val m: (String) -> Float = { it.length.toFloat() }
        assertEquals(listOf("Il canone", "mensile è", "di 650", "euro"), Text.wrap("Il canone mensile è di 650 euro", 10f, m))
        assertEquals(listOf("abcdefghij", "klm"), Text.wrap("abcdefghijklm", 10f, m))
        assertEquals(listOf("uno", " ", "due"), Text.wrap("uno\n\ndue", 10f, m))
        assertEquals(listOf(" "), Text.wrap("   ", 10f, m))
    }

    @Test fun dateHasTwoDigits() {
        val cal = java.util.Calendar.getInstance().apply { set(2026, 2, 5, 12, 0) }
        assertEquals("05/03/2026", Text.date(cal.timeInMillis))
    }

    @Test fun formFieldsFindMyData() {
        assertEquals("cf", Text.myDataKey("Codice Fiscale"))
        assertEquals("cf", Text.myDataKey("txtCF"))
        assertEquals("cognome", Text.myDataKey("Cognome"))
        assertEquals("nome", Text.myDataKey("nome_1"))
        assertEquals("nomecompleto", Text.myDataKey("Nome e cognome"))
        assertEquals("nascita", Text.myDataKey("Data di nascita"))
        assertEquals("luogo", Text.myDataKey("nato a"))
        assertEquals("email", Text.myDataKey("E-mail"))
        assertEquals("telefono", Text.myDataKey("Tel."))
        assertEquals("cap", Text.myDataKey("C.A.P."))
        assertEquals("citta", Text.myDataKey("Comune di residenza"))
        assertEquals("indirizzo", Text.myDataKey("Indirizzo di residenza"))
        assertEquals("indirizzo", Text.myDataKey("Via"))
        assertNull(Text.myDataKey("Importo"))
        val data = mapOf("nome" to "Mario", "cognome" to "Rossi")
        assertEquals("Mario Rossi", Text.myDataValue("nomecompleto", data))
        assertEquals("Rossi", Text.myDataValue("cognome", data))
        assertNull(Text.myDataValue("cf", data))
    }

    @Test fun listOrderAndFilter() {
        val a = Store.Doc("a", "Contratto affitto", 300, 2, 10)
        val b = Store.Doc("b", "Bolletta luce", 100, 1, 900)
        val c = Store.Doc("c", "Città – modulo", 200, 1, 50)
        val all = listOf(a, b, c)
        assertEquals(listOf(a, c, b), Store.arrange(all, "", Store.SORT_RECENT))
        assertEquals(listOf(b, c, a), Store.arrange(all, "", Store.SORT_NAME))
        assertEquals(listOf(b, c, a), Store.arrange(all, "", Store.SORT_SIZE))
        assertEquals(listOf(c), Store.arrange(all, "citta", Store.SORT_RECENT))
        assertEquals(listOf(a), Store.arrange(all, "  AFFITTO ", Store.SORT_NAME))
    }

    @Test fun uprightTextOnRotatedPages() {
        fun apply(m: FloatArray, x: Float, y: Float) = (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])
        fun near(p: Pair<Float, Float>, x: Float, y: Float) { assertEquals(x, p.first, 0.01f); assertEquals(y, p.second, 0.01f) }
        val m0 = PdfEdit.displayMatrix(0f, 0f, 595f, 842f, 0)
        near(apply(m0, 10f, 20f), 10f, 20f)
        // pagina girata di 90° in senso orario: in basso a sinistra sullo schermo c'è l'angolo in basso a destra del foglio
        val m90 = PdfEdit.displayMatrix(0f, 0f, 595f, 842f, 90)
        near(apply(m90, 0f, 0f), 595f, 0f)
        near(apply(m90, 842f, 0f), 595f, 842f)
        near(apply(m90, 0f, 595f), 0f, 0f)
        val m180 = PdfEdit.displayMatrix(0f, 0f, 595f, 842f, 180)
        near(apply(m180, 0f, 0f), 595f, 842f)
        val m270 = PdfEdit.displayMatrix(0f, 0f, 595f, 842f, 270)
        near(apply(m270, 0f, 0f), 0f, 842f)
        assertEquals("3 / 10", PdfEdit.pageLabel(2, 10))
    }

    @Test fun readingPiecesAreShort() {
        val t = (1..400).joinToString(" ") { "Frase numero $it." }
        val parts = ReadAloud.chunks(t, 300)
        assertTrue(parts.all { it.length <= 301 })
        assertTrue(parts.all { it.endsWith(".") })
        assertEquals(t.replace(" ", ""), parts.joinToString("").replace(" ", ""))
        assertEquals(listOf("Breve."), ReadAloud.chunks("Breve."))
    }
}

class SelectTest {
    private val words = listOf(
        Word("Il", 0.10f, 0.10f, 0.03f, 0.02f, 0), Word("canone", 0.14f, 0.10f, 0.10f, 0.02f, 0), Word("mensile", 0.25f, 0.10f, 0.11f, 0.02f, 0),
        Word("è", 0.10f, 0.14f, 0.02f, 0.02f, 1), Word("650", 0.13f, 0.14f, 0.05f, 0.02f, 1)
    )

    @Test fun textKeepsLines() {
        assertEquals("canone mensile\nè 650", TextSel.text(words, 1, 4))
        assertEquals("canone mensile\nè 650", TextSel.text(words, 4, 1))
        assertEquals("Il", TextSel.text(words, 0, 0))
        assertEquals("", TextSel.text(emptyList(), 0, 3))
    }

    @Test fun oneBoxPerLine() {
        val b = TextSel.lineBoxes(words, 1, 4)
        assertEquals(2, b.size)
        assertEquals(0.14f, b[0][0], 0.001f); assertEquals(0.36f - 0.14f, b[0][2], 0.001f)
        assertEquals(0.10f, b[1][0], 0.001f); assertEquals(0.18f - 0.10f, b[1][2], 0.001f)
    }

    @Test fun wordUnderTheFinger() {
        assertEquals(1, TextSel.at(words, 0.18f, 0.11f, 0.7f))
        assertEquals(4, TextSel.at(words, 0.16f, 0.15f, 0.7f))
        assertEquals(2, TextSel.at(words, 0.40f, 0.11f, 0.7f))
        assertEquals(-1, TextSel.at(words, 0.9f, 0.9f, 0.7f, 0.05f))
        assertEquals(-1, TextSel.at(emptyList(), 0.5f, 0.5f, 0.7f))
    }
}
