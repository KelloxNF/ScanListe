package de.scanliste.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fundstelle eines Werts in einer anderen Liste. */
data class Seen(val listName: String, val row: Int, val time: Long)

/**
 * Doppelte Geräte über alle Listen: Index Wert → erste Fundstelle in einer *anderen* Liste.
 * Nur Warnung, keine Sperre – dasselbe Gerät darf z. B. in einer Eingangs- und einer Ausgangsliste stehen.
 */
object OtherLists {

    /** Werte einer Liste in kompakter Form (ohne Regeln, Soll-Liste usw.). */
    private class Values(val id: String, val created: Long, val hits: List<Pair<String, Seen>>)

    private class Cached(val modified: Long, val length: Long, val values: Values)

    /** Je Listendatei die zuletzt gelesenen Werte – unveränderte Dateien werden nicht erneut eingelesen. */
    private val cache = HashMap<String, Cached>()

    private fun valuesOf(l: ScanList): Values {
        val hits = ArrayList<Pair<String, Seen>>()
        l.rows.forEachIndexed { r, row ->
            for (v in row.cells) if (v.isNotEmpty()) hits += v to Seen(l.name, r, row.time)
        }
        return Values(l.id, l.created, hits)
    }

    /** Älteste Liste zuerst, damit bei mehreren Treffern die erste Erfassung genannt wird. */
    private fun merge(all: List<Values>): Map<String, Seen> {
        val map = HashMap<String, Seen>()
        for (l in all.sortedBy { it.created }) for ((v, s) in l.hits) if (v !in map) map[v] = s
        return map
    }

    /** Reine Funktion (testbar): alle Werte aller Listen außer [exceptId]. */
    fun index(lists: List<ScanList>, exceptId: String): Map<String, Seen> =
        merge(lists.filter { it.id != exceptId }.map(::valuesOf))

    /** Liest alle anderen Listen vom Gerät (im Hintergrund aufrufen); nur geänderte Dateien werden neu geparst. */
    fun load(ctx: Context, exceptId: String): Map<String, Seen> = synchronized(cache) {
        val present = HashSet<String>()
        val all = ListStore.files(ctx).mapNotNull { f ->
            val id = f.nameWithoutExtension
            present += id
            if (id == exceptId) return@mapNotNull null
            val modified = f.lastModified()
            val length = f.length()
            cache[id]?.takeIf { it.modified == modified && it.length == length }?.values
                ?: ListStore.loadFile(f)?.let(::valuesOf)?.also { cache[id] = Cached(modified, length, it) }
        }
        cache.keys.retainAll(present)   // gelöschte Listen vergessen
        merge(all)
    }

    /** Treffer für die neu übernommenen [values]; null, wenn keiner in einer anderen Liste steht. */
    fun warning(index: Map<String, Seen>, values: List<String>): String? {
        val hits = values.mapNotNull { v -> index[v]?.let { v to it } }
        if (hits.isEmpty()) return null
        val (v, s) = hits.first()
        val day = SimpleDateFormat("dd.MM.", Locale.GERMANY).format(Date(s.time))
        val more = if (hits.size > 1) " (+${hits.size - 1} weitere)" else ""
        return "⚠ Übernommen, aber Achtung: \"$v\" steht auch in Liste ‚${s.listName}‘ (Zeile ${s.row + 1}, $day)$more"
    }
}
