package de.scanliste.app

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV-Export im Format, das deutsches Excel direkt öffnet:
 * Semikolon als Trenner, UTF-8 mit BOM (für Umlaute), CRLF als Zeilenende.
 *
 * Die gescannten Werte werden als ="WERT" geschrieben. Excel behandelt sie dadurch als Text:
 * keine Darstellung wie 4,00638E+12, keine verlorenen führenden Nullen, keine Umwandlung in Datum.
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
            val cells = mutableListOf(esc((i + 1).toString()))
            cells += row.cells.map { asText(it) }
            if (list.withTimestamp) cells += esc(formatTime(row.time))
            sb.append(cells.joinToString(SEP)).append("\r\n")
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

    /** Excel erlaubt in einer Formel höchstens 255 Zeichen pro Text. */
    private const val MAX_TEXT_FORMULA = 255

    /**
     * Schreibt einen Wert so, dass Excel ihn als Text übernimmt: ="WERT".
     * Anführungszeichen im Wert werden verdoppelt, der Wert kann also nicht aus dem Text
     * ausbrechen (sicher gegen Formel-Einschleusung). Sehr lange Werte (z. B. große QR-Codes)
     * passen nicht in eine Excel-Formel und werden normal geschrieben.
     */
    private fun asText(raw: String): String {
        if (raw.isEmpty()) return ""
        val v = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
        if (v.length > MAX_TEXT_FORMULA) return esc(v)
        return quote("=\"" + v.replace("\"", "\"\"") + "\"")
    }

    private fun esc(raw: String): String = quote(defuse(raw))

    private fun quote(s: String): String {
        return if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' || it == ',' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }
}
