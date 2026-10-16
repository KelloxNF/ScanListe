package de.scanliste.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatprüfung je Spalte, Soll-Liste und Zielmenge. */
class ListFeaturesTest {
    private fun genexis() = ScanList("g", "G", mutableListOf("Seriennummer", "PON-SN"), withTimestamp = false, genexis = true).apply {
        rules[0] = ColumnRule(listOf("GNXS"), 12)
        rules[1] = ColumnRule(listOf("Z."))
    }

    @Test fun ruleParseAndDescribe() {
        val r = ColumnRule.parse(" GNXS , gnxt ", "12")!!
        assertEquals(listOf("GNXS", "gnxt"), r.prefixes)
        assertTrue(r.accepts("gnxs05DFA614"))
        assertFalse(r.accepts("GNXS05DFA61"))   // 11 Zeichen
        assertEquals("beginnt mit GNXS oder gnxt, 12 Zeichen", r.describe())
        assertNull(ColumnRule.parse("", " "))
    }

    @Test fun singleValueRejectedByFormat() {
        val l = ScanList("n", "N", mutableListOf("SN"), withTimestamp = false)
        l.rules[0] = ColumnRule(listOf("GNXS"))
        assertTrue(l.add("4006381333931") is AddResult.Rejected)
        assertEquals(0, l.rows.size)
        assertEquals(AddResult.Added(0, 0), l.add("GNXS05DFA614"))
    }

    @Test fun swappedPairIsDetected() {
        val l = genexis()
        val r = l.addPairs(listOf("Z.0225460513", "GNXS05DFA614"))
        assertTrue(r is PairResult.Rejected && r.message.contains("vertauscht"))
        assertEquals(0, l.rows.size)
        assertEquals(PairResult.Added(0, 1, 0), l.addPairs(listOf("GNXS05DFA614", "Z.0225460513")))
    }

    @Test fun oneBadPairRejectsWholeQr() {
        val l = genexis()
        val r = l.addPairs(listOf("GNXS05DFA614", "Z.0225460513", "XXXX", "Z.0225460516"))
        assertTrue(r is PairResult.Rejected)
        assertEquals(0, l.rows.size)
    }

    @Test fun expectedListRejectsForeignAndReportsMissing() {
        val l = ScanList("n", "N", mutableListOf("SN", "MAC"), withTimestamp = false)
        l.expected.addAll(listOf("A1", "A2", "A3"))
        l.expectedColumn = 0
        assertTrue(l.add("B9") is AddResult.Rejected)       // Spalte SN, nicht in der Soll-Liste
        assertEquals(AddResult.Added(0, 0), l.add("A2"))
        assertEquals(AddResult.Added(0, 1), l.add("MAC-1"))  // Spalte MAC wird nicht geprüft
        assertEquals(listOf("A1", "A3"), l.missing())
        assertEquals(1, l.foundExpected())
    }

    @Test fun expectedListWithPairs() {
        val l = genexis().apply { expected.addAll(listOf("GNXS05DFA614")) }
        assertTrue(l.addPairs(listOf("GNXS05DFA61A", "Z.0225460516")) is PairResult.Rejected)
        assertEquals(PairResult.Added(0, 1, 0), l.addPairs(listOf("GNXS05DFA614", "Z.0225460513")))
        assertEquals(emptyList<String>(), l.missing())
    }

    @Test fun targetStopsAtLimit() {
        val l = ScanList("n", "N", mutableListOf("SN"), withTimestamp = false, target = 2)
        l.add("1"); l.add("2")
        assertTrue(l.targetReached())
        assertTrue(l.add("3") is AddResult.Rejected)
        assertEquals(2, l.rows.size)
    }

    @Test fun targetWithTwoColumnsCountsRows() {
        val l = ScanList("n", "N", mutableListOf("SN", "MAC"), withTimestamp = false, target = 1)
        l.add("S1")
        assertFalse(l.targetReached())                   // Zeile noch nicht voll
        assertEquals(AddResult.Added(0, 1), l.add("M1"))
        assertTrue(l.targetReached())
        assertTrue(l.add("S2") is AddResult.Rejected)
    }

    @Test fun targetRejectsQrThatDoesNotFit() {
        val l = genexis().apply { rules[0] = null; rules[1] = null; target = 3 }
        l.addPairs(listOf("A1", "B1", "A2", "B2"))
        val r = l.addPairs(listOf("A3", "B3", "A4", "B4"))
        assertTrue(r is PairResult.Rejected && r.message.contains("Nur noch 1"))
        assertEquals(PairResult.Added(2, 1, 0), l.addPairs(listOf("A3", "B3")))
        assertTrue(l.targetReached())
    }
}
