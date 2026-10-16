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

    /** Reine Funktion (testbar): alle Werte aller Listen außer [exceptId]. Ältester Fund gewinnt. */
    fun index(lists: List<ScanList>, exceptId: String): Map<String, Seen> {
        val map = HashMap<String, Seen>()
        for (l in lists.sortedBy { it.created }) {
            if (l.id == exceptId) continue
            l.rows.forEachIndexed { r, row ->
                for (v in row.cells) if (v.isNotEmpty() && v !in map) map[v] = Seen(l.name, r, row.time)
            }
        }
        return map
    }

    /** Lädt alle anderen Listen vom Gerät (im Hintergrund aufrufen). */
    fun load(ctx: Context, exceptId: String): Map<String, Seen> =
        index(ListStore.infos(ctx).filter { it.id != exceptId }.mapNotNull { ListStore.load(ctx, it.id) }, exceptId)

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
