package de.inventur.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zähl-Logik der Zähl-Ansicht ohne Oberfläche – mit simuliertem Speicher (auch „voll“). */
class CountSessionTest {
    private var saves = 0
    private var full = false

    private fun session(vararg arts: Article) = CountSession(
        Inventory(
            id = "t", customer = Customer("K", "", ""), stichtag = "2026-09-30", letterDate = "2026-09-30",
            counter = "", writer = "", place = "", timeFrom = "", timeTo = "", blind = false, closed = true,
            articles = arts.toMutableList(), counts = mutableListOf(), estimated = mutableSetOf(),
            notes = mutableMapOf(), created = 0, updated = 0,
        ),
        listOf("Versand", "Lager", "Außenlager"),
        save = { if (full) throw java.io.IOException("voll") else saves++ },
        clock = { 0L },
    )

    private val a = Article("A", "Box A", 10, 0)
    private val b = Article("B", "Box B", 5, 0)

    @Test fun addCountsInSelectedZoneAndOpensInventory() {
        val s = session(a)
        assertTrue(s.add("A", 3))
        s.zone = "Lager"
        assertTrue(s.add("A", 2))
        assertEquals(3, s.inv.zoneQty("A", "Versand"))
        assertEquals(2, s.inv.zoneQty("A", "Lager"))
        assertEquals(5, s.inv.counted("A"))
        assertFalse(s.inv.closed)
        assertTrue(s.inv.timeFrom.isNotBlank())
        assertEquals(2, saves)
    }

    @Test fun addRollsBackWhenStorageFull() {
        val s = session(a)
        full = true
        assertFalse(s.add("A", 3))
        assertNull(s.inv.counted("A"))
        assertEquals("", s.inv.timeFrom)
        assertTrue(s.inv.closed)
        full = false
        assertEquals(CountSession.UndoResult.Nothing, s.undo())   // nichts auf dem Rückgängig-Stapel
    }

    @Test fun undoTakesBackOnlyScansAndSkipsCorrected() {
        val s = session(a, b)
        s.add("A", 4); s.add("B", 2)
        s.setZones("B", mapOf("Versand" to 7, "Lager" to null, "Außenlager" to null))   // Korrektur
        val r = s.undo()
        assertEquals(CountSession.UndoResult.Done("Box A", 4, "Versand"), r)
        assertNull(s.inv.counted("A"))
        assertEquals(7, s.inv.counted("B"))
        assertEquals(CountSession.UndoResult.Nothing, s.undo())
    }

    @Test fun undoKeepsEntryWhenNotSaved() {
        val s = session(a)
        s.add("A", 4)
        full = true
        assertEquals(CountSession.UndoResult.NotSaved, s.undo())
        assertEquals(4, s.inv.counted("A"))
        full = false
        assertEquals(CountSession.UndoResult.Done("Box A", 4, "Versand"), s.undo())
    }

    @Test fun bestandStimmtReplacesAllZonesAndClearsEstimate() {
        val s = session(a)
        s.add("A", 3); s.zone = "Lager"; s.add("A", 1)
        s.toggleEstimated("A")
        s.zone = "Außenlager"
        assertTrue(s.bestandStimmt(a))
        assertEquals(10, s.inv.counted("A"))
        assertEquals(10, s.inv.zoneQty("A", "Außenlager"))
        assertNull(s.inv.zoneQty("A", "Versand"))
        assertTrue("A" !in s.inv.estimated)
        assertEquals(CountSession.UndoResult.Nothing, s.undo())
    }

    @Test fun fixedAmountOnlyWhenOnAndPositive() {
        val s = session(a)
        assertNull(s.fixedAmount())
        s.fixedOn = true; s.fixedN = 0
        assertNull(s.fixedAmount())
        s.fixedN = 20
        assertEquals(20, s.fixedAmount())
    }

    @Test fun stateAndBlindLabel() {
        val s = session(a)
        assertEquals(StateKey.OPEN, s.state(a))
        s.add("A", 9); assertEquals(StateKey.BAD, s.state(a))
        s.add("A", 1); assertEquals(StateKey.OK, s.state(a))
        assertEquals("Stimmt", s.label(StateKey.OK))
        s.inv.blind = true
        assertEquals("Gezählt", s.label(StateKey.OK))
        s.toggleEstimated("A"); assertEquals(StateKey.EST, s.state(a))
    }

    @Test fun noteRenameAndZoneLines() {
        val s = session(a)
        s.setNote("A", "  Karton offen ")
        assertEquals("Karton offen", s.inv.notes["A"])
        s.setNote("A", " ")
        assertTrue("A" !in s.inv.notes)
        s.rename("A", "Box A neu")
        assertEquals("Box A neu", s.inv.article("A")!!.name)
        s.add("A", 2); s.zone = "Lager"; s.add("A", 1)
        assertEquals(listOf("Versand: 2", "Lager: 1"), s.zoneLines("A"))
    }

    @Test fun deleteRestoresEverythingWhenNotSaved() {
        val s = session(a, b)
        s.add("A", 3); s.add("B", 1); s.toggleEstimated("A"); s.setNote("A", "x")
        full = true
        assertFalse(s.delete("A"))
        assertEquals(listOf("A", "B"), s.inv.articles.map { it.sku })
        assertEquals(3, s.inv.counted("A"))
        assertTrue("A" in s.inv.estimated)
        assertEquals("x", s.inv.notes["A"])
        full = false
        assertTrue(s.delete("A"))
        assertEquals(listOf("B"), s.inv.articles.map { it.sku })
        assertNull(s.inv.counted("A"))
    }

    @Test fun recountKeepsFirstCountAndResetsOnlyDifferences() {
        val s = session(a, b)
        s.add("A", 9)            // Soll 10 → Differenz
        s.add("B", 5)            // Soll 5 → stimmt
        assertEquals(listOf("A"), s.startRecount())
        assertEquals(9, s.inv.firstCounts["A"])
        assertNull(s.inv.counted("A"))
        assertEquals(5, s.inv.counted("B"))
        assertEquals(CountSession.UndoResult.Done("Box B", 5, "Versand"), s.undo())   // A ist nicht mehr rückgängig zu machen
        // zweite Nachzählung behält die echte 1. Zählung
        s.add("A", 11)
        s.startRecount()
        assertEquals(9, s.inv.firstCounts["A"])
    }

    @Test fun recountRollsBackWhenNotSaved() {
        val s = session(a)
        s.add("A", 9)
        full = true
        assertNull(s.startRecount())
        assertEquals(9, s.inv.counted("A"))
        assertTrue(s.inv.firstCounts.isEmpty())
    }

    @Test fun recountWithoutDifferences() {
        val s = session(a)
        s.add("A", 10)
        assertEquals(emptyList<String>(), s.startRecount())
    }
}
