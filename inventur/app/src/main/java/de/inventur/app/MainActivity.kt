package de.inventur.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Übersicht aller Inventuren – zugleich die Gesamtübersicht über alle Kunden. */
class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private var items: List<Inventory> = emptyList()

    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { CsvExport.write(it, CsvExport.overview(items)) }
            toast("Gesamtübersicht gespeichert")
        }.onFailure { toast("Speichern fehlgeschlagen: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Colors.init(this)
        DataWedge.ensureProfile(this)

        val root = vbox()
        empty = text(
            "Noch keine Inventur angelegt.\n\nTippe auf „Neue Inventur“, wähle den Kunden und importiere den Systembestand (Excel- oder CSV-Export aus dem Lagersystem).",
            16f, color = Colors.grey,
        ).apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(48), dp(32), dp(32)) }
        recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity) }
        root.addView(empty.lp())
        root.addView(recycler.lp(height = 0, weight = 1f))
        root.addView(button("＋  Neue Inventur") {
            startActivity(Intent(this, EditActivity::class.java))
        }.apply { minHeight = dp(64) }.lp())
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        items = InventoryStore.all(this)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recycler.adapter = Adapter()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Gesamtübersicht teilen (CSV)")
        menu.add(0, 2, 0, "Gesamtübersicht speichern (CSV)")
        menu.add(0, 3, 0, "Einstellungen")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> runCatching {
                Share.clear(this)
                val f = Share.file(this, "Inventur_Gesamtuebersicht.csv")
                f.outputStream().use { CsvExport.write(it, CsvExport.overview(items)) }
                Share.send(this, f, "text/csv", "Inventur – Gesamtübersicht")
            }.onFailure { toast("Teilen fehlgeschlagen: ${it.message}") }
            2 -> saveCsv.launch("Inventur_Gesamtuebersicht.csv")
            3 -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun open(inv: Inventory) {
        startActivity(Intent(this, CountActivity::class.java).putExtra(CountActivity.EXTRA_ID, inv.id))
    }

    private fun longPress(inv: Inventory) {
        AlertDialog.Builder(this)
            .setTitle(inv.title())
            .setItems(arrayOf("Zählen", "Ergebnis & Dokumente", "Kopfdaten bearbeiten", "Löschen")) { _, which ->
                when (which) {
                    0 -> open(inv)
                    1 -> startActivity(Intent(this, ResultActivity::class.java).putExtra(CountActivity.EXTRA_ID, inv.id))
                    2 -> startActivity(Intent(this, EditActivity::class.java).putExtra(CountActivity.EXTRA_ID, inv.id))
                    3 -> AlertDialog.Builder(this)
                        .setTitle("Inventur löschen?")
                        .setMessage("${inv.title()}\n\nAlle Zählungen dieser Inventur gehen verloren.")
                        .setPositiveButton("Löschen") { _, _ -> InventoryStore.delete(this, inv.id); onResume() }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val root: LinearLayout, val bar: View, val title: TextView, val sub: TextView, val state: TextView) :
            RecyclerView.ViewHolder(root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val root = ctx.hbox().apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, dp(12), 0)
                isClickable = true
                isFocusable = true
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val ta = ctx.obtainStyledAttributes(attrs)
                background = ta.getDrawable(0)
                ta.recycle()
            }
            val bar = View(ctx)
            root.addView(bar, LinearLayout.LayoutParams(dp(6), dp(72)))
            val texts = ctx.vbox().apply { setPadding(dp(12), dp(10), dp(8), dp(10)) }
            val title = ctx.text(sizeSp = 17f, bold = true)
            val sub = ctx.text(sizeSp = 14f, color = Colors.grey)
            texts.addView(title); texts.addView(sub)
            root.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val state = ctx.text(sizeSp = 13f, bold = true).apply { gravity = Gravity.END }
            root.addView(state)
            return VH(root, bar, title, sub, state)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val inv = items[position]
            val s = inv.summary()
            h.title.text = inv.customer.name.ifBlank { "(ohne Kunde)" }
            h.sub.text = "Stichtag ${Dates.display(inv.stichtag)} · ${s.articles - s.notCounted}/${s.articles} gezählt" +
                if (s.diff > 0) " · ${s.diff} Differenz" + (if (s.diff > 1) "en" else "") else ""
            val (label, color) = when {
                s.articles == 0 -> "Kein Bestand" to Colors.grey
                s.notCounted > 0 -> "Offen" to Colors.idle
                s.diff > 0 -> "Differenz" to Colors.error
                else -> "Ohne Differenz" to Colors.ok
            }
            h.state.text = if (inv.closed) "$label\n✓ abgeschlossen" else label
            h.state.setTextColor(color)
            h.bar.setBackgroundColor(if (inv.closed) color else Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)))
            h.root.setOnClickListener { open(inv) }
            h.root.setOnLongClickListener { longPress(inv); true }
        }
    }
}
