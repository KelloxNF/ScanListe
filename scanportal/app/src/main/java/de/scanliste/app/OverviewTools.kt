package de.scanliste.app

import android.app.DatePickerDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import de.inventur.app.InventoryStore
import de.inventur.app.Settings
import de.scanportal.app.ExcelCsv
import de.scanportal.app.Share
import de.scanportal.app.dp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** Vorlagen, Sammelexport und Tagesbericht in der Listen-Übersicht. */
class OverviewTools(private val activity: MainActivity) {

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    private fun form(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
    }

    private fun label(text: String, top: Int = 12) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setPadding(0, activity.dp(top), 0, 0)
    }

    private fun field(hint: String, value: String) = EditText(activity).apply {
        this.hint = hint
        setText(value)
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    /** Arbeit im Hintergrund, Ergebnis auf dem Haupt-Thread (nicht mehr, wenn die Übersicht geschlossen ist). */
    private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        Thread {
            val r = runCatching(work)
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) done(r) }
        }.start()
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date())

    // ------------------------------------------------------------ Vorlagen

    /** Neue Liste: Auswahl „Leere Liste“ oder eine Vorlage. Ohne Vorlagen direkt der bisherige Dialog. */
    fun newList(empty: () -> Unit) {
        val templates = TemplateStore.all(activity)
        if (templates.isEmpty()) { empty(); return }
        val items = arrayOf("Leere Liste …") + templates.map { t ->
            "Vorlage: ${t.name}" + (t.target?.let { " ($it)" } ?: "")
        }
        AlertDialog.Builder(activity)
            .setTitle("Neue Liste")
            .setItems(items) { _, which -> if (which == 0) empty() else fromTemplate(templates[which - 1]) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun fromTemplate(t: ListTemplate) {
        val taken = ListStore.infos(activity).map { it.name }
        val box = form()
        box.addView(label("Spalten: ${t.columns.joinToString(", ")}" +
            (if (t.genexis) " · Multidata-QR-Code" else "") +
            (if (t.rules.any { it != null }) " · Formatprüfung" else "") +
            (t.target?.let { " · Zielmenge $it" } ?: ""), 0).apply { alpha = 0.7f })
        box.addView(label("Name"))
        val name = field("z. B. Palette 1", Naming.firstFree(t.baseName, taken))
        box.addView(name)
        AlertDialog.Builder(activity)
            .setTitle(t.name)
            .setView(box)
            .setPositiveButton("Anlegen") { _, _ ->
                val list = t.create(ListStore.newId(), name.text.toString().trim().ifEmpty { Naming.firstFree(t.baseName, taken) })
                if (activity.trySave(list)) activity.open(list.id)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    fun manageTemplates() {
        val templates = TemplateStore.all(activity)
        if (templates.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Vorlagen")
                .setMessage("Noch keine Vorlagen. In einer Liste ⋮ → „Als Vorlage speichern …“ wählen – dann lassen sich neue Listen mit denselben Einstellungen über + anlegen.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Vorlage löschen?")
            .setItems(templates.map { it.name }.toTypedArray()) { _, which ->
                val t = templates[which]
                AlertDialog.Builder(activity)
                    .setTitle("„${t.name}“ löschen?")
                    .setMessage("Bestehende Listen bleiben unverändert.")
                    .setPositiveButton("Löschen") { _, _ ->
                        runCatching { TemplateStore.remove(activity, t.name) }.onFailure { toast("Löschen fehlgeschlagen") }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    // ------------------------------------------------------------ Sammelexport

    /** Mehrere Listen wählen → eine CSV oder eine Sammel-Packliste. */
    fun combinedExport() {
        val infos = ListStore.infos(activity)
        if (infos.isEmpty()) { toast("Noch keine Listen."); return }
        val checked = BooleanArray(infos.size)
        val labels = infos.map { "${it.name} (${it.count})" + if (it.closed) " ✓" else "" }.toTypedArray()
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Listen für den Sammelexport")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("CSV teilen", null)
            .setNeutralButton("Packliste (PDF) …", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        dialog.setOnShowListener {
            fun chosen(): List<ListInfo>? = infos.filterIndexed { i, _ -> checked[i] }.ifEmpty {
                toast("Bitte mindestens eine Liste auswählen."); null
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sel = chosen() ?: return@setOnClickListener
                dialog.dismiss()
                shareCombinedCsv(sel)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val sel = chosen() ?: return@setOnClickListener
                dialog.dismiss()
                combinedPackList(sel)
            }
        }
        dialog.show()
    }

    private fun load(sel: List<ListInfo>): List<ScanList> = sel.mapNotNull { ListStore.load(activity, it.id) }

    private fun shareCombinedCsv(sel: List<ListInfo>) {
        background({
            val lists = load(sel)
            Share.prepare(activity, "Sammelexport_${stamp()}.csv") { ExcelCsv.write(it, ListReports.combinedCsv(lists)) }
        }) { r ->
            r.onSuccess { Share.open(activity, it, "text/csv", "Sammelexport (${sel.size} Listen)") }
                .onFailure { toast("Export fehlgeschlagen: ${it.message}") }
        }
    }

    private fun combinedPackList(sel: List<ListInfo>) {
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val box = form()
        box.addView(label("Vorne eine Übersicht mit allen Listen und der Gesamtzahl, danach je Liste eine eigene Packliste mit QR-Codes – zum Beilegen zu jeder Palette.", 0).apply { alpha = 0.7f })
        box.addView(label("Bezeichnung"))
        val title = field("z. B. Auftrag 4711", "Sammel-Packliste " + SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date()))
        box.addView(title)
        box.addView(label("Kunde / Empfänger"))
        val customer = CustomerPicker.field(activity, prefs.getString(ListTools.PREF_CUSTOMER, "") ?: "")
        box.addView(customer)
        box.addView(label("Bemerkung (optional)"))
        val note = field("z. B. Lieferschein 4711", "")
        box.addView(note)
        AlertDialog.Builder(activity)
            .setTitle("Sammel-Packliste (${sel.size} Listen)")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Teilen") { _, _ ->
                val c = customer.text.toString().trim()
                CustomerPicker.remember(activity, c)
                val sender = Settings.sender(activity)
                val summary = PackInfo(title.text.toString().trim().ifEmpty { "Sammel-Packliste" }, c, note.text.toString().trim(), sender)
                background({
                    val parts = load(sel).map { l -> PackInfo(l.name, c, summary.note, sender) to PackSheet.of(l) }
                    Share.prepare(activity, ListTools.packFileName(summary.title)) { PackList.write(parts, summary, it) }
                }) { r ->
                    r.onSuccess { Share.open(activity, it, "application/pdf", summary.title) }
                        .onFailure { toast("Packliste fehlgeschlagen: ${it.message}") }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------ Tagesbericht

    fun dailyReport() {
        val today = LocalDate.now()
        DatePickerDialog(activity, { _, y, m, d -> showDay(LocalDate.of(y, m + 1, d)) }, today.year, today.monthValue - 1, today.dayOfMonth)
            .apply { setTitle("Tagesbericht für …") }
            .show()
    }

    private fun showDay(date: LocalDate) {
        background({
            ListReports.day(date, ListStore.files(activity).mapNotNull { ListStore.loadFile(it) }, InventoryStore.all(activity))
        }) { r ->
            val day = r.getOrElse { toast("Bericht fehlgeschlagen: ${it.message}"); return@background }
            val name = "Tagesbericht_%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
            val text = TextView(activity).apply {
                this.text = ListReports.dayText(day)
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
            }
            val b = AlertDialog.Builder(activity)
                .setView(ScrollView(activity).apply { addView(text) })
                .setNegativeButton("Schließen", null)
            if (!day.isEmpty) {
                b.setPositiveButton("PDF teilen") { _, _ ->
                    background({ Share.prepare(activity, "$name.pdf") { ListReports.dayPdf(day, Settings.sender(activity), it) } }) { f ->
                        f.onSuccess { Share.open(activity, it, "application/pdf", name.replace('_', ' ')) }
                            .onFailure { toast("Teilen fehlgeschlagen: ${it.message}") }
                    }
                }
                b.setNeutralButton("CSV teilen") { _, _ ->
                    background({ Share.prepare(activity, "$name.csv") { ExcelCsv.write(it, ListReports.dayCsv(day)) } }) { f ->
                        f.onSuccess { Share.open(activity, it, "text/csv", name.replace('_', ' ')) }
                            .onFailure { toast("Teilen fehlgeschlagen: ${it.message}") }
                    }
                }
            }
            b.show()
        }
    }
}
