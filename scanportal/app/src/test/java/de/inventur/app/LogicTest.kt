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
        inv.addCount(CountEntry("TST20002937Ref", "A", 17, 1))
        inv.addCount(CountEntry("TST20002937Ref", "C", 32, 2))   // 49 = stimmt
        inv.addCount(CountEntry("TST20003013", "A", 25, 3))      // 26 erwartet → -1
        inv.addCount(CountEntry("TST20003013Ref", "A", 114, 4))
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
        inv.addCount(CountEntry("A1", "A", 4, 1)); inv.addCount(CountEntry("A1", "A", 3, 2))
        inv.setZone("A1", "A", 9)
        assertEquals(9, inv.counted("A1"))
        inv.setZone("A1", "A", null)
        assertNull(inv.counted("A1"))
    }

    @Test fun letterRowsUseFullNamesAndCountedQuantity() {
        val inv = inventory(Importer.toArticles(exportRows).articles)
        inv.addCount(CountEntry("TST20002937Ref", "A", 49, 1))
        inv.addCount(CountEntry("TST20003013", "A", 26, 2))
        inv.addCount(CountEntry("TST20003013Ref", "A", 114, 3))
        inv.addCount(CountEntry("TST20003096", "A", 434, 4))
        inv.addCount(CountEntry("TST4043972269040Ref", "A", 84, 5))
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
        inv.addCount(CountEntry("TST20003096", "A", 434, 1))
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
        inv.addCount(CountEntry("=A1", "A", 1, 1))
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

class FixesTest {
    private fun inv(articles: List<Article>) = Inventory(
        id = "t", customer = Customer("K", "", ""), stichtag = "2026-09-30", letterDate = "2026-09-30",
        counter = "", writer = "", place = "", timeFrom = "", timeTo = "", blind = false, closed = false,
        articles = articles.toMutableList(), counts = mutableListOf(), estimated = mutableSetOf(),
        notes = mutableMapOf(), created = 0, updated = 0,
    )

    @Test fun indexStaysCorrectAfterEveryChange() {
        val i = inv(listOf(Article("A", "a", 10, 0), Article("B", "b", 0, 0)))
        assertNull(i.counted("A"))
        val e1 = CountEntry("A", "Versand", 4, 1)
        i.addCount(e1); i.addCount(CountEntry("A", "Lager", 3, 2))
        assertEquals(7, i.counted("A")); assertEquals(4, i.zoneQty("A", "Versand"))
        i.removeCount(e1)
        assertEquals(3, i.counted("A")); assertNull(i.zoneQty("A", "Versand"))
        i.addCount(CountEntry("B", "Lager", 2, 3))
        assertEquals(2, i.listArticles().size)                 // B erscheint, weil gezählt
        assertEquals("B", i.removeLastCount()!!.sku)
        assertEquals(1, i.listArticles().size)
        i.setZone("A", "Lager", 10)
        assertEquals(10, i.counted("A")); assertEquals(Status.STIMMT, i.status(i.article("A")!!))
        i.replaceCounts(listOf(CountEntry("A", "C", 1, 9)))
        assertEquals(1, i.counted("A")); assertEquals(listOf("C"), i.countedZones())
    }

    @Test fun duplicateSkusAreSummedNotDropped() {
        val rows = listOf(
            listOf("Artikelnummer", "Artikel", "Bestand", "Bestand in Technik"),
            listOf("X1", "Router", "10", "1"),
            listOf("X2", "Kabel", "5", "0"),
            listOf("X1", "Router", "7", "2"),
        )
        val r = Importer.toArticles(rows)
        assertEquals(2, r.articles.size)
        assertEquals(17, r.articles[0].soll); assertEquals(3, r.articles[0].technik)
        assertTrue(r.info.contains("1 Zeilen mit gleicher Artikelnummer zusammengezählt"))
    }

    @Test fun manyArticlesAndScansStayFast() {
        val arts = (1..500).map { Article("S$it", "Artikel $it", it, 0) }
        val i = inv(arts)
        val zones = listOf("Versand", "Lager", "Außenlager")
        val t0 = System.nanoTime()
        // 20.000 Einzelscans („Jeder Scan = 1 Stück“), nach jedem Scan Liste + Zusammenfassung neu berechnen
        for (n in 0 until 20_000) {
            i.addCount(CountEntry("S${n % 500 + 1}", zones[n % 3], 1, n.toLong()))
            if (n % 50 == 0) { i.summary(); i.listArticles().forEach { a -> i.status(a); zones.forEach { z -> i.zoneQty(a.sku, z) } } }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(40, i.counted("S1"))
        assertTrue("zu langsam: $ms ms", ms < 5_000)
    }
}

class UndoAndCompactTest {
    private fun inv() = Inventory(
        id = "t", customer = Customer("K", "", ""), stichtag = "2026-09-30", letterDate = "2026-09-30",
        counter = "", writer = "", place = "", timeFrom = "", timeTo = "", blind = false, closed = false,
        articles = mutableListOf(Article("A", "a", 5, 0), Article("B", "b", 5, 0)), counts = mutableListOf(),
        estimated = mutableSetOf(), notes = mutableMapOf(), created = 0, updated = 0,
    )

    @Test fun consecutivePieceScansAreStoredAsOneEntry() {
        val i = inv()
        repeat(1000) { i.addCount(CountEntry("A", "Versand", 1, it.toLong())) }
        assertEquals(1, i.counts.size)
        assertEquals(1000, i.counted("A"))
        i.addCount(CountEntry("B", "Versand", 1, 2000))
        i.addCount(CountEntry("A", "Versand", 1, 2001))
        assertEquals(3, i.counts.size)
        assertEquals(1001, i.counted("A"))
    }

    @Test fun retractTakesBackExactlyTheAddedAmount() {
        val i = inv()
        i.addCount(CountEntry("A", "Versand", 17, 1))
        i.addCount(CountEntry("A", "Versand", 32, 2))   // zusammengefasst zu 49
        assertTrue(i.retract("A", "Versand", 32))
        assertEquals(17, i.counted("A"))
        assertTrue(i.retract("A", "Versand", 17))
        assertNull(i.counted("A"))
        assertTrue(!i.retract("A", "Versand", 1))
    }

    @Test fun correctionIsNotUndoneByRetractOfOtherZone() {
        val i = inv()
        i.addCount(CountEntry("A", "Versand", 3, 1))
        i.setZone("A", "Lager", 2)                      // Korrektur in anderem Bereich
        assertTrue(i.retract("A", "Versand", 3))        // Rückgängig betrifft nur den Scan
        assertEquals(2, i.counted("A"))
        assertEquals(2, i.zoneQty("A", "Lager"))
    }
}

class ThirdRoundTest {
    private fun inv(arts: List<Article>) = Inventory(
        id = "t", customer = Customer("K", "", ""), stichtag = "2026-09-30", letterDate = "2026-09-30",
        counter = "", writer = "", place = "", timeFrom = "", timeTo = "", blind = false, closed = false,
        articles = arts.toMutableList(), counts = mutableListOf(), estimated = mutableSetOf(),
        notes = mutableMapOf(), created = 0, updated = 0,
    )

    @Test fun negativeSystemStockIsListed() {
        val i = inv(listOf(Article("N", "Minus", -3, 0), Article("Z", "Null", 0, 0)))
        assertEquals(listOf("N"), i.listArticles().map { it.sku })
        assertEquals(Status.NICHT_GEZAEHLT, i.status(i.article("N")!!))
        i.addCount(CountEntry("N", "Lager", 0, 1))
        assertEquals(Status.DIFFERENZ, i.status(i.article("N")!!))
        assertEquals(3, i.summary().plus)
    }

    @Test fun estimatedQuantityWithDeviationIsNotOhneDifferenz() {
        val i = inv(listOf(Article("A", "a", 10, 0)))
        i.addCount(CountEntry("A", "Lager", 8, 1))
        i.estimated.add("A")
        assertEquals(Status.GESCHAETZT, i.status(i.article("A")!!))
        assertEquals("Differenz", i.summary().overall)
        i.setZone("A", "Lager", 10)
        assertEquals("Ohne Differenz", i.summary().overall)
    }

    @Test fun safeFileOverwritesCompletely() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val f = java.io.File(dir, "x.json")
        de.scanportal.app.SafeFile.write(f, "a".repeat(10_000))
        de.scanportal.app.SafeFile.write(f, "kurz")
        assertEquals("kurz", f.readText())
        assertTrue(!java.io.File(dir, "x.json.tmp").exists())
    }
}

class DeleteArticleTest {
    @Test fun removeArticleDropsEverything() {
        val i = Inventory(
            id = "t", customer = Customer("K", "", ""), stichtag = "2026-09-30", letterDate = "2026-09-30",
            counter = "", writer = "", place = "", timeFrom = "", timeTo = "", blind = false, closed = false,
            articles = mutableListOf(Article("A", "a", 5, 0), Article("B", "b", 3, 0)), counts = mutableListOf(),
            estimated = mutableSetOf(), notes = mutableMapOf(), created = 0, updated = 0,
        )
        i.addCount(CountEntry("A", "Lager", 5, 1)); i.addCount(CountEntry("B", "Lager", 3, 2))
        i.estimated.add("A"); i.notes["A"] = "x"
        i.removeArticle("A")
        assertEquals(listOf("B"), i.listArticles().map { it.sku })
        assertNull(i.counted("A")); assertTrue("A" !in i.estimated); assertTrue("A" !in i.notes)
        assertEquals(listOf(LetterRow("b", 3)), i.letterRows())
        assertEquals("Ohne Differenz", i.summary().overall)
    }
}
