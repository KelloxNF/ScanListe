package de.inventur.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/** Ergebnis des Imports: die Artikel und ein Hinweistext für den Benutzer. */
data class ImportResult(val articles: List<Article>, val info: String)

/**
 * Liest den Systembestand aus einer Excel-Datei (.xlsx) oder CSV-Datei.
 * Erwartete Spalten (Kopfzeile, Reihenfolge egal): Artikelnummer, Artikel, Bestand, Bestand in Technik.
 * Ohne erkennbare Kopfzeile gilt: Spalte A = Artikelnummer, B = Artikel, C = Bestand, D = Technik.
 */
object Importer {

    fun read(bytes: ByteArray): ImportResult {
        val rows = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            Xlsx.firstSheet(bytes)
        } else {
            CsvReader.parse(decode(bytes))
        }
        return toArticles(rows)
    }

    fun toArticles(rows: List<List<String>>): ImportResult {
        val nonEmpty = rows.filter { r -> r.any { it.isNotBlank() } }
        if (nonEmpty.isEmpty()) throw IllegalArgumentException("Die Datei ist leer.")

        var headerIdx = -1
        var cSku = -1; var cName = -1; var cSoll = -1; var cTech = -1
        for ((i, r) in nonEmpty.take(15).withIndex()) {
            val h = r.map { norm(it) }
            val sku = h.indexOfFirst { it.contains("artikelnummer") || it.contains("artikelnr") || it == "artnr" || it.contains("art-nr") || it == "sku" || it.contains("materialnummer") }
            if (sku >= 0) {
                headerIdx = i
                cSku = sku
                cTech = h.indexOfFirst { it.contains("technik") }
                cSoll = h.indices.firstOrNull { j -> j != cTech && (h[j].contains("bestand") || h[j] == "menge" || h[j].contains("lagerbestand")) } ?: -1
                cName = h.indices.firstOrNull { j -> j != cSku && (h[j] == "artikel" || h[j].contains("bezeichnung") || h[j].contains("artikelname") || h[j] == "modell" || h[j] == "name") } ?: -1
                break
            }
        }
        val data: List<List<String>>
        if (headerIdx < 0) {
            cSku = 0; cName = 1; cSoll = 2; cTech = 3
            data = nonEmpty
        } else {
            if (cName < 0) cName = if (cSku + 1 != cSoll && cSku + 1 != cTech) cSku + 1 else -1
            if (cSoll < 0) throw IllegalArgumentException("Keine Spalte „Bestand“ gefunden.")
            data = nonEmpty.drop(headerIdx + 1)
        }

        val seen = HashSet<String>()
        val out = ArrayList<Article>()
        var skipped = 0
        for (r in data) {
            val sku = r.getOrNull(cSku)?.trim().orEmpty()
            if (sku.isEmpty()) { skipped++; continue }
            if (!seen.add(sku)) { skipped++; continue }
            val name = (if (cName >= 0) r.getOrNull(cName) else null)?.trim().orEmpty().ifEmpty { sku }
            val soll = number(r.getOrNull(cSoll))
            val tech = if (cTech >= 0) number(r.getOrNull(cTech)) else 0
            if (soll == null && headerIdx < 0 && out.isEmpty()) { skipped++; continue } // vermutlich Kopfzeile
            out.add(Article(sku, name, soll ?: 0, tech ?: 0))
        }
        if (out.isEmpty()) throw IllegalArgumentException("Keine Artikel gefunden.")
        val withStock = out.count { it.soll > 0 }
        val info = buildString {
            append("${out.size} Artikel importiert, davon $withStock mit Bestand")
            if (skipped > 0) append(" ($skipped Zeilen übersprungen)")
            if (headerIdx < 0) append(". Keine Kopfzeile erkannt – Spalten A–D verwendet")
            append(".")
        }
        return ImportResult(out, info)
    }

    private fun norm(s: String) = s.trim().lowercase().replace(".", "").replace(" ", "").replace("_", "")

    /** "1.234" → 1234, "12,0" → 12, "49" → 49, "" → null. */
    fun number(raw: String?): Int? {
        var s = raw?.trim()?.replace(" ", "")?.replace(" ", "") ?: return null
        if (s.isEmpty()) return null
        if (Regex("^-?\\d{1,3}(\\.\\d{3})+(,\\d+)?$").matches(s)) s = s.replace(".", "")
        s = s.replace(",", ".")
        return s.toDoubleOrNull()?.roundToInt()
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('�')) String(bytes, Charset.forName("windows-1252")) else utf8
    }
}

