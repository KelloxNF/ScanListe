package de.inventur.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/** Feste Angaben: Absender, Fußzeile und Geschäftsführung fürs Anschreiben, Zählbereiche. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Colors.init(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Einstellungen"

        val form = vbox(16)
        form.addView(label("Absenderzeile (klein über der Kundenadresse)"))
        val sender = field("Firma GmbH | Straße 1 | 12345 Ort").apply { setText(Settings.sender(this@SettingsActivity)) }
        form.addView(sender)

        form.addView(label("Geschäftsführer (unter der Unterschriftslinie)"))
        val ceo = field("Vorname Nachname").apply { setText(Settings.ceo(this@SettingsActivity)) }
        form.addView(ceo)

        form.addView(label("Fußzeile Anschreiben (mehrzeilig, optional)"))
        val footer = field("Firma GmbH · Geschäftsführer … · USt-IdNr. …", multiLine = true).apply {
            minLines = 3
            maxLines = 6
            setText(Settings.footer(this@SettingsActivity))
        }
        form.addView(footer)

        form.addView(label("Zählbereiche (mit Komma getrennt)"))
        val zones = field("A,B,C").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(Settings.zones(this@SettingsActivity).joinToString(","))
        }
        form.addView(zones)
        form.addView(text("z. B. A,B,C oder Halle 1,Halle 2,Technik", 12f, color = Colors.grey))

        form.addView(button("Speichern") {
            Settings.save(this, sender.text.toString(), footer.text.toString(), ceo.text.toString(), zones.text.toString())
            toast("Gespeichert")
            finish()
        }.apply { minHeight = dp(56) })

        setContentView(ScrollView(this).apply { addView(form) })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
