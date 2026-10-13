package de.inventur.app

import de.scanportal.app.ExcelCsv

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hilfsklasse zum Schreiben mehrseitiger A4-PDFs mit dem Android-eigenen PdfDocument. */
private class PdfWriter(landscape: Boolean, private val onNewPage: (PdfWriter) -> Unit = {}) {
    val w = if (landscape) 842 else 595
    val h = if (landscape) 595 else 842
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    lateinit var c: Canvas
    var pageNo = 0
    var y = 0f

    fun newPage() {
        finishPage()
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        page = p
        c = p.canvas
        onNewPage(this)
    }

    private fun finishPage() {
        page?.let { doc.finishPage(it) }
        page = null
    }

    fun write(out: OutputStream) {
        finishPage()
        doc.writeTo(out)
        doc.close()
    }
}

private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK, align: Paint.Align = Paint.Align.LEFT) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.color = color
        textAlign = align
    }

/** Bricht Text an Wortgrenzen um, sodass jede Zeile höchstens [width] breit ist. */
private fun wrap(text: String, p: Paint, width: Float): List<String> {
    val out = ArrayList<String>()
    for (para in text.split("\n")) {
        var line = ""
        for (word in para.split(" ")) {
            val cand = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(cand) <= width || line.isEmpty()) line = cand else { out.add(line); line = word }
        }
        out.add(line)
    }
    return out
}

private fun fit(text: String, p: Paint, width: Float): String {
    if (p.measureText(text) <= width) return text
    var t = text
    while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
    return "$t…"
}

private fun fmt(n: Int) = String.format(Locale.GERMANY, "%,d", n)

object Documents {