/** Einfacher CSV-Leser: erkennt Semikolon, Komma oder Tab und Anführungszeichen. */
object CsvReader {
    fun parse(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> firstLine.count { it == c } } ?: ';'
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                sep -> { row.add(cell.toString()); cell.clear() }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList() }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }
}

/** Minimaler .xlsx-Leser (erstes Tabellenblatt, nur Werte) ohne externe Bibliothek. */
object Xlsx {

    fun firstSheet(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name.trimStart('/')
                if (n == "xl/workbook.xml" || n == "xl/_rels/workbook.xml.rels" || n == "xl/sharedStrings.xml" ||
                    n.startsWith("xl/worksheets/sheet")
                ) files[n] = zip.readBytes()
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val sheetPath = firstSheetPath(files) ?: files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
            ?: throw IllegalArgumentException("Kein Tabellenblatt in der Excel-Datei gefunden.")
        val sheet = files[sheetPath] ?: throw IllegalArgumentException("Tabellenblatt nicht lesbar.")
        return readSheet(sheet, shared)
    }

    private fun parser(b: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(b), "UTF-8")
    }

    private fun local(name: String?) = name?.substringAfter(':') ?: ""

    private fun firstSheetPath(files: Map<String, ByteArray>): String? {
        val wb = files["xl/workbook.xml"] ?: return null
        val rels = files["xl/_rels/workbook.xml.rels"] ?: return null
        var rid: String? = null
        val p = parser(wb)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "sheet") {
                for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == "id") rid = p.getAttributeValue(i)
                break
            }
        }
        rid ?: return null
        val r = parser(rels)
        while (r.next() != XmlPullParser.END_DOCUMENT) {
            if (r.eventType == XmlPullParser.START_TAG && local(r.name) == "Relationship" && r.getAttributeValue(null, "Id") == rid) {
                val t = r.getAttributeValue(null, "Target") ?: return null
                return if (t.startsWith("/")) t.trimStart('/') else "xl/" + t.removePrefix("./")
            }
        }
        return null
    }

    private fun sharedStrings(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = parser(b)
        val sb = StringBuilder()
        var inSi = false; var inT = false; var inPh = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "si" -> { inSi = true; sb.clear() }
                    "t" -> inT = true
                    "rPh" -> inPh = true
                }
                XmlPullParser.TEXT -> if (inSi && inT && !inPh) sb.append(p.text)
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "si" -> { inSi = false; out.add(sb.toString()) }
                    "t" -> inT = false
                    "rPh" -> inPh = false
                }
            }
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return n - 1
    }

    private fun readSheet(b: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row: MutableMap<Int, String>? = null
        var col = 0; var type = ""; var nextCol = 0
        val value = StringBuilder()
        var inV = false; var inT = false
        val p = parser(b)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "row" -> { row = HashMap(); nextCol = 0 }
                    "c" -> {
                        val r = p.getAttributeValue(null, "r")
                        col = if (r != null) colIndex(r) else nextCol
                        nextCol = col + 1
                        type = p.getAttributeValue(null, "t") ?: ""
                        value.clear()
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "inlineStr", "str" -> raw
                            "b" -> if (raw == "1") "WAHR" else "FALSCH"
                            "e" -> ""
                            else -> raw.toDoubleOrNull()?.let { d ->
                                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else raw
                            } ?: raw
                        }
                        row?.put(col, text)
                    }
                    "row" -> {
                        val r = row
                        if (r != null) {
                            val max = r.keys.maxOrNull() ?: -1
                            rows.add(List(max + 1) { r[it] ?: "" })
                        }
                        row = null
                    }
                }
            }
        }
        return rows
    }
}
