package de.inventur.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ein Artikel aus dem Systembestand (WMS-Export). */
data class Article(
    val sku: String,
    val name: String,
    val soll: Int,
    val technik: Int,
) {
    /** Refware erkennt man an der Endung "Ref" der Artikelnummer. */
    val isRef: Boolean get() = sku.trim().endsWith("Ref")

    /** Artikelnummer ohne "Ref" – verbindet Neu- und Refware zu einer Zeile im Anschreiben. */
    val baseSku: String get() = sku.trim().let { if (isRef) it.dropLast(3) else it }

    /** Artikelname ohne " Ref". */
    val baseName: String get() = name.trim().removeSuffix(" Ref").removeSuffix(" ref").trim()
}

/** Eine einzelne Zählung: Menge eines Artikels in einem Zählbereich. */
data class CountEntry(
    val sku: String,
    val zone: String,
    val qty: Int,
    val time: Long,
)

data class Customer(
    val name: String,
    val street: String,
    val city: String,
)

enum class Status(val label: String) {
    STIMMT("Stimmt"),
    DIFFERENZ("Differenz"),
    GESCHAETZT("Geschätzt"),
    NICHT_GEZAEHLT("Nicht gezählt"),
}

data class Summary(
    val articles: Int,
    val ok: Int,
    val diff: Int,
    val estimated: Int,
    val notCounted: Int,
    val plus: Int,
    val minus: Int,
) {
    val overall: String
        get() = when {
            articles == 0 -> "Kein Systembestand"
            notCounted > 0 -> "Offen"
            diff > 0 -> "Differenz"
            else -> "Ohne Differenz"
        }
}

/** Eine Zeile im Anschreiben: volle Artikelbezeichnung und gezählte Menge. */
data class LetterRow(val name: String, val qty: Int)

