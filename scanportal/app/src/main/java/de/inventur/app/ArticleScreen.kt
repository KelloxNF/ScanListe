package de.inventur.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.scanportal.app.dp

/**
 * Artikel-Bildschirm der Zähl-Ansicht: Bereichswahl, Zählkarte, Rückmeldeleiste,
 * „Menge eingeben“, feste Menge je Scan und die Werkzeuge unter „Ändern“.
 */
class ArticleScreen(private val host: CountActivity) {
    private val session get() = host.session
    private val inv get() = session.inv
    private val sheets get() = host.sheets

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

    val view: ScrollView = build()

    private fun build(): ScrollView = with(host) {
        val body = vbox().apply { setPadding(dp(14), dp(10), dp(14), dp(16)) }
        fun gap(v: View, top: Int = 10, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
            body.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) })

        // Bereiche
        val zr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zoneButtons = session.zones.mapIndexed { i, z ->
            Button(this).apply {
                text = z
                isAllCaps = false
                textSize = 14f
                maxLines = 1
                minHeight = 0; minimumHeight = 0
                setPadding(dp(2), 0, dp(2), 0)
                stateListAnimator = null
                setOnClickListener { session.zone = z; renderZones(); render(); say("$z gewählt") }
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

        feedbackBar = text(sizeSp = 14f, color = Color.WHITE).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        say(READY)
        gap(feedbackBar)

        gap(flatButton("Menge eingeben", Colors.accent, Color.WHITE) { sheets.askQty() }.apply { textSize = 17f }, height = dp(54))

        // Feste Menge je Scan
        fixedRow = hbox().apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        fixedBox = CheckBox(this).apply {
            text = "Feste Menge je Scan:"
            textSize = 15f
            setTextColor(Colors.appFg)
            setOnCheckedChangeListener { _, c -> session.fixedOn = c; renderFixed() }
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
                    session.fixedN = s?.toString()?.trim()?.toIntOrNull() ?: 0
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
                foreground = touchFeedback()
                setOnClickListener { onClick() }
            }
            row.addView(text(label, 15f, color = if (danger) Colors.badEdge else Colors.appFg), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = text("", 13f, color = Colors.appSub).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(180) }
            row.addView(v)
            if (tools.childCount > 0) tools.addView(View(this).apply { setBackgroundColor(Colors.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            tools.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            return row to v
        }
        tool("Bestand stimmt") { sheets.bestandStimmt() }.let { tSoll = it.first; tSollVal = it.second }
        tool("Mengen je Bereich korrigieren") { sheets.zones() }.second.text = "›"
        tEstVal = tool("Menge geschätzt") { host.toggleEstimated() }.second
        tNoteVal = tool("Bemerkung") { sheets.note() }.second
        tool("Bezeichnung umbenennen") { sheets.rename() }.second.text = "›"
        tool("Artikel löschen", danger = true) { sheets.delete() }
        gap(tools, 6)

        ScrollView(this).apply { addView(body); setBackgroundColor(Colors.appBg) }
    }

    /** Rückmeldeleiste unter der Zählkarte. */
    fun say(msg: String, color: Int = Colors.barIdle) {
        feedbackBar.text = msg
        feedbackBar.background = host.rounded(color, 10f)
    }

    /** Vor dem Einblenden: Zustand der Sitzung übernehmen und nach oben scrollen. */
    fun show() {
        fixedBox.isChecked = session.fixedOn
        if (fixedQty.text.toString() != session.fixedN.toString() && session.fixedN > 0) fixedQty.setText(session.fixedN.toString())
        renderZones(); renderFixed(); render()
        say(READY)
        view.scrollTo(0, 0)
    }

    private fun renderZones() {
        zoneButtons.forEachIndexed { i, b ->
            val sel = session.zones[i] == session.zone
            b.background = host.rounded(if (sel) Colors.brand else Colors.chipBg, 8f)
            b.setTextColor(if (sel) Color.WHITE else Colors.appFg)
            b.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun renderFixed() {
        val on = session.fixedAmount() != null
        fixedRow.background = host.rounded(if (on) Colors.estFill else Colors.cardBg, 12f)
        fixedBox.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    fun render() {
        val a = host.cur ?: return
        val st = session.state(a)
        dIst.text = inv.counted(a.sku)?.toString() ?: "–"
        dSoll.text = if (inv.blind) "" else SpannableStringBuilder("Soll ").apply {
            val s = length
            append(a.soll.toString())
            setSpan(StyleSpan(Typeface.BOLD), s, length, 0)
        }
        dState.text = session.label(st)
        countCard.background = host.rounded(st.fill, 16f, st.edge, 3)
        val zl = session.zoneLines(a.sku)
        dZones.text = if (zl.isEmpty()) "noch nicht gezählt" else zl.joinToString("   ") + if (a.sku in inv.estimated) "   (geschätzt)" else ""
        tSoll.visibility = if (inv.blind || a.soll <= 0) View.GONE else View.VISIBLE
        tSollVal.text = "${a.soll} in „${session.zone}“"
        tEstVal.text = if (a.sku in inv.estimated) "An" else "Aus"
        tNoteVal.text = inv.notes[a.sku]?.takeIf { it.isNotBlank() } ?: "Keine"
        host.updateTitle()
    }

    companion object {
        const val READY = "Bereit – Artikel scannen"
    }
}
