package de.scanliste.app

import de.inventur.app.Article
import de.inventur.app.CountEntry
import de.inventur.app.Customer
import de.inventur.app.Inventory
import de.inventur.app.InventoryStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Alles, was gespeichert wird, muss nach dem Laden genau so wieder da sein. */
class RoundTripTest {
    private fun <T> roundTrip(o: JSONObject, read: (JSONObject) -> T): T = read(JSONObject(o.toString()))

    @Test fun scanListKeepsEverything() {
        val l = ScanList("id1", "Palette 7", mutableListOf("Seriennummer", "PON-SN"), withTimestamp = true, genexis = true, target = 40)
        l.rules[0] = ColumnRule(listOf("GNXS", "GNXT"), 12)
        l.rules[1] = ColumnRule(listOf("Z."))
        l.expected.addAll(listOf("GNXS05DFA614", "GNXS05DFA61A"))
        l.expectedColumn = 0
        l.addPairs(listOf("GNXS05DFA614", "Z.0225460513"))
        l.add("GNXS05DFA61A")   // halbe Zeile
        val r = roundTrip(ListStore.toJson(l), ListStore::fromJson)
        assertEquals(l.name, r.name)
        assertEquals(l.columns, r.columns)
        assertEquals(true, r.genexis)
        assertEquals(40, r.target)
        assertEquals(l.rules, r.rules)
        assertEquals(l.expected.toList(), r.expected.toList())
        assertEquals(0, r.expectedColumn)
        assertEquals(l.rows.map { it.cells }, r.rows.map { it.cells })
        assertEquals(l.rows.map { it.time }, r.rows.map { it.time })
        assertEquals(3, r.valueCount())
        assertEquals(listOf<String>(), r.missing())
    }

    @Test fun plainListDefaults() {
        val l = ScanList("id2", "Einfach", mutableListOf("SN"), withTimestamp = false)
        l.add("A1")
        val r = roundTrip(ListStore.toJson(l), ListStore::fromJson)
        assertEquals(false, r.genexis)
        assertNull(r.target)
        assertEquals(listOf<ColumnRule?>(null), r.rules)
        assertEquals(0, r.expected.size)
        assertEquals(false, r.withTimestamp)
    }

    @Test fun inventoryKeepsEverything() {
        val inv = Inventory(
            id = "inv1", customer = Customer("Testkunde", "Straße 1", "12345 Ort"), stichtag = "2026-09-30", letterDate = "2026-10-01",
            counter = "Holly", writer = "Luke", place = "Halle", timeFrom = "08:00", timeTo = "11:00", blind = true, closed = true,
            articles = mutableListOf(Article("A", "Box A", 10, 2), Article("B", "Box B", 0, 0)),
            counts = mutableListOf(CountEntry("A", "Versand", 7, 5L), CountEntry("A", "Lager", 2, 6L)),
            estimated = mutableSetOf("A"), notes = mutableMapOf("A" to "Karton offen; \"Ecke\" eingedrückt"),
            created = 1L, updated = 2L, includeZero = true, firstCounts = mutableMapOf("A" to 9),
        )
        val r = roundTrip(InventoryStore.toJson(inv), InventoryStore::fromJson)
        assertEquals(inv.customer, r.customer)
        assertEquals(listOf(inv.stichtag, inv.letterDate, inv.counter, inv.writer, inv.place, inv.timeFrom, inv.timeTo),
            listOf(r.stichtag, r.letterDate, r.counter, r.writer, r.place, r.timeFrom, r.timeTo))
        assertEquals(true, r.blind); assertEquals(true, r.closed); assertEquals(true, r.includeZero)
        assertEquals(inv.articles, r.articles)
        assertEquals(inv.counts, r.counts)
        assertEquals(inv.estimated, r.estimated)
        assertEquals(inv.notes, r.notes)
        assertEquals(mapOf("A" to 9), r.firstCounts)
        assertEquals(9, r.counted("A"))
        assertEquals(1L, r.created)
    }

    @Test fun oldInventoryFileWithoutNewFields() {
        // Stand vor Version 1.15: ohne includeZero und firstCounts
        val old = JSONObject("""{"id":"x","customer":{"name":"K","street":"","city":""},"stichtag":"2026-09-30",
            "articles":[{"sku":"A","name":"Box","soll":3,"technik":0}],"counts":[{"sku":"A","zone":"Versand","qty":3,"time":1}]}""")
        val r = InventoryStore.fromJson(old)
        assertEquals(false, r.includeZero)
        assertEquals(0, r.firstCounts.size)
        assertEquals("2026-09-30", r.letterDate)
        assertEquals(3, r.counted("A"))
    }
}