class Inventory(
    val id: String,
    var customer: Customer,
    var stichtag: String,          // yyyy-MM-dd
    var letterDate: String,        // yyyy-MM-dd
    var counter: String,
    var writer: String,
    var place: String,
    var timeFrom: String,          // HH:mm
    var timeTo: String,            // HH:mm
    var blind: Boolean,
    var closed: Boolean,
    val articles: MutableList<Article>,
    counts: MutableList<CountEntry>,
    val estimated: MutableSet<String>,
    val notes: MutableMap<String, String>,
    var created: Long,
    var updated: Long,
) {
    fun article(sku: String): Article? = articles.firstOrNull { it.sku == sku }

    fun findBySku(value: String): Article? =
        articles.firstOrNull { it.sku.trim().equals(value.trim(), ignoreCase = true) }

    // ---- Zählungen: nur über die Methoden unten ändern, damit der Index stimmt ----

    private val _counts: MutableList<CountEntry> = counts

    /** Alle Zählungen in Erfassungsreihenfolge (nur lesen). */
    val counts: List<CountEntry> get() = _counts

    /** Summen je Artikel und Bereich – wird bei jeder Änderung neu aufgebaut statt bei jeder Abfrage. */
    private var index: Map<String, Map<String, Int>>? = null

    private fun idx(): Map<String, Map<String, Int>> = index ?: HashMap<String, HashMap<String, Int>>().also { m ->
        for (c in _counts) {
            val z = m.getOrPut(c.sku) { HashMap() }
            z[c.zone] = (z[c.zone] ?: 0) + c.qty
        }
        index = m
    }

    /**
     * Fügt eine Zählung hinzu. Folgt sie direkt auf eine Zählung desselben Artikels im selben
     * Bereich, wird die Menge dort aufaddiert – so bleibt die Datei auch bei tausenden
     * Einzelscans („Jeder Scan = 1 Stück“) klein und das Speichern schnell.
     */
    fun addCount(e: CountEntry) {
        val last = _counts.lastOrNull()
        if (last != null && last.sku == e.sku && last.zone == e.zone && last.qty > 0 && e.qty > 0) {
            _counts[_counts.size - 1] = last.copy(qty = last.qty + e.qty, time = e.time)
        } else {
            _counts.add(e)
        }
        index = null
    }

    /**
     * Nimmt eine zuvor hinzugefügte Menge wieder zurück (für „Rückgängig“): zieht sie von der
     * jüngsten Zählung dieses Artikels in diesem Bereich ab. Gibt false zurück, wenn dort nichts mehr steht.
     */
    fun retract(sku: String, zone: String, qty: Int): Boolean {
        val i = _counts.indexOfLast { it.sku == sku && it.zone == zone }
        if (i < 0) return false
        val e = _counts[i]
        if (e.qty == qty) _counts.removeAt(i) else _counts[i] = e.copy(qty = e.qty - qty)
        index = null
        return true
    }

    fun removeCount(e: CountEntry): Boolean = _counts.remove(e).also { index = null }

    /** Entfernt die zuletzt erfasste Zählung und gibt sie zurück. */
    fun removeLastCount(): CountEntry? {
        val last = _counts.maxByOrNull { it.time } ?: return null
        _counts.remove(last)
        index = null
        return last
    }

    fun replaceCounts(list: List<CountEntry>) { _counts.clear(); _counts.addAll(list); index = null }

    /** Bereiche, in denen gezählt wurde (z. B. alte Namen nach Umbenennung in den Einstellungen). */
    fun countedZones(): List<String> = _counts.map { it.zone }.distinct()

    fun hasCount(sku: String) = idx().containsKey(sku)

    /** Gezählte Gesamtmenge oder null, wenn der Artikel noch nicht gezählt wurde. */
    fun counted(sku: String): Int? = idx()[sku]?.values?.sum()

    fun zoneQty(sku: String, zone: String): Int? = idx()[sku]?.get(zone)

    /** Artikel, die auf die Zählliste gehören: Bestand > 0 oder bereits etwas gezählt. */
    fun listArticles(): List<Article> = articles.filter { it.soll > 0 || hasCount(it.sku) }

    fun status(a: Article): Status {
        val ist = counted(a.sku) ?: return Status.NICHT_GEZAEHLT
        if (a.sku in estimated) return Status.GESCHAETZT
        return if (ist == a.soll) Status.STIMMT else Status.DIFFERENZ
    }

    fun summary(): Summary {
        val list = listArticles()
        var ok = 0; var diff = 0; var est = 0; var nc = 0; var plus = 0; var minus = 0
        for (a in list) {
            when (status(a)) {
                Status.STIMMT -> ok++
                Status.DIFFERENZ -> diff++
                Status.GESCHAETZT -> est++
                Status.NICHT_GEZAEHLT -> nc++
            }
            val ist = counted(a.sku) ?: continue
            val d = ist - a.soll
            if (d > 0) plus += d else minus -= d
        }
        return Summary(list.size, ok, diff, est, nc, plus, minus)
    }

    /**
     * Zeilen fürs Anschreiben: je Artikel der Zählliste eine Zeile mit voller Bezeichnung
     * (wie in der App, also auch mit „Ref“) und gezählter Menge, in der Reihenfolge des Systembestands.
     */
    fun letterRows(): List<LetterRow> = listArticles().map { LetterRow(it.name, counted(it.sku) ?: 0) }

    /** Ändert nur die Bezeichnung eines Artikels, die Artikelnummer bleibt. */
    fun rename(sku: String, newName: String) {
        val i = articles.indexOfFirst { it.sku == sku }
        if (i >= 0 && newName.isNotBlank()) articles[i] = articles[i].copy(name = newName.trim())
    }

    /** Setzt die Menge eines Artikels in einem Bereich auf einen festen Wert (Korrektur). */
    fun setZone(sku: String, zone: String, qty: Int?) {
        _counts.removeAll { it.sku == sku && it.zone == zone }
        if (qty != null) _counts.add(CountEntry(sku, zone, qty, System.currentTimeMillis()))
        index = null
    }

    fun title(): String = "${customer.name} – ${Dates.display(stichtag)}"
}

object Dates {
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE
    private val de = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
    private val long = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMANY)

    fun today(): String = LocalDate.now().format(iso)
    fun parse(s: String): LocalDate? = runCatching { LocalDate.parse(s, iso) }.getOrNull()
    fun display(s: String): String = parse(s)?.format(de) ?: s
    fun long(s: String): String = parse(s)?.format(long) ?: s
    fun year(s: String): String = parse(s)?.year?.toString() ?: ""
}
