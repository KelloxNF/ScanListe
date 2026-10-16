package de.scanliste.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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
 * am Ende Gesamtzahl und Unterschriften „Gepackt von“ / „Geprüft von“, danach eine Seite mit QR-Codes zum
 * Einlesen beim Wareneingang. Mehrere Listen ergeben eine Sammel-Packliste mit Übersichtsseite.
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
    private const val QR_PER_PAGE = 6    // 2 × 3 Codes je Seite
    private const val QR_SIZE = 200f     // ca. 7 cm – gut lesbar für den Scanner

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

    /** Ein Abschnitt der PDF: eine Packliste (oder die Übersicht einer Sammel-Packliste). */
    private class Part(
        val heading: String,
        val info: PackInfo,
        val sheet: PackSheet,
        val devices: Int,
        val codes: List<String>,
    )

    private sealed class Page(val part: Part) {
        class Rows(part: Part, val index: Int, val start: Int, val count: Int, val last: Boolean) : Page(part)
        class Codes(part: Part, val first: Int, val count: Int) : Page(part)
    }

    /** Anzahl Seiten einer Packliste mit [rows] Zeilen und [codes] QR-Codes (für Tests). */
    fun pageCount(rows: Int, codes: Int): Int = paginate(rows).size + (codes + QR_PER_PAGE - 1) / QR_PER_PAGE

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

    private fun devicesText(n: Int) = "$n ${if (n == 1) "Gerät" else "Geräte"}"

    /** Eine Packliste. */
    fun write(sheet: PackSheet, info: PackInfo, out: OutputStream) = write(listOf(info to sheet), info, out)

    /**
     * Eine oder mehrere Packlisten in einer PDF. Bei mehreren kommt vorne eine Übersicht
     * (Bezeichnung und Anzahl je Liste, Gesamt) mit den Angaben aus [summary].
     */
    fun write(parts: List<Pair<PackInfo, PackSheet>>, summary: PackInfo, out: OutputStream) {
        val units = ArrayList<Part>()
        if (parts.size > 1) {
            val total = parts.sumOf { it.second.rows.size }
            units += Part(
                "Sammel-Packliste", summary,
                PackSheet(listOf("Bezeichnung", "Geräte"), parts.map { (i, s) -> listOf(i.title, s.rows.size.toString()) }),
                total, emptyList(),
            )
        }
        for ((i, s) in parts) units += Part("Packliste", i, s, s.rows.size, PackCode.encode(i.title, s.rows))

        val pages = ArrayList<Page>()
        for (u in units) {
            val rowPages = paginate(u.sheet.rows.size)
            rowPages.forEachIndexed { k, (start, count) -> pages += Page.Rows(u, k, start, count, k == rowPages.lastIndex) }
            u.codes.indices.chunked(QR_PER_PAGE).forEach { c -> pages += Page.Codes(u, c.first(), c.size) }
        }

        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())
        val day = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
        val doc = PdfDocument()
        try {
            val p = Paints()
            pages.forEachIndexed { pi, page ->
                val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pi + 1).create())
                val c = pdfPage.canvas
                when (page) {
                    is Page.Rows -> drawRows(c, page, day, p)
                    is Page.Codes -> drawCodes(c, page, p)
                }
                c.drawText(fit("${page.part.info.title} · erstellt $stamp", p.small, W - 2 * M - 80f), M, H - 28f, p.small)
                c.drawText("Seite ${pi + 1} von ${pages.size}", W - M, H - 28f, p.smallRight)
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()   // auch bei Fehler (z. B. Speicher voll) die Seitenpuffer freigeben
        }
    }

    private class Paints {
        val small = paint(8f, color = Color.DKGRAY)
        val smallRight = paint(8f, color = Color.DKGRAY, align = Paint.Align.RIGHT)
        val title = paint(20f, bold = true)
        val sub = paint(13f, bold = true)
        val text = paint(9.5f)
        val label = paint(9.5f, bold = true)
        val head = paint(9f, bold = true, color = Color.WHITE)
        val headRight = paint(9f, bold = true, color = Color.WHITE, align = Paint.Align.RIGHT)
        val num = paint(9.5f, align = Paint.Align.RIGHT)
        val total = paint(11f, bold = true)
        val grid = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.4f }
        val headFill = Paint().apply { color = Color.rgb(31, 56, 100) }
        val stripe = Paint().apply { color = Color.rgb(243, 245, 248) }
        val line = Paint().apply { color = Color.BLACK; strokeWidth = 0.7f }
        val black = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    }

    private fun drawRows(c: Canvas, page: Page.Rows, day: String, p: Paints) {
        val u = page.part
        val info = u.info
        val rows = u.sheet.rows
        val columns = u.sheet.columns.ifEmpty { listOf("Wert") }
        val nrW = 34f
        val colW = (W - 2 * M - nrW) / columns.size
        var y: Float
        if (page.index == 0) {
            if (info.sender.isNotBlank()) c.drawText(fit(info.sender, p.small, W - 2 * M), M, 40f, p.small)
            c.drawText(u.heading, M, 72f, p.title)
            val facts = listOf(
                "Bezeichnung" to info.title,
                "Kunde / Empfänger" to info.customer,
                "Datum" to day,
                "Anzahl" to devicesText(u.devices),
                "Bemerkung" to info.note,
            ).filter { it.second.isNotBlank() }
            var fy = 100f
            for ((k, v) in facts) {
                c.drawText(k, M, fy, p.label)
                c.drawText(fit(v, p.text, W - 2 * M - 120f), M + 120f, fy, p.text)
                fy += 16f
            }
            y = FIRST_TABLE
        } else {
            y = TOP
        }
        if (page.count > 0 || page.start == 0) {
            c.drawRect(M, y - 11f, W - M, y + 4f, p.headFill)
            c.drawText("Nr.", M + nrW - 4f, y, p.headRight)
            columns.forEachIndexed { i, name -> c.drawText(fit(name, p.head, colW - 8f), M + nrW + i * colW + 6f, y, p.head) }
            y += ROW
            for (k in 0 until page.count) {
                val i = page.start + k
                if (i % 2 == 1) c.drawRect(M, y - 11f, W - M, y + 4f, p.stripe)
                c.drawText((i + 1).toString(), M + nrW - 4f, y, p.num)
                rows[i].forEachIndexed { col, v ->
                    if (col < columns.size) c.drawText(fit(v, p.text, colW - 8f), M + nrW + col * colW + 6f, y, p.text)
                }
                c.drawLine(M, y + 4f, W - M, y + 4f, p.grid)
                y += ROW
            }
        }
        if (page.last) {
            y += 22f
            c.drawText("Gesamt: ${devicesText(u.devices)}", M, y, p.total)
            y += 48f
            val half = (W - 2 * M - 30f) / 2
            c.drawLine(M, y, M + half, y, p.line)
            c.drawLine(M + half + 30f, y, W - M, y, p.line)
            c.drawText("Gepackt von (Name, Datum)", M, y + 12f, p.small)
            c.drawText("Geprüft von (Name, Datum)", M + half + 30f, y + 12f, p.small)
        }
    }

    private fun drawCodes(c: Canvas, page: Page.Codes, p: Paints) {
        val u = page.part
        c.drawText("QR-Codes zum Einlesen – ${fit(u.info.title, p.sub, W - 2 * M - 160f)}", M, 60f, p.sub)
        c.drawText("Wareneingang: in Scan-Portal eine Liste öffnen und alle Codes dieser Seite(n) scannen.", M, 78f, p.text)
        c.drawText("Die Geräte werden zur Soll-Liste; danach die Geräte selbst scannen.", M, 90f, p.text)
        val gap = (W - 2 * M - 2 * QR_SIZE) / 3
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        )
        for (k in 0 until page.count) {
            val idx = page.first + k
            val col = k % 2
            val row = k / 2
            val x = M + gap + col * (QR_SIZE + gap)
            val y = 108f + row * (QR_SIZE + 36f)
            // Ein einzelner riesiger Wert (z. B. ein ganzer Sammel-QR in einer Zelle) passt evtl. in keinen
            // QR-Code – dann nur einen Hinweis statt die ganze Packliste scheitern zu lassen.
            val m = runCatching { QRCodeWriter().encode(u.codes[idx], BarcodeFormat.QR_CODE, 0, 0, hints) }.getOrNull()
            if (m == null) {
                c.drawRect(x, y, x + QR_SIZE, y + QR_SIZE, Paint(p.grid).apply { style = Paint.Style.STROKE })
                c.drawText("Zu viele Daten für einen QR-Code", x + 8f, y + QR_SIZE / 2, p.small)
                continue
            }
            val cell = QR_SIZE / m.width
            for (my in 0 until m.height) {
                var mx = 0
                while (mx < m.width) {
                    if (!m.get(mx, my)) { mx++; continue }
                    var end = mx
                    while (end < m.width && m.get(end, my)) end++   // zusammenhängende Module als ein Rechteck
                    c.drawRect(x + mx * cell, y + my * cell, x + end * cell, y + (my + 1) * cell, p.black)
                    mx = end
                }
            }
            val n = u.codes[idx].count { it == '\n' }
            c.drawText("Teil ${idx + 1} von ${u.codes.size} · ${devicesText(n)}", x, y + QR_SIZE + 14f, p.small)
        }
    }
}
