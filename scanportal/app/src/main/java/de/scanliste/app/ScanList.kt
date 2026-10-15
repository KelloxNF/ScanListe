package de.scanliste.app

/** Eine Zeile der Tabelle: ein Wert pro Spalte, plus Zeitpunkt der Erfassung. */
data class ScanRow(val cells: MutableList<String>, var time: Long)

/** Ergebnis eines Genexis-Scans (QR-Code mit mehreren Werten, je zwei gehören zusammen). */
sealed class PairResult {
    /** [rows] neue Zeilen ab [firstRow]; [skipped] Paare standen schon vollständig in der Liste. */
    data class Added(val firstRow: Int, val rows: Int, val skipped: Int) : PairResult()
    /** Nichts übernommen; [message] erklärt warum, [row] zeigt ggf. auf die betroffene Zeile. */
    data class Rejected(val message: String, val row: Int? = null) : PairResult()
}

/** Was „Letzten Scan rückgängig“ entfernt hat. */
sealed class Undone {
    data class Rows(val count: Int) : Undone()
    data class Value(val value: String) : Undone()
}

sealed class AddResult {
    object Empty : AddResult()
    data class Duplicate(val row: Int, val col: Int) : AddResult()
    data class Added(val row: Int, val col: Int) : AddResult()
}

/**
 * Eine Scan-Liste mit frei benannten Spalten.
 *
 * Scans füllen die Tabelle zeilenweise nach unten: Bei einer Spalte erzeugt jeder Scan
 * eine neue Zeile. Bei mehreren Spalten wird die aktuelle Zeile von links nach rechts
 * gefüllt, danach geht es in der nächsten Zeile weiter.
 *
 * Ein Wert darf in der ganzen Liste nur einmal vorkommen (über alle Spalten hinweg).
 */
