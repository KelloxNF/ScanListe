package de.scanliste.app

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import de.inventur.app.InventoryStore

/**
 * Kunde für Packliste und Sammel-Packliste: Eingabefeld mit Vorschlägen – zuletzt benutzte Kunden zuerst,
 * dann alle Kunden aus den Inventuren. Ergibt einheitliche Schreibweisen auf allen Papieren.
 */
object CustomerPicker {
    private const val PREFS = "settings"
    private const val PREF_RECENT = "pack_customers_recent"
    private const val MAX_RECENT = 15

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Zuletzt benutzte Kunden, neuester zuerst. */
    fun recent(ctx: Context): List<String> =
        prefs(ctx).getString(PREF_RECENT, "")!!.split('\n').filter { it.isNotBlank() }

    /** Merkt sich [name] als zuletzt benutzten Kunden (und als Vorbelegung fürs nächste Mal). */
    fun remember(ctx: Context, name: String) {
        val n = name.trim()
        val ed = prefs(ctx).edit().putString(ListTools.PREF_CUSTOMER, n)
        if (n.isNotEmpty()) ed.putString(PREF_RECENT, merge(listOf(n) + recent(ctx), emptyList()).take(MAX_RECENT).joinToString("\n"))
        ed.apply()
    }

    /** Reine Funktion: zuletzt benutzte zuerst, dann Inventur-Kunden; doppelte (Groß-/Kleinschreibung egal) nur einmal. */
    fun merge(recent: List<String>, inventory: List<String>): List<String> {
        val seen = HashSet<String>()
        return (recent + inventory).map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }

    /** Eingabefeld mit Vorschlagsliste; tippen auf das Feld zeigt alle Vorschläge. */
    fun field(activity: Activity, initial: String): AutoCompleteTextView = AutoCompleteTextView(activity).apply {
        hint = "z. B. Musterfirma GmbH – antippen für Vorschläge"
        setText(initial)
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        threshold = 0
        fun show(names: List<String>) {
            setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, names))
        }
        show(recent(activity))
        setOnClickListener { if (adapter != null && adapter.count > 0) showDropDown() }
        // Inventur-Kunden im Hintergrund dazuladen (liest alle Inventuren)
        val ctx = activity.applicationContext
        Thread {
            val names = runCatching { merge(recent(ctx), InventoryStore.customers(ctx).map { it.name }) }.getOrNull()
            // Auch setzen, wenn der Dialog noch nicht sichtbar ist – sonst gingen schnelle Ergebnisse verloren
            if (names != null) activity.runOnUiThread { if (!activity.isFinishing) show(names) }
        }.start()
    }
}
