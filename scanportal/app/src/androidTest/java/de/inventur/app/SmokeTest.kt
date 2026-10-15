package de.inventur.app

import de.scanportal.app.DataWedge
import de.scanportal.app.PortalActivity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.appcompat.app.AppCompatDelegate
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
            "Anyone", Settings.DEFAULT_ZONES,
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
            Triple("TST20002937Ref", "Versand", 17), Triple("TST20002937Ref", "Außenlager", 32),
            Triple("TST20003013", "Versand", 26),
            Triple("TST20003013Ref", "Versand", 62), Triple("TST20003013Ref", "Lager", 2), Triple("TST20003013Ref", "Außenlager", 50),
            Triple("TST20003096", "Versand", 34), Triple("TST20003096", "Lager", 4), Triple("TST20003096", "Außenlager", 395),
            Triple("TST20003062", "Versand", 103), Triple("TST20002966", "Versand", 320),
            Triple("TST000004", "Versand", 55), Triple("TST000002", "Versand", 938),
        )
        counts.forEachIndexed { i, (s, z, q) -> inv.addCount(CountEntry(s, z, q, i.toLong())) }
        inv.notes["TST20003096"] = "1 Karton beschädigt, Rest in Technik prüfen"
        inv.rename("TST20002966", "FRITZ!Fon X6 schwarz (umbenannt)")
        InventoryStore.save(ctx, inv)

        // PDFs
        val letter = File(shots, "anschreiben.pdf")
        letter.outputStream().use { Documents.letter(inv, Settings.sender(ctx), Settings.footer(ctx), Settings.ceo(ctx), it) }
        val sheet = File(shots, "zaehlliste.pdf")
        sheet.outputStream().use { Documents.countSheet(inv, Settings.zones(ctx), it) }
        assertTrue(renderPdf(letter, "pdf_anschreiben") >= 1)
        assertTrue(renderPdf(sheet, "pdf_zaehlliste") >= 1)
        // Option „Artikel mit Bestand 0 aufführen“
        inv.includeZero = true
        val letter0 = File(shots, "anschreiben_mit_0.pdf")
        letter0.outputStream().use { Documents.letter(inv, Settings.sender(ctx), Settings.footer(ctx), Settings.ceo(ctx), it) }
        val sheet0 = File(shots, "zaehlliste_mit_0.pdf")
        sheet0.outputStream().use { Documents.countSheet(inv, Settings.zones(ctx), it) }
        assertTrue(renderPdf(letter0, "pdf_anschreiben_mit_0") >= 1)
        assertTrue(renderPdf(sheet0, "pdf_zaehlliste_mit_0") >= 1)
        inv.includeZero = false

        // Bildschirme
        ActivityScenario.launch(PortalActivity::class.java).use { shot("0_portal") }
        ActivityScenario.launch(de.scanliste.app.MainActivity::class.java).use { shot("0_scanliste") }

        // Genexis-Scan: ein QR-Code mit 3 Paaren landet in 3 Zeilen
        val gx = de.scanliste.app.ScanList(
            de.scanliste.app.ListStore.newId(), "Genexis Test", mutableListOf("Seriennummer", "PON-SN"),
            withTimestamp = true, genexis = true,
        )
        de.scanliste.app.ListStore.save(ctx, gx)
        val gxIntent = Intent(ctx, de.scanliste.app.ListActivity::class.java).putExtra(de.scanliste.app.ListActivity.EXTRA_ID, gx.id)
        ActivityScenario.launch<de.scanliste.app.ListActivity>(gxIntent).use {
            instr.waitForIdleSync(); Thread.sleep(400)
            ctx.sendBroadcast(Intent(DataWedge.SCAN_ACTION).setPackage(ctx.packageName)
                .putExtra(DataWedge.EXTRA_DATA, "GNXS05DFA614\nZ.0225460513\nGNXS05DFA61A\nZ.0225460516\nGNXS05DFA6EE\nZ.0225460580"))
            instr.waitForIdleSync(); Thread.sleep(600)
            // Einzelner Barcode im Genexis-Modus: Fehlermeldung, nichts übernehmen
            ctx.sendBroadcast(Intent(DataWedge.SCAN_ACTION).setPackage(ctx.packageName)
                .putExtra(DataWedge.EXTRA_DATA, "4006381333931"))
            instr.waitForIdleSync(); Thread.sleep(600)
            shot("0b_genexis")
            val saved = de.scanliste.app.ListStore.load(ctx, gx.id)!!
            assertEquals(3, saved.rows.size)
            assertEquals(6, saved.valueCount())
            assertEquals(listOf("GNXS05DFA61A", "Z.0225460516"), saved.rows[1].cells)
        }
        ActivityScenario.launch(MainActivity::class.java).use { shot("1_uebersicht") }
        ActivityScenario.launch(EditActivity::class.java).use { shot("2_neue_inventur") }

        val countIntent = Intent(ctx, CountActivity::class.java).putExtra(CountActivity.EXTRA_ID, inv.id)
        fun scan(code: String) {
            ctx.sendBroadcast(Intent(DataWedge.SCAN_ACTION).setPackage(ctx.packageName).putExtra(DataWedge.EXTRA_DATA, code))
            instr.waitForIdleSync(); Thread.sleep(400)
        }
        fun open(act: CountActivity, sku: String) = act.openArticle(InventoryStore.load(ctx, inv.id)!!.article(sku)!!)
        ActivityScenario.launch<CountActivity>(countIntent).use {
            shot("3_zaehlen")
            // Scan ohne geöffneten Artikel: nichts zählen, nur Hinweis
            scan("TST20002974")
            shot("4_scan_ohne_artikel")
            assertEquals(null, InventoryStore.load(ctx, inv.id)!!.counted("TST20002974"))
        }
        ActivityScenario.launch<CountActivity>(countIntent).use { sc ->
            // Feste Menge 20 je Scan, Artikel öffnen, zwei Scans, ein falscher Artikel
            sc.onActivity { act -> act.session.fixedOn = true; act.session.fixedN = 20; open(act, "TST20003013") }
            instr.waitForIdleSync(); Thread.sleep(500)
            scan("TST20003013"); scan("TST20003013")
            scan("TST20003096")   // falscher Artikel – darf nicht zählen
            shot("9_artikel_fenster")
            scan("TST20003013\nTST20003096")   // QR-Code mit mehreren Werten – darf nichts zählen
            val now = InventoryStore.load(ctx, inv.id)!!
            assertEquals(66, now.counted("TST20003013"))
            assertEquals(433, now.counted("TST20003096"))
        }
        ActivityScenario.launch<CountActivity>(countIntent).use { sc ->
            // Artikel-Bildschirm, dann „Mengen je Bereich korrigieren“
            sc.onActivity { act -> open(act, "TST20002974") }
            shot("8a_artikel_offen")
            sc.onActivity { act -> act.sheets.zones() }
            shot("8_korrigieren")
        }
        ActivityScenario.launch<CountActivity>(countIntent).use { sc ->
            // „Bestand stimmt“ bei bereits erfassten Mengen → Warnung
            sc.onActivity { act -> open(act, "TST20003096") }
            instr.waitForIdleSync(); Thread.sleep(500)
            sc.onActivity { act -> act.sheets.bestandStimmt() }
            shot("8b_bestand_stimmt_warnung")
        }
        ActivityScenario.launch<CountActivity>(countIntent).use { sc ->
            // Unbekannter Barcode bei geöffnetem Artikel → Zuordnen-Frage
            sc.onActivity { act -> open(act, "TST20002974") }
            scan("4006381333931")
            shot("5_barcode_zuordnen")
        }
        val resIntent = Intent(ctx, ResultActivity::class.java).putExtra(CountActivity.EXTRA_ID, inv.id)
        ActivityScenario.launch<ResultActivity>(resIntent).use { shot("6_ergebnis") }
        ActivityScenario.launch(SettingsActivity::class.java).use { shot("7_einstellungen") }

        // Dunkelmodus
        instr.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        ActivityScenario.launch(PortalActivity::class.java).use { shot("n0_portal") }
        ActivityScenario.launch(MainActivity::class.java).use { shot("n1_uebersicht") }
        ActivityScenario.launch(EditActivity::class.java).use { shot("n2_neue_inventur") }
        ActivityScenario.launch<CountActivity>(countIntent).use { sc ->
            shot("n3_zaehlen")
            sc.onActivity { act -> open(act, "TST20003096") }
            instr.waitForIdleSync(); Thread.sleep(500)
            shot("n4_artikel_fenster")
        }
        ActivityScenario.launch<ResultActivity>(resIntent).use { shot("n6_ergebnis") }
        instr.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
    }
}
