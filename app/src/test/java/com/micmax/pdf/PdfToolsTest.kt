package com.micmax.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfToolsTest {
    private fun near(a: Pair<Float, Float>, x: Float, y: Float) { assertEquals(x, a.first, 0.01f); assertEquals(y, a.second, 0.01f) }

    @Test fun coordinatesNoRotation() {
        near(PdfEdit.toPdf(0f, 0f, 0f, 0f, 595f, 842f, 0), 0f, 842f)
        near(PdfEdit.toPdf(1f, 1f, 0f, 0f, 595f, 842f, 0), 595f, 0f)
        near(PdfEdit.toPdf(0.5f, 0.25f, 10f, 20f, 100f, 200f, 0), 60f, 170f)
    }

    @Test fun coordinatesRotated() {
        near(PdfEdit.toPdf(0f, 0f, 0f, 0f, 595f, 842f, 90), 0f, 0f)
        near(PdfEdit.toPdf(1f, 0f, 0f, 0f, 595f, 842f, 90), 0f, 842f)
        near(PdfEdit.toPdf(0f, 0f, 0f, 0f, 595f, 842f, 180), 595f, 0f)
        near(PdfEdit.toPdf(0f, 0f, 0f, 0f, 595f, 842f, 270), 595f, 842f)
        near(PdfEdit.toPdf(0f, 0f, 0f, 0f, 595f, 842f, -90), 595f, 842f)
    }

    @Test fun snippetKeepsContext() {
        val t = "Il canone mensile e di 650 euro da pagare entro il 5 di ogni mese a mezzo bonifico bancario."
        val s = PdfEdit.snippetAround(t, t.indexOf("canone"), 6)
        assertTrue(s.contains("canone")); assertTrue(s.length < t.length)
        assertEquals("canone", PdfEdit.snippetAround("canone", 0, 6))
        assertTrue(PdfEdit.snippetAround(t, t.indexOf("bancario"), 8).startsWith("…"))
    }

    @Test fun names() {
        assertEquals("Contratto", Store.cleanName("Contratto.PDF"))
        assertEquals("a_b", Store.cleanName("a/b.pdf"))
        assertEquals("Documento", Store.cleanName("  .pdf "))
        assertEquals("Contratto (firmato).pdf", Store.fileName("Contratto (firmato)"))
    }

    @Test fun sizesAndDates() {
        assertEquals("999 B", Store.size(999)); assertEquals("12 KB", Store.size(12_345)); assertEquals("1,2 MB", Store.size(1_234_567))
        val now = 1_000_000_000_000L
        assertEquals("adesso", Store.ago(now - 10_000, now)); assertEquals("5 min fa", Store.ago(now - 5 * 60_000, now))
        assertEquals("3 ore fa", Store.ago(now - 3 * 3_600_000, now)); assertEquals("ieri", Store.ago(now - 30 * 3_600_000, now))
        assertEquals("4 giorni fa", Store.ago(now - 4 * 86_400_000, now))
    }
}
