package de.scanliste.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Genexis-Scan: QR-Code mit mehreren Werten, je zwei aufeinanderfolgende gehören in eine Zeile. */
class GenexisScanTest {
    /** Inhalt des QR-Codes aus der echten Beispiel-Liste vom 15.10.2026 (10 Paare). */
    private val qr = listOf(
        "GNXS05DFA614", "Z.0225460513", "GNXS05DFA61A", "Z.0225460516", "GNXS05DFA6EE", "Z.0225460580",
        "GNXS05DFA6FE", "Z.0225460588", "GNXS05DFA70A", "Z.022546058E", "GNXS05DFA724", "Z.022546059B",
        "GNXS05DFA73E", "Z.02254605A8", "GNXS05DFA758", "Z.02254605B5", "GNXS05DFA778", "Z.02254605C5",
        "GNXS05DFA7C6", "Z.02254605EC",
    ).joinToString("\n")

    private fun list() = ScanList("t", "Test", mutableListOf("Seriennummer", "PON-SN"), withTimestamp = true, genexis = true)

    @Test fun qrIsSplitIntoPairsInOrder() {
        val l = list()
        val r = l.addPairs(ScanList.splitValues(qr))
        assertEquals(PairResult.Added(0, 10, 0), r)
        assertEquals(10, l.rows.size)
        assertEquals(listOf("GNXS05DFA614", "Z.0225460513"), l.rows[0].cells)
        assertEquals(listOf("GNXS05DFA724", "Z.022546059B"), l.rows[5].cells)
        assertEquals(listOf("GNXS05DFA7C6", "Z.02254605EC"), l.rows[9].cells)
        assertEquals(20, l.valueCount())
    }

    @Test fun windowsLineEndsAndBlankLinesWork() {
        assertEquals(listOf("A1", "B1", "A2", "B2"), ScanList.splitValues("A1\r\nB1\r\n\r\n A2 \rB2\n"))
    }

    @Test fun oddCountIsRejectedCompletely() {
        val l = list()
        val r = l.addPairs(listOf("A1", "B1", "A2"))
        assertTrue(r is PairResult.Rejected)
        assertEquals(0, l.rows.size)
    }

    @Test fun sameQrTwiceIsSkippedNotDoubled() {
        val l = list()
        l.addPairs(ScanList.splitValues(qr))
        assertEquals(PairResult.Added(10, 0, 10), l.addPairs(ScanList.splitValues(qr)))
        assertEquals(10, l.rows.size)
    }

    @Test fun overlappingQrAddsOnlyNewPairs() {
        val l = list()
        l.addPairs(listOf("A1", "B1", "A2", "B2"))
        assertEquals(PairResult.Added(2, 1, 1), l.addPairs(listOf("A2", "B2", "A3", "B3")))
        assertEquals(listOf("A3", "B3"), l.rows[2].cells)
    }

    @Test fun valueWithDifferentPartnerIsRejected() {
        val l = list()
        l.addPairs(listOf("A1", "B1"))
        val r = l.addPairs(listOf("A1", "X9", "A2", "B2"))
        assertTrue(r is PairResult.Rejected)
        assertEquals(1, l.rows.size)
    }

    @Test fun halfFilledRowBlocksQrSoNoPairIsBroken() {
        val l = list()
        l.add("EINZELN")
        val r = l.addPairs(listOf("A1", "B1"))
        assertTrue(r is PairResult.Rejected)
        assertEquals(listOf("EINZELN", ""), l.rows[0].cells)
        l.add("PARTNER")                       // Lücke füllen – danach geht es wieder
        assertEquals(PairResult.Added(1, 1, 0), l.addPairs(listOf("A1", "B1")))
    }

    @Test fun undoRemovesWholeScan() {
        val l = list()
        l.addPairs(listOf("A1", "B1"))
        l.addPairs(listOf("A2", "B2", "A3", "B3"))
        assertEquals(2, l.removeLastRows(2))
        assertEquals(1, l.rows.size)
        assertEquals(2, l.valueCount())
        assertEquals(PairResult.Added(1, 1, 0), l.addPairs(listOf("A2", "B2")))   // wieder frei
    }
}