class ScanList(
    val id: String,
    var name: String,
    val columns: MutableList<String>,
    val withTimestamp: Boolean,
    val rows: MutableList<ScanRow> = mutableListOf(),
    val created: Long = System.currentTimeMillis(),
    var updated: Long = created,
    /**
     * Genexis-Scan: Ein QR-Code mit mehreren Werten (je Zeile ein Wert) wird in Paare zerlegt –
     * 1.+2. Wert, 3.+4. Wert … Jedes Paar kommt in eine eigene Zeile (1. Spalte / 2. Spalte).
     */
    var genexis: Boolean = false,
) {
    companion object {
        /** Teilt den Inhalt eines Scans in einzelne Werte (eine Zeile je Wert). */
        fun splitValues(raw: String): List<String> =
            raw.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
    }


    /** Alle vorhandenen Werte, für eine sofortige Duplikatprüfung auch bei sehr großen Listen. */
    private val values: HashSet<String> = HashSet<String>().also { set ->
        rows.forEach { row -> row.cells.forEach { if (it.isNotEmpty()) set.add(it) } }
    }

    /** Liefert die Position eines vorhandenen Werts oder null. */
    fun find(value: String): Pair<Int, Int>? {
        rows.forEachIndexed { r, row ->
            val c = row.cells.indexOf(value)
            if (c >= 0) return r to c
        }
        return null
    }

    /** Position (Zeile, Spalte), in die der nächste Scan geschrieben wird. */
    fun nextSlot(): Pair<Int, Int> {
        val last = rows.lastOrNull()
        if (last != null) {
            val free = last.cells.indexOfFirst { it.isEmpty() }
            if (free >= 0) return rows.size - 1 to free
        }
        return rows.size to 0
    }

    fun add(raw: String): AddResult {
        val value = raw.trim()
        if (value.isEmpty()) return AddResult.Empty
        if (value in values) {
            val (r, c) = find(value) ?: (0 to 0)
            return AddResult.Duplicate(r, c)
        }

        val now = System.currentTimeMillis()
        val (r, c) = nextSlot()
        if (r == rows.size) {
            rows.add(ScanRow(MutableList(columns.size) { "" }, now))
        }
        rows[r].cells[c] = value
        values.add(value)
        updated = now
        return AddResult.Added(r, c)
    }

    /**
     * Genexis-Scan: übernimmt [values] paarweise, jedes Paar in eine neue Zeile.
     * Alles oder nichts – bei einem Problem wird kein einziger Wert übernommen, damit kein Paar
     * auseinandergerissen wird. Paare, die schon vollständig in der Liste stehen, werden übersprungen.
     */
    fun addPairs(values: List<String>): PairResult {
        if (columns.size < 2) return PairResult.Rejected("Genexis-Scan braucht 2 Spalten.")
        if (values.size % 2 != 0) {
            return PairResult.Rejected("${values.size} Werte im QR-Code – ungerade Anzahl, Paare unvollständig. Nichts übernommen.")
        }
        values.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            return PairResult.Rejected("\"${it.key}\" steht mehrfach im QR-Code. Nichts übernommen.")
        }
        val last = rows.lastOrNull()
        if (last != null && last.cells.any { it.isNotEmpty() } && last.cells.take(2).any { it.isEmpty() }) {
            return PairResult.Rejected(
                "Zeile ${rows.size} ist unvollständig – erst den fehlenden Wert scannen oder rückgängig machen. Nichts übernommen.",
                rows.size - 1,
            )
        }
        val fresh = ArrayList<Pair<String, String>>()
        var skipped = 0
        for ((a, b) in values.chunked(2).map { it[0] to it[1] }) {
            val hasA = a in this.values
            val hasB = b in this.values
            when {
                hasA && hasB && find(a)?.let { (r, _) -> rows[r].cells.take(2) == listOf(a, b) } == true -> skipped++
                hasA || hasB -> {
                    val v = if (hasA) a else b
                    val r = find(v)?.first
                    return PairResult.Rejected(
                        "\"$v\" steht schon in Zeile ${(r ?: 0) + 1}, aber nicht mit demselben Partner. Nichts übernommen.",
                        r,
                    )
                }
                else -> fresh.add(a to b)
            }
        }
        val first = rows.size
        val now = System.currentTimeMillis()
        for ((a, b) in fresh) {
            val cells = MutableList(columns.size) { "" }
            cells[0] = a; cells[1] = b
            rows.add(ScanRow(cells, now))
            this.values.add(a); this.values.add(b)
        }
        if (fresh.isNotEmpty()) updated = now
        return PairResult.Added(first, fresh.size, skipped)
    }

    /** Entfernt die letzten [count] Zeilen (für „Rückgängig“ nach einem Genexis-Scan). */
    fun removeLastRows(count: Int): Int {
        var n = 0
        while (n < count && rows.isNotEmpty()) {
            rows.removeAt(rows.size - 1).cells.forEach { values.remove(it) }
            n++
        }
        if (n > 0) updated = System.currentTimeMillis()
        return n
    }

    /**
     * „Letzten Scan rückgängig“. [lastScanRows] = Zeilen des zuletzt gemerkten Scans
     * (0 = ein Einzelwert, > 0 = ein Genexis-QR-Code, null = nichts gemerkt, z. B. nach Neustart).
     * Im Genexis-Modus werden immer ganze Zeilen entfernt – nie ein halbes Paar.
     * null = Liste war leer.
     */
    fun undoScan(lastScanRows: Int?): Undone? {
        val rowsToRemove = when {
            lastScanRows != null && lastScanRows > 0 -> lastScanRows
            genexis -> 1
            else -> 0
        }
        if (rowsToRemove > 0) return removeLastRows(rowsToRemove).takeIf { it > 0 }?.let { Undone.Rows(it) }
        return undoLast()?.let { Undone.Value(it) }
    }

    /** Entfernt den zuletzt erfassten Wert. Gibt ihn zurück oder null, wenn die Liste leer ist. */
    fun undoLast(): String? {
        val last = rows.lastOrNull() ?: return null
        val i = last.cells.indexOfLast { it.isNotEmpty() }
        if (i < 0) {
            rows.removeAt(rows.size - 1)
            return undoLast()
        }
        val value = last.cells[i]
        last.cells[i] = ""
        values.remove(value)
        if (last.cells.all { it.isEmpty() }) rows.removeAt(rows.size - 1)
        updated = System.currentTimeMillis()
        return value
    }

    fun removeRow(index: Int) {
        if (index in rows.indices) {
            rows[index].cells.forEach { values.remove(it) }
            rows.removeAt(index)
            updated = System.currentTimeMillis()
        }
    }

    fun valueCount(): Int = values.size
}
