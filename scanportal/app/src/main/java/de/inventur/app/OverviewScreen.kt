package de.inventur.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.scanportal.app.dp
import de.scanportal.app.dpf

/** Kachelfarben je Status (wie im Beispiel „Inventur Kachelansicht“). */
val StateKey.fill: Int
    get() = when (this) {
        StateKey.OPEN -> Colors.openBg; StateKey.OK -> Colors.okFill; StateKey.BAD -> Colors.badFill; StateKey.EST -> Colors.estFill
    }

val StateKey.edge: Int
    get() = when (this) {
        StateKey.OPEN -> Colors.openEdge; StateKey.OK -> Colors.okEdge; StateKey.BAD -> Colors.badEdge; StateKey.EST -> Colors.estEdge
    }

/**
 * Übersicht der Zähl-Ansicht: Statuszeile, Suche, Filterleiste (über ⋮ ein-/ausschaltbar)
 * und die Liste schlichter Kacheln (Bezeichnung, Farbe = Status).
 */
class OverviewScreen(private val host: CountActivity) {
    private val session get() = host.session
    private val inv get() = session.inv

    /** Auch Artikel ohne Systembestand und ohne Zählung zeigen (⋮-Menü). */
    var showAll = false
        set(v) { field = v; render() }

    /** Filterleiste sichtbar (⋮-Menü). Ausblenden setzt den Filter auf „Alle“ zurück. */
    var chipsVisible = false
        set(v) {
            field = v
            chipsScroll.visibility = if (v) View.VISIBLE else View.GONE
            if (!v) { chipFilter = null; recountFilter = false; renderChips() }
            render()
        }

    private var chipFilter: StateKey? = null   // null = Alle

    /** Filter „Nachzählung“: nur Artikel mit festgehaltener 1. Zählung. */
    private var recountFilter = false
    private lateinit var recountChip: Button

    /** Filterleiste einblenden und „Nachzählung“ wählen (nach dem Start einer Nachzählung). */
    fun showRecount() {
        chipsVisible = true
        chipFilter = null
        recountFilter = true
        renderChips()
        render()
    }
    private var query = ""
    private var visible: List<Article> = emptyList()

    private lateinit var statusLine: TextView
    private lateinit var search: EditText
    private lateinit var filterInfo: TextView
    private lateinit var chipsScroll: HorizontalScrollView
    private val chipViews = LinkedHashMap<StateKey?, Button>()
    private lateinit var empty: TextView
    private val adapter = Adapter()

    val view: LinearLayout = build()

    /** Steht der Artikel gerade in der Liste (bei ausgeblendeten Artikeln ohne Bestand)? */
    fun isListed(a: Article) = showAll || a in inv.listArticles()

    private fun build(): LinearLayout = with(host) {
        val root = vbox()
        statusLine = text(sizeSp = 13f, bold = true, color = Colors.appSub).apply { setPadding(dp(14), dp(8), dp(14), 0) }
        root.addView(statusLine)

        // Suche (Pille mit Lupe und ✕)
        val pill = FrameLayout(this).apply { background = rounded(Colors.cardBg, 22f, Colors.line) }
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
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); render() }
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

        // Filterleiste
        val chips = hbox().apply { setPadding(dp(14), dp(8), dp(14), 0) }
        for (k in listOf(null, StateKey.OPEN, StateKey.BAD, StateKey.EST, StateKey.OK)) {
            val b = Button(this).apply {
                isAllCaps = false
                textSize = 13f
                minHeight = 0; minimumHeight = 0
                setPadding(dp(12), 0, dp(12), 0)
                stateListAnimator = null
                setOnClickListener { chipFilter = k; recountFilter = false; renderChips(); render() }
            }
            chipViews[k] = b
            chips.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(6) })
        }
        recountChip = Button(this).apply {
            isAllCaps = false
            textSize = 13f
            minHeight = 0; minimumHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            stateListAnimator = null
            setOnClickListener { chipFilter = null; recountFilter = true; renderChips(); render() }
        }
        // vorne, damit der aktive Nachzählungs-Filter ohne Wischen sichtbar ist
        chips.addView(recountChip, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(6) })
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
            visibility = View.GONE
        }
        root.addView(chipsScroll)
        renderChips()

        val listHost = FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(host)
            adapter = this@OverviewScreen.adapter
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
        root
    }

    /** Beschriftung (Blindzählung: „Gezählt“) und Hervorhebung der Filter-Chips. */
    fun renderChips() = with(host) {
        fun paint(b: Button, on: Boolean) {
            b.background = rounded(if (on) Colors.brand else Colors.cardBg, 16f, if (on) Colors.brand else Colors.line)
            b.setTextColor(if (on) Color.WHITE else Colors.appFg)
        }
        for ((k, b) in chipViews) {
            b.text = k?.let { session.label(it) } ?: "Alle"
            paint(b, !recountFilter && k == chipFilter)
        }
        val firsts = inv.firstCounts
        if (firsts.isEmpty()) recountFilter = false
        recountChip.visibility = if (firsts.isEmpty()) View.GONE else View.VISIBLE
        val open = firsts.keys.count { inv.counted(it) == null }
        recountChip.text = "Nachzählung ($open offen)"
        paint(recountChip, recountFilter)
    }

    fun render() {
        val base = when {
            recountFilter -> inv.articles.filter { it.sku in inv.firstCounts }   // auch Soll-0-Artikel, die zurückgesetzt wurden
            showAll -> inv.articles.toList()
            else -> inv.listArticles()
        }
        val q = query.lowercase()
        visible = base.filter { a ->
            (q.isEmpty() || a.name.lowercase().contains(q) || a.sku.lowercase().contains(q)) &&
                (chipFilter == null || session.state(a) == chipFilter)
        }
        adapter.notifyDataSetChanged()
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        filterInfo.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        filterInfo.text = "Filter aktiv: ${visible.size} von ${base.size} Artikeln"
        val done = base.count { inv.counted(it.sku) != null }
        statusLine.text = "Stichtag ${Dates.display(inv.stichtag)} · $done von ${base.size} Artikeln gezählt" +
            (if (inv.blind) " · Blindzählung" else "") + (if (inv.closed) " · abgeschlossen" else "")
    }

    /** Tastatur der Suche schließen, sonst verdeckt sie den Artikel-Bildschirm. */
    fun hideKeyboard() {
        search.clearFocus()
        host.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(frame: FrameLayout, val card: LinearLayout, val strip: View, val label: TextView) : RecyclerView.ViewHolder(frame)

        override fun getItemCount() = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = with(host) {
            val frame = FrameLayout(this).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, 0, dp(8))
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(56)
                clipToOutline = true
                elevation = dpf(1f)
                isClickable = true
                isFocusable = true
                foreground = touchFeedback()
            }
            val strip = View(this)
            card.addView(strip, LinearLayout.LayoutParams(dp(7), ViewGroup.LayoutParams.MATCH_PARENT))
            val label = TextView(this).apply {
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(16), dp(14))
            }
            card.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            frame.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            VH(frame, card, strip, label)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = visible[position]
            val st = session.state(a)
            h.label.text = a.name
            h.label.setTextColor(Colors.appFg)
            h.card.background = host.rounded(st.fill, 12f)
            h.strip.setBackgroundColor(st.edge)
            h.card.contentDescription = "${a.name}, ${session.label(st)}"
            h.card.setOnClickListener { host.openArticle(a) }
        }
    }
}
