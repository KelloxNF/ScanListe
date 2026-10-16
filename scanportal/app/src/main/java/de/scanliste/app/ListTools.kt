package de.scanliste.app

import android.net.Uri
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import de.inventur.app.Importer
import de.scanportal.app.Share
import de.scanportal.app.dp

/** Dialoge für Formatprüfung, Zielmenge und Soll-Liste einer Liste. */
class ListTools(private val activity: ListActivity) {
    private val list get() = activity.list

    private fun field(hint: String, value: String, number: Boolean = false) = EditText(activity).apply {
        this.hint = hint
        setText(value)
        maxLines = 1
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    }

    private fun label(text: String, top: Int = 12) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setPadding(0, activity.dp(top), 0, 0)
    }

    private fun form(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ Formatprüfung

    /** Je Spalte: „beginnt mit“ (mehrere mit Komma) und Länge. Leer = keine Prüfung. */
    fun editRules() {
        val box = form()
        box.addView(label("Werte, die nicht passen, werden abgelehnt. Leer lassen = keine Prüfung.", 0).apply { alpha = 0.7f })
        val inputs = list.columns.mapIndexed { i, col ->
            val r = list.rules.getOrNull(i)
            box.addView(label(col).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
            val prefix = field("beginnt mit, z. B. GNXS (mehrere mit Komma)", r?.prefixes?.joinToString(", ") ?: "")
            val length = field("Länge in Zeichen (leer = egal)", r?.length?.toString() ?: "", number = true)
            box.addView(prefix); box.addView(length)
            prefix to length
        }
        AlertDialog.Builder(activity)
            .setTitle("Formatprüfung")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Speichern") { _, _ ->
                val old = list.rules.toList()
                inputs.forEachIndexed { i, (p, l) -> list.rules[i] = ColumnRule.parse(p.text.toString(), l.text.toString()) }
                if (!activity.persist()) { old.forEachIndexed { i, r -> list.rules[i] = r }; return@setPositiveButton }
                activity.updateStatus()
                val bad = list.rows.count { row -> row.cells.withIndex().any { (i, v) -> v.isNotEmpty() && list.rules.getOrNull(i)?.accepts(v) == false } }
                activity.showFeedback(
                    if (list.rules.all { it == null }) "Formatprüfung aus"
                    else "Formatprüfung gespeichert" + if (bad > 0) " · Achtung: $bad bereits erfasste Zeilen passen nicht" else "",
                    error = bad > 0,
                )
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------ Zielmenge

    fun editTarget() {
        val box = form()
        box.addView(label("Wie viele Zeilen (Geräte) gehören in diese Liste, z. B. 40 je Palette? Ist sie voll, ertönt ein Doppelton und weitere Scans werden abgelehnt. Leer = unbegrenzt.", 0).apply { alpha = 0.7f })
        val f = field("z. B. 40", list.target?.toString() ?: "", number = true)
        box.addView(f)
        AlertDialog.Builder(activity)
            .setTitle("Zielmenge")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                val old = list.target
                list.target = f.text.toString().trim().toIntOrNull()?.takeIf { it > 0 }
                if (!activity.persist()) { list.target = old; return@setPositiveButton }
                activity.updateStatus()
                val t = list.target
                activity.showFeedback(
                    when {
                        t == null -> "Zielmenge aus"
                        list.rows.size > t -> "Zielmenge $t – Achtung: die Liste hat schon ${list.rows.size} Zeilen"
                        else -> "Zielmenge $t gespeichert · noch ${t - list.completeRows()} frei"
                    },
                    error = t != null && list.rows.size > t,
                )
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------ Soll-Liste

    /** Datei lesen (im Hintergrund), dann Spalte der Datei und ggf. Spalte der Liste wählen. */
    fun loadExpected(uri: Uri) {
        activity.showFeedback("Soll-Liste wird gelesen …", error = false)
        Thread {
            val result = runCatching {
                val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Datei nicht lesbar.")
                Importer.readRows(bytes)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { chooseFileColumn(it) }
                    .onFailure { activity.showFeedback("✗ Soll-Liste nicht lesbar: ${it.message}", error = true) }
            }
        }.start()
    }

    /** Werte einer Spalte; die erste Zeile zählt als Überschrift, wenn sie keine Ziffer enthält. */
    private fun columnValues(rows: List<List<String>>, col: Int): List<String> {
        val cells = rows.mapNotNull { it.getOrNull(col)?.trim()?.takeIf { v -> v.isNotEmpty() } }
        val body = if (cells.firstOrNull()?.none { it.isDigit() } == true) cells.drop(1) else cells
        return body.distinct()
    }

    private fun chooseFileColumn(rows: List<List<String>>) {
        val width = rows.maxOfOrNull { it.size } ?: 0
        val cols = (0 until width).map { it to columnValues(rows, it) }.filter { it.second.isNotEmpty() }
        if (cols.isEmpty()) { activity.showFeedback("✗ Die Datei enthält keine Werte.", error = true); return }
        val labels = cols.map { (i, v) ->
            val head = rows.firstOrNull()?.getOrNull(i)?.trim().orEmpty().takeIf { h -> h.isNotEmpty() && h.none { it.isDigit() } }
            "Spalte ${'A' + i}${head?.let { " „$it“" } ?: ""}: ${v.size} Werte, z. B. ${v.first()}"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Welche Spalte enthält die Soll-Werte?")
            .setItems(labels) { _, which -> chooseListColumn(cols[which].second) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun chooseListColumn(values: List<String>) {
        if (list.columns.size == 1) { applyExpected(values, 0); return }
        AlertDialog.Builder(activity)
            .setTitle("Gegen welche Spalte der Liste prüfen?")
            .setItems(list.columns.toTypedArray()) { _, which -> applyExpected(values, which) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun applyExpected(values: List<String>, col: Int) {
        val oldValues = list.expected.toList()
        val oldCol = list.expectedColumn
        list.expected.clear(); list.expected.addAll(values)
        list.expectedColumn = col
        if (!activity.persist()) {
            list.expected.clear(); list.expected.addAll(oldValues); list.expectedColumn = oldCol
            return
        }
        activity.invalidateOptionsMenu()
        activity.updateStatus()
        val foreign = list.rows.count { r -> r.cells.getOrNull(col)?.let { it.isNotEmpty() && it !in list.expected } == true }
        activity.showFeedback(
            "Soll-Liste geladen: ${values.size} Werte für „${list.columns[col]}“ · ${list.foundExpected()} schon erfasst" +
                if (foreign > 0) " · Achtung: $foreign erfasste Werte stehen nicht in der Soll-Liste" else "",
            error = foreign > 0,
        )
    }

    fun showMissing() {
        val missing = list.missing()
        if (missing.isEmpty()) { toast("Alle ${list.expected.size} Soll-Werte sind erfasst."); return }
        val shown = missing.take(300)
        val text = TextView(activity).apply {
            text = shown.joinToString("\n") + if (missing.size > shown.size) "\n… und ${missing.size - shown.size} weitere" else ""
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
        }
        AlertDialog.Builder(activity)
            .setTitle("Es fehlen noch ${missing.size} von ${list.expected.size}")
            .setView(ScrollView(activity).apply {
                addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Als CSV teilen") { _, _ ->
                runCatching {
                    Share.send(activity, "Fehlend_${Csv.fileName(list)}", "text/csv", "Fehlend – ${list.name}") { out ->
                        Csv.writeValues(out, list.columns.getOrElse(list.expectedColumn) { "Wert" }, missing)
                    }
                }.onFailure { toast("Teilen fehlgeschlagen: ${it.message}") }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    fun clearExpected() {
        AlertDialog.Builder(activity)
            .setTitle("Soll-Liste entfernen?")
            .setMessage("Danach werden wieder alle Werte angenommen. Die erfassten Werte bleiben erhalten.")
            .setPositiveButton("Entfernen") { _, _ ->
                val old = list.expected.toList()
                list.expected.clear()
                if (!activity.persist()) { list.expected.addAll(old); return@setPositiveButton }
                activity.invalidateOptionsMenu()
                activity.updateStatus()
                activity.showFeedback("Soll-Liste entfernt", error = false)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------ Packliste (PDF)

    /** Kopfdaten abfragen, dann Packliste als PDF teilen oder auf dem Gerät speichern. */
    fun packList() {
        if (list.rows.none { r -> r.cells.any { it.isNotEmpty() } }) { toast("Die Liste ist leer."); return }
        val prefs = activity.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val box = form()
        box.addView(label("Blatt zum Beilegen zur Palette: Kopf mit Bezeichnung, Kunde, Datum und Anzahl, darunter alle Werte der Liste.", 0).apply { alpha = 0.7f })
        box.addView(label("Bezeichnung (z. B. Palette)"))
        val title = plain("z. B. Palette 3", list.name)
        box.addView(title)
        box.addView(label("Kunde / Empfänger"))
        val customer = CustomerPicker.field(activity, prefs.getString(PREF_CUSTOMER, "") ?: "")
        box.addView(customer)
        box.addView(label("Bemerkung (optional)"))
        val note = plain("z. B. Lieferschein 4711", "")
        box.addView(note)
        fun info(): PackInfo {
            val c = customer.text.toString().trim()
            CustomerPicker.remember(activity, c)
            return PackInfo(title.text.toString().trim().ifEmpty { list.name }, c, note.text.toString().trim(), de.inventur.app.Settings.sender(activity))
        }
        AlertDialog.Builder(activity)
            .setTitle("Packliste (PDF)")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Teilen") { _, _ -> sharePack(info()) }
            .setNeutralButton("Speichern …") { _, _ -> activity.savePack(info()) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun plain(hint: String, value: String) = EditText(activity).apply {
        this.hint = hint
        setText(value)
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun sharePack(info: PackInfo) {
        val copy = PackSheet.of(list)
        val name = packFileName(info.title)
        Thread {
            val file = runCatching { Share.prepare(activity, name) { PackList.write(copy, info, it) } }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                file.onSuccess { Share.open(activity, it, "application/pdf", "Packliste – ${info.title}") }
                    .onFailure { toast("Packliste fehlgeschlagen: ${it.message}") }
            }
        }.start()
    }

    // ------------------------------------------------------------ Abschließen, Folgepalette, Vorlage

    fun toggleClosed() {
        list.closed = !list.closed
        if (!activity.persist()) { list.closed = !list.closed; return }
        activity.invalidateOptionsMenu()
        activity.updateStatus()
        activity.showFeedback(
            if (list.closed) "Liste abgeschlossen – weitere Scans werden abgelehnt. ⋮ → „Liste wieder öffnen“ zum Weiterscannen."
            else "Liste wieder geöffnet – Scans werden wieder angenommen.",
            error = false,
        )
    }

    /**
     * Neue Liste mit denselben Einstellungen (Spalten, Formatprüfung, Zielmenge, Multidata) und
     * hochgezählter Nummer im Namen. [auto] = von selbst angeboten, weil die Zielmenge erreicht ist.
     */
    fun nextPalette(auto: Boolean) {
        val taken = ListStore.infos(activity).map { it.name }
        val box = form()
        box.addView(label(
            (if (auto) "Zielmenge erreicht. " else "") +
                "Neue Liste mit denselben Einstellungen (Spalten, Formatprüfung" +
                (list.target?.let { ", Zielmenge $it" } ?: "") + (if (list.genexis) ", Multidata-QR-Code" else "") + ").", 0,
        ).apply { alpha = 0.8f })
        box.addView(label("Name"))
        val name = plain("z. B. Palette 5", Naming.next(list.name, taken))
        box.addView(name)
        val close = android.widget.CheckBox(activity).apply {
            text = "Diese Liste abschließen"
            isChecked = !list.closed
            isEnabled = !list.closed
            setPadding(0, activity.dp(8), 0, 0)
        }
        box.addView(close)
        AlertDialog.Builder(activity)
            .setTitle(if (auto) "Nächste Palette anlegen?" else "Nächste Palette")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Anlegen") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { Naming.next(list.name, taken) }
                val next = ListTemplate.of(list, "").create(ListStore.newId(), n)
                try {
                    ListStore.save(activity, next)
                } catch (e: Exception) {
                    toast("Speichern fehlgeschlagen – ist der Gerätespeicher voll?"); return@setPositiveButton
                }
                if (close.isChecked && !list.closed) {
                    list.closed = true
                    if (!activity.persist()) list.closed = false
                }
                activity.startActivity(
                    android.content.Intent(activity, ListActivity::class.java).putExtra(ListActivity.EXTRA_ID, next.id),
                )
                activity.finish()
            }
            .setNegativeButton(if (auto) "Nicht jetzt" else "Abbrechen", null)
            .show()
    }

    fun saveTemplate() {
        val existing = TemplateStore.all(activity).map { it.name }
        val box = form()
        box.addView(label("Speichert Spalten, Zeitstempel, Multidata-QR-Code, Formatprüfung und Zielmenge (ohne Werte und Soll-Liste). Neue Listen heißen dann „${Naming.base(list.name)} 1“, „… 2“ usw.", 0).apply { alpha = 0.7f })
        box.addView(label("Name der Vorlage"))
        val f = plain("z. B. Genexis-Palette 40", Naming.base(list.name) + (list.target?.let { " $it Stück" } ?: ""))
        box.addView(f)
        AlertDialog.Builder(activity)
            .setTitle("Als Vorlage speichern")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                val n = f.text.toString().trim().ifEmpty { Naming.base(list.name) }
                runCatching { TemplateStore.put(activity, ListTemplate.of(list, n)) }
                    .onSuccess {
                        activity.showFeedback(
                            "Vorlage „$n“ " + (if (existing.any { it.equals(n, true) }) "aktualisiert" else "gespeichert") +
                                " – in der Listen-Übersicht über + auswählbar",
                            error = false,
                        )
                    }
                    .onFailure { toast("Speichern fehlgeschlagen – ist der Gerätespeicher voll?") }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------ Wareneingang: QR-Code einer Packliste

    /** Werte eines Packlisten-QR-Codes zur Soll-Liste hinzufügen. Gibt immer false zurück (kein Listenwert). */
    fun applyPackCode(raw: String): Boolean {
        val part = PackCode.decode(raw)
        if (part == null || part.rows.isEmpty()) {
            activity.showFeedback("✗ Packlisten-Code nicht lesbar oder leer.", error = true)
            activity.tones.error()
            return false
        }
        if (list.closed) {
            activity.showFeedback("✗ ${ScanList.CLOSED}", error = true)
            activity.tones.error()
            return false
        }
        val wasEmpty = list.expected.isEmpty()
        val col = if (wasEmpty) 0 else list.expectedColumn
        val values = part.rows.mapNotNull { r -> r.getOrNull(col)?.takeIf { it.isNotEmpty() } }
        val old = list.expected.toList()
        val before = old.size
        val oldColumn = list.expectedColumn
        list.expected.addAll(values)
        if (wasEmpty) list.expectedColumn = 0
        if (!activity.persist()) {
            list.expected.clear(); list.expected.addAll(old)
            list.expectedColumn = oldColumn
            return false
        }
        val added = list.expected.size - before
        activity.invalidateOptionsMenu()
        activity.updateStatus()
        val partText = if (part.parts > 1) " Teil ${part.part}/${part.parts}" else ""
        // Schon erfasste Werte, die nicht auf der Packliste stehen, sofort melden
        val ec = list.expectedColumn
        val foreign = list.rows.count { r -> r.cells.getOrNull(ec)?.let { it.isNotEmpty() && it !in list.expected } == true }
        val foreignText = if (foreign > 0) " Achtung: $foreign schon erfasste Werte stehen nicht auf der Packliste." else ""
        val more = if (part.parts > 1 && part.part < part.parts) " Weitere Teile scannen, dann die Geräte." else " Jetzt die Geräte scannen."
        activity.showFeedback(
            if (added == 0) "Packliste ‚${part.title}‘$partText ist schon in der Soll-Liste (${list.expected.size} Werte)."
            else "✓ Packliste ‚${part.title}‘$partText: $added Werte zur Soll-Liste (jetzt ${list.expected.size}).$more$foreignText",
            error = false,
            warn = foreign > 0,
        )
        activity.tones.ok()
        return false
    }

    companion object {
        internal const val PREF_CUSTOMER = "pack_customer"

        fun packFileName(title: String): String {
            val safe = title.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß_-]+"), "_").trim('_').ifEmpty { "Liste" }
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY).format(java.util.Date())
            return "Packliste_${safe}_$stamp.pdf"
        }
    }
}
