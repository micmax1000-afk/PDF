package com.micmax.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File

/** Un PDF di prova fatto sul momento (per le prove automatiche). */
object Selftest {
    fun samplePdf(c: Context): File {
        val f = File(c.cacheDir, "prova_${System.currentTimeMillis()}.pdf")
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 22f }
        for (p in 1..3) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, p).create())
            page.canvas.drawText("CONTRATTO DI LOCAZIONE - pagina $p", 50f, 100f, paint)
            page.canvas.drawText("Il canone mensile e di 650 euro da pagare entro il 5.", 50f, 160f, paint)
            page.canvas.drawText("Il conduttore si impegna a restituire l'immobile.", 50f, 200f, paint)
            doc.finishPage(page)
        }
        f.outputStream().use { doc.writeTo(it) }; doc.close()
        return f
    }
}
