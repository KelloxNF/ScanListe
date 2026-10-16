package de.scanliste.app

/**
 * Inhalt der QR-Codes auf der Packliste. Erste Zeile ist der Kopf, danach eine Zeile je Gerät,
 * Spalten durch Tabulator getrennt:
 * ```
 * SCANPORTAL-PACKLISTE<TAB>1/2<TAB>90<TAB>Palette 3
 * GNXS05DFA614<TAB>Z.0225460513
 * ```
 * Große Listen werden auf mehrere Codes verteilt, damit jeder Code gut lesbar bleibt.
 */
object PackCode {
    const val MAGIC = "SCANPORTAL-PACKLISTE"

    /** Höchstens so viele Bytes je QR-Code – hält die Module groß genug für den Scanner. */
    const val MAX_BYTES = 700

    data class Part(val title: String, val part: Int, val parts: Int, val total: Int, val rows: List<List<String>>)

    fun isPackCode(raw: String) = raw.trimStart().startsWith(MAGIC)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun header(part: Int, parts: Int, total: Int, title: String) = "$MAGIC\t$part/$parts\t$total\t${clean(title)}"

    /** Teilt die Zeilen auf Codes mit höchstens [maxBytes] Bytes auf (mindestens eine Zeile je Code). */
    fun encode(title: String, rows: List<List<String>>, maxBytes: Int = MAX_BYTES): List<String> {
        val lines = rows.map { r -> r.joinToString("\t") { clean(it) } }
        val head = header(999, 999, rows.size, title).toByteArray().size + 1
        val chunks = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var size = head
        for (l in lines) {
            val b = l.toByteArray().size + 1
            if (cur.isNotEmpty() && size + b > maxBytes) { chunks += cur; cur = ArrayList(); size = head }
            cur += l; size += b
        }
        if (cur.isNotEmpty() || chunks.isEmpty()) chunks += cur
        return chunks.mapIndexed { i, c -> (listOf(header(i + 1, chunks.size, rows.size, title)) + c).joinToString("\n") }
    }

    /** Liest einen gescannten Code; null, wenn es kein gültiger Packlisten-Code ist. */
    fun decode(raw: String): Part? {
        val lines = raw.split('\n', '\r').filter { it.isNotBlank() }
        val head = lines.firstOrNull()?.trim()?.split('\t') ?: return null
        if (head.size < 3 || head[0] != MAGIC) return null
        val (p, n) = head[1].split('/').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        val rows = lines.drop(1).map { l -> l.split('\t').map { it.trim() } }.filter { r -> r.any { it.isNotEmpty() } }
        return Part(head.getOrElse(3) { "" }, p, n, head[2].toIntOrNull() ?: rows.size, rows)
    }
}
