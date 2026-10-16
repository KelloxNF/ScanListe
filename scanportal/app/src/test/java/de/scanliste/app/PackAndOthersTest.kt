package de.scanliste.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doppelte Geräte über alle Listen und Seitenaufteilung der Packliste. */
class PackAndOthersTest {
    private fun list(id: String, name: String, created: Long, vararg values: String) =
        ScanList(id, name, mutableListOf("SN"), withTimestamp = false, created = created).apply { values.forEach { add(it) } }

    @Test fun indexSkipsCurrentListAndKeepsOldestHit() {
        val a = list("a", "Palette 3", 1_000, "GNXS05DFA614", "X1")
        val b = list("b", "Palette 7", 2_000, "GNXS05DFA614")
        val cur = list("c", "Aktuell", 3_000, "X1")
        val idx = OtherLists.index(listOf(b, cur, a), "c")
        assertEquals("Palette 3", idx["GNXS05DFA614"]!!.listName)
        assertEquals(1, idx["X1"]!!.row)
        assertEquals(setOf("GNXS05DFA614", "X1"), idx.keys)   // nichts aus der aktuellen Liste
        assertNull(OtherLists.index(listOf(cur), "c")["X1"])
    }

    @Test fun warningNamesListRowAndCount() {
        val idx = OtherLists.index(listOf(list("a", "Palette 3", 1_000, "A", "B", "C")), "x")
        assertNull(OtherLists.warning(idx, listOf("Z")))
        val w = OtherLists.warning(idx, listOf("Z", "B", "C"))!!
        assertTrue(w, w.contains("\"B\" steht auch in Liste ‚Palette 3‘ (Zeile 2"))
        assertTrue(w, w.contains("+1 weitere"))
    }

    @Test fun paginationCoversAllRowsAndLeavesRoomForSignatures() {
        val (first, other) = PackList.capacity()
        assertEquals(listOf(0 to 0), PackList.paginate(0))
        assertEquals(listOf(0 to 10), PackList.paginate(10))
        // erste Seite voll → Unterschriften auf eigener Seite
        assertEquals(listOf(0 to first, first to 0), PackList.paginate(first))
        for (n in listOf(1, first - 1, first + 1, first + other, 500)) {
            val pages = PackList.paginate(n)
            var next = 0
            pages.forEachIndexed { i, (start, count) ->
                assertEquals(next, start)
                assertTrue(count <= if (i == 0) first else other)
                next += count
            }
            assertEquals(n, next)
        }
    }

    @Test fun packSheetIsACopyWithoutEmptyRows() {
        val l = ScanList("p", "P", mutableListOf("SN", "PON"), withTimestamp = false)
        l.add("A"); l.add("B"); l.add("C")
        l.rows.add(ScanRow(mutableListOf("", ""), 0))
        val sheet = PackSheet.of(l)
        assertEquals(listOf(listOf("A", "B"), listOf("C", "")), sheet.rows)
        l.rows[0].cells[0] = "geändert"
        assertEquals("A", sheet.rows[0][0])   // spätere Scans ändern die Kopie nicht
    }

    @Test fun packFileNameIsSafe() {
        assertTrue(ListTools.packFileName("Palette 3 / Kunde").matches(Regex("Packliste_Palette_3_Kunde_\\d{4}-\\d{2}-\\d{2}\\.pdf")))
    }
}
