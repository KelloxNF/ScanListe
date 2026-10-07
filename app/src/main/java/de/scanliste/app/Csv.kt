package de.scanliste.app

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV-Export im Format, das deutsches Excel direkt öffnet:
 * Semikolon als Trenner, UTF-8 mit BOM (für Umlaute), CRLF als Zeilenende.
 */
object Csv {

    private const val SEP = ";"

    private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)

    fun formatTime(t: Long): String = if (t > 0) timeFormat.format(Date(t)) else ""

    fun build(list: ScanList): String {
        val sb = StringBuilder()
        val header = mutableListOf("Nr")
        header += list.columns
        if (list.withTimestamp) header += "Zeitstempel"
        sb.append(header.joinToString(SEP) { esc(it) }).append("\r\n")

        list.rows.forEachIndexed { i, row ->
            val line = mutableListOf((i + 1).toString())
            line += row.cells
            if (list.withTimestamp) line += formatTime(row.time)
            sb.append(line.joinToString(SEP) { esc(it) }).append("\r\n")
        }
        return sb.toString()
    }

    fun write(out: OutputStream, list: ScanList) {
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        out.write(build(list).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun fileName(list: ScanList): String {
        val safe = list.name.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß_-]+"), "_").trim('_').ifEmpty { "Liste" }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date())
        return "${safe}_$stamp.csv"
    }

    private val plainNumber = Regex("^[+-]?\\d+([.,]\\d+)?$")

    /**
     * Schutz vor Formel-Einschleusung: Excel würde Zellen, die mit = + - @ beginnen,
     * als Formel ausführen (z. B. aus einem manipulierten QR-Code). Solche Werte bekommen
     * ein vorangestelltes Hochkomma, damit Excel sie als Text anzeigt. Reine Zahlen bleiben unverändert.
     */
    private fun defuse(s: String): String {
        if (s.isEmpty()) return s
        val dangerous = s[0] in "=+-@\t\r" || s[0] == '＝' || s[0] == '＋' || s[0] == '－' || s[0] == '＠'
        return if (dangerous && !plainNumber.matches(s)) "'$s" else s
    }

    private fun esc(raw: String): String {
        val s = defuse(raw)
        return if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' || it == ',' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }
}
