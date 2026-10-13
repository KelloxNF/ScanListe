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
import android.text.InputType
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zählen nach dem Beispiel „Inventur Kachelansicht“:
 * Übersicht = Liste schlichter Kacheln (nur Bezeichnung, Farbe = Status), Suche, Filterleiste
 * (über ⋮ ein-/ausschaltbar). Antippen öffnet den Artikel-Bildschirm mit allen Werkzeugen.
 * Gescannt wird nur bei geöffnetem Artikel.
 */
class CountActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "inventory_id"
    }

    private enum class StateKey(val label: String) { OPEN("Nicht gezählt"), OK("Stimmt"), BAD("Differenz"), EST("Geschätzt") }

    private lateinit var inv: Inventory
    private lateinit var zones: List<String>
    private var zone = ""
    private var showAll = false
    private var chipsVisible = false
    private var chipFilter: StateKey? = null   // null = Alle
    private var query = ""
    private var visible: List<Article> = emptyList()

    // Feste Menge je Scan – gilt für die ganze Zähl-Sitzung, über Artikel hinweg
    private var fixedOn = false
    private var fixedN = 1

    /** Geöffneter Artikel (Artikel-Bildschirm) oder null (Übersicht). */
    private var cur: Article? = null

    /** Offenes Unterfenster (Bottom-Sheet). */
    private var sheet: BottomSheetDialog? = null

    /** Was in dieser Sitzung per Scan/Menge hinzugefügt wurde – nur das macht „Rückgängig“ wieder weg. */
    private data class Added(val sku: String, val zone: String, val qty: Int)
    private val undoStack = ArrayDeque<Added>()

    // Übersicht
    private lateinit var overview: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var search: EditText
    private lateinit var filterInfo: TextView
    private lateinit var chipsScroll: HorizontalScrollView
    private val chipViews = LinkedHashMap<StateKey?, Button>()
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val adapter = Adapter()

    // Artikel-Bildschirm
    private lateinit var detail: ScrollView
    private lateinit var zoneButtons: List<Button>
    private lateinit var countCard: LinearLayout
    private lateinit var dIst: TextView
    private lateinit var dSoll: TextView
    private lateinit var dState: TextView
    private lateinit var dZones: TextView
    private lateinit var feedbackBar: TextView
    private lateinit var fixedRow: LinearLayout
    private lateinit var fixedBox: CheckBox
    private lateinit var fixedQty: EditText
    private lateinit var tSoll: LinearLayout
    private lateinit var tSollVal: TextView
    private lateinit var tEstVal: TextView
    private lateinit var tNoteVal: TextView

    private lateinit var tones: Feedback
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
        zones = (Settings.zones(this) + inv.countedZones()).distinct()
        zone = zones.first()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tones = Feedback(this)

        val host = FrameLayout(this).apply { setBackgroundColor(Colors.appBg) }
        overview = buildOverview()
        detail = buildDetail()
        detail.visibility = View.GONE
        host.addView(overview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(detail, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(host)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (cur != null) closeArticle() else finish()
            }
        })
        updateTitle()
        renderList()
    }

    override fun onResume() {
        super.onResume()
        // Nach Rückkehr aus Kopfdaten/Ergebnis neu laden (nicht, solange ein Artikel offen ist)
        if (cur == null) InventoryStore.load(this, inv.id)?.let { inv = it; updateTitle(); renderList() }
        ContextCompat.registerReceiver(this, scanReceiver, IntentFilter(DataWedge.SCAN_ACTION), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) { unregisterReceiver(scanReceiver); receiverRegistered = false }
    }

    override fun onDestroy() {
        sheet?.dismiss()
        if (::tones.isInitialized) tones.release()
        super.onDestroy()
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        cornerRadius = dpf(radiusDp)
        setColor(fill)
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

    private fun updateTitle() {
        val a = cur
        if (a == null) {
            supportActionBar?.title = inv.customer.name
            supportActionBar?.subtitle = "Inventur zählen"
        } else {
            supportActionBar?.title = a.name
            supportActionBar?.subtitle = a.sku + if (inv.notes[a.sku]?.isNotBlank() == true) "  · 📝" else ""
        }
        invalidateOptionsMenu()
    }

    // ================================================================ Übersicht

    private fun buildOverview(): LinearLayout {
        val root = vbox()
        statusLine = text(sizeSp = 13f, bold = true, color = Colors.appSub).apply { setPadding(dp(14), dp(8), dp(14), 0) }
        root.addView(statusLine)

        // Suche (Pille mit Lupe und ✕)
        val pill = FrameLayout(this).apply {
            background = rounded(Colors.cardBg, 22f, Colors.line)
        }
        search = EditText(this).apply {
            hint = "Filtern: Name oder Artikelnummer"
            textSize = 16f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = null
            setTextColor(Colors.appFg)
            setHintTextColor(Colors.appSub)
            setPadding(dp(40), 0, dp(44), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); renderList() }
            })
            setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter) {
                    val hit = if (visible.size == 1) visible[0] else inv.findBySku(query)
                    if (hit != null) openArticle(hit)
                }
                enter
            }
        }
        pill.addView(search, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        pill.addView(text("🔍", 15f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(dp(40), dp(44), Gravity.START))
        pill.addView(text("✕", 18f, color = Colors.appSub).apply {
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = "Suche leeren"
            setOnClickListener { search.setText("") }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END))
        root.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { setMargins(dp(14), dp(8), dp(14), 0) })

        filterInfo = text(sizeSp = 12f, color = Colors.appSub).apply { setPadding(dp(16), dp(4), dp(16), 0); visibility = View.GONE }
        root.addView(filterInfo)

        // Filterleiste (über ⋮ ein-/ausschaltbar)
        val chips = hbox().apply { setPadding(dp(14), dp(8), dp(14), 0) }
        for (k in listOf(null, StateKey.OPEN, StateKey.BAD, StateKey.EST, StateKey.OK)) {
            val b = Button(this).apply {
                text = k?.label ?: "Alle"
                isAllCaps = false
                textSize = 13f
                minHeight = 0; minimumHeight = 0
                setPadding(dp(12), 0, dp(12), 0)
                stateListAnimator = null
                setOnClickListener { chipFilter = k; updateChips(); renderList() }
            }
            chipViews[k] = b
            chips.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(6) })
        }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
            visibility = View.GONE
        }
        root.addView(chipsScroll)
        updateChips()

        val listHost = FrameLayout(this)
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CountActivity)
            adapter = this@CountActivity.adapter
            setPadding(dp(14), dp(10), dp(14), dp(12))
            clipToPadding = false
        }
        empty = text("Kein Artikel passt zum Filter.", 14f, color = Colors.appSub).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(30), dp(10), dp(30))
            visibility = View.GONE
        }
        listHost.addView(recycler)
        listHost.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(listHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(flatButton("Ergebnis & Dokumente ›", Colors.chipBg, Colors.appFg) { openResult() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(dp(14), 0, dp(14), dp(12)) })
        return root
    }

    private fun flatButton(label: String, bg: Int, fg: Int, stroke: Int? = null, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(fg)
        background = rounded(bg, 12f, stroke)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun updateChips() {
        for ((k, b) in chipViews) {
            b.text = k?.let { label(it) } ?: "Alle"
            val on = k == chipFilter
            b.background = rounded(if (on) Colors.brand else Colors.cardBg, 16f, if (on) Colors.brand else Colors.line)
            b.setTextColor(if (on) Color.WHITE else Colors.appFg)
        }
    }

    private fun state(a: Article): StateKey {
        val ist = inv.counted(a.sku) ?: return StateKey.OPEN
        if (a.sku in inv.estimated) return StateKey.EST
        if (inv.blind) return StateKey.OK
        return if (ist == a.soll) StateKey.OK else StateKey.BAD
    }

    /** Bei Blindzählung bedeutet grün nur „gezählt“ – nicht „stimmt“ (der Soll ist ja unbekannt). */
    private fun label(k: StateKey) = if (inv.blind && k == StateKey.OK) "Gezählt" else k.label

    private fun fill(k: StateKey) = when (k) {
        StateKey.OPEN -> Colors.openBg; StateKey.OK -> Colors.okFill; StateKey.BAD -> Colors.badFill; StateKey.EST -> Colors.estFill
    }

    private fun edge(k: StateKey) = when (k) {
        StateKey.OPEN -> Colors.openEdge; StateKey.OK -> Colors.okEdge; StateKey.BAD -> Colors.badEdge; StateKey.EST -> Colors.estEdge
    }

    private fun renderList() {
        val base = if (showAll) inv.articles.toList() else inv.listArticles()
        val q = query.lowercase()
        visible = base.filter { a ->
            (q.isEmpty() || a.name.lowercase().contains(q) || a.sku.lowercase().contains(q)) &&
                (chipFilter == null || state(a) == chipFilter)
        }
        adapter.notifyDataSetChanged()
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        filterInfo.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        filterInfo.text = "Filter aktiv: ${visible.size} von ${base.size} Artikeln"
        val done = base.count { inv.counted(it.sku) != null }
        statusLine.text = "Stichtag ${Dates.display(inv.stichtag)} · $done von ${base.size} Artikeln gezählt" +
            (if (inv.blind) " · Blindzählung" else "") + (if (inv.closed) " · abgeschlossen" else "")
    }

    private var lastToast: Toast? = null

    /** Immer nur ein Hinweis gleichzeitig – sonst stauen sich bei schnellem Scannen dutzende Meldungen. */
    private fun toastShort(msg: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    // ================================================================ Artikel-Bildschirm

    private fun buildDetail(): ScrollView {
        val body = vbox().apply { setPadding(dp(14), dp(10), dp(14), dp(16)) }
        fun gap(v: View, top: Int = 10) = body.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })

        // Bereiche
        val zr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zoneButtons = zones.mapIndexed { i, z ->
            Button(this).apply {
                text = z
                isAllCaps = false
                textSize = 14f
                maxLines = 1
                minHeight = 0; minimumHeight = 0
                setPadding(dp(2), 0, dp(2), 0)
                stateListAnimator = null
                setOnClickListener { zone = z; renderZones(); renderDetail(); say("$z gewählt") }
            }.also { zr.addView(it, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) marginStart = dp(6) }) }
        }
        gap(zr, 0)

        // Zählkarte
        countCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val left = vbox()
        left.addView(text("GESAMT GEZÄHLT", 12f, color = Colors.appSub).apply { letterSpacing = 0.06f })
        dIst = text("–", 52f, bold = true, color = Colors.appFg).apply { includeFontPadding = false }
        left.addView(dIst)
        countCard.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = vbox().apply { gravity = Gravity.END }
        dSoll = text(sizeSp = 18f, color = Colors.appFg).apply { gravity = Gravity.END }
        dState = text(sizeSp = 14f, color = Colors.appSub).apply { gravity = Gravity.END }
        right.addView(dSoll); right.addView(dState)
        countCard.addView(right)
        gap(countCard)

        dZones = text(sizeSp = 14f, color = Colors.appFg).apply { setPadding(dp(2), 0, dp(2), 0) }
        gap(dZones)

        feedbackBar = text("Bereit – Artikel scannen", 14f, color = Color.WHITE).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        say("Bereit – Artikel scannen")
        gap(feedbackBar)

        gap(flatButton("Menge eingeben", Colors.accent, Color.WHITE) { askQty() }.apply { textSize = 17f }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        })
        (body.getChildAt(body.childCount - 1).layoutParams as LinearLayout.LayoutParams).height = dp(54)

        // Feste Menge je Scan
        fixedRow = hbox().apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        fixedBox = CheckBox(this).apply {
            text = "Feste Menge je Scan:"
            textSize = 15f
            setTextColor(Colors.appFg)
            setOnCheckedChangeListener { _, c -> fixedOn = c; renderFixed() }
        }
        fixedQty = EditText(this).apply {
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Colors.appFg)
            background = rounded(Colors.cardBg, 8f, Colors.line)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    fixedN = s?.toString()?.trim()?.toIntOrNull() ?: 0
                    renderFixed()
                }
            })
        }
        fixedRow.addView(fixedBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fixedRow.addView(fixedQty, LinearLayout.LayoutParams(dp(74), dp(38)))
        fixedRow.addView(text(" Stk.", 15f, color = Colors.appFg))
        gap(fixedRow)

        // Ändern
        gap(text("ÄNDERN", 12f, color = Colors.appSub).apply { letterSpacing = 0.06f; setPadding(dp(2), dp(4), 0, 0) })
        val tools = vbox().apply { background = rounded(Colors.cardBg, 12f); clipToOutline = true }
        fun tool(label: String, danger: Boolean = false, onClick: () -> Unit): Pair<LinearLayout, TextView> {
            val row = hbox().apply {
                minimumHeight = dp(50)
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                isFocusable = true
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                foreground = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener { onClick() }
            }
            row.addView(text(label, 15f, color = if (danger) Colors.badEdge else Colors.appFg), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = text("", 13f, color = Colors.appSub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(180) }
            row.addView(v)
            if (tools.childCount > 0) tools.addView(View(this).apply { setBackgroundColor(Colors.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            tools.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            return row to v
        }
        tool("Bestand stimmt") { sheetBestandStimmt() }.let { tSoll = it.first; tSollVal = it.second }
        tool("Mengen je Bereich korrigieren") { sheetZones() }.second.text = "›"
        tEstVal = tool("Menge geschätzt") { toggleEstimated() }.second
        tNoteVal = tool("Bemerkung") { sheetNote() }.second
        tool("Bezeichnung umbenennen") { sheetRename() }.second.text = "›"
        tool("Artikel löschen", danger = true) { sheetDelete() }
        gap(tools, 6)

        return ScrollView(this).apply { addView(body); setBackgroundColor(Colors.appBg) }
    }

    private fun say(msg: String, color: Int = Colors.barIdle) {
        feedbackBar.text = msg
        feedbackBar.background = rounded(color, 10f)
    }

    private fun renderZones() {
        zoneButtons.forEachIndexed { i, b ->
            val sel = zones[i] == zone
            b.background = rounded(if (sel) Colors.brand else Colors.chipBg, 8f)
            b.setTextColor(if (sel) Color.WHITE else Colors.appFg)
            b.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun renderFixed() {
        val on = fixedAmount() != null
        fixedRow.background = rounded(if (on) Colors.estFill else Colors.cardBg, 12f)
        fixedBox.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun renderDetail() {
        val a = cur ?: return
        val st = state(a)
        val ist = inv.counted(a.sku)
        dIst.text = ist?.toString() ?: "–"
        dSoll.text = if (inv.blind) "" else android.text.SpannableStringBuilder("Soll ").apply {
            val s = length
            append(a.soll.toString())
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
        }
        dState.text = label(st)
        countCard.background = rounded(fill(st), 16f, edge(st), 3)
        val zl = zones.mapNotNull { z -> inv.zoneQty(a.sku, z)?.let { "$z: $it" } }
        dZones.text = if (zl.isEmpty()) "noch nicht gezählt" else zl.joinToString("   ") + if (a.sku in inv.estimated) "   (geschätzt)" else ""
        tSoll.visibility = if (inv.blind || a.soll <= 0) View.GONE else View.VISIBLE
        tSollVal.text = "${a.soll} in „$zone“"
        tEstVal.text = if (a.sku in inv.estimated) "An" else "Aus"
        tNoteVal.text = inv.notes[a.sku]?.takeIf { it.isNotBlank() } ?: "Keine"
        updateTitle()
    }

    private fun openArticle(a: Article) {
        if (cur != null || sheet?.isShowing == true) return
        cur = inv.article(a.sku) ?: a
        fixedBox.isChecked = fixedOn
        if (fixedQty.text.toString() != fixedN.toString() && fixedN > 0) fixedQty.setText(fixedN.toString())
        renderZones(); renderFixed(); renderDetail()
        say("Bereit – Artikel scannen")
        detail.scrollTo(0, 0)
        detail.translationX = overview.width.toFloat().coerceAtLeast(1f)
        detail.visibility = View.VISIBLE
        detail.animate().translationX(0f).setDuration(220).withEndAction { overview.visibility = View.GONE }.start()
    }

    private fun closeArticle() {
        if (cur == null) return
        cur = null
        overview.visibility = View.VISIBLE
        detail.animate().translationX(detail.width.toFloat()).setDuration(220).withEndAction { detail.visibility = View.GONE }.start()
        updateTitle()
        renderList()
    }

    // ================================================================ Zählen

    private fun fixedAmount(): Int? = if (fixedOn && fixedN > 0) fixedN else null

    private fun persist(): Boolean = try {
        InventoryStore.save(this, inv); true
    } catch (e: Exception) {
        tones.error()
        if (cur != null) say("✗ Nicht gespeichert – ist der Gerätespeicher voll?", Colors.barError) else toastShort("Nicht gespeichert – Speicher voll?")
        false
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
        tones.ok()
        say("✓ ${if (n > 0) "+$n" else "$n"} in $zone → gesamt ${inv.counted(a.sku) ?: 0}", Colors.barOk)
        renderDetail()
        return true
    }

    private fun countArticle(a: Article) {
        val f = fixedAmount()
        if (f != null) add(a, f) else { tones.ok(); askQty() }
    }

    private fun onScan(code: String) {
        if (code.isEmpty()) return
        if (sheet?.isShowing == true) { tones.error(); toastShort("Bitte zuerst das offene Fenster schließen."); return }
        val a = cur
        if (a == null) {
            tones.error()
            val known = inv.findBySku(code)
                ?: BarcodeStore.lookup(this, inv.customer.name, code).mapNotNull { inv.article(it) }.singleOrNull()
            toastShort(
                when {
                    known == null -> "Erst einen Artikel antippen, dann scannen."
                    !showAll && known !in inv.listArticles() ->
                        "„${known.name}“ steht nicht in der Liste (Bestand 0) – ⋮ → „Artikel ohne Bestand zeigen“, dann antippen."
                    else -> "Erst „${known.name}“ antippen, dann scannen."
                },
            )
            return
        }
        val mapped = BarcodeStore.lookup(this, inv.customer.name, code)
        if (inv.findBySku(code)?.sku == a.sku || a.sku in mapped) { countArticle(a); return }
        val other = inv.findBySku(code) ?: mapped.firstNotNullOfOrNull { inv.article(it) }
        if (other != null) {
            tones.error()
            say("✗ Barcode gehört zu „${other.name}“ – nicht gezählt", Colors.barError)
            return
        }
        // Unbekannter Barcode → diesem (geöffneten) Artikel zuordnen
        tones.error()
        say("Unbekannter Barcode $code", Colors.barWarn)
        openSheet(
            "Barcode $code zuordnen?",
            "Die App merkt sich für diesen Kunden: $code = ${a.name} (${a.sku}). Haben Neu- und Refware dieselbe EAN, gilt der Barcode nur in dem Artikel, der gerade offen ist.",
            null, "Zuordnen",
        ) {
            BarcodeStore.assign(this, inv.customer.name, code, (mapped + a.sku).distinct())
            recycler.post { countArticle(a) }
            true
        }
    }

    // ================================================================ Unterfenster (Bottom-Sheet)

    /** Bottom-Sheet mit Titel, Hinweis, Inhalt und „Abbrechen“ / [okText]. onOk liefert false, wenn es offen bleiben soll. */
    private fun openSheet(title: String, hint: String?, content: View?, okText: String, okColor: Int = Colors.accent, onOk: () -> Boolean): BottomSheetDialog {
        val d = BottomSheetDialog(this)
        val box = vbox(16).apply { background = rounded(Colors.cardBg, 0f) }
        box.addView(text(title, 17f, bold = true, color = Colors.appFg))
        if (hint != null) box.addView(text(hint, 13f, color = Colors.appSub).apply { setPadding(0, dp(8), 0, 0) })
        if (content != null) box.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val two = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        two.addView(flatButton("Abbrechen", Colors.cardBg, Colors.appFg, Colors.line) { d.dismiss() }.apply { textSize = 17f },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
        two.addView(flatButton(okText, okColor, Color.WHITE) { if (onOk()) d.dismiss() }.apply { textSize = 17f },
            LinearLayout.LayoutParams(0, dp(54), 1f))
        box.addView(two)
        d.setContentView(ScrollView(this).apply { addView(box) })
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.setOnDismissListener { if (sheet === d) sheet = null }
        sheet = d
        d.show()
        return d
    }

    private fun input(hint: String, number: Boolean, value: String = "", signed: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 18f
        inputType = when {
            number && signed -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        maxLines = 1
        setTextColor(Colors.appFg)
        setHintTextColor(Colors.appSub)
        background = rounded(Colors.cardBg, 10f, Colors.line)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun askQty() {
        val a = cur ?: return
        val before = inv.zoneQty(a.sku, zone)
        val box = vbox()
        val qty = input("Menge", number = true, signed = true)
        val err = text("", 13f, color = Colors.badEdge)
        box.addView(qty); box.addView(err)
        box.addView(text("Minus-Zahl zieht ab, z. B. −2.", 13f, color = Colors.appSub))
        lateinit var d: BottomSheetDialog
        val ok = {
            val v = qty.text.toString().trim().toIntOrNull()
            when {
                v == null || v == 0 -> { err.text = "Menge eingeben"; false }
                (before ?: 0) + v < 0 -> { err.text = "Dann wäre die Menge in $zone negativ"; false }
                else -> add(a, v)
            }
        }
        d = openSheet(a.name, "$zone · bisher hier: ${before ?: "–"} · gesamt: ${inv.counted(a.sku) ?: "–"}", box, "OK", onOk = ok)
        qty.imeOptions = EditorInfo.IME_ACTION_DONE
        qty.setOnEditorActionListener { _, _, event ->
            if ((event == null || event.action == KeyEvent.ACTION_DOWN) && d.isShowing && ok()) d.dismiss()
            true
        }
        qty.requestFocus()
    }

    private fun sheetBestandStimmt() {
        val a = cur ?: return
        val existing = zones.mapNotNull { z -> inv.zoneQty(a.sku, z)?.let { "$z: $it" } }
        val warn = if (existing.isEmpty()) null else text(
            "⚠ Achtung: Für diesen Artikel sind bereits Mengen erfasst (${existing.joinToString(", ")}, " +
                "gesamt ${inv.counted(a.sku)}). Alle bereits existierenden Mengen werden gelöscht und durch " +
                "${a.soll} in „$zone“ ersetzt.",
            15f, bold = true, color = Colors.badEdge,
        ).apply {
            background = rounded(Colors.badFill, 10f, Colors.badEdge)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        openSheet(
            if (existing.isEmpty()) "Bestand stimmt" else "Bestand stimmt – vorhandene Mengen löschen?",
            "Trägt ${a.soll} in „$zone“ ein. Die anderen Bereiche werden geleert.",
            warn,
            if (existing.isEmpty()) "Eintragen" else "Löschen & eintragen",
            if (existing.isEmpty()) Colors.accent else Colors.badEdge,
        ) {
            for (z in zones) inv.setZone(a.sku, z, if (z == zone) a.soll else null)
            undoStack.removeAll { it.sku == a.sku }
            inv.closed = false
            if (persist()) say("✓ gespeichert – gesamt ${a.soll}", Colors.barOk)
            renderDetail()
            true
        }
    }

    private fun sheetZones() {
        val a = cur ?: return
        val box = vbox()
        val fields = zones.map { z ->
            val row = hbox().apply { setPadding(0, dp(4), 0, dp(4)) }
            row.addView(text(z, 15f, color = Colors.appFg), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
            val f = input("–", number = true, value = inv.zoneQty(a.sku, z)?.toString() ?: "")
            row.addView(f, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row)
            z to f
        }
        box.addView(text("Leeres Feld = in diesem Bereich nicht gezählt.", 13f, color = Colors.appSub).apply { setPadding(0, dp(6), 0, 0) })
        openSheet("Mengen je Bereich", if (inv.blind) null else "Soll: ${a.soll}", box, "Speichern") {
            for ((z, f) in fields) {
                val v = f.text.toString().trim().toIntOrNull()
                if (v != inv.zoneQty(a.sku, z)) {
                    inv.setZone(a.sku, z, v)
                    undoStack.removeAll { it.sku == a.sku && it.zone == z }
                }
            }
            inv.closed = false
            if (persist()) say("✓ gespeichert – gesamt ${inv.counted(a.sku) ?: "–"}", Colors.barOk)
            renderDetail()
            true
        }
    }

    private fun toggleEstimated() {
        val a = cur ?: return
        if (a.sku in inv.estimated) inv.estimated.remove(a.sku) else inv.estimated.add(a.sku)
        if (persist()) say(if (a.sku in inv.estimated) "Als geschätzt markiert" else "Markierung „geschätzt“ entfernt")
        renderDetail()
    }

    private fun sheetNote() {
        val a = cur ?: return
        val f = input("Bemerkung", number = false, value = inv.notes[a.sku] ?: "")
        openSheet("Bemerkung", null, f, "Speichern") {
            val v = f.text.toString().trim()
            if (v.isEmpty()) inv.notes.remove(a.sku) else inv.notes[a.sku] = v
            persist(); renderDetail()
            true
        }
        f.requestFocus()
    }

    private fun sheetRename() {
        val a = cur ?: return
        val f = input("Bezeichnung", number = false, value = a.name)
        openSheet("Bezeichnung umbenennen", "Artikelnummer ${a.sku} bleibt. Die App merkt sich die neue Bezeichnung für diesen Kunden.", f, "Speichern") {
            val v = f.text.toString().trim()
            if (v.isNotEmpty() && v != a.name) {
                inv.rename(a.sku, v)
                NameStore.put(this, inv.customer.name, a.sku, v)
                persist()
                cur = inv.article(a.sku)
            }
            renderDetail()
            true
        }
        f.requestFocus()
    }

    private fun sheetDelete() {
        val a = cur ?: return
        val c = inv.counted(a.sku)
        openSheet(
            "Artikel löschen?",
            "${a.name} (${a.sku}) wird aus dieser Inventur entfernt" +
                (if (c != null) " – mit allen gezählten Mengen ($c Stk.)" else "") +
                ". Er erscheint dann weder in der Zählliste noch im Anschreiben. Bei einem erneuten Import des Systembestands ist er wieder da.",
            null, "Löschen",
        ) {
            val backup = InventoryStore.toJson(inv).toString()
            inv.removeArticle(a.sku)
            undoStack.removeAll { it.sku == a.sku }
            if (persist()) {
                toastShort("${a.name} gelöscht")
                closeArticle()
            } else {
                inv = InventoryStore.fromJson(org.json.JSONObject(backup))
            }
            true
        }
    }

    private fun undo() {
        // Nur Scans/Mengen dieser Sitzung zurücknehmen – Korrekturen bleiben unangetastet.
        while (true) {
            val last = undoStack.removeLastOrNull()
            if (last == null) { toastShort("Nichts mehr rückgängig zu machen."); return }
            if (!inv.retract(last.sku, last.zone, last.qty)) continue  // inzwischen korrigiert
            val name = inv.article(last.sku)?.name ?: last.sku
            if (persist()) {
                toastShort("Rückgängig: $name ${last.qty} in ${last.zone}")
            } else {
                inv.addCount(CountEntry(last.sku, last.zone, last.qty, System.currentTimeMillis()))
                undoStack.addLast(last)
            }
            renderList()
            return
        }
    }

    private fun openResult() {
        startActivity(Intent(this, ResultActivity::class.java).putExtra(EXTRA_ID, inv.id))
    }

    // ================================================================ Menü

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (cur != null) return true   // im Artikel-Bildschirm kein Menü
        menu.add(0, 7, 0, "Filter anzeigen").apply { isCheckable = true; isChecked = chipsVisible }
        menu.add(0, 1, 0, "Letzte Zählung rückgängig")
        menu.add(0, 2, 0, "Artikel ohne Bestand zeigen").apply { isCheckable = true; isChecked = showAll }
        menu.add(0, 3, 0, "Kopfdaten bearbeiten")
        menu.add(0, 4, 0, "Barcode-Zuordnungen dieses Kunden löschen")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> if (cur != null) closeArticle() else finish()
            7 -> {
                chipsVisible = !chipsVisible
                item.isChecked = chipsVisible
                chipsScroll.visibility = if (chipsVisible) View.VISIBLE else View.GONE
                if (!chipsVisible) { chipFilter = null; updateChips() }
                renderList()
            }
            1 -> undo()
            2 -> { showAll = !showAll; item.isChecked = showAll; renderList() }
            3 -> startActivity(Intent(this, EditActivity::class.java).putExtra(EXTRA_ID, inv.id))
            4 -> androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Barcode-Zuordnungen löschen?")
                .setMessage("${BarcodeStore.count(this, inv.customer.name)} gespeicherte Zuordnungen für ${inv.customer.name}. Unbekannte Barcodes werden danach wieder abgefragt.")
                .setPositiveButton("Löschen") { _, _ -> BarcodeStore.clear(this, inv.customer.name) }
                .setNegativeButton("Abbrechen", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ================================================================ Kachel-Liste

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val frame: FrameLayout, val card: LinearLayout, val strip: View, val label: TextView) : RecyclerView.ViewHolder(frame)

        override fun getItemCount() = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val frame = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, 0, dp(8))
            }
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(56)
                clipToOutline = true
                elevation = dpf(1f)
                isClickable = true
                isFocusable = true
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                foreground = ta.getDrawable(0)
                ta.recycle()
            }
            val strip = View(parent.context)
            card.addView(strip, LinearLayout.LayoutParams(dp(7), ViewGroup.LayoutParams.MATCH_PARENT))
            val label = TextView(parent.context).apply {
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(16), dp(14))
            }
            card.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            frame.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return VH(frame, card, strip, label)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = visible[position]
            val st = state(a)
            h.label.text = a.name
            h.label.setTextColor(Colors.appFg)
            h.card.background = rounded(fill(st), 12f)
            h.strip.setBackgroundColor(edge(st))
            h.card.contentDescription = "${a.name}, ${label(st)}"
            h.card.setOnClickListener { openArticle(a) }
        }
    }
}
