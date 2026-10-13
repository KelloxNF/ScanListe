package de.inventur.app

import de.scanportal.app.DataWedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zählen: Artikel als Kacheln (nur Bezeichnung, Farbe = Status). Antippen öffnet das
 * Artikel-Fenster mit allen Werkzeugen. Gescannt wird nur bei geöffnetem Artikel.
 */
class CountActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "inventory_id"
    }

    private lateinit var inv: Inventory
    private lateinit var zones: List<String>
    private var zone = ""
    private var showAll = false
    private var onlyOpen = false     // Filter: nur nicht gezählte
    private var onlyDiff = false     // Filter: nur Differenzen
    private var filter = ""
    private var visible: List<Article> = emptyList()

    // Feste Menge je Scan – gilt für die ganze Zähl-Sitzung, über Artikel hinweg
    private var fixedOn = false
    private var fixedN = 1

    private lateinit var statusText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var search: EditText
    private lateinit var filterInfo: TextView
    private lateinit var recycler: RecyclerView
    private val adapter = Adapter()
    private lateinit var feedback: Feedback

    /** Zweites Fenster über dem Artikel-Fenster (Korrigieren, Löschen, Zuordnen …). */
    private var dialog: AlertDialog? = null

    /** Das geöffnete Artikel-Fenster. */
    private var detail: Detail? = null

    /** Was in dieser Sitzung per Scan/Menge hinzugefügt wurde – nur das macht „Rückgängig“ wieder weg. */
    private data class Added(val sku: String, val zone: String, val qty: Int)
    private val undoStack = ArrayDeque<Added>()

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
        val loaded = intent.getStringExtra(EXTRA_ID)?.let { InventoryStore.load(this, it) }
        if (loaded == null) { finish(); return }
        inv = loaded
        // Auch Bereiche anzeigen, in denen schon gezählt wurde, die aber in den Einstellungen umbenannt sind
        zones = (Settings.zones(this) + inv.countedZones()).distinct()
        zone = zones.first()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = inv.customer.name
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        feedback = Feedback(this)

        val root = vbox()
        statusText = text(sizeSp = 14f, bold = true).apply { setPadding(dp(12), dp(8), dp(12), dp(4)) }
        root.addView(statusText)

        feedbackText = text("Artikel antippen, dann scannen", 16f, color = Color.WHITE).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(Colors.barIdle)
        }
        root.addView(feedbackText)

        search = field("🔍 Suchen: Name oder Artikelnummer").apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filter = s?.toString()?.trim().orEmpty(); refresh() }
            })
            setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter) {
                    val v = text.toString().trim()
                    val hit = if (visible.size == 1) visible[0] else inv.findBySku(v)
                    if (hit != null) openArticle(hit)
                }
                enter
            }
        }
        val searchRow = hbox().apply { setPadding(dp(12), dp(4), dp(4), 0) }
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(button("✕") { search.setText("") }.apply { textSize = 18f }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(searchRow)
        filterInfo = text(sizeSp = 13f, color = Colors.grey).apply { setPadding(dp(14), 0, dp(14), dp(4)) }
        root.addView(filterInfo)

        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@CountActivity, 2)
            adapter = this@CountActivity.adapter
            setPadding(dp(6), dp(2), dp(6), dp(6))
            clipToPadding = false
        }
        root.addView(recycler.lp(height = 0, weight = 1f))
        root.addView(button("Ergebnis & Dokumente  ›") { openResult() }.apply { minHeight = dp(56) }.lp())
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Nach Rückkehr aus Kopfdaten/Ergebnis neu laden (nicht, solange ein Artikel offen ist)
        if (detail == null) InventoryStore.load(this, inv.id)?.let { inv = it; title = it.customer.name; refresh() }
        ContextCompat.registerReceiver(this, scanReceiver, IntentFilter(DataWedge.SCAN_ACTION), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) { unregisterReceiver(scanReceiver); receiverRegistered = false }
    }

    override fun onDestroy() {
        if (::feedback.isInitialized) feedback.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Kacheln

    private fun refresh() {
        var base = if (showAll) inv.articles.toList() else inv.listArticles()
        if (onlyOpen) base = base.filter { inv.counted(it.sku) == null }
        if (onlyDiff) base = base.filter { inv.status(it) == Status.DIFFERENZ || (it.sku in inv.estimated && inv.counted(it.sku) != it.soll) }
        val f = filter.lowercase()
        visible = if (f.isEmpty()) base else base.filter { it.name.lowercase().contains(f) || it.sku.lowercase().contains(f) }
        adapter.notifyDataSetChanged()
        val active = buildList {
            if (onlyOpen) add("nur nicht gezählte")
            if (onlyDiff) add("nur Differenzen")
            if (f.isNotEmpty()) add("Suche „$filter“")
        }
        filterInfo.text = if (active.isEmpty()) "" else "Filter: ${active.joinToString(", ")} – ${visible.size} Artikel"
        filterInfo.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
        val s = inv.summary()
        statusText.text = "Stichtag ${Dates.display(inv.stichtag)} · ${s.articles - s.notCounted} von ${s.articles} Artikeln gezählt" +
            (if (inv.blind) " · Blindzählung" else "") + (if (inv.closed) " · abgeschlossen" else "")
    }

    private fun tileColor(a: Article): Int {
        val ist = inv.counted(a.sku) ?: return Colors.tileNone
        if (inv.blind) return Colors.tileBlind
        return when (inv.status(a)) {
            Status.STIMMT -> Colors.tileOk
            Status.GESCHAETZT -> Colors.tileEst
            Status.DIFFERENZ -> Colors.tileDiff
            Status.NICHT_GEZAEHLT -> if (ist == 0) Colors.tileNone else Colors.tileNone
        }
    }

    private fun showFeedback(msg: String, color: Int) {
        feedbackText.text = msg
        feedbackText.setBackgroundColor(color)
    }

    // ---------------------------------------------------------------- Scannen

    private fun onScan(code: String) {
        if (code.isEmpty()) return
        if (dialog?.isShowing == true) {
            feedback.error()
            val msg = "Bitte zuerst das offene Fenster schließen"
            detail?.say(msg, Colors.barError) ?: showFeedback(msg, Colors.barError)
            return
        }
        val d = detail
        if (d == null) {
            // Ohne geöffneten Artikel wird nichts gezählt – nur ein Hinweis
            feedback.error()
            val known = articleFor(code)
            showFeedback(
                if (known != null) "Nicht gezählt: „${known.name}“ – bitte zuerst den Artikel antippen"
                else "Nicht gezählt – bitte zuerst einen Artikel antippen",
                Colors.barWarn,
            )
            return
        }
        d.onScan(code)
    }

    /** Artikel zu einem Barcode/einer Artikelnummer, falls eindeutig. */
    private fun articleFor(code: String): Article? {
        inv.findBySku(code)?.let { return it }
        val list = BarcodeStore.lookup(this, inv.customer.name, code).mapNotNull { inv.article(it) }
        return list.singleOrNull()
    }

    private fun add(a: Article, n: Int): Boolean {
        val now = System.currentTimeMillis()
        val hhmm = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(now))
        val prevFrom = inv.timeFrom; val prevTo = inv.timeTo; val prevClosed = inv.closed
        inv.addCount(CountEntry(a.sku, zone, n, now))
        if (inv.timeFrom.isBlank()) inv.timeFrom = hhmm
        inv.timeTo = hhmm
        inv.closed = false
        if (!persist()) {
            inv.retract(a.sku, zone, n)
            inv.timeFrom = prevFrom; inv.timeTo = prevTo; inv.closed = prevClosed
            return false
        }
        undoStack.addLast(Added(a.sku, zone, n))
        if (undoStack.size > 500) undoStack.removeFirst()
        feedback.ok()
        refresh()
        return true
    }

    private fun persist(): Boolean = try {
        InventoryStore.save(this, inv); true
    } catch (e: Exception) {
        feedback.error()
        val msg = "✗ Nicht gespeichert – ist der Gerätespeicher voll?"
        detail?.say(msg, Colors.barError) ?: showFeedback(msg, Colors.barError)
        false
    }

    // ---------------------------------------------------------------- Artikel-Fenster

    private fun openArticle(a: Article) {
        if (detail != null || dialog?.isShowing == true) return
        detail = Detail(a).also { it.show() }
    }

    /** Fenster eines geöffneten Artikels mit allen Werkzeugen. Scans zählen nur hier. */
    private inner class Detail(private var a: Article) {
        private val ctx = this@CountActivity
        private val box = ctx.vbox(16)
        private val skuText = ctx.text(sizeSp = 14f, color = Colors.grey)
        private val sollText = ctx.text(sizeSp = 20f, bold = true)
        private val totalText = ctx.text(sizeSp = 34f, bold = true).apply { gravity = Gravity.END }
        private val zonesText = ctx.text(sizeSp = 16f)
        private val zoneButtons: List<Button>
        private val fixedBox = CheckBox(ctx)
        private val fixedQty = ctx.field("1", number = true)
        private val fixedRow = ctx.hbox()
        private val qty = ctx.field("Menge", number = true, signed = true)
        private val bar = ctx.text("Jetzt scannen oder Menge eingeben", 16f, color = Color.WHITE)
        private val dlg: AlertDialog

        init {
            box.addView(skuText)
            val top = ctx.hbox()
            top.addView(sollText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(totalText)
            box.addView(top)
            box.addView(zonesText)

            // Bereich
            val zr = ctx.hbox().apply { setPadding(0, dp(10), 0, dp(4)) }
            zoneButtons = zones.map { z ->
                ctx.button(z) { zone = z; updateZoneButtons(); say("$z gewählt", Colors.barIdle) }.apply {
                    textSize = 14f; maxLines = 1
                    setPadding(dp(2), paddingTop, dp(2), paddingBottom)
                }.also { zr.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            }
            box.addView(zr)

            // Feste Menge je Scan
            fixedBox.text = "Feste Menge je Scan:"
            fixedBox.textSize = 16f
            fixedBox.isChecked = fixedOn
            fixedQty.setText(fixedN.toString())
            fixedQty.textSize = 20f
            fixedQty.gravity = Gravity.CENTER
            fixedBox.setOnCheckedChangeListener { _, c -> fixedOn = c; updateFixedRow() }
            fixedQty.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { fixedN = it }
                    updateFixedRow()
                }
            })
            fixedRow.setPadding(dp(4), dp(2), dp(4), dp(2))
            fixedRow.addView(fixedBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fixedRow.addView(fixedQty, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            fixedRow.addView(ctx.text(" Stk.", 16f))
            box.addView(fixedRow)

            // Menge von Hand
            val mr = ctx.hbox()
            qty.textSize = 22f
            qty.imeOptions = EditorInfo.IME_ACTION_DONE
            qty.setOnEditorActionListener { _, _, event ->
                if (event == null || event.action == KeyEvent.ACTION_DOWN) addTyped()
                true
            }
            mr.addView(qty, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            mr.addView(ctx.button("Hinzufügen") { addTyped() })
            box.addView(mr)

            // Rückmeldung
            bar.minHeight = dp(44)
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.maxLines = 3
            bar.setPadding(dp(12), dp(4), dp(12), dp(4))
            bar.setBackgroundColor(Colors.barIdle)
            box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

            // Werkzeuge
            val tr = ctx.hbox().apply { setPadding(0, dp(8), 0, 0) }
            tr.addView(ctx.button("↶ Rückgängig") { undo() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tr.addView(ctx.button("Korrigieren …") { editArticle(a) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(tr)

            dlg = AlertDialog.Builder(ctx)
                .setTitle(a.name)
                .setView(ScrollView(ctx).apply { addView(box) })
                .setPositiveButton("Fertig", null)
                .create()
            dlg.setOnDismissListener {
                if (detail === this) detail = null
                showFeedback("Artikel antippen, dann scannen", Colors.barIdle)
                refresh()
            }
            render()
            updateZoneButtons()
            updateFixedRow()
        }

        fun show() {
            dlg.show()
            dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun dismiss() = dlg.dismiss()

        fun say(msg: String, color: Int) {
            bar.text = msg
            bar.setBackgroundColor(color)
        }

        /** Artikel neu einlesen (nach Umbenennen/Korrektur) und Anzeige aktualisieren. */
        fun render() {
            inv.article(a.sku)?.let { a = it }
            dlg.setTitle(a.name)
            skuText.text = "Artikelnummer ${a.sku}"
            sollText.text = if (inv.blind) "" else "Soll ${a.soll}"
            val ist = inv.counted(a.sku)
            totalText.text = ist?.toString() ?: "–"
            totalText.setTextColor(
                when {
                    ist == null -> Colors.grey
                    inv.blind -> Colors.ok
                    else -> Colors.status(inv.status(a))
                },
            )
            zonesText.text = zones.joinToString("   ") { z -> "$z: ${inv.zoneQty(a.sku, z) ?: "–"}" } +
                (if (a.sku in inv.estimated) "\n(geschätzt)" else "") +
                (inv.notes[a.sku]?.takeIf { it.isNotBlank() }?.let { "\n📝 $it" } ?: "")
        }

        private fun updateZoneButtons() {
            zoneButtons.forEachIndexed { i, b ->
                val sel = zones[i] == zone
                b.setBackgroundColor(if (sel) Colors.brand else Color.rgb(225, 229, 236))
                b.setTextColor(if (sel) Color.WHITE else Color.BLACK)
            }
        }

        private fun updateFixedRow() {
            val on = fixedAmount() != null
            fixedRow.setBackgroundColor(if (on) Colors.estBg else Color.TRANSPARENT)
            fixedBox.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }

        private fun addTyped() {
            val n = qty.text.toString().trim().toIntOrNull()
            if (n == null || n == 0) { qty.error = "Menge eingeben"; return }
            if ((inv.zoneQty(a.sku, zone) ?: 0) + n < 0) { qty.error = "Dann wäre die Menge in $zone negativ"; return }
            if (counted(n)) qty.setText("")
        }

        private fun counted(n: Int): Boolean {
            if (!add(a, n)) return false
            val sign = if (n > 0) "+$n" else "$n"
            say("✓ $sign in $zone  →  gesamt ${inv.counted(a.sku) ?: 0}", Colors.barOk)
            render()
            return true
        }

        fun onScan(code: String) {
            val isThis = inv.findBySku(code)?.sku == a.sku ||
                a.sku in BarcodeStore.lookup(ctx, inv.customer.name, code)
            if (isThis) {
                val f = fixedAmount()
                if (f != null) {
                    counted(f)
                } else {
                    feedback.ok()
                    say("Artikel erkannt – Menge eingeben und „Hinzufügen“", Colors.barIdle)
                    qty.requestFocus()
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(qty, 0)
                }
                return
            }
            val other = articleFor(code)
            val anyKnown = inv.findBySku(code) != null || BarcodeStore.lookup(ctx, inv.customer.name, code).isNotEmpty()
            feedback.error()
            if (anyKnown) {
                say("✗ Falscher Artikel" + (other?.let { ": „${it.name}“" } ?: "") + " – nicht gezählt", Colors.barError)
            } else {
                assignBarcode(code)
            }
        }

        /** Unbekannter Barcode bei geöffnetem Artikel: diesem Artikel zuordnen? */
        private fun assignBarcode(code: String) {
            say("Unbekannter Barcode $code", Colors.barWarn)
            dialog = AlertDialog.Builder(ctx)
                .setTitle("Barcode zuordnen?")
                .setMessage("$code ist noch keinem Artikel zugeordnet.\n\nGehört er zu „${a.name}“?\n\nHaben Neu- und Refware dieselbe EAN, kann derselbe Barcode beiden Artikeln zugeordnet werden.")
                .setPositiveButton("Ja, zuordnen") { _, _ ->
                    dialog = null
                    val list = (BarcodeStore.lookup(ctx, inv.customer.name, code) + a.sku).distinct()
                    BarcodeStore.assign(ctx, inv.customer.name, code, list)
                    say("✓ Barcode zugeordnet – jetzt wird er bei diesem Artikel gezählt", Colors.barOk)
                    onScan(code)
                }
                .setNegativeButton("Nein", null)
                .setOnDismissListener { if (dialog?.isShowing != true) dialog = null }
                .show()
        }
    }

    /** Feste Menge je Scan, falls eingeschaltet und gültig, sonst null (= Menge abfragen). */
    private fun fixedAmount(): Int? = if (fixedOn && fixedN > 0) fixedN else null

    // ---------------------------------------------------------------- Korrigieren / Löschen

    private fun editArticle(a: Article) {
        if (dialog?.isShowing == true) return
        val box = vbox(20)
        box.addView(text("Artikelnummer ${a.sku}", 13f, color = Colors.grey))
        box.addView(text("Bezeichnung", 13f, bold = true))
        val nameField = field().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(a.name)
        }
        box.addView(nameField)
        if (!inv.blind) box.addView(text("Soll: ${a.soll}", 14f).apply { setPadding(0, dp(8), 0, 0) })
        val sollBox = CheckBox(this).apply { text = "Bestand stimmt (${a.soll} in „$zone“ eintragen)" }
        // Nur bei positivem Soll sinnvoll – ein negativer oder leerer Systembestand kann nicht „stimmen“
        if (!inv.blind && a.soll > 0) box.addView(sollBox)
        val fields = zones.map { z ->
            val row = hbox()
            row.addView(text(z, 15f).lp(dp(110)))
            val f = field("–", number = true).apply { setText(inv.zoneQty(a.sku, z)?.toString() ?: "") }
            row.addView(f.lp(0, weight = 1f))
            box.addView(row)
            z to f
        }
        // Bestand stimmt: Soll im aktuell gewählten Bereich eintragen, übrige Bereiche leeren;
        // beim Abhaken werden die vorherigen Werte wiederhergestellt.
        var previous: List<String> = emptyList()
        sollBox.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                previous = fields.map { it.second.text.toString() }
                for ((z, f) in fields) f.setText(if (z == zone) a.soll.toString() else "")
            } else {
                fields.forEachIndexed { i, (_, f) -> f.setText(previous.getOrElse(i) { "" }) }
            }
        }
        val est = CheckBox(this).apply { text = "Menge geschätzt"; isChecked = a.sku in inv.estimated }
        box.addView(est)
        val note = field("Bemerkung").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(inv.notes[a.sku] ?: "")
        }
        box.addView(note)
        box.addView(text("Leeres Feld = in diesem Bereich nicht gezählt.", 12f, color = Colors.grey))

        val d = AlertDialog.Builder(this)
            .setTitle("Korrigieren: ${a.name}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Speichern") { _, _ ->
                val newName = nameField.text.toString().trim()
                if (newName.isNotEmpty() && newName != a.name) {
                    inv.rename(a.sku, newName)
                    NameStore.put(this, inv.customer.name, a.sku, newName)
                }
                for ((z, f) in fields) {
                    val v = f.text.toString().trim().toIntOrNull()
                    if (v != inv.zoneQty(a.sku, z)) {
                        inv.setZone(a.sku, z, v)
                        // Frühere Scans dieses Bereichs sind jetzt durch die Korrektur ersetzt
                        undoStack.removeAll { it.sku == a.sku && it.zone == z }
                    }
                }
                if (est.isChecked) inv.estimated.add(a.sku) else inv.estimated.remove(a.sku)
                val n = note.text.toString().trim()
                if (n.isEmpty()) inv.notes.remove(a.sku) else inv.notes[a.sku] = n
                inv.closed = false
                if (persist()) detail?.say("✓ Gespeichert – gesamt ${inv.counted(a.sku) ?: "–"}", Colors.barOk)
                detail?.render()
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .setNeutralButton("Löschen") { _, _ -> dialog = null; confirmDelete(a) }
            .create()
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    private fun confirmDelete(a: Article) {
        val c = inv.counted(a.sku)
        dialog = AlertDialog.Builder(this)
            .setTitle("Artikel löschen?")
            .setMessage(
                "${a.name}\n${a.sku}\n\nDer Artikel wird aus dieser Inventur entfernt" +
                    (if (c != null) " – mit allen gezählten Mengen ($c Stk.)" else "") +
                    ". Er erscheint dann weder in der Zählliste noch im Anschreiben.\n\n" +
                    "Bei einem erneuten Import des Systembestands ist er wieder da.",
            )
            .setPositiveButton("Löschen") { _, _ ->
                dialog = null
                val backup = InventoryStore.toJson(inv).toString()
                inv.removeArticle(a.sku)
                undoStack.removeAll { it.sku == a.sku }
                if (persist()) {
                    detail?.dismiss()
                    showFeedback("🗑 ${a.name} gelöscht", Colors.barIdle)
                } else {
                    inv = InventoryStore.fromJson(org.json.JSONObject(backup))
                }
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .setOnDismissListener { if (dialog?.isShowing != true) dialog = null }
            .show()
    }

    private fun undo() {
        // Nur Scans/Mengen dieser Sitzung zurücknehmen – Korrekturen bleiben unangetastet.
        while (true) {
            val last = undoStack.removeLastOrNull()
            if (last == null) {
                val msg = "Nichts mehr rückgängig zu machen – Korrekturen über „Korrigieren“"
                detail?.say(msg, Colors.barIdle) ?: toast(msg)
                return
            }
            if (!inv.retract(last.sku, last.zone, last.qty)) continue  // inzwischen korrigiert
            val name = inv.article(last.sku)?.name ?: last.sku
            if (persist()) {
                val msg = "↶ Rückgängig: $name ${last.qty} in ${last.zone}"
                detail?.say(msg, Colors.barIdle) ?: showFeedback(msg, Colors.barIdle)
            } else {
                inv.addCount(CountEntry(last.sku, last.zone, last.qty, System.currentTimeMillis()))
                undoStack.addLast(last)
            }
            detail?.render()
            refresh()
            return
        }
    }

    private fun openResult() {
        startActivity(Intent(this, ResultActivity::class.java).putExtra(EXTRA_ID, inv.id))
    }

    // ---------------------------------------------------------------- Menü

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 5, 0, "Filter: nur nicht gezählte").apply { isCheckable = true; isChecked = onlyOpen }
        menu.add(0, 6, 0, "Filter: nur Differenzen").apply { isCheckable = true; isChecked = onlyDiff }
        menu.add(0, 2, 0, "Artikel ohne Bestand zeigen").apply { isCheckable = true; isChecked = showAll }
        menu.add(0, 1, 0, "Letzte Zählung rückgängig")
        menu.add(0, 3, 0, "Kopfdaten bearbeiten")
        menu.add(0, 4, 0, "Barcode-Zuordnungen dieses Kunden löschen")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> undo()
            2 -> { showAll = !showAll; item.isChecked = showAll; refresh() }
            5 -> { onlyOpen = !onlyOpen; item.isChecked = onlyOpen; refresh() }
            6 -> { onlyDiff = !onlyDiff; item.isChecked = onlyDiff; refresh() }
            3 -> startActivity(Intent(this, EditActivity::class.java).putExtra(EXTRA_ID, inv.id))
            4 -> AlertDialog.Builder(this)
                .setTitle("Barcode-Zuordnungen löschen?")
                .setMessage("${BarcodeStore.count(this, inv.customer.name)} gespeicherte Zuordnungen für ${inv.customer.name}. Unbekannte Barcodes werden danach wieder abgefragt.")
                .setPositiveButton("Löschen") { _, _ -> BarcodeStore.clear(this, inv.customer.name) }
                .setNegativeButton("Abbrechen", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------------------------------------------------------------- Kachel-Liste

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val frame: FrameLayout, val tile: TextView) : RecyclerView.ViewHolder(frame)

        override fun getItemCount() = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val frame = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            val tile = TextView(ctx).apply {
                minHeight = dp(96)
                gravity = Gravity.CENTER
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 4
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true
                isFocusable = true
                val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                foreground = ta.getDrawable(0)
                ta.recycle()
            }
            frame.addView(tile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return VH(frame, tile)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = visible[position]
            h.tile.text = a.name
            h.tile.setTextColor(Colors.tileText)
            h.tile.background = GradientDrawable().apply {
                cornerRadius = dpf(14f)
                setColor(tileColor(a))
            }
            h.tile.contentDescription = a.name + " – " + inv.status(a).label
            h.tile.setOnClickListener { openArticle(a) }
        }
    }
}
