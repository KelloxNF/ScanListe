package de.scanliste.app

import de.scanportal.app.ExcelCsv

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV-Export im Format, das deutsches Excel direkt öffnet:
 * Semikolon als Trenner, UTF-8 mit BOM (für Umlaute), CRLF als Zeilenende.
 */
object Csv {

    private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)

    fun formatTime(t: Long): String = if (t > 0) timeFormat.format(Date(t)) else ""

    fun build(list: ScanList): String {
        val sb = StringBuilder()
        val header = mutableListOf("Nr")
        header += list.columns
        if (list.withTimestamp) header += "Zeitstempel"
        sb.append(ExcelCsv.line(header))

        list.rows.forEachIndexed { i, row ->
            val line = mutableListOf((i + 1).toString())
            line += row.cells
            if (list.withTimestamp) line += formatTime(row.time)
            sb.append(ExcelCsv.line(line))
        }
        return sb.toString()
    }

    fun write(out: OutputStream, list: ScanList) = ExcelCsv.write(out, build(list))

    /** Einspaltige CSV, z. B. die noch fehlenden Werte einer Soll-Liste. */
    fun writeValues(out: OutputStream, header: String, values: List<String>) =
        ExcelCsv.write(out, buildString {
            append(ExcelCsv.line(listOf(header)))
            values.forEach { append(ExcelCsv.line(listOf(it))) }
        })

    fun fileName(list: ScanList): String {
        val safe = list.name.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß_-]+"), "_").trim('_').ifEmpty { "Liste" }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date())
        return "${safe}_$stamp.csv"
    }
}
