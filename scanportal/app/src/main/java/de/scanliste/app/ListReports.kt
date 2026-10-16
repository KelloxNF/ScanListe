package de.scanliste.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import de.inventur.app.Inventory
import de.scanportal.app.ExcelCsv
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Sammelexport mehrerer Listen und Tagesbericht. Die Rechenteile sind reine Funktionen (testbar). */
object ListReports {

    // ------------------------------------------------------------ Sammelexport

    /**
     * Eine CSV aus mehreren Listen: Spalte „Liste“ vorne, dann Nr und alle Spalten aller Listen
     * (gleich benannte Spalten werden zusammengelegt), Zeitstempel, falls eine Liste einen hat.
     */
    fun combinedCsv(lists: List<ScanList>): String {
        val columns = LinkedHashSet<String>().apply { lists.forEach { addAll(it.columns) } }.toList()
        val withTime = lists.any { it.withTimestamp }
        val sb = StringBuilder()
        sb.append(ExcelCsv.line(listOf("Liste", "Nr") + columns + if (withTime) listOf("Zeitstempel") else emptyList()))
        for (l in lists) {
            val pos = l.columns.map { columns.indexOf(it) }
            var nr = 0
            for (row in l.rows) {
                if (row.cells.all { it.isEmpty() }) continue
                val cells = MutableList(columns.size) { "" }
                row.cells.forEachIndexed { i, v -> pos.getOrNull(i)?.let { p -> if (cells[p].isEmpty()) cells[p] = v } }
                val line = mutableListOf(l.name, (++nr).toString()).apply { addAll(cells) }
                if (withTime) line += if (l.withTimestamp) Csv.formatTime(row.time) else ""
                sb.append(ExcelCsv.line(line))
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ Tagesbericht

    data class ListDay(val name: String, val rows: Int, val from: Long, val to: Long, val closed: Boolean, val total: Int)
    data class InventoryDay(val name: String, val entries: Int, val articles: Int, val from: Long, val to: Long)
    data class Day(val date: LocalDate, val lists: List<ListDay>, val inventories: List<InventoryDay>) {
        val devices get() = lists.sumOf { it.rows }
        val isEmpty get() = lists.isEmpty() && inventories.isEmpty()
    }

    private fun range(date: LocalDate, zone: ZoneId): LongRange {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until end
    }

    /** Was an [date] erfasst wurde: je Liste die Zeilen mit Zeitpunkt an diesem Tag, je Inventur die Zählungen. */
    fun day(date: LocalDate, lists: List<ScanList>, inventories: List<Inventory>, zone: ZoneId = ZoneId.systemDefault()): Day {
        val r = range(date, zone)
        val ls = lists.mapNotNull { l ->
            val times = l.rows.filter { row -> row.time in r && row.cells.any { it.isNotEmpty() } }.map { it.time }
            if (times.isEmpty()) null
            else ListDay(l.name, times.size, times.min(), times.max(), l.closed, l.rows.count { row -> row.cells.any { it.isNotEmpty() } })
        }.sortedBy { it.from }
        val inv = inventories.mapNotNull { i ->
            val e = i.counts.filter { it.time in r }
            if (e.isEmpty()) null
            else InventoryDay(i.customer.name, e.size, e.map { it.sku }.distinct().size, e.minOf { it.time }, e.maxOf { it.time })
        }.sortedBy { it.from }
        return Day(date, ls, inv)
    }

    private fun clock(t: Long) = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(t))
    private fun dateText(d: LocalDate) = "%02d.%02d.%04d".format(d.dayOfMonth, d.monthValue, d.year)

    fun dayCsv(d: Day): String = buildString {
        append(ExcelCsv.line(listOf("Bereich", "Name", "Anzahl", "Artikel", "von", "bis", "Status")))
        d.lists.forEach {
            append(ExcelCsv.line(listOf("Liste", it.name, it.rows.toString(), "", clock(it.from), clock(it.to),
                if (it.closed) "abgeschlossen" else "offen")))
        }
        d.inventories.forEach {
            append(ExcelCsv.line(listOf("Inventur", it.name, it.entries.toString(), it.articles.toString(), clock(it.from), clock(it.to), "")))
        }
        append(ExcelCsv.line(listOf("Summe", "Geräte in Listen", d.devices.toString(), "", "", "", "")))
    }

    /** Kurzfassung für die Anzeige vor dem Teilen. */
    fun dayText(d: Day): String = buildString {
        append("Tagesbericht ${dateText(d.date)}\n\n")
        if (d.isEmpty) { append("An diesem Tag wurde nichts erfasst."); return@buildString }
        if (d.lists.isNotEmpty()) {
            append("Listen: ${d.lists.size}, Geräte: ${d.devices}\n")
            d.lists.forEach { append("• ${it.name}: ${it.rows} (${clock(it.from)}–${clock(it.to)})${if (it.closed) " ✓" else ""}\n") }
        }
        if (d.inventories.isNotEmpty()) {
            append("\nInventur:\n")
            d.inventories.forEach { append("• ${it.name}: ${it.entries} Zählungen, ${it.articles} Artikel (${clock(it.from)}–${clock(it.to)})\n") }
        }
    }.trimEnd()

    fun dayPdf(d: Day, sender: String, out: OutputStream) {
        val w = 595; val h = 842; val m = 42f
        fun p(size: Float, bold: Boolean = false, color: Int = Color.BLACK, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color; textAlign = align
                typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        val small = p(8f, color = Color.DKGRAY)
        val text = p(9.5f)
        val right = p(9.5f, align = Paint.Align.RIGHT)
        val head = p(9f, bold = true, color = Color.WHITE)
        val headRight = p(9f, bold = true, color = Color.WHITE, align = Paint.Align.RIGHT)
        val fill = Paint().apply { color = Color.rgb(31, 56, 100) }
        val stripe = Paint().apply { color = Color.rgb(243, 245, 248) }
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())

        // Zeilen: (Spalte 1, Anzahl, Artikel, von–bis, Status) mit Abschnittsköpfen
        data class Line(val cells: List<String>, val header: Boolean = false, val title: String? = null)
        val lines = ArrayList<Line>()
        if (d.lists.isNotEmpty()) {
            lines += Line(emptyList(), title = "Listen (Scannen)")
            lines += Line(listOf("Liste", "Geräte", "", "Zeit", "Status"), header = true)
            d.lists.forEach { lines += Line(listOf(it.name, it.rows.toString(), "", "${clock(it.from)}–${clock(it.to)}", if (it.closed) "abgeschlossen" else "offen")) }
            lines += Line(listOf("Summe", d.devices.toString(), "", "", ""))
        }
        if (d.inventories.isNotEmpty()) {
            lines += Line(emptyList(), title = "Inventur")
            lines += Line(listOf("Kunde", "Zählungen", "Artikel", "Zeit", ""), header = true)
            d.inventories.forEach { lines += Line(listOf(it.name, it.entries.toString(), it.articles.toString(), "${clock(it.from)}–${clock(it.to)}", "")) }
        }
        if (lines.isEmpty()) lines += Line(emptyList(), title = "An diesem Tag wurde nichts erfasst.")

        val doc = PdfDocument()
        try {
            val perFirst = 40; val perNext = 46
            val pages = ArrayList<List<Line>>()
            var rest: List<Line> = lines
            var cap = perFirst
            while (rest.isNotEmpty() || pages.isEmpty()) { pages += rest.take(cap); rest = rest.drop(cap); cap = perNext }
            pages.forEachIndexed { pi, pl ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pi + 1).create())
                val c = page.canvas
                var y: Float
                if (pi == 0) {
                    if (sender.isNotBlank()) c.drawText(sender, m, 40f, small)
                    c.drawText("Tagesbericht ${dateText(d.date)}", m, 72f, p(20f, bold = true))
                    c.drawText("Geräte in Listen: ${d.devices} · Listen: ${d.lists.size} · Inventuren: ${d.inventories.size}", m, 92f, text)
                    y = 124f
                } else y = 52f
                for ((k, l) in pl.withIndex()) {
                    when {
                        l.title != null -> { y += 8f; c.drawText(l.title, m, y, p(12f, bold = true)); y += 6f }
                        l.header -> {
                            c.drawRect(m, y - 11f, w - m, y + 4f, fill)
                            c.drawText(l.cells[0], m + 6f, y, head)
                            c.drawText(l.cells[1], 350f, y, headRight)
                            c.drawText(l.cells[2], 410f, y, headRight)
                            c.drawText(l.cells[3], 420f, y, head)
                            c.drawText(l.cells[4], 480f, y, head)
                        }
                        else -> {
                            if (k % 2 == 0) c.drawRect(m, y - 11f, w - m, y + 4f, stripe)
                            val bold = l.cells[0] == "Summe"
                            c.drawText(l.cells[0].take(44), m + 6f, y, if (bold) p(9.5f, bold = true) else text)
                            c.drawText(l.cells[1], 350f, y, if (bold) p(9.5f, bold = true, align = Paint.Align.RIGHT) else right)
                            c.drawText(l.cells[2], 410f, y, right)
                            c.drawText(l.cells[3], 420f, y, text)
                            c.drawText(l.cells[4], 480f, y, small)
                        }
                    }
                    y += 15f
                }
                c.drawText("Scan-Portal · erstellt $stamp", m, h - 28f, small)
                c.drawText("Seite ${pi + 1} von ${pages.size}", w - m, h - 28f, p(8f, color = Color.DKGRAY, align = Paint.Align.RIGHT))
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
