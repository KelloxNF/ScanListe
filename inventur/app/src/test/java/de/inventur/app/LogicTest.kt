package de.inventur.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    /** Aufbau wie die echte "Bestandsübersicht intern und extern" (Werte als Text). */
    private val exportRows = listOf(
        listOf("Artikelnummer", "Artikel", "Bestand", "Bestand in Technik"),
        listOf("TST000001", "Anleitung Fritz!Box 5490", "0", "0"),
        listOf("TST20002937", "Fritz!Box 5530", "0", "0"),
        listOf("TST20002937Ref", "Fritz!Box 5530 Ref", "49", "97"),
        listOf("TST20003013", "FRITZ!Box 5590", "26", "0"),
        listOf("TST20003013Ref", "FRITZ!Box 5590 Ref", "114", "568"),
        listOf("TST20003096", "FRITZ!Box 5690", "434", "0"),
        listOf("TST20003096Ref", "FRITZ!Box 5690 Ref", "0", "413"),
        listOf("TST4043972269040", "waipu.tv 4K Stick", "0", "0"),
        listOf("TST4043972269040Ref", "waipu.tv 4K Stick Ref", "84", "0"),
    )

    private fun inventory(articles: List<Article>) = Inventory(
        id = "t", customer = Customer("Testkunde", "Musterstraße 123", "12345 Musterstadt"),
        stichtag = "2026-09-30", letterDate = "2026-09-30", counter = "Holly Molly", writer = "Lucky Luke",
        place = "Hauptgebäude", timeFrom = "08:00", timeTo = "11:00", blind = false, closed = false,
        articles = articles.toMutableList(), counts = mutableListOf(), estimated = mutableSetOf(),
        notes = mutableMapOf(), created = 0, updated = 0,
    )

    @Test fun importMapsColumnsByHeader() {
        val r = Importer.toArticles(exportRows)
        assertEquals(9, r.articles.size)
        val a = r.articles.first { it.sku == "TST20003013Ref" }
        assertEquals("FRITZ!Box 5590 Ref", a.name)
        assertEquals(114, a.soll)
        assertEquals(568, a.technik)
        assertTrue(a.isRef)
        assertEquals("TST20003013", a.baseSku)
        assertEquals("FRITZ!Box 5590", a.baseName)
        assertTrue(r.info.startsWith("9 Artikel importiert, davon 5 mit Bestand"))
    }

    @Test fun importColumnsInOtherOrder() {
        val rows = listOf(
            listOf("Bestand in Technik", "Bezeichnung", "Lagerbestand", "Art.-Nr."),
            listOf("3", "Kabel", "1.234", "K1"),
        )
        val a = Importer.toArticles(rows).articles.single()
        assertEquals("K1", a.sku); assertEquals("Kabel", a.name); assertEquals(1234, a.soll); assertEquals(3, a.technik)
    }

    @Test fun importWithoutHeaderUsesColumnsAtoD() {
        val rows = listOf(listOf("X1", "Router", "5", "1"), listOf("X2", "Kabel", "7", ""))
        val r = Importer.toArticles(rows)
        assertEquals(2, r.articles.size)
        assertEquals(7, r.articles[1].soll)
    }

    @Test fun numbers() {
        assertEquals(1234, Importer.number("1.234"))
        assertEquals(12, Importer.number("12,0"))
        assertEquals(49, Importer.number(" 49 "))
        assertEquals(49, Importer.number("49.0"))
        assertNull(Importer.number(""))
        assertNull(Importer.number("abc"))
    }

    @Test fun csvReaderSemicolonAndQuotes() {
        val rows = CsvReader.parse("Artikelnummer;Artikel;Bestand\r\nA1;\"Box; groß\";5\r\nA2;Kabel;\"1.000\"\r\n")
        assertEquals(3, rows.size)
        assertEquals("Box; groß", rows[1][1])
        assertEquals(1000, Importer.toArticles(rows).articles[1].soll)
    }

    @Test fun statusAndSummary() {
        val inv = inventory(Importer.toArticles(exportRows).articles)
        assertEquals(5, inv.listArticles().size) // nur Artikel mit Bestand
        inv.counts.add(CountEntry("TST20002937Ref", "A", 17, 1))
        inv.counts.add(CountEntry("TST20002937Ref", "C", 32, 2))   // 49 = stimmt
        inv.counts.add(CountEntry("TST20003013", "A", 25, 3))      // 26 erwartet → -1
        inv.counts.add(CountEntry("TST20003013Ref", "A", 114, 4))
        inv.estimated.add("TST20003013Ref")
        val s = inv.summary()
        assertEquals(5, s.articles); assertEquals(1, s.ok); assertEquals(1, s.diff)
        assertEquals(1, s.estimated); assertEquals(2, s.notCounted); assertEquals(1, s.minus)
        assertEquals("Offen", s.overall)
        assertEquals(Status.DIFFERENZ, inv.status(inv.article("TST20003013")!!))
        assertEquals(17, inv.zoneQty("TST20002937Ref", "A"))
        assertNull(inv.zoneQty("TST20002937Ref", "B"))
    }

    @Test fun setZoneReplacesEntries() {
        val inv = inventory(listOf(Article("A1", "Box", 10, 0)))
        inv.counts.add(CountEntry("A1", "A", 4, 1)); inv.counts.add(CountEntry("A1", "A", 3, 2))
        inv.setZone("A1", "A", 9)
        assertEquals(9, inv.counted("A1"))
        inv.setZone("A1", "A", null)
        assertNull(inv.counted("A1"))
    }

    @Test fun letterRowsUseFullNamesAndCountedQuantity() {
        val inv = inventory(Importer.toArticles(exportRows).articles)
        inv.counts.add(CountEntry("TST20002937Ref", "A", 49, 1))
        inv.counts.add(CountEntry("TST20003013", "A", 26, 2))
        inv.counts.add(CountEntry("TST20003013Ref", "A", 114, 3))
        inv.counts.add(CountEntry("TST20003096", "A", 434, 4))
        inv.counts.add(CountEntry("TST4043972269040Ref", "A", 84, 5))
        assertEquals(
            listOf(
                LetterRow("Fritz!Box 5530 Ref", 49),
                LetterRow("FRITZ!Box 5590", 26),
                LetterRow("FRITZ!Box 5590 Ref", 114),
                LetterRow("FRITZ!Box 5690", 434),
                LetterRow("waipu.tv 4K Stick Ref", 84),
            ),
            inv.letterRows(),
        )
        assertEquals("Ohne Differenz", inv.summary().overall)
    }

    @Test fun renameChangesOnlyName() {
        val inv = inventory(Importer.toArticles(exportRows).articles)
        inv.counts.add(CountEntry("TST20003096", "A", 434, 1))
        inv.rename("TST20003096", "  FRITZ!Box 5690 (Neuware)  ")
        val a = inv.article("TST20003096")!!
        assertEquals("FRITZ!Box 5690 (Neuware)", a.name)
        assertEquals(434, a.soll)
        assertEquals(434, inv.counted("TST20003096"))
        assertEquals("FRITZ!Box 5690 (Neuware)", inv.letterRows().first { it.qty == 434 }.name)
        inv.rename("TST20003096", "   ")
        assertEquals("FRITZ!Box 5690 (Neuware)", inv.article("TST20003096")!!.name)
    }

    @Test fun csvExportHasHeaderAndDefusesFormulas() {
        val inv = inventory(listOf(Article("=A1", "Box", 1, 0)))
        inv.counts.add(CountEntry("=A1", "A", 1, 1))
        val csv = CsvExport.result(inv, listOf("A", "B"))
        assertTrue(csv.startsWith("Kunde;Stichtag;Artikelnummer;Artikel;Soll;Technik;A;B;Ist;Differenz;Status;Bemerkung\r\n"))
        assertTrue(csv.contains(";'=A1;"))
        assertTrue(csv.contains("Testkunde;30.09.2026;"))
    }

    @Test fun dates() {
        assertEquals("30.09.2026", Dates.display("2026-09-30"))
        assertEquals("30. September 2026", Dates.long("2026-09-30"))
    }
}

class XlsxTest {
    @Test fun readsRealExportFile() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("bestand_testkunde.xlsx")!!.readBytes()
        val r = Importer.read(bytes)
        assertEquals(23, r.articles.size)
        val a = r.articles.first { it.sku == "TST20003013Ref" }
        assertEquals("FRITZ!Box 5590 Ref", a.name)
        assertEquals(114, a.soll)
        assertEquals(568, a.technik)
        assertEquals(10, r.articles.count { it.soll > 0 })
        assertEquals(938, r.articles.first { it.sku == "TST000002" }.soll)
    }
}
