package de.scanliste.app

/** Eine Zeile der Tabelle: ein Wert pro Spalte, plus Zeitpunkt der Erfassung. */
data class ScanRow(val cells: MutableList<String>, var time: Long)

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
) {

    /** Alle vorhandenen Werte, für eine sofortige Duplikatprüfung auch bei sehr großen Listen. */
    private val values: HashSet<String> = HashSet<String>().also { set ->
        rows.forEach { row -> row.cells.forEach { if (it.isNotEmpty()) set.add(it) } }
    }

    fun contains(value: String): Boolean = value in values

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
