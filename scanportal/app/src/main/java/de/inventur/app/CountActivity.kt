package de.inventur.app

import de.scanportal.app.DataWedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zählen: Bereich wählen, Artikel scannen oder antippen, Menge eintragen. */
class CountActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "inventory_id"
    }

    private lateinit var inv: Inventory
    private lateinit var zones: List<String>
    private var zone = ""
    private var showAll = false
    private var filter = ""
    private var visible: List<Article> = emptyList()

    private lateinit var statusText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var pieceMode: CheckBox
    private lateinit var search: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var zoneButtons: List<Button>
    private val adapter = Adapter()
    private lateinit var feedback: Feedback
    private var dialog: AlertDialog? = null

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
        zone = savedInstanceState?.getString("zone")?.takeIf { it in zones } ?: zones.first()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = inv.customer.name
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        feedback = Feedback(this)

        val root = vbox()
        statusText = text(sizeSp = 14f, bold = true).apply { setPadding(dp(12), dp(8), dp(12), dp(4)) }
        root.addView(statusText)

        // Bereichsauswahl
        val zoneRow = hbox().apply { setPadding(dp(8), 0, dp(8), 0) }
        zoneButtons = zones.map { z ->
            button(z) { zone = z; updateZoneButtons(); showFeedback("$z gewählt", Colors.barIdle) }.apply {
                textSize = 14f
                maxLines = 1
                setPadding(dp(2), paddingTop, dp(2), paddingBottom)
            }.also {
                zoneRow.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        root.addView(zoneRow)

        pieceMode = CheckBox(this).apply {
            text = "Jeder Scan = 1 Stück (sonst Menge eingeben)"
            textSize = 14f
            isChecked = getSharedPreferences("count", MODE_PRIVATE).getBoolean("piece", false)
            setOnCheckedChangeListener { _, c -> getSharedPreferences("count", MODE_PRIVATE).edit().putBoolean("piece", c).apply() }
            setPadding(dp(4), 0, 0, 0)
        }
        root.addView(pieceMode.lp().apply { setPadding(dp(8), 0, dp(8), 0) })

        feedbackText = text("Bereit – Artikel scannen oder antippen", 16f, color = Color.WHITE).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(Colors.barIdle)
        }
        root.addView(feedbackText)

        search = field("Suchen oder Artikelnummer eingeben").apply {
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
                    if (v.isNotEmpty()) {
                        if (visible.size == 1) countArticle(visible[0]) else onScan(v)
                        setText("")
                    }
                }
                enter
            }
        }
        root.addView(search.lp().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) })

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CountActivity)
            adapter = this@CountActivity.adapter
        }
        root.addView(recycler.lp(height = 0, weight = 1f))
        root.addView(button("Ergebnis & Dokumente  ›") { openResult() }.apply { minHeight = dp(56) }.lp())
        setContentView(root)

        updateZoneButtons()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("zone", zone)
    }

    override fun onResume() {
        super.onResume()
        // Nach Rückkehr aus Kopfdaten/Ergebnis neu laden
        InventoryStore.load(this, inv.id)?.let { inv = it; title = it.customer.name; refresh() }
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

    // ---------------------------------------------------------------- Anzeige

    private fun updateZoneButtons() {
        zoneButtons.forEachIndexed { i, b ->
            val sel = zones[i] == zone
            b.setBackgroundColor(if (sel) Colors.brand else Color.rgb(225, 229, 236))
            b.setTextColor(if (sel) Color.WHITE else Color.BLACK)
        }
    }

    private fun refresh() {
        val base = if (showAll) inv.articles else inv.listArticles()
        val f = filter.lowercase()
        visible = if (f.isEmpty()) base else base.filter { it.name.lowercase().contains(f) || it.sku.lowercase().contains(f) }
        adapter.notifyDataSetChanged()
        val s = inv.summary()
        statusText.text = "Stichtag ${Dates.display(inv.stichtag)} · ${s.articles - s.notCounted} von ${s.articles} Artikeln gezählt" +
            (if (inv.blind) " · Blindzählung" else "") + (if (inv.closed) " · abgeschlossen" else "")
    }

    private fun showFeedback(msg: String, color: Int) {
        feedbackText.text = msg
        feedbackText.setBackgroundColor(color)
    }

    // ---------------------------------------------------------------- Scannen

    private fun onScan(value: String) {
        if (value.isEmpty()) return
        if (dialog?.isShowing == true) {
            feedback.error()
            showFeedback("Bitte zuerst das offene Fenster schließen", Colors.barError)
            return
        }
        inv.findBySku(value)?.let { countArticle(it); return }
        val known = BarcodeStore.lookup(this, inv.customer.name, value).mapNotNull { inv.article(it) }
        when {
            known.size == 1 -> countArticle(known[0])
            known.size > 1 -> chooseArticle(value, known)
            else -> assignBarcode(value)
        }
    }

    /** Barcode gehört zu mehreren Artikeln (z. B. Neu- und Refware) → nachfragen. */
    private fun chooseArticle(code: String, options: List<Article>) {
        feedback.ok()
        dialog = AlertDialog.Builder(this)
            .setTitle("Welcher Artikel? ($code)")
            .setItems(options.map { "${it.name}\n${it.sku}" }.toTypedArray()) { _, which ->
                dialog = null
                countArticle(options[which])
            }
            .setNegativeButton("Abbrechen", null)
            .setOnDismissListener { if (dialog?.isShowing != true) dialog = null }
            .show()
    }

    /** Unbekannter Barcode → einmalig einem oder mehreren Artikeln zuordnen. */
    private fun assignBarcode(code: String) {
        feedback.error()
        showFeedback("Unbekannter Barcode $code – bitte zuordnen. Gleiche EAN bei Neu- und Refware? Beide ankreuzen.", Colors.barWarn)
        val all = inv.listArticles() + inv.articles.filter { it.soll == 0 && !inv.hasCount(it.sku) }
        val checked = BooleanArray(all.size)
        dialog = AlertDialog.Builder(this)
            .setTitle("Barcode $code zuordnen")
            .setMultiChoiceItems(all.map { "${it.name}  (${it.sku})" }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Zuordnen") { _, _ ->
                val chosen = all.filterIndexed { i, _ -> checked[i] }
                dialog = null
                if (chosen.isEmpty()) return@setPositiveButton
                BarcodeStore.assign(this, inv.customer.name, code, chosen.map { it.sku })
                if (chosen.size == 1) countArticle(chosen[0]) else chooseArticle(code, chosen)
            }
            .setNegativeButton("Abbrechen", null)
            .setOnDismissListener { if (dialog?.isShowing != true) dialog = null }
            .show()
    }

    /** Ein Artikel wurde erkannt: entweder +1 oder Menge abfragen. */
    private fun countArticle(a: Article) {
        if (pieceMode.isChecked) {
            add(a, 1)
        } else {
            feedback.ok()
            askQuantity(a)
        }
    }

    private fun askQuantity(a: Article) {
        val box = vbox(20)
        val before = inv.zoneQty(a.sku, zone)
        box.addView(text(a.sku, 13f, color = Colors.grey))
        box.addView(text("$zone · bisher hier: ${before ?: "–"} · gesamt: ${inv.counted(a.sku) ?: "–"}", 14f))
        val qty = field("Menge", number = true, signed = true).apply { textSize = 26f }
        box.addView(qty)
        val d = AlertDialog.Builder(this)
            .setTitle(a.name)
            .setView(box)
            .setPositiveButton("OK", null)
            .setNeutralButton("Ändern") { _, _ -> dialog = null; editArticle(a) }
            .setNegativeButton("Abbrechen", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.setOnShowListener {
            qty.requestFocus()
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = qty.text.toString().trim().toIntOrNull()
                if (n == null || n == 0) { qty.error = "Menge eingeben"; return@setOnClickListener }
                d.dismiss()
                add(a, n)
            }
        }
        // Nur einmal auslösen: Eine Hardware-Enter-Taste meldet sich beim Drücken UND beim Loslassen.
        qty.setOnEditorActionListener { _, _, event ->
            if ((event == null || event.action == KeyEvent.ACTION_DOWN) && d.isShowing) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            true
        }
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    private fun add(a: Article, n: Int) {
        val now = System.currentTimeMillis()
        val hhmm = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(now))
        val prevFrom = inv.timeFrom; val prevTo = inv.timeTo; val prevClosed = inv.closed
        val entry = CountEntry(a.sku, zone, n, now)
        inv.addCount(entry)
        if (inv.timeFrom.isBlank()) inv.timeFrom = hhmm
        inv.timeTo = hhmm
        inv.closed = false
        if (!persist()) {
            inv.retract(a.sku, zone, n)
            inv.timeFrom = prevFrom; inv.timeTo = prevTo; inv.closed = prevClosed
            return
        }
        undoStack.addLast(Added(a.sku, zone, n))
        if (undoStack.size > 500) undoStack.removeFirst()
        feedback.ok()
        val total = inv.counted(a.sku) ?: 0
        val sign = if (n > 0) "+$n" else "$n"
        showFeedback("✓ ${a.name}  $sign in $zone  →  gesamt $total", Colors.barOk)
        refresh()
        visible.indexOf(a).takeIf { it >= 0 }?.let { recycler.scrollToPosition(it) }
    }

    private fun persist(): Boolean = try {
        InventoryStore.save(this, inv); true
    } catch (e: Exception) {
        feedback.error()
        showFeedback("✗ Nicht gespeichert – ist der Gerätespeicher voll?", Colors.barError)
        false
    }

    // ---------------------------------------------------------------- Bearbeiten

    private fun editArticle(a: Article) {
        val box = vbox(20)
        box.addView(text("Artikelnummer ${a.sku}", 13f, color = Colors.grey))
        box.addView(text("Bezeichnung", 13f, bold = true))
        val nameField = field().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(a.name)
        }
        box.addView(nameField)
        if (!inv.blind) box.addView(text("Soll: ${a.soll}", 14f).apply { setPadding(0, dp(8), 0, 0) })
        val sollBox = CheckBox(this).apply { text = "Ist = Sollbestand (${a.soll} in „$zone“ eintragen)" }
        if (!inv.blind) box.addView(sollBox)
        val fields = zones.map { z ->
            val row = hbox()
            row.addView(text(z, 15f).lp(dp(110)))
            val f = field("–", number = true).apply { setText(inv.zoneQty(a.sku, z)?.toString() ?: "") }
            row.addView(f.lp(0, weight = 1f))
            box.addView(row)
            z to f
        }
        // Ist = Soll: Soll im aktuell gewählten Bereich eintragen, übrige Bereiche leeren;
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
            .setTitle(a.name)
            .setView(android.widget.ScrollView(this).apply { addView(box) })
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
                if (persist()) showFeedback("✓ ${inv.article(a.sku)?.name ?: a.name} gespeichert – gesamt ${inv.counted(a.sku) ?: "–"}", Colors.barOk)
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .create()
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    private fun undo() {
        // Nur Scans/Mengen dieser Sitzung zurücknehmen – Korrekturen aus „Ändern“ bleiben unangetastet.
        while (true) {
            val last = undoStack.removeLastOrNull()
            if (last == null) {
                toast("Nichts mehr rückgängig zu machen. Korrekturen bitte über „Ändern“ (lange tippen).")
                return
            }
            if (!inv.retract(last.sku, last.zone, last.qty)) continue  // inzwischen per „Ändern“ geändert
            if (persist()) {
                val name = inv.article(last.sku)?.name ?: last.sku
                showFeedback("↶ Rückgängig: $name ${last.qty} in ${last.zone}", Colors.barIdle)
            } else {
                inv.addCount(CountEntry(last.sku, last.zone, last.qty, System.currentTimeMillis()))
                undoStack.addLast(last)
            }
            refresh()
            return
        }
    }

    private fun openResult() {
        startActivity(Intent(this, ResultActivity::class.java).putExtra(EXTRA_ID, inv.id))
    }

    // ---------------------------------------------------------------- Menü

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Letzte Zählung rückgängig")
        menu.add(0, 2, 0, "Artikel ohne Bestand zeigen").apply { isCheckable = true; isChecked = showAll }
        menu.add(0, 3, 0, "Kopfdaten bearbeiten")
        menu.add(0, 4, 0, "Barcode-Zuordnungen dieses Kunden löschen")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> undo()
            2 -> { showAll = !showAll; item.isChecked = showAll; refresh() }
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

    // ---------------------------------------------------------------- Liste

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val root: LinearLayout, val name: TextView, val sku: TextView, val zonesText: TextView, val count: TextView, val soll: TextView) :
            RecyclerView.ViewHolder(root)

        override fun getItemCount() = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val root = ctx.hbox().apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val left = ctx.vbox()
            val name = ctx.text(sizeSp = 16f, bold = true)
            val sku = ctx.text(sizeSp = 12f, color = Colors.grey)
            val zt = ctx.text(sizeSp = 13f)
            left.addView(name); left.addView(sku); left.addView(zt)
            root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val right = ctx.vbox().apply { gravity = Gravity.END }
            val count = ctx.text(sizeSp = 24f, bold = true).apply { gravity = Gravity.END }
            val soll = ctx.text(sizeSp = 12f, color = Colors.grey).apply { gravity = Gravity.END }
            right.addView(count); right.addView(soll)
            root.addView(right, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            return VH(root, name, sku, zt, count, soll)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = visible[position]
            val ist = inv.counted(a.sku)
            val st = inv.status(a)
            h.name.text = a.name
            h.sku.text = a.sku + (if (inv.notes[a.sku]?.isNotBlank() == true) "  · 📝" else "")
            h.zonesText.text = zones.mapNotNull { z -> inv.zoneQty(a.sku, z)?.let { "$z: $it" } }.joinToString("   ")
                .ifEmpty { "noch nicht gezählt" } + if (a.sku in inv.estimated) "   (geschätzt)" else ""
            h.count.text = ist?.toString() ?: "–"
            h.soll.text = if (inv.blind) "" else "Soll ${a.soll}"
            val bg = when {
                ist == null -> if (position % 2 == 1) Color.argb(14, 0, 0, 0) else Color.TRANSPARENT
                inv.blind -> Colors.okBg
                st == Status.STIMMT -> Colors.okBg
                st == Status.GESCHAETZT -> Colors.estBg
                else -> Colors.diffBg
            }
            h.root.setBackgroundColor(bg)
            h.count.setTextColor(
                when {
                    ist == null -> Colors.grey
                    inv.blind || st == Status.STIMMT -> Colors.ok
                    st == Status.GESCHAETZT -> Colors.warn
                    else -> Colors.error
                },
            )
            h.root.setOnClickListener { if (dialog?.isShowing != true) askQuantity(a) }
            h.root.setOnLongClickListener { if (dialog?.isShowing != true) editArticle(a); true }
        }
    }
}