    fun fileBase(inv: Inventory): String {
        val safe = inv.customer.name.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß_-]+"), "_").trim('_').ifEmpty { "Kunde" }
        return "Inventur_${safe}_${inv.stichtag}"
    }

    // ------------------------------------------------------------------ Anschreiben

    fun letter(inv: Inventory, sender: String, footer: String, ceo: String, out: OutputStream) {
        val left = 70f
        val right = 595f - 55f
        val width = right - left
        val body = paint(10.5f)
        val bold = paint(10.5f, bold = true)
        val small = paint(7f, color = Color.DKGRAY).apply { isUnderlineText = true }
        val foot = paint(7f, color = Color.DKGRAY, align = Paint.Align.CENTER)
        val lh = 15f

        val pdf = PdfWriter(landscape = false) { p ->
            if (footer.isNotBlank()) {
                val lines = footer.lines().filter { it.isNotBlank() }
                var fy = p.h - 30f - (lines.size - 1) * 9f
                for (l in lines) { p.c.drawText(l.trim(), p.w / 2f, fy, foot); fy += 9f }
            }
            p.y = 70f
        }
        // Unterkante: Platz für die Fußzeile lassen
        val footerLines = footer.lines().count { it.isNotBlank() }
        val bottom = 842f - 45f - footerLines * 9f
        pdf.newPage()
        val c = { pdf.c }

        // Absenderzeile + Anschrift (Fensterbereich nach DIN 5008)
        pdf.y = 133f
        if (sender.isNotBlank()) c().drawText(fit(sender, small, width), left, pdf.y, small)
        pdf.y = 155f
        for (l in listOf(inv.customer.name, inv.customer.street, inv.customer.city).filter { it.isNotBlank() }) {
            c().drawText(l, left, pdf.y, body); pdf.y += lh
        }

        // Datum
        c().drawText(Dates.long(inv.letterDate), right, 255f, paint(10.5f, align = Paint.Align.RIGHT))

        // Betreff
        pdf.y = 295f
        for (l in wrap("Inventur ${inv.customer.name} – Lagerbestand zum Stichtag ${Dates.long(inv.stichtag)}", bold, width)) {
            c().drawText(l, left, pdf.y, bold); pdf.y += lh
        }
        pdf.y += lh
        c().drawText("Sehr geehrte Damen und Herren,", left, pdf.y, body); pdf.y += lh * 2

        val sentence = buildString {
            append("hiermit bestätigen wir Ihnen den nachfolgenden Lagerbestand Ihrer bei uns befindlichen Artikel.")
            append("\n\n")
            append("Die Inventur fand am ${Dates.long(inv.stichtag)}")
            if (inv.timeFrom.isNotBlank() && inv.timeTo.isNotBlank()) append(" zwischen ${inv.timeFrom} und ${inv.timeTo} Uhr")
            append(" statt.")
            if (inv.counter.isNotBlank()) append(" Zähler war ${inv.counter}")
            if (inv.writer.isNotBlank()) append(if (inv.counter.isNotBlank()) ", Schriftführer ${inv.writer}" else " Schriftführer war ${inv.writer}")
            if (inv.counter.isNotBlank() || inv.writer.isNotBlank()) append(".")
        }
        for (l in wrap(sentence, body, width)) { c().drawText(l, left, pdf.y, body); pdf.y += lh }
        pdf.y += lh
        c().drawText("Lagerbestand zum ${Dates.display(inv.stichtag)}:", left, pdf.y, bold); pdf.y += lh * 1.6f

        // Tabelle
        val colQty = right
        val numBold = paint(10.5f, bold = true, align = Paint.Align.RIGHT)
        val num = paint(10.5f, align = Paint.Align.RIGHT)
        val line = Paint().apply { color = Color.GRAY; strokeWidth = 0.5f }
        val thick = Paint().apply { color = Color.BLACK; strokeWidth = 1.2f }
        fun header() {
            c().drawText("Artikel", left, pdf.y, bold)
            c().drawText("Menge", colQty, pdf.y, numBold)
            c().drawLine(left, pdf.y + 5f, right, pdf.y + 5f, thick)
            pdf.y += lh + 3f
        }
        header()
        for (r in inv.letterRows()) {
            if (pdf.y > bottom - 20f) { pdf.newPage(); pdf.y = 90f; header() }
            c().drawText(fit(r.name, body, width - 90f), left, pdf.y, body)
            c().drawText(fmt(r.qty), colQty, pdf.y, num)
            c().drawLine(left, pdf.y + 5f, right, pdf.y + 5f, line)
            pdf.y += lh + 2f
        }

        // Schluss mit Unterschrift
        if (pdf.y + 135f > bottom) { pdf.newPage(); pdf.y = 90f }
        pdf.y += lh
        c().drawText("Anlage: Zählliste", left, pdf.y, body); pdf.y += lh * 2
        c().drawText("Mit freundlichen Grüßen", left, pdf.y, body); pdf.y += lh * 3.5f
        c().drawLine(left, pdf.y, left + 190f, pdf.y, Paint().apply { color = Color.BLACK; strokeWidth = 0.7f })
        pdf.y += lh
        if (ceo.isNotBlank()) { c().drawText(ceo, left, pdf.y, body); pdf.y += lh }
        c().drawText("Geschäftsführer", left, pdf.y, body)

        pdf.write(out)
    }

    // ------------------------------------------------------------------ Zählliste

    fun countSheet(inv: Inventory, zones: List<String>, out: OutputStream) {
        val m = 36f
        val small = paint(7.5f, color = Color.DKGRAY)
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())
        val pdf = PdfWriter(landscape = true) { p ->
            p.c.drawText("${inv.customer.name} · Stichtag ${Dates.display(inv.stichtag)} · erstellt $stamp", m, p.h - 20f, small)
            p.c.drawText("Seite ${p.pageNo}", p.w - m, p.h - 20f, paint(7.5f, color = Color.DKGRAY, align = Paint.Align.RIGHT))
        }
        pdf.newPage()
        val c = { pdf.c }
        val right = pdf.w - m

        // Kopf
        c().drawText("Inventur-Zählliste", m, 50f, paint(16f, bold = true))
        c().drawText(inv.customer.name, m + 170f, 50f, paint(16f, bold = true))
        val lab = paint(9f, bold = true)
        val v = paint(9f)
        val info = listOf(
            "Stichtag:" to Dates.display(inv.stichtag),
            "Zähler:" to inv.counter,
            "Schreiber:" to inv.writer,
        )
        val info2 = listOf(
            "Zählort:" to inv.place,
            "Uhrzeit:" to listOf(inv.timeFrom, inv.timeTo).filter { it.isNotBlank() }.joinToString(" – ").let { if (it.isEmpty()) "" else "$it Uhr" },
            "Status:" to (if (inv.closed) "abgeschlossen" else "in Bearbeitung"),
        )
        var yy = 74f
        for (i in info.indices) {
            c().drawText(info[i].first, m, yy, lab); c().drawText(fit(info[i].second, v, 190f), m + 62f, yy, v)
            c().drawText(info2[i].first, m + 260f, yy, lab); c().drawText(fit(info2[i].second, v, right - 260f - (m + 315f)), m + 315f, yy, v)
            yy += 13f
        }
        val sig = Paint().apply { color = Color.BLACK; strokeWidth = 0.6f }
        c().drawText("Unterschrift Zähler:", right - 250f, 76f, lab); c().drawLine(right - 150f, 78f, right, 78f, sig)
        c().drawText("Unterschrift Schreiber:", right - 250f, 100f, lab); c().drawLine(right - 150f, 102f, right, 102f, sig)

        // Spalten
        data class Col(val title: String, val width: Float, val alignRight: Boolean)
        val fixed = mutableListOf(Col("Nr.", 24f, true), Col("Artikelnummer", 112f, false))
        val after = mutableListOf(Col("Soll", 46f, true))
        zones.forEach { after.add(Col(it, 58f, true)) }
        after.add(Col("Gesamt", 48f, true)); after.add(Col("Differenz", 52f, true)); after.add(Col("Status", 74f, false))
        val nameW = (right - m) - fixed.sumOf { it.width.toDouble() }.toFloat() - after.sumOf { it.width.toDouble() }.toFloat()
        val cols = fixed + Col("Artikel", nameW, false) + after
        val rowH = 15f
        val headPaint = paint(8.5f, bold = true, color = Color.WHITE)
        val headFill = Paint().apply { color = Color.rgb(31, 56, 100) }
        val stripe = Paint().apply { color = Color.rgb(242, 244, 247) }
        val grid = Paint().apply { color = Color.rgb(190, 190, 190); strokeWidth = 0.4f }
        val cellP = paint(8.5f)
        val cellR = paint(8.5f, align = Paint.Align.RIGHT)
        val cellB = paint(8.5f, bold = true, align = Paint.Align.RIGHT)

        fun header() {
            c().drawRect(m, pdf.y, right, pdf.y + rowH + 2f, headFill)
            var x = m
            for (col in cols) {
                val hp = Paint(headPaint).apply { textAlign = if (col.alignRight) Paint.Align.RIGHT else Paint.Align.LEFT }
                c().drawText(col.title, if (col.alignRight) x + col.width - 4f else x + 4f, pdf.y + 11.5f, hp)
                x += col.width
            }
            pdf.y += rowH + 2f
        }
        pdf.y = 120f
        header()
        val rows = inv.listArticles()
        rows.forEachIndexed { i, a ->
            if (pdf.y + rowH > pdf.h - 40f) { pdf.newPage(); pdf.y = 40f; header() }
            if (i % 2 == 1) c().drawRect(m, pdf.y, right, pdf.y + rowH, stripe)
            val ist = inv.counted(a.sku)
            val st = inv.status(a)
            val values = mutableListOf((i + 1).toString(), a.sku, a.name, fmt(a.soll))
            zones.forEach { z -> values.add(inv.zoneQty(a.sku, z)?.let { fmt(it) } ?: "") }
            values.add(ist?.let { fmt(it) } ?: "")
            values.add(ist?.let { val d = it - a.soll; if (d > 0) "+${fmt(d)}" else fmt(d) } ?: "")
            values.add(st.label)
            var x = m
            cols.forEachIndexed { j, col ->
                val p = when {
                    col.title == "Gesamt" -> cellB
                    col.title == "Status" -> paint(8.5f, bold = true, color = statusColor(st))
                    col.title == "Differenz" && ist != null && ist != a.soll -> paint(8.5f, bold = true, color = Color.rgb(156, 0, 6), align = Paint.Align.RIGHT)
                    col.alignRight -> cellR
                    else -> cellP
                }
                val text = fit(values[j], p, col.width - 8f)
                c().drawText(text, if (col.alignRight) x + col.width - 4f else x + 4f, pdf.y + 10.5f, p)
                x += col.width
            }
            c().drawLine(m, pdf.y + rowH, right, pdf.y + rowH, grid)
            pdf.y += rowH
        }

        // Zusammenfassung
        val s = inv.summary()
        if (pdf.y + 40f > pdf.h - 40f) { pdf.newPage(); pdf.y = 40f }
        pdf.y += 22f
        c().drawText(
            "Artikel: ${s.articles} · stimmt: ${s.ok} · Differenz: ${s.diff} · geschätzt: ${s.estimated} · nicht gezählt: ${s.notCounted}" +
                " · Mehrbestand: ${fmt(s.plus)} · Fehlbestand: ${fmt(s.minus)}",
            m, pdf.y, paint(9f, bold = true),
        )
        val notes = rows.mapNotNull { a -> inv.notes[a.sku]?.takeIf { it.isNotBlank() }?.let { "${a.name}: $it" } }
        if (notes.isNotEmpty()) {
            pdf.y += 16f
            c().drawText("Bemerkungen:", m, pdf.y, paint(9f, bold = true))
            val np = paint(8.5f)
            for (n in notes) for (line in wrap(n, np, right - m)) {
                pdf.y += 12f
                if (pdf.y > pdf.h - 40f) { pdf.newPage(); pdf.y = 50f }
                c().drawText(line, m, pdf.y, np)
            }
        }
        pdf.write(out)
    }

    private fun statusColor(s: Status) = when (s) {
        Status.STIMMT -> Color.rgb(0, 97, 0)
        Status.DIFFERENZ -> Color.rgb(156, 0, 6)
        Status.GESCHAETZT -> Color.rgb(156, 87, 0)
        Status.NICHT_GEZAEHLT -> Color.DKGRAY
    }
}

