package de.inventur.app

import de.scanportal.app.dp

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ergebnis der Inventur: Differenzen, Abschluss und Dokumente (Anschreiben, Zählliste, CSV). */
class ResultActivity : AppCompatActivity() {

    private lateinit var inv: Inventory
    private lateinit var zones: List<String>
    private lateinit var summaryText: TextView
    private lateinit var closeButton: Button
    private lateinit var recycler: RecyclerView
    /**
     * Welches Dokument gerade über „Auf dem Gerät speichern“ angelegt wird ("letter", "sheet", "csv").
     * Wird gesichert, falls Android die App öffnet, während die Dateiauswahl offen ist –
     * sonst entstünde eine leere Datei.
     */
    private var pendingKind: String? = null

    private val savePdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? -> writePending(uri) }
    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? -> writePending(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Colors.init(this)
        pendingKind = savedInstanceState?.getString("pendingKind")
        val loaded = intent.getStringExtra(CountActivity.EXTRA_ID)?.let { InventoryStore.load(this, it) }
        if (loaded == null) { finish(); return }
        inv = loaded
        zones = (Settings.zones(this) + inv.countedZones()).distinct()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Ergebnis – ${inv.customer.name}"

        val root = vbox()
        summaryText = text(sizeSp = 15f).apply { setPadding(dp(14), dp(10), dp(14), dp(6)) }
        root.addView(summaryText)

        val docs = hbox().apply { setPadding(dp(6), 0, dp(6), 0) }
        docs.addView(button("Anschreiben\n(PDF)") { letter() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        docs.addView(button("Zählliste\n(PDF)") { sheet() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        docs.addView(button("Ergebnis\n(CSV)") { csv() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(docs)
        val zeroCount = inv.articles.count { inv.isZeroOnly(it) }
        val zero = CheckBox(this).apply {
            text = "Artikel mit Bestand 0 in Anschreiben und Zählliste aufführen ($zeroCount)"
            textSize = 15f
            isChecked = inv.includeZero
            setOnCheckedChangeListener { box, on ->
                if (on == inv.includeZero) return@setOnCheckedChangeListener   // Zurücksetzen nach Fehler
                inv.includeZero = on
                val saved = runCatching { InventoryStore.save(this@ResultActivity, inv) }.isSuccess
                if (!saved) {
                    inv.includeZero = !on
                    box.isChecked = !on
                    toast("Nicht gespeichert – ist der Gerätespeicher voll?")
                }
            }
        }
        root.addView(zero.lp().apply { setPadding(dp(10), dp(4), dp(10), dp(4)) })
        closeButton = button("") { toggleClosed() }
        root.addView(closeButton.lp().apply { setPadding(dp(6), 0, dp(6), 0) })

        // Tabellenkopf
        val head = hbox().apply { setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.rgb(225, 229, 236)) }
        head.addView(text("Artikel", 13f, bold = true, color = Color.BLACK), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (t in listOf("Soll", "Ist", "Diff.")) {
            head.addView(text(t, 13f, bold = true, color = Color.BLACK).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(head)
        recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ResultActivity) }
        root.addView(recycler.lp(height = 0, weight = 1f))
        setContentView(root)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingKind", pendingKind)
    }

    private fun render() {
        val s = inv.summary()
        val sb = StringBuilder()
        sb.append("Stichtag ${Dates.display(inv.stichtag)}")
        if (inv.timeFrom.isNotBlank()) sb.append(" · ${inv.timeFrom}–${inv.timeTo} Uhr")
        sb.append("\n${s.articles} Artikel: ${s.ok} stimmen, ${s.diff} mit Differenz, ${s.estimated} geschätzt, ${s.notCounted} nicht gezählt")
        if (s.plus > 0 || s.minus > 0) sb.append("\nMehrbestand ${s.plus} Stk. · Fehlbestand ${s.minus} Stk.")
        sb.append("\nErgebnis: ${s.overall}")
        summaryText.text = sb
        summaryText.setTextColor(
            when (s.overall) {
                "Ohne Differenz" -> Colors.ok
                "Differenz" -> Colors.error
                else -> Colors.idle
            },
        )
        closeButton.text = if (inv.closed) "✓ Abgeschlossen – wieder öffnen" else "Inventur abschließen"
        recycler.adapter = Adapter(inv.listArticles())
    }

    private fun toggleClosed() {
        if (!inv.closed) {
            val s = inv.summary()
            val warn = buildList {
                if (s.notCounted > 0) add("${s.notCounted} Artikel sind noch nicht gezählt.")
                if (s.diff > 0) add("${s.diff} Artikel haben eine Differenz.")
            }
            if (warn.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Trotzdem abschließen?")
                    .setMessage(warn.joinToString("\n"))
                    .setPositiveButton("Abschließen") { _, _ -> setClosed(true) }
                    .setNegativeButton("Abbrechen", null)
                    .show()
                return
            }
            setClosed(true)
        } else setClosed(false)
    }

    private fun setClosed(c: Boolean) {
        inv.closed = c
        if (c && inv.timeTo.isBlank()) inv.timeTo = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
        runCatching { InventoryStore.save(this, inv) }.onFailure { toast("Nicht gespeichert: ${it.message}") }
        render()
    }

    // ---------------------------------------------------------------- Dokumente

    /** Schreibt das Dokument der Art [kind] ("letter", "sheet", "csv"). */
    private fun writer(kind: String): (OutputStream) -> Unit = when (kind) {
        "letter" -> { out -> Documents.letter(inv, Settings.sender(this), Settings.footer(this), Settings.ceo(this), out) }
        "sheet" -> { out -> Documents.countSheet(inv, zones, out) }
        else -> { out -> CsvExport.write(out, CsvExport.result(inv, zones)) }
    }

    private fun letter() {
        val s = inv.summary()
        val go = {
            deliver("letter", "${Documents.fileBase(inv)}_Anschreiben.pdf", "application/pdf", "Inventur ${inv.customer.name} – Anschreiben")
        }
        val hints = buildList {
            if (s.notCounted > 0) add("${s.notCounted} Artikel sind noch nicht gezählt und erscheinen im Anschreiben mit 0.")
            if (Settings.ceo(this@ResultActivity).isBlank()) add("Name der Geschäftsführung fehlt (Einstellungen).")
        }
        if (hints.isEmpty()) go() else AlertDialog.Builder(this)
            .setTitle("Hinweis")
            .setMessage(hints.joinToString("\n\n"))
            .setPositiveButton("Trotzdem erstellen") { _, _ -> go() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun sheet() {
        deliver("sheet", "${Documents.fileBase(inv)}_Zaehlliste.pdf", "application/pdf", "Inventur ${inv.customer.name} – Zählliste")
    }

    private fun csv() {
        deliver("csv", "${Documents.fileBase(inv)}_Ergebnis.csv", "text/csv", "Inventur ${inv.customer.name} – Ergebnis")
    }

    /** Fragt: teilen (E-Mail, Drive, Drucken …) oder auf dem Gerät speichern. */
    private fun deliver(kind: String, name: String, mime: String, subject: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Teilen (E-Mail, Drive, Drucken …)", "Auf dem Gerät speichern …")) { _, which ->
                if (which == 0) {
                    try {
                        Share.clear(this)
                        val f = Share.file(this, name)
                        f.outputStream().use { writer(kind)(it) }
                        Share.send(this, f, mime, subject)
                    } catch (e: Exception) {
                        toast("Erstellen fehlgeschlagen: ${e.message}")
                    }
                } else {
                    pendingKind = kind
                    if (mime == "application/pdf") savePdf.launch(name) else saveCsv.launch(name)
                }
            }
            .show()
    }

    private fun writePending(uri: Uri?) {
        val kind = pendingKind ?: return
        pendingKind = null
        if (uri == null) return
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { writer(kind)(it) }
            toast("Gespeichert")
        } catch (e: Exception) {
            toast("Speichern fehlgeschlagen: ${e.message}")
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------- Liste

    private inner class Adapter(val rows: List<Article>) : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val root: LinearLayout, val name: TextView, val soll: TextView, val ist: TextView, val diff: TextView) :
            RecyclerView.ViewHolder(root)

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val root = ctx.hbox().apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            val name = ctx.text(sizeSp = 14f)
            root.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fun num() = ctx.text(sizeSp = 14f).apply { gravity = Gravity.END }.also {
                root.addView(it, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            return VH(root, name, num(), num(), num())
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = rows[position]
            val ist = inv.counted(a.sku)
            val st = inv.status(a)
            h.name.text = "${a.name}\n${st.label}" + (inv.notes[a.sku]?.let { " · $it" } ?: "")
            h.soll.text = a.soll.toString()
            h.ist.text = ist?.toString() ?: "–"
            val d = ist?.minus(a.soll)
            h.diff.text = when { d == null -> ""; d > 0 -> "+$d"; else -> d.toString() }
            h.diff.setTextColor(if (d != null && d != 0) Colors.error else Colors.ok)
            h.name.setTextColor(if (st == Status.STIMMT) h.soll.currentTextColor else Colors.status(st))
            h.root.setBackgroundColor(if (position % 2 == 1) Color.argb(14, 0, 0, 0) else Color.TRANSPARENT)
        }
    }
}
