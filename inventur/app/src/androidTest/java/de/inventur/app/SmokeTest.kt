package de.inventur.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Durchlauf auf einem echten Android (Emulator): Import der echten Bestandsübersicht,
 * alle Bildschirme öffnen, Scan simulieren, PDFs erzeugen. Screenshots landen in files/shots.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val shots = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun shot(name: String) {
        instr.waitForIdleSync()
        Thread.sleep(1200)
        val bmp = instr.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun renderPdf(file: File, prefix: String): Int {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { p ->
                        val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        File(shots, "${prefix}_${i + 1}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                return r.pageCount
            }
        }
    }

    @Test
    fun fullFlow() {
        Settings.save(
            ctx, "Sehrlocka GmbH | Walter-White-Street 22 | 12345 Antartica",
            "Sehrlocka GmbH · Walter-White-Street 22 · 12345 Antartica\nGeschäftsführer: Anyone · USt-IdNr. DE302506362",
            "Anyone", "A,B,C",
        )
        val bytes = instr.context.assets.open("bestand_testkunde.xlsx").use { it.readBytes() }
        val imp = Importer.read(bytes)
        assertEquals(23, imp.articles.size)

        val inv = Inventory(
            id = "smoke", customer = Customer("Testkunde", "Musterstraße 123", "12345 Musterstadt"),
            stichtag = "2026-09-30", letterDate = "2026-09-30", counter = "Holly Molly", writer = "Lucky Luke",
            place = "Hauptgebäude", timeFrom = "08:00", timeTo = "11:00", blind = false, closed = false,
            articles = imp.articles.toMutableList(), counts = mutableListOf(), estimated = mutableSetOf(),
            notes = mutableMapOf(), created = 1, updated = 1,
        )
        val counts = listOf(
            Triple("TST20002937Ref", "A", 17), Triple("TST20002937Ref", "C", 32),
            Triple("TST20003013", "A", 26),
            Triple("TST20003013Ref", "A", 62), Triple("TST20003013Ref", "B", 2), Triple("TST20003013Ref", "C", 50),
            Triple("TST20003096", "A", 34), Triple("TST20003096", "B", 4), Triple("TST20003096", "C", 395),
            Triple("TST20003062", "A", 103), Triple("TST20002966", "A", 320),
            Triple("TST000004", "A", 55), Triple("TST000002", "A", 938),
        )
        counts.forEachIndexed { i, (s, z, q) -> inv.counts.add(CountEntry(s, z, q, i.toLong())) }
        inv.notes["TST20003096"] = "1 Karton beschädigt, Rest in Technik prüfen"
        InventoryStore.save(ctx, inv)

        // PDFs
        val letter = File(shots, "anschreiben.pdf")
        letter.outputStream().use { Documents.letter(inv, Settings.sender(ctx), Settings.footer(ctx), Settings.ceo(ctx), it) }
        val sheet = File(shots, "zaehlliste.pdf")
        sheet.outputStream().use { Documents.countSheet(inv, Settings.zones(ctx), it) }
        assertTrue(renderPdf(letter, "pdf_anschreiben") >= 1)
        assertTrue(renderPdf(sheet, "pdf_zaehlliste") >= 1)

        // Bildschirme
        ActivityScenario.launch(MainActivity::class.java).use { shot("1_uebersicht") }
        ActivityScenario.launch(EditActivity::class.java).use { shot("2_neue_inventur") }

        val countIntent = Intent(ctx, CountActivity::class.java).putExtra(CountActivity.EXTRA_ID, inv.id)
        ActivityScenario.launch<CountActivity>(countIntent).use {
            shot("3_zaehlen")
            // Scan simulieren (wie DataWedge): Menge-Dialog erscheint
            ctx.sendBroadcast(
                Intent(DataWedge.SCAN_ACTION).setPackage(ctx.packageName).putExtra(DataWedge.EXTRA_DATA, "TST4043972269040Ref"),
            )
            shot("4_scan_menge")
        }
        ActivityScenario.launch<CountActivity>(countIntent).use {
            // Unbekannter Barcode → Zuordnen-Dialog
            ctx.sendBroadcast(
                Intent(DataWedge.SCAN_ACTION).setPackage(ctx.packageName).putExtra(DataWedge.EXTRA_DATA, "4006381333931"),
            )
            shot("5_barcode_zuordnen")
        }
        val resIntent = Intent(ctx, ResultActivity::class.java).putExtra(CountActivity.EXTRA_ID, inv.id)
        ActivityScenario.launch<ResultActivity>(resIntent).use { shot("6_ergebnis") }
        ActivityScenario.launch(SettingsActivity::class.java).use { shot("7_einstellungen") }
    }
}
