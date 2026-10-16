package de.scanliste.app

import de.inventur.app.Article
import de.inventur.app.CountEntry
import de.inventur.app.Customer
import de.inventur.app.Inventory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Vorlagen, Folgepalette, Abschließen, Packlisten-QR, Sammelexport, Tagesbericht, Inventur-Fortschritt. */
class PaletteWorkflowTest {

    @Test fun nextNameCountsUpAndSkipsTaken() {
        assertEquals("Palette 5", Naming.next("Palette 4", emptyList()))
        assertEquals("P010", Naming.next("P009", emptyList()))
        assertEquals("Palette 6", Naming.next("Palette 4", listOf("palette 5")))
        assertEquals("Eingang 2", Naming.next("Eingang", emptyList()))
        // Vorgabename mit Uhrzeit: keine Minute hochzählen
        assertEquals("Liste 16.10.2026 09:30 2", Naming.next("Liste 16.10.2026 09:30", emptyList()))
        assertEquals("Palette", Naming.base("Palette 12"))
        assertEquals("4711", Naming.base("4711"))
        assertEquals("Palette 8", Naming.firstFree("Palette", listOf("Palette 3", "Palette 7", "Anderes 9")))
        assertEquals("Palette 1", Naming.firstFree("Palette", emptyList()))
        // Lange Nummer (z. B. Seriennummer als Name) läuft nicht über
        assertEquals("SN 12345678901234567891234", Naming.next("SN 12345678901234567891233", emptyList()))
        assertEquals("X 99999999999999999999", Naming.firstFree("X", listOf("X 99999999999999999998")))
    }

    @Test fun templateKeepsSettingsButNoValues() {
        val l = ScanList("a", "Palette 4", mutableListOf("Seriennummer", "PON-SN"), withTimestamp = false, genexis = true, target = 40)
        l.rules[0] = ColumnRule(listOf("GNXS"), 12)
        l.expected.add("X")
        l.addPairs(listOf("GNXS05DFA614", "Z.0225460513"))
        val t = ListTemplate.of(l, "Genexis-Palette 40")
        assertEquals("Palette", t.baseName)
        val back = TemplateStore.fromJson(JSONArray(TemplateStore.toJson(listOf(t)).toString()))
        assertEquals(listOf(t), back)
        val n = back[0].create("b", "Palette 5")
        assertEquals(listOf("Seriennummer", "PON-SN"), n.columns)
        assertTrue(n.genexis)
        assertEquals(40, n.target)
        assertEquals(ColumnRule(listOf("GNXS"), 12), n.rules[0])
        assertEquals(0, n.rows.size)
        assertTrue(n.expected.isEmpty())
    }

    @Test fun closedListRejectsScansAndSurvivesSaving() {
        val l = ScanList("c", "Palette 1", mutableListOf("SN", "PON"), withTimestamp = false, genexis = true)
        l.closed = true
        assertTrue(l.add("A") is AddResult.Rejected)
        assertTrue(l.addPairs(listOf("A", "B")) is PairResult.Rejected)
        assertEquals(0, l.rows.size)
        val r = ListStore.fromJson(JSONObject(ListStore.toJson(l).toString()))
        assertTrue(r.closed)
        r.closed = false
        assertTrue(r.addPairs(listOf("A", "B")) is PairResult.Added)
        assertFalse(ListStore.fromJson(JSONObject(ListStore.toJson(ScanList("d", "D", mutableListOf("SN"), false)).toString())).closed)
    }

