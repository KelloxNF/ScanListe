package de.inventur.app

import de.scanportal.app.dp

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate

/** Neue Inventur anlegen oder Kopfdaten einer bestehenden bearbeiten. */
class EditActivity : AppCompatActivity() {

    private var existing: Inventory? = null
    private var imported: List<Article>? = null

    private lateinit var customer: AutoCompleteTextView
    private lateinit var street: EditText
    private lateinit var city: EditText
    private lateinit var stichtag: EditText
    private lateinit var letterDate: EditText
    private lateinit var counter: EditText
    private lateinit var writer: EditText
    private lateinit var place: EditText
    private lateinit var timeFrom: EditText
    private lateinit var timeTo: EditText
    private lateinit var blind: CheckBox
    private lateinit var importInfo: TextView

    private var stichtagIso = Dates.today()
    private var letterIso = Dates.today()
    private var letterTouched = false

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Colors.init(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        existing = intent.getStringExtra(CountActivity.EXTRA_ID)?.let { InventoryStore.load(this, it) }
        title = if (existing == null) "Neue Inventur" else "Kopfdaten"

        val form = vbox(16)
        var known: List<Customer> = emptyList()

        form.addView(label("Kunde"))
        customer = AutoCompleteTextView(this).apply {
            hint = "Name des Kunden"
            textSize = 16f
            threshold = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setOnItemClickListener { parent, _, pos, _ ->
                val name = parent.getItemAtPosition(pos) as String
                known.firstOrNull { it.name == name }?.let { c -> street.setText(c.street); city.setText(c.city) }
            }
        }
        form.addView(customer)
        // Bekannte Kunden im Hintergrund sammeln (liest alle gespeicherten Inventuren)
        Thread {
            val list = InventoryStore.customers(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                known = list
                customer.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
            }
        }.start()
        form.addView(label("Straße"))
        street = field("Musterstraße 123").also { form.addView(it) }
        form.addView(label("PLZ / Ort"))
        city = field("12345 Musterstadt").also { form.addView(it) }

        form.addView(label("Stichtag"))
        stichtag = dateField { iso -> stichtagIso = iso; if (!letterTouched && existing == null) { letterIso = iso; letterDate.setText(Dates.display(iso)) } }
        form.addView(stichtag)
        form.addView(label("Briefdatum Anschreiben"))
        letterDate = dateField { iso -> letterIso = iso; letterTouched = true }
        form.addView(letterDate)

        form.addView(label("Zähler"))
        counter = field("Name").also { form.addView(it) }
        form.addView(label("Schreiber"))
        writer = field("Name").also { form.addView(it) }
        form.addView(label("Zählort"))
        place = field("z. B. Hauptgebäude").also { form.addView(it) }

        form.addView(label("Uhrzeit von / bis (wird beim Zählen automatisch eingetragen)"))
        val times = hbox()
        timeFrom = field("08:00").apply { inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME }
        timeTo = field("11:00").apply { inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME }
        times.addView(timeFrom.lp(0, weight = 1f))
        times.addView(text("  –  "))
        times.addView(timeTo.lp(0, weight = 1f))
        form.addView(times)

        blind = CheckBox(this).apply {
            text = "Blindzählung (Sollbestand beim Zählen ausblenden)"
            textSize = 15f
        }
        form.addView(blind.lp().apply { setPadding(0, dp(12), 0, 0) })

        form.addView(label("Systembestand"))
        importInfo = text("Noch nicht importiert.", 14f, color = Colors.grey)
        form.addView(importInfo)
        form.addView(button("Systembestand importieren (Excel oder CSV) …") { pickFile.launch(arrayOf("*/*")) })
        form.addView(text(
            "Erwartete Spalten: Artikelnummer, Artikel, Bestand, Bestand in Technik. Endet die Artikelnummer auf „Ref“, gilt der Artikel als Refware.",
            12f, color = Colors.grey,
        ))

        form.addView(button(if (existing == null) "Inventur anlegen" else "Speichern") { save() }.apply {
            minHeight = dp(60)
        }.lp().also { (it.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin = dp(20) })

        setContentView(ScrollView(this).apply { addView(form) })
        fill()
    }

    private fun fill() {
        val inv = existing
        if (inv == null) {
            stichtag.setText(Dates.display(stichtagIso))
            letterDate.setText(Dates.display(letterIso))
            blind.isChecked = false
            return
        }
        customer.setText(inv.customer.name)
        street.setText(inv.customer.street)
        city.setText(inv.customer.city)
        stichtagIso = inv.stichtag; letterIso = inv.letterDate
        stichtag.setText(Dates.display(inv.stichtag))
        letterDate.setText(Dates.display(inv.letterDate))
        counter.setText(inv.counter); writer.setText(inv.writer); place.setText(inv.place)
        timeFrom.setText(inv.timeFrom); timeTo.setText(inv.timeTo)
        blind.isChecked = inv.blind
        importInfo.text = "${inv.articles.size} Artikel, davon ${inv.articles.count { it.soll > 0 }} mit Bestand."
    }

    private fun dateField(onPick: (String) -> Unit): EditText = field().apply {
        isFocusable = false
        isClickable = true
        setOnClickListener {
            val cur = Dates.parse(if (this === stichtag) stichtagIso else letterIso) ?: LocalDate.now()
            DatePickerDialog(this@EditActivity, { _, y, m, d ->
                val iso = LocalDate.of(y, m + 1, d).toString()
                setText(Dates.display(iso))
                onPick(iso)
            }, cur.year, cur.monthValue - 1, cur.dayOfMonth).show()
        }
    }

    private fun importFrom(uri: Uri) {
        importInfo.text = "Wird gelesen …"
        importInfo.setTextColor(Colors.grey)
        // Lesen im Hintergrund, damit große Excel-Dateien die Oberfläche nicht einfrieren
        Thread {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Datei nicht lesbar.")
                Importer.read(bytes)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { res -> applyImport(res) }.onFailure { e ->
                    importInfo.text = "Import fehlgeschlagen."
                    importInfo.setTextColor(Colors.error)
                    AlertDialog.Builder(this)
                        .setTitle("Import fehlgeschlagen")
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun applyImport(res: ImportResult) {
        val apply = {
            imported = res.articles
            importInfo.text = "✓ " + res.info
            importInfo.setTextColor(Colors.ok)
        }
        val inv = existing
        if (inv != null && inv.counts.isNotEmpty()) {
            val newSkus = res.articles.map { it.sku }.toHashSet()
            val missing = inv.counts.map { it.sku }.distinct().filter { it !in newSkus }
            val warn = if (missing.isEmpty()) "" else
                "\n\nAchtung: ${missing.size} bereits gezählte Artikel fehlen in der neuen Datei " +
                    "(${missing.take(5).joinToString(", ")}${if (missing.size > 5) " …" else ""}) und wären danach nicht mehr sichtbar."
            AlertDialog.Builder(this)
                .setTitle("Systembestand ersetzen?")
                .setMessage("Die bereits gezählten Mengen bleiben erhalten und werden über die Artikelnummer zugeordnet.\n\n${res.info}$warn")
                .setPositiveButton("Ersetzen") { _, _ -> apply() }
                .setNegativeButton("Abbrechen", null)
                .show()
        } else apply()
    }

    private fun save() {
        val name = customer.text.toString().trim()
        if (name.isEmpty()) { customer.error = "Bitte Kunden eintragen"; customer.requestFocus(); return }
        val inv = existing
        if (inv == null && imported == null) {
            toast("Bitte zuerst den Systembestand importieren.")
            return
        }
        val target = inv ?: Inventory(
            id = InventoryStore.newId(),
            customer = Customer(name, "", ""),
            stichtag = stichtagIso, letterDate = letterIso,
            counter = "", writer = "", place = "", timeFrom = "", timeTo = "",
            blind = false, closed = false,
            articles = mutableListOf(), counts = mutableListOf(),
            estimated = mutableSetOf(), notes = mutableMapOf(),
            created = System.currentTimeMillis(), updated = System.currentTimeMillis(),
        )
        val oldName = inv?.customer?.name
        target.customer = Customer(name, street.text.toString().trim(), city.text.toString().trim())
        target.stichtag = stichtagIso
        target.letterDate = letterIso
        target.counter = counter.text.toString().trim()
        target.writer = writer.text.toString().trim()
        target.place = place.text.toString().trim()
        target.timeFrom = timeFrom.text.toString().trim()
        target.timeTo = timeTo.text.toString().trim()
        target.blind = blind.isChecked
        imported?.let { target.articles.clear(); target.articles.addAll(NameStore.apply(this, name, it)) }
        try {
            InventoryStore.save(this, target)
        } catch (e: Exception) {
            toast("Nicht gespeichert – ist der Gerätespeicher voll?")
            return
        }
        // Erst nach erfolgreichem Speichern: Barcode-Zuordnungen und eigene Bezeichnungen zum neuen Kundennamen mitnehmen
        if (oldName != null && oldName.trim().lowercase() != name.lowercase()) {
            val moved = BarcodeStore.renameCustomer(this, oldName, name) and NameStore.renameCustomer(this, oldName, name)
            if (!moved) toast("Barcode-Zuordnungen/Bezeichnungen konnten nicht übernommen werden – Speicher voll?")
        }
        if (inv == null) {
            startActivity(Intent(this, CountActivity::class.java).putExtra(CountActivity.EXTRA_ID, target.id))
        }
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
