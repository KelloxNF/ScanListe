package de.scanliste.app

/** Eine Zeile der Tabelle: ein Wert pro Spalte, plus Zeitpunkt der Erfassung. */
data class ScanRow(val cells: MutableList<String>, var time: Long)

/**
 * Formatprüfung einer Spalte: Der Wert muss mit einem der [prefixes] beginnen (Groß-/Kleinschreibung egal)
 * und – falls angegeben – genau [length] Zeichen lang sein. Leere Regel = alles erlaubt.
 */
data class ColumnRule(val prefixes: List<String> = emptyList(), val length: Int? = null) {
    val isEmpty get() = prefixes.isEmpty() && length == null

    fun accepts(v: String) =
        (prefixes.isEmpty() || prefixes.any { v.startsWith(it, ignoreCase = true) }) && (length == null || v.length == length)

    /** z. B. „beginnt mit GNXS, 12 Zeichen“. */
    fun describe(): String = listOfNotNull(
        prefixes.takeIf { it.isNotEmpty() }?.let { "beginnt mit ${it.joinToString(" oder ")}" },
        length?.let { "$it Zeichen" },
    ).joinToString(", ")

    companion object {
        /** Aus den Eingaben im Dialog: Präfixe mit Komma getrennt, Länge als Zahl (leer = egal). */
        fun parse(prefixes: String, length: String): ColumnRule? {
            val r = ColumnRule(
                prefixes.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
                length.trim().toIntOrNull()?.takeIf { it > 0 },
            )
            return r.takeIf { !it.isEmpty }
        }
    }
}

/** Ergebnis eines Multidata-QR-Codes (QR-Code mit mehreren Werten, je zwei gehören zusammen). */
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
    /** Abgelehnt durch Formatprüfung, Soll-Liste oder Zielmenge – nichts übernommen. */
    data class Rejected(val message: String) : AddResult()
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
     * Multidata-QR-Code: Ein QR-Code mit mehreren Werten (je Zeile ein Wert) wird in Paare zerlegt –
     * 1.+2. Wert, 3.+4. Wert … Jedes Paar kommt in eine eigene Zeile (1. Spalte / 2. Spalte).
     */
    var genexis: Boolean = false,
    /** Formatprüfung je Spalte (Index = Spalte), null = keine Prüfung. */
    val rules: MutableList<ColumnRule?> = MutableList(columns.size) { null },
    /** Soll-Liste: erwartete Werte in Spalte [expectedColumn]; leer = kein Abgleich. */
    val expected: LinkedHashSet<String> = LinkedHashSet(),
    var expectedColumn: Int = 0,
    /** Zielmenge in Zeilen (z. B. 40 Geräte je Palette); null = unbegrenzt. */
    var target: Int? = null,
    /** Abgeschlossen (z. B. Palette verpackt): nimmt keine Scans mehr an, bis sie wieder geöffnet wird. */
    var closed: Boolean = false,
) {
    companion object {
        const val CLOSED = "Liste ist abgeschlossen – nicht übernommen. Zum Weiterscannen ⋮ → „Liste wieder öffnen“."

        /** Teilt den Inhalt eines Scans in einzelne Werte (eine Zeile je Wert). */
        fun splitValues(raw: String): List<String> =
            raw.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
    }

    init {
        while (rules.size < columns.size) rules.add(null)
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

    // ------------------------------------------------------------ Prüfungen

    /** Zeilen, die vollständig gefüllt sind. */
    fun completeRows(): Int = rows.count { r -> r.cells.none { it.isEmpty() } }

    /** Zielmenge erreicht: so viele Zeilen wie gewünscht, alle vollständig. */
    fun targetReached(): Boolean = target?.let { completeRows() >= it } ?: false

    /** Soll-Werte, die noch nicht gescannt sind, in der Reihenfolge der Soll-Liste. */
    fun missing(): List<String> = expected.filter { it !in values }

    /** Wie viele Soll-Werte schon gescannt sind. */
    fun foundExpected(): Int = expected.count { it in values }

    /** Grund, warum [v] nicht in Spalte [col] darf – oder null, wenn alles passt. */
    private fun problem(v: String, col: Int): String? {
        rules.getOrNull(col)?.let { r ->
            if (!r.accepts(v)) return "\"$v\" passt nicht zu ${columns[col]} (${r.describe()})"
        }
        if (expected.isNotEmpty() && col == expectedColumn && v !in expected) {
            return "\"$v\" steht nicht in der Soll-Liste"
        }
        return null
    }

    // ------------------------------------------------------------ Erfassen

    fun add(raw: String): AddResult {
        val value = raw.trim()
        if (value.isEmpty()) return AddResult.Empty
        if (closed) return AddResult.Rejected(CLOSED)
        if (value in values) {
            val (r, c) = find(value) ?: (0 to 0)
            return AddResult.Duplicate(r, c)
        }
        val (r, c) = nextSlot()
        target?.let { t ->
            if (r >= t) return AddResult.Rejected("Zielmenge erreicht ($t von $t). Nicht übernommen.")
        }
        problem(value, c)?.let { return AddResult.Rejected("$it. Nicht übernommen.") }

        val now = System.currentTimeMillis()
        if (r == rows.size) {
            rows.add(ScanRow(MutableList(columns.size) { "" }, now))
        }
        rows[r].cells[c] = value
        values.add(value)
        updated = now
        return AddResult.Added(r, c)
    }

    /**
     * Multidata-QR-Code: übernimmt [values] paarweise, jedes Paar in eine neue Zeile.
     * Alles oder nichts – bei einem Problem wird kein einziger Wert übernommen, damit kein Paar
     * auseinandergerissen wird. Paare, die schon vollständig in der Liste stehen, werden übersprungen.
     */
    fun addPairs(values: List<String>): PairResult {
        if (closed) return PairResult.Rejected(CLOSED)
        if (columns.size < 2) return PairResult.Rejected("Multidata-QR-Code braucht 2 Spalten.")
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
                else -> {
                    pairProblem(a, b)?.let { return PairResult.Rejected("$it Nichts übernommen.") }
                    fresh.add(a to b)
                }
            }
        }
        target?.let { t ->
            val free = t - rows.size
            if (fresh.size > free) {
                return PairResult.Rejected(
                    if (free <= 0) "Zielmenge erreicht ($t von $t). Nichts übernommen."
                    else "Nur noch $free von $t frei, der QR-Code hat ${fresh.size} neue Paare. Nichts übernommen.",
                )
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

    /** Formatprüfung und Soll-Liste für ein Paar; erkennt vertauschte Reihenfolge. */
    private fun pairProblem(a: String, b: String): String? {
        val pa = problem(a, 0)
        val pb = problem(b, 1)
        if (pa == null && pb == null) return null
        val formatOnly = rules.take(2).any { it != null }
        if (formatOnly && rules[0]?.accepts(b) != false && rules[1]?.accepts(a) != false &&
            (rules[0]?.accepts(a) == false || rules[1]?.accepts(b) == false)
        ) {
            return "Reihenfolge im QR-Code vertauscht: \"$a\" / \"$b\"."
        }
        return "${pa ?: pb}."
    }

    // ------------------------------------------------------------ Zurücknehmen

    /** Entfernt die letzten [count] Zeilen (für „Rückgängig“ nach einem Multidata-QR-Code). */
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
     * Im Multidata-Modus werden immer ganze Zeilen entfernt – nie ein halbes Paar.
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