/** CSV-Export (Format siehe ExcelCsv). */
object CsvExport {

    fun result(inv: Inventory, zones: List<String>): String {
        val sb = StringBuilder()
        val head = mutableListOf("Kunde", "Stichtag", "Artikelnummer", "Artikel", "Soll", "Technik")
        head += zones
        head += listOf("Ist", "Differenz", "Status", "Bemerkung")
        sb.line(head)
        for (a in inv.listArticles()) {
            val ist = inv.counted(a.sku)
            val r = mutableListOf(inv.customer.name, Dates.display(inv.stichtag), a.sku, a.name, a.soll.toString(), a.technik.toString())
            zones.forEach { z -> r.add(inv.zoneQty(a.sku, z)?.toString() ?: "") }
            r += listOf(ist?.toString() ?: "", ist?.let { (it - a.soll).toString() } ?: "", inv.status(a).label, inv.notes[a.sku] ?: "")
            sb.line(r)
        }
        return sb.toString()
    }

    fun overview(list: List<Inventory>): String {
        val sb = StringBuilder()
        sb.line(listOf("Kunde", "Stichtag", "Artikel", "Stimmt", "Differenzen", "Geschätzt", "Nicht gezählt", "Mehrbestand", "Fehlbestand", "Ergebnis", "Abgeschlossen"))
        for (inv in list) {
            val s = inv.summary()
            sb.line(listOf(
                inv.customer.name, Dates.display(inv.stichtag), s.articles.toString(), s.ok.toString(), s.diff.toString(),
                s.estimated.toString(), s.notCounted.toString(), s.plus.toString(), s.minus.toString(), s.overall,
                if (inv.closed) "ja" else "nein",
            ))
        }
        return sb.toString()
    }

    fun write(out: OutputStream, text: String) = ExcelCsv.write(out, text)

    private fun StringBuilder.line(cells: List<String>) {
        append(ExcelCsv.line(cells))
    }
}
