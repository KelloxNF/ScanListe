package de.inventur.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import de.scanportal.app.DataWedge
import de.scanportal.app.Tones

/**
 * Zählen nach dem Beispiel „Inventur Kachelansicht“. Diese Activity verbindet nur die Teile:
 *  - [CountSession]: Zähl-Logik und Speichern (ohne Oberfläche)
 *  - [OverviewScreen]: Kachel-Liste mit Suche und Filter
 *  - [ArticleScreen]: geöffneter Artikel mit allen Werkzeugen
 *  - [ArticleSheets]: Unterfenster (Menge, Korrektur, Umbenennen …)
 * Gescannt wird nur bei geöffnetem Artikel.
 */
class CountActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "inventory_id"
    }

    internal lateinit var session: CountSession
    internal lateinit var overview: OverviewScreen
    internal lateinit var article: ArticleScreen
    internal lateinit var sheets: ArticleSheets

    /** Geöffneter Artikel (Artikel-Bildschirm) oder null (Übersicht). */
    internal var cur: Article? = null

    private lateinit var tones: Tones
    private var receiverRegistered = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = intent.getStringExtra(DataWedge.EXTRA_DATA) ?: return
            onScan(data.trim())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Colors.init(this)
        val inv = intent.getStringExtra(EXTRA_ID)?.let { InventoryStore.load(this, it) }
        if (inv == null) { finish(); return }
        val zones = (Settings.zones(this) + inv.countedZones()).distinct()
        session = CountSession(inv, zones, save = { InventoryStore.save(this, it) })
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tones = Tones(this)

        sheets = ArticleSheets(this)
        overview = OverviewScreen(this)
        article = ArticleScreen(this)
        article.view.visibility = View.GONE
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Colors.appBg)
            addView(overview.view, FrameLayout.LayoutParams(full, full))
            addView(article.view, FrameLayout.LayoutParams(full, full))
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (cur != null) closeArticle() else finish()
            }
        })
        updateTitle()
        overview.render()
    }

    override fun onResume() {
        super.onResume()
        // Nach Rückkehr aus Kopfdaten/Ergebnis neu laden (nicht, solange ein Artikel offen ist)
        if (cur == null) InventoryStore.load(this, session.inv.id)?.let {
            session.reload(it); updateTitle(); overview.renderChips(); overview.render()
        }
        ContextCompat.registerReceiver(this, scanReceiver, IntentFilter(DataWedge.SCAN_ACTION), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) { unregisterReceiver(scanReceiver); receiverRegistered = false }
    }

    override fun onDestroy() {
        if (::sheets.isInitialized) sheets.dismiss()
        if (::tones.isInitialized) tones.release()
        super.onDestroy()
    }

    internal fun updateTitle() {
        val a = cur
        if (a == null) {
            supportActionBar?.title = session.inv.customer.name
            supportActionBar?.subtitle = "Inventur zählen"
        } else {
            supportActionBar?.title = a.name
            supportActionBar?.subtitle = a.sku + if (session.inv.notes[a.sku]?.isNotBlank() == true) "  · 📝" else ""
        }
        invalidateOptionsMenu()
    }

    private var lastToast: Toast? = null

    /** Immer nur ein Hinweis gleichzeitig – sonst stauen sich bei schnellem Scannen dutzende Meldungen. */
    internal fun toastShort(msg: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    /** Speichern ist fehlgeschlagen (meist: Gerätespeicher voll). */
    internal fun saveFailed() {
        tones.error()
        if (cur != null) article.say("✗ Nicht gespeichert – ist der Gerätespeicher voll?", Colors.barError)
        else toastShort("Nicht gespeichert – Speicher voll?")
    }

    // ================================================================ Artikel öffnen / schließen

    internal fun openArticle(a: Article) {
        if (cur != null || sheets.isShowing) return
        cur = session.inv.article(a.sku) ?: a
        overview.hideKeyboard()
        article.show()
        val detail = article.view
        detail.translationX = overview.view.width.toFloat().coerceAtLeast(1f)
        detail.visibility = View.VISIBLE
        detail.animate().translationX(0f).setDuration(220).withEndAction { overview.view.visibility = View.GONE }.start()
    }

    internal fun closeArticle() {
        if (cur == null) return
        cur = null
        overview.view.visibility = View.VISIBLE
        val detail = article.view
        detail.animate().translationX(detail.width.toFloat()).setDuration(220).withEndAction { detail.visibility = View.GONE }.start()
        updateTitle()
        overview.render()
    }

    // ================================================================ Zählen

    /** Zählt [n] im gewählten Bereich und meldet das Ergebnis in der Rückmeldeleiste. */
    internal fun add(a: Article, n: Int): Boolean {
        if (!session.add(a.sku, n)) { saveFailed(); return false }
        tones.ok()
        article.say("✓ ${if (n > 0) "+$n" else "$n"} in ${session.zone} → gesamt ${session.inv.counted(a.sku) ?: 0}", Colors.barOk)
        article.render()
        return true
    }

    /** Scan des geöffneten Artikels: feste Menge sofort zählen, sonst nach der Menge fragen. */
    internal fun countArticle(a: Article) {
        val f = session.fixedAmount()
        if (f != null) add(a, f) else { tones.ok(); sheets.askQty() }
    }

    internal fun toggleEstimated() {
        val a = cur ?: return
        if (session.toggleEstimated(a.sku)) {
            article.say(if (a.sku in session.inv.estimated) "Als geschätzt markiert" else "Markierung „geschätzt“ entfernt")
        } else saveFailed()
        article.render()
    }

    private fun onScan(code: String) {
        if (code.isEmpty()) return
        val inv = session.inv
        if (sheets.isShowing) { tones.error(); toastShort("Bitte zuerst das offene Fenster schließen."); return }
        if (code.contains('\n') || code.contains('\r')) {
            // z. B. ein Genexis-QR-Code mit vielen Seriennummern: gehört in die Listen, nicht in die Inventur
            tones.error()
            val msg = "✗ QR-Code mit mehreren Werten – bitte den Barcode des Artikels scannen."
            if (cur != null) article.say(msg, Colors.barError) else toastShort(msg)
            return
        }
        val a = cur
        if (a == null) {
            tones.error()
            val known = inv.findBySku(code)
                ?: BarcodeStore.lookup(this, inv.customer.name, code).mapNotNull { inv.article(it) }.singleOrNull()
            toastShort(
                when {
                    known == null -> "Erst einen Artikel antippen, dann scannen."
                    !overview.isListed(known) ->
                        "„${known.name}“ steht nicht in der Liste (Bestand 0) – ⋮ → „Artikel ohne Bestand zeigen“, dann antippen."
                    else -> "Erst „${known.name}“ antippen, dann scannen."
                },
            )
            return
        }
        val mapped = BarcodeStore.lookup(this, inv.customer.name, code)
        if (inv.findBySku(code)?.sku == a.sku || a.sku in mapped) { countArticle(a); return }
        val other = inv.findBySku(code) ?: mapped.firstNotNullOfOrNull { inv.article(it) }
        tones.error()
        if (other != null) {
            article.say("✗ Barcode gehört zu „${other.name}“ – nicht gezählt", Colors.barError)
            return
        }
        // Unbekannter Barcode → diesem (geöffneten) Artikel zuordnen
        article.say("Unbekannter Barcode $code", Colors.barWarn)
        sheets.assignBarcode(code, a, mapped)
    }

    private fun undo() {
        when (val r = session.undo()) {
            CountSession.UndoResult.Nothing -> { toastShort("Nichts mehr rückgängig zu machen."); return }
            CountSession.UndoResult.NotSaved -> saveFailed()
            is CountSession.UndoResult.Done -> toastShort("Rückgängig: ${r.name} ${r.qty} in ${r.zone}")
        }
        overview.render()
    }

    internal fun openResult() {
        startActivity(Intent(this, ResultActivity::class.java).putExtra(EXTRA_ID, session.inv.id))
    }

    // ================================================================ Menü

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (cur != null) return true   // im Artikel-Bildschirm kein Menü
        menu.add(0, 7, 0, "Filter anzeigen").apply { isCheckable = true; isChecked = overview.chipsVisible }
        menu.add(0, 1, 0, "Letzte Zählung rückgängig")
        menu.add(0, 2, 0, "Artikel ohne Bestand zeigen").apply { isCheckable = true; isChecked = overview.showAll }
        menu.add(0, 3, 0, "Kopfdaten bearbeiten")
        menu.add(0, 4, 0, "Barcode-Zuordnungen dieses Kunden löschen")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val customer = session.inv.customer.name
        when (item.itemId) {
            android.R.id.home -> if (cur != null) closeArticle() else finish()
            7 -> { overview.chipsVisible = !overview.chipsVisible; item.isChecked = overview.chipsVisible }
            1 -> undo()
            2 -> { overview.showAll = !overview.showAll; item.isChecked = overview.showAll }
            3 -> startActivity(Intent(this, EditActivity::class.java).putExtra(EXTRA_ID, session.inv.id))
            4 -> AlertDialog.Builder(this)
                .setTitle("Barcode-Zuordnungen löschen?")
                .setMessage("${BarcodeStore.count(this, customer)} gespeicherte Zuordnungen für $customer. Unbekannte Barcodes werden danach wieder abgefragt.")
                .setPositiveButton("Löschen") { _, _ -> if (!BarcodeStore.clear(this, customer)) saveFailed() }
                .setNegativeButton("Abbrechen", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
