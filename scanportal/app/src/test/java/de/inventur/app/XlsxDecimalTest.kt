package de.inventur.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Kommazahlen aus Excel dürfen nicht als Tausenderpunkt gelesen werden (1.234 ≠ 1234). */
class XlsxDecimalTest {
    private fun xlsx(sheet: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); z.write(sheet.toByteArray()); z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun s(ref: String, t: String) = """<c r="$ref" t="inlineStr"><is><t>$t</t></is></c>"""

    @Test fun decimalsAreRoundedNotMultiplied() {
        val xml = """<worksheet><sheetData>
            <row r="1">${s("A1", "Artikelnummer")}${s("B1", "Artikel")}${s("C1", "Bestand")}</row>
            <row r="2">${s("A2", "X1")}${s("B2", "Box")}<c r="C2"><v>1.234</v></c></row>
            <row r="3">${s("A3", "X2")}${s("B3", "Box 2")}<c r="C3"><v>1234</v></c></row>
            </sheetData></worksheet>"""
        val r = Importer.read(xlsx(xml))
        assertEquals(1, r.articles.first { it.sku == "X1" }.soll)
        assertEquals(1234, r.articles.first { it.sku == "X2" }.soll)
    }
}
