package de.inventur.app

import android.graphics.Color
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import de.scanportal.app.dp

/** Unterfenster (Bottom-Sheets) des Artikel-Bildschirms. Alle wirken auf den geöffneten Artikel. */
class ArticleSheets(private val host: CountActivity) {
    private val session get() = host.session
    private val inv get() = session.inv
    private val screen get() = host.article

    /** Offenes Unterfenster oder null. */
    var current: BottomSheetDialog? = null
        private set

    val isShowing get() = current?.isShowing == true

    fun dismiss() = current?.dismiss()

    /** Bottom-Sheet mit Titel, Hinweis, Inhalt und „Abbrechen“ / [okText]. onOk liefert false, wenn es offen bleiben soll. */
    private fun open(title: String, hint: String?, content: View?, okText: String, okColor: Int = Colors.accent, onOk: () -> Boolean): BottomSheetDialog = with(host) {
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
        d.setOnDismissListener { if (current === d) current = null }
        current = d
        d.show()
        d
    }

    private fun input(hint: String, number: Boolean, value: String = "", signed: Boolean = false) = EditText(host).apply {
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
        background = host.rounded(Colors.cardBg, 10f, Colors.line)
        setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
    }

    fun askQty() {
        with(host) {
            val a = cur ?: return
            val zone = session.zone
            val before = inv.zoneQty(a.sku, zone)
            val box = vbox()
            val qty = input("Menge", number = true, signed = true)
            val err = text("", 13f, color = Colors.badEdge)
            box.addView(qty); box.addView(err)
            box.addView(text("Minus-Zahl zieht ab, z. B. −2.", 13f, color = Colors.appSub))
            val ok = {
                val v = qty.text.toString().trim().toIntOrNull()
                when {
                    v == null || v == 0 -> { err.text = "Menge eingeben"; false }
                    (before ?: 0) + v < 0 -> { err.text = "Dann wäre die Menge in $zone negativ"; false }
                    else -> add(a, v)
                }
            }
            val d = open(a.name, "$zone · bisher hier: ${before ?: "–"} · gesamt: ${inv.counted(a.sku) ?: "–"}", box, "OK", onOk = ok)
            qty.imeOptions = EditorInfo.IME_ACTION_DONE
            qty.setOnEditorActionListener { _, _, event ->
                if ((event == null || event.action == KeyEvent.ACTION_DOWN) && d.isShowing && ok()) d.dismiss()
                true
            }
            qty.requestFocus()
        }
    }

    fun bestandStimmt() {
        with(host) {
            val a = cur ?: return
            val zone = session.zone
            val existing = session.zoneLines(a.sku)
            val warn = if (existing.isEmpty()) null else text(
                "⚠ Achtung: Für diesen Artikel sind bereits Mengen erfasst (${existing.joinToString(", ")}, " +
                    "gesamt ${inv.counted(a.sku)}). Alle bereits existierenden Mengen werden gelöscht und durch " +
                    "${a.soll} in „$zone“ ersetzt.",
                15f, bold = true, color = Colors.badEdge,
            ).apply {
                background = rounded(Colors.badFill, 10f, Colors.badEdge)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            open(
                if (existing.isEmpty()) "Bestand stimmt" else "Bestand stimmt – vorhandene Mengen löschen?",
                "Trägt ${a.soll} in „$zone“ ein. Die anderen Bereiche werden geleert.",
                warn,
                if (existing.isEmpty()) "Eintragen" else "Löschen & eintragen",
                if (existing.isEmpty()) Colors.accent else Colors.badEdge,
            ) {
                if (session.bestandStimmt(a)) screen.say("✓ gespeichert – gesamt ${a.soll}", Colors.barOk) else saveFailed()
                screen.render()
                true
            }
        }
    }

    fun zones() {
        with(host) {
            val a = cur ?: return
            val box = vbox()
            val fields = session.zones.map { z ->
                val row = hbox().apply { setPadding(0, dp(4), 0, dp(4)) }
                row.addView(text(z, 15f, color = Colors.appFg), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
                val f = input("–", number = true, value = inv.zoneQty(a.sku, z)?.toString() ?: "")
                row.addView(f, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                box.addView(row)
                z to f
            }
            box.addView(text("Leeres Feld = in diesem Bereich nicht gezählt.", 13f, color = Colors.appSub).apply { setPadding(0, dp(6), 0, 0) })
            open("Mengen je Bereich", if (inv.blind) null else "Soll: ${a.soll}", box, "Speichern") {
                val values = fields.associate { (z, f) -> z to f.text.toString().trim().toIntOrNull() }
                if (session.setZones(a.sku, values)) screen.say("✓ gespeichert – gesamt ${inv.counted(a.sku) ?: "–"}", Colors.barOk) else saveFailed()
                screen.render()
                true
            }
        }
    }

    fun note() {
        with(host) {
            val a = cur ?: return
            val f = input("Bemerkung", number = false, value = inv.notes[a.sku] ?: "")
            open("Bemerkung", null, f, "Speichern") {
                if (!session.setNote(a.sku, f.text.toString())) saveFailed()
                screen.render()
                true
            }
            f.requestFocus()
        }
    }

    fun rename() {
        with(host) {
            val a = cur ?: return
            val f = input("Bezeichnung", number = false, value = a.name)
            open("Bezeichnung umbenennen", "Artikelnummer ${a.sku} bleibt. Die App merkt sich die neue Bezeichnung für diesen Kunden.", f, "Speichern") {
                val v = f.text.toString().trim()
                if (v.isNotEmpty() && v != a.name) {
                    NameStore.put(this, inv.customer.name, a.sku, v)
                    if (!session.rename(a.sku, v)) saveFailed()
                    cur = inv.article(a.sku)
                }
                screen.render()
                true
            }
            f.requestFocus()
        }
    }

    fun delete() {
        with(host) {
            val a = cur ?: return
            val c = inv.counted(a.sku)
            open(
                "Artikel löschen?",
                "${a.name} (${a.sku}) wird aus dieser Inventur entfernt" +
                    (if (c != null) " – mit allen gezählten Mengen ($c Stk.)" else "") +
                    ". Er erscheint dann weder in der Zählliste noch im Anschreiben. Bei einem erneuten Import des Systembestands ist er wieder da.",
                null, "Löschen",
            ) {
                if (session.delete(a.sku)) {
                    toastShort("${a.name} gelöscht")
                    closeArticle()
                } else saveFailed()
                true
            }
        }
    }

    /** Unbekannter Barcode bei geöffnetem Artikel: diesem Artikel zuordnen und dann zählen. */
    fun assignBarcode(code: String, a: Article, mapped: List<String>) = with(host) {
        open(
            "Barcode $code zuordnen?",
            "Die App merkt sich für diesen Kunden: $code = ${a.name} (${a.sku}). Haben Neu- und Refware dieselbe EAN, gilt der Barcode nur in dem Artikel, der gerade offen ist.",
            null, "Zuordnen",
        ) {
            BarcodeStore.assign(this, inv.customer.name, code, (mapped + a.sku).distinct())
            screen.view.post { countArticle(a) }
            true
        }
    }
}
