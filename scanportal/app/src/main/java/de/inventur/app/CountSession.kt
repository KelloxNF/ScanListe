package de.inventur.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status einer Kachel in der Zähl-Ansicht (bestimmt Farbe und Filter). */
enum class StateKey(val label: String) { OPEN("Nicht gezählt"), OK("Stimmt"), BAD("Differenz"), EST("Geschätzt") }

/**
 * Zähl-Logik ohne Oberfläche: gewählter Bereich, feste Menge je Scan, „Rückgängig“ und alle
 * Änderungen an einem Artikel. Jede Änderung wird sofort mit [save] gespeichert; wirft [save]
 * (z. B. Speicher voll), liefert die Funktion false.
 */
class CountSession(
    inv: Inventory,
    val zones: List<String>,
    private val save: (Inventory) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var inv: Inventory = inv
        private set

    var zone: String = zones.first()

    // Feste Menge je Scan – gilt für die ganze Zähl-Sitzung, über Artikel hinweg
    var fixedOn = false
    var fixedN = 1
    fun fixedAmount(): Int? = if (fixedOn && fixedN > 0) fixedN else null

    /** Was in dieser Sitzung per Scan/Menge hinzugefügt wurde – nur das macht „Rückgängig“ wieder weg. */
    private data class Added(val sku: String, val zone: String, val qty: Int)
    private val undoStack = ArrayDeque<Added>()

    /** Nach Rückkehr aus Kopfdaten/Ergebnis den gespeicherten Stand übernehmen. */
    fun reload(fresh: Inventory) { inv = fresh }

    fun state(a: Article): StateKey {
        val ist = inv.counted(a.sku) ?: return StateKey.OPEN
        if (a.sku in inv.estimated) return StateKey.EST
        if (inv.blind) return StateKey.OK
        return if (ist == a.soll) StateKey.OK else StateKey.BAD
    }

    /** Bei Blindzählung bedeutet grün nur „gezählt“ – nicht „stimmt“ (der Soll ist ja unbekannt). */
    fun label(k: StateKey) = if (inv.blind && k == StateKey.OK) "Gezählt" else k.label

    /** Bereiche mit Menge, z. B. „Versand: 3“. */
    fun zoneLines(sku: String): List<String> = zones.mapNotNull { z -> inv.zoneQty(sku, z)?.let { "$z: $it" } }

    private fun persist(): Boolean = try { save(inv); true } catch (e: Exception) { false }

    /** Zählt [n] im aktuellen Bereich dazu (negativ = abziehen). Bei Speicherfehler bleibt alles wie vorher. */
    fun add(sku: String, n: Int): Boolean {
        val now = clock()
        val hhmm = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(now))
        val prevFrom = inv.timeFrom; val prevTo = inv.timeTo; val prevClosed = inv.closed
        inv.addCount(CountEntry(sku, zone, n, now))
        if (inv.timeFrom.isBlank()) inv.timeFrom = hhmm
        inv.timeTo = hhmm
        inv.closed = false
        if (!persist()) {
            inv.retract(sku, zone, n)
            inv.timeFrom = prevFrom; inv.timeTo = prevTo; inv.closed = prevClosed
            return false
        }
        undoStack.addLast(Added(sku, zone, n))
        if (undoStack.size > 500) undoStack.removeFirst()
        return true
    }

    sealed class UndoResult {
        object Nothing : UndoResult()
        object NotSaved : UndoResult()
        data class Done(val name: String, val qty: Int, val zone: String) : UndoResult()
    }

    /** Nimmt den letzten Scan/die letzte Menge dieser Sitzung zurück – Korrekturen bleiben unangetastet. */
    fun undo(): UndoResult {
        while (true) {
            val last = undoStack.removeLastOrNull() ?: return UndoResult.Nothing
            if (!inv.retract(last.sku, last.zone, last.qty)) continue  // inzwischen korrigiert
            if (!persist()) {
                inv.addCount(CountEntry(last.sku, last.zone, last.qty, clock()))
                undoStack.addLast(last)
                return UndoResult.NotSaved
            }
            return UndoResult.Done(inv.article(last.sku)?.name ?: last.sku, last.qty, last.zone)
        }
    }

    /** „Bestand stimmt“: Soll in den aktuellen Bereich, alle anderen Bereiche leeren, keine Schätzung. */
    fun bestandStimmt(a: Article): Boolean {
        for (z in zones) inv.setZone(a.sku, z, if (z == zone) a.soll else null)
        inv.estimated.remove(a.sku)   // „Bestand stimmt“ ist eine genaue Zählung, keine Schätzung
        undoStack.removeAll { it.sku == a.sku }
        inv.closed = false
        return persist()
    }

    /** Korrektur: setzt die Mengen je Bereich (null = nicht gezählt). Nur geänderte Bereiche werden angefasst. */
    fun setZones(sku: String, values: Map<String, Int?>): Boolean {
        for ((z, v) in values) {
            if (v != inv.zoneQty(sku, z)) {
                inv.setZone(sku, z, v)
                undoStack.removeAll { it.sku == sku && it.zone == z }
            }
        }
        inv.closed = false
        return persist()
    }

    fun toggleEstimated(sku: String): Boolean {
        if (sku in inv.estimated) inv.estimated.remove(sku) else inv.estimated.add(sku)
        return persist()
    }

    fun setNote(sku: String, text: String): Boolean {
        val v = text.trim()
        if (v.isEmpty()) inv.notes.remove(sku) else inv.notes[sku] = v
        return persist()
    }

    fun rename(sku: String, name: String): Boolean {
        inv.rename(sku, name)
        return persist()
    }

    /** Entfernt den Artikel aus dieser Inventur. Bei Speicherfehler wird er vollständig wiederhergestellt. */
    fun delete(sku: String): Boolean {
        val pos = inv.articles.indexOfFirst { it.sku == sku }
        val article = inv.articles.getOrNull(pos)
        val counts = inv.counts.toList()
        val wasEstimated = sku in inv.estimated
        val note = inv.notes[sku]
        inv.removeArticle(sku)
        undoStack.removeAll { it.sku == sku }
        if (persist()) return true
        if (article != null) inv.articles.add(pos, article)
        inv.replaceCounts(counts)
        if (wasEstimated) inv.estimated.add(sku)
        if (note != null) inv.notes[sku] = note
        return false
    }
}
