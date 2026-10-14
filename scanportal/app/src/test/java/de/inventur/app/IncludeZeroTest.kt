package de.inventur.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Option „Artikel mit Bestand 0 aufführen“ für Anschreiben, Zählliste und CSV. */
class IncludeZeroTest {
    private fun inv() = Inventory(
        id = "t", customer = Customer("K", "", ""), stichtag = "2026-09-30", letterDate = "2026-09-30",
        counter = "", writer = "", place = "", timeFrom = "", timeTo = "", blind = false, closed = false,
        articles = mutableListOf(Article("A", "Box A", 5, 0), Article("Z", "Box Null", 0, 0), Article("C", "Box C", 0, 0)),
        counts = mutableListOf(CountEntry("A", "Versand", 5, 0), CountEntry("C", "Versand", 2, 0)),
        estimated = mutableSetOf(), notes = mutableMapOf(), created = 0, updated = 0,
    )

    @Test fun offShowsOnlyCountList() {
        val i = inv()
        assertEquals(listOf("A", "C"), i.docArticles().map { it.sku })
        assertEquals(listOf(LetterRow("Box A", 5), LetterRow("Box C", 2)), i.letterRows())
    }

    @Test fun onAddsZeroStockInImportOrder() {
        val i = inv().apply { includeZero = true }
        assertEquals(listOf("A", "Z", "C"), i.docArticles().map { it.sku })
        assertEquals(LetterRow("Box Null", 0), i.letterRows()[1])
        assertEquals("Kein Bestand", i.docStatus(i.article("Z")!!))
        assertEquals("Differenz", i.docStatus(i.article("C")!!))   // gezählt trotz Bestand 0
        // Ergebnis bleibt unverändert: 0-Artikel machen die Inventur nicht „offen“
        assertEquals(2, i.summary().articles)
        val csv = CsvExport.result(i, listOf("Versand"))
        assertEquals(4, csv.trimEnd().lines().size)
        assert(csv.contains("Kein Bestand"))
    }
}