    @Test fun packCodeRoundTripAndSplitting() {
        val rows = (1..90).map { listOf("GNXS05DF%04d".format(it), "Z.02254%05d".format(it)) }
        val codes = PackCode.encode("Palette\t3", rows)
        assertTrue(codes.size > 1)
        assertTrue(codes.all { it.toByteArray().size <= PackCode.MAX_BYTES })
        val parts = codes.map { PackCode.decode(it)!! }
        assertEquals(rows, parts.flatMap { it.rows })
        assertEquals((1..codes.size).toList(), parts.map { it.part })
        assertTrue(parts.all { it.parts == codes.size && it.total == 90 && it.title == "Palette 3" })
        // Zeilenenden vom Scanner (\r\n) stören nicht
        assertEquals(rows.take(3), PackCode.decode(PackCode.encode("P", rows.take(3))[0].replace("\n", "\r\n"))!!.rows)
        assertNull(PackCode.decode("GNXS05DFA614\nZ.0225460513"))
        assertFalse(PackCode.isPackCode("GNXS05DFA614"))
        assertEquals(1, PackCode.encode("leer", emptyList()).size)
        assertEquals(3, PackList.pageCount(10, 7))
    }

    @Test fun combinedCsvMergesColumns() {
        val a = ScanList("a", "Palette 1", mutableListOf("Seriennummer", "PON-SN"), withTimestamp = false).apply { add("S1"); add("P1") }
        val b = ScanList("b", "Karton", mutableListOf("Seriennummer", "EAN"), withTimestamp = false).apply { add("S2"); add("E2") }
        val lines = ListReports.combinedCsv(listOf(a, b)).trim('﻿').trim().split("\r\n")
        assertEquals("Liste;Nr;Seriennummer;PON-SN;EAN", lines[0])
        assertEquals("Palette 1;1;S1;P1;", lines[1])
        assertEquals("Karton;1;S2;;E2", lines[2])
    }

    @Test fun customerSuggestionsRecentFirstWithoutDuplicates() {
        // zuletzt benutzte zuerst, Inventur-Kunden danach, doppelte (auch anders geschrieben) nur einmal
        assertEquals(listOf("Testkunde GmbH", "alpha ag", "Beta KG"),
            CustomerPicker.merge(listOf("Testkunde GmbH", " alpha ag ", ""), listOf("Alpha AG", "Beta KG", "testkunde gmbh")))
    }

    @Test fun combinedCsvKeepsDuplicateColumnNames() {
        val a = ScanList("a", "Doppelt", mutableListOf("SN", "SN"), withTimestamp = false).apply { add("1"); add("2") }
        val lines = ListReports.combinedCsv(listOf(a)).trim().split("\r\n")
        assertEquals("Liste;Nr;SN;SN (2)", lines[0])
        assertEquals("Doppelt;1;1;2", lines[1])
    }

    @Test fun dayReportCountsOnlyThatDay() {
        val day = LocalDate.of(2026, 10, 16)
        val t = day.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val l = ScanList("a", "Palette 1", mutableListOf("SN"), withTimestamp = true).apply {
            add("A"); add("B"); add("C")
            rows[0].time = t; rows[1].time = t + 3_600_000; rows[2].time = t - 86_400_000   // gestern
            closed = true
        }
        val inv = Inventory(
            id = "i", customer = Customer("Testkunde", "", ""), stichtag = "2026-09-30", letterDate = "2026-09-30",
            counter = "", writer = "", place = "", timeFrom = "", timeTo = "", blind = false, closed = false,
            articles = mutableListOf(Article("A", "Box A", 5, 0)),
            counts = mutableListOf(CountEntry("A", "Versand", 2, t), CountEntry("A", "Lager", 1, t + 60_000)),
            estimated = mutableSetOf(), notes = mutableMapOf(), created = 0, updated = 0,
        )
        val d = ListReports.day(day, listOf(l), listOf(inv), ZoneOffset.UTC)
        assertEquals(2, d.devices)
        assertEquals(3, d.lists[0].total)
        assertTrue(d.lists[0].closed)
        assertEquals(2, d.inventories[0].entries)
        assertEquals(1, d.inventories[0].articles)
        assertTrue(ListReports.dayCsv(d).contains("Summe;Geräte in Listen;2"))
        assertTrue(ListReports.day(day.plusDays(5), listOf(l), listOf(inv), ZoneOffset.UTC).isEmpty)
        // Inventur-Fortschritt je Bereich
        assertEquals(listOf("Versand" to 1, "Lager" to 1, "Außenlager" to 0),
            inv.zoneProgress(listOf("Versand", "Lager", "Außenlager"), inv.articles))
    }
}
