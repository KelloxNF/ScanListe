package de.scanliste.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Angaben für den Kopf der Packliste. */
data class PackInfo(val title: String, val customer: String, val note: String, val sender: String)

/** Unveränderliche Kopie der Listendaten, damit die PDF im Hintergrund entstehen kann. */
class PackSheet(val columns: List<String>, val rows: List<List<String>>) {
    companion object {
        /** Nur Zeilen mit mindestens einem Wert; billig genug für den Haupt-Thread. */
        fun of(list: ScanList) = PackSheet(
            list.columns.toList(),
            list.rows.filter { r -> r.cells.any { it.isNotEmpty() } }.map { it.cells.toList() },
        )
    }
}

/**
 * Packliste als PDF (A4 hoch): Kopf mit Bezeichnung, Kunde, Datum und Anzahl, dann alle Zeilen der Liste,
 * am Ende Gesamtzahl und Unterschriften „Gepackt von“ / „Geprüft von“. Zum Beilegen zur Palette.
 */
object PackList {
    private const val W = 595f
    private const val H = 842f
    private const val M = 42f            // Seitenrand links/rechts
    private const val TOP = 48f          // erste Zeile auf Folgeseiten
    private const val FIRST_TABLE = 214f // Tabellenbeginn auf Seite 1 (unter dem Kopf)
    private const val ROW = 15f
    private const val BOTTOM = 772f      // darunter nur noch die Fußzeile
    private const val SIGN = 96f         // Platz für Gesamtzahl und Unterschriften

    /** Zeilen pro Seite: (erste Seite, Folgeseiten). Eine Zeile Tabellenkopf ist jeweils abgezogen. */
    fun capacity(): Pair<Int, Int> =
        ((BOTTOM - FIRST_TABLE - ROW) / ROW).toInt() to ((BOTTOM - TOP - ROW) / ROW).toInt()

    /**
     * Teilt [rows] Zeilen auf Seiten auf: Liste von (erste Zeile, Anzahl). Passt der Unterschriftsblock
     * nicht mehr unter die letzte Zeile, kommt eine eigene letzte Seite ohne Zeilen dazu.
     */
    fun paginate(rows: Int): List<Pair<Int, Int>> {
        val (first, other) = capacity()
        val pages = ArrayList<Pair<Int, Int>>()
        var start = 0
        var cap = first
        do {
            val n = minOf(cap, rows - start)
            pages += start to n
            start += n
            cap = other
        } while (start < rows)
        val lastTop = if (pages.size == 1) FIRST_TABLE else TOP
        val used = lastTop + ROW + pages.last().second * ROW
        if (used + SIGN > BOTTOM) pages += rows to 0
        return pages
    }

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            this.color = color
            textAlign = align
        }

    /** Kürzt [text] auf [width] mit „…“ – per breakText in einem Schritt statt Zeichen für Zeichen. */
    private fun fit(text: String, p: Paint, width: Float): String {
        val one = text.replace('\n', ' ').replace('\r', ' ')
        if (p.measureText(one) <= width) return one
        val n = p.breakText(one, true, (width - p.measureText("…")).coerceAtLeast(0f), null)
        return one.take(n) + "…"
    }

    fun write(sheet: PackSheet, info: PackInfo, out: OutputStream) {
        val now = Date()
        val day = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(now)
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(now)
        val rows = sheet.rows
        val columns = sheet.columns.ifEmpty { listOf("Wert") }
        val pages = paginate(rows.size)

        val small = paint(8f, color = Color.DKGRAY)
        val label = paint(9.5f, bold = true)
        val value = paint(9.5f)
        val head = paint(9f, bold = true, color = Color.WHITE)
        val cell = paint(9.5f)
        val num = paint(9.5f, align = Paint.Align.RIGHT)
        val grid = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.4f }
        val headFill = Paint().apply { color = Color.rgb(31, 56, 100) }
        val stripe = Paint().apply { color = Color.rgb(243, 245, 248) }
        val line = Paint().apply { color = Color.BLACK; strokeWidth = 0.7f }

        val nrW = 34f
        val colW = (W - 2 * M - nrW) / columns.size

        val doc = PdfDocument()
        try {
        pages.forEachIndexed { pi, (start, count) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pi + 1).create())
            val c = page.canvas
            var y: Float
            if (pi == 0) {
                if (info.sender.isNotBlank()) c.drawText(fit(info.sender, small, W - 2 * M), M, 40f, small)
                c.drawText("Packliste", M, 72f, paint(20f, bold = true))
                val facts = listOf(
                    "Bezeichnung" to info.title,
                    "Kunde / Empfänger" to info.customer,
                    "Datum" to day,
                    "Anzahl" to "${rows.size} ${if (rows.size == 1) "Gerät" else "Geräte"}",
                    "Bemerkung" to info.note,
                ).filter { it.second.isNotBlank() }
                var fy = 100f
                for ((k, v) in facts) {
                    c.drawText(k, M, fy, label)
                    c.drawText(fit(v, value, W - 2 * M - 120f), M + 120f, fy, value)
                    fy += 16f
                }
                y = FIRST_TABLE
            } else {
                y = TOP
            }
            if (count > 0 || start == 0) {
                // Tabellenkopf
                c.drawRect(M, y - 11f, W - M, y + 4f, headFill)
                c.drawText("Nr.", M + nrW - 4f, y, Paint(head).apply { textAlign = Paint.Align.RIGHT })
                columns.forEachIndexed { i, name ->
                    c.drawText(fit(name, head, colW - 8f), M + nrW + i * colW + 6f, y, head)
                }
                y += ROW
                for (k in 0 until count) {
                    val r = rows[start + k]
                    if ((start + k) % 2 == 1) c.drawRect(M, y - 11f, W - M, y + 4f, stripe)
                    c.drawText((start + k + 1).toString(), M + nrW - 4f, y, num)
                    r.forEachIndexed { i, v ->
                        if (i < columns.size) c.drawText(fit(v, cell, colW - 8f), M + nrW + i * colW + 6f, y, cell)
                    }
                    c.drawLine(M, y + 4f, W - M, y + 4f, grid)
                    y += ROW
                }
            }
            if (pi == pages.lastIndex) {
                y += 22f
                c.drawText("Gesamt: ${rows.size} ${if (rows.size == 1) "Gerät" else "Geräte"}", M, y, paint(11f, bold = true))
                y += 48f
                val half = (W - 2 * M - 30f) / 2
                c.drawLine(M, y, M + half, y, line)
                c.drawLine(M + half + 30f, y, W - M, y, line)
                c.drawText("Gepackt von (Name, Datum)", M, y + 12f, small)
                c.drawText("Geprüft von (Name, Datum)", M + half + 30f, y + 12f, small)
            }
            c.drawText(fit("${info.title} · erstellt $stamp", small, W - 2 * M - 80f), M, H - 28f, small)
            c.drawText("Seite ${pi + 1} von ${pages.size}", W - M, H - 28f, paint(8f, color = Color.DKGRAY, align = Paint.Align.RIGHT))
            doc.finishPage(page)
        }
        doc.writeTo(out)
        } finally {
            doc.close()   // auch bei Fehler (z. B. Speicher voll) die Seitenpuffer freigeben
        }
    }
}
