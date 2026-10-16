package de.scanliste.app

import de.scanportal.app.R
import de.scanportal.app.DataWedge
import de.scanportal.app.ScanHint

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Übersicht aller Listen. */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val GENEXIS_COLUMNS = "Seriennummer, PON-SN"
        const val MENU_EXPORT = 1
        const val MENU_DAY = 2
        const val MENU_TEMPLATES = 3
    }

    private lateinit var adapter: ListsAdapter
    private val tools = OverviewTools(this)
    private lateinit var emptyView: TextView

    /** Scans in der Übersicht landen nirgends, darauf weisen wir deutlich hin. */
    private val scanHint = ScanHint(this) { "Scan nicht gespeichert – bitte zuerst eine Liste öffnen" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        DataWedge.ensureProfile(this)

        emptyView = findViewById(R.id.empty)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        adapter = ListsAdapter(
            onClick = { open(it.id) },
            onLongClick = { showListMenu(it) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        recycler.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { tools.newList { showCreateDialog() } }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_EXPORT, 0, "Sammelexport …")
        menu.add(0, MENU_DAY, 1, "Tagesbericht …")
        menu.add(0, MENU_TEMPLATES, 2, "Vorlagen …")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        MENU_EXPORT -> { tools.combinedExport(); true }
        MENU_DAY -> { tools.dailyReport(); true }
        MENU_TEMPLATES -> { tools.manageTemplates(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        scanHint.register()
        reload()
    }

    override fun onPause() {
        super.onPause()
        scanHint.unregister()
    }

    private fun reload() {
        val lists = ListStore.infos(this)
        adapter.submit(lists)
        emptyView.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
    }

    internal fun open(id: String) {
        startActivity(Intent(this, ListActivity::class.java).putExtra(ListActivity.EXTRA_ID, id))
    }

    internal fun trySave(list: ScanList): Boolean = try {
        ListStore.save(this, list)
        true
    } catch (e: Exception) {
        Toast.makeText(this, "Speichern fehlgeschlagen – ist der Gerätespeicher voll?", Toast.LENGTH_LONG).show()
        false
    }

    private fun showCreateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_list, null)
        val nameInput = view.findViewById<EditText>(R.id.input_name)
        val columnsInput = view.findViewById<EditText>(R.id.input_columns)
        val timestamp = view.findViewById<CheckBox>(R.id.check_timestamp)
        val genexis = view.findViewById<CheckBox>(R.id.check_genexis)
        genexis.setOnCheckedChangeListener { _, on ->
            // Passende Spaltennamen vorschlagen, solange noch die Vorgabe drinsteht
            val cur = columnsInput.text.toString().trim()
            if (on && (cur.isEmpty() || cur == "Seriennummer")) columnsInput.setText(GENEXIS_COLUMNS)
            if (!on && cur == GENEXIS_COLUMNS) columnsInput.setText("Seriennummer")
        }

        nameInput.setText("Liste " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date()))
        nameInput.selectAll()

        AlertDialog.Builder(this)
            .setTitle("Neue Liste")
            .setView(view)
            .setPositiveButton("Anlegen") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "Neue Liste" }
                var columns = columnsInput.text.toString()
                    .split(',', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { listOf("Seriennummer") }
                // Multidata-QR-Code braucht genau zwei Spalten: eine je Wert des Paars
                if (genexis.isChecked && columns.size != 2) columns = GENEXIS_COLUMNS.split(", ")
                val list = ScanList(
                    id = ListStore.newId(),
                    name = name,
                    columns = columns.toMutableList(),
                    withTimestamp = timestamp.isChecked,
                    genexis = genexis.isChecked,
                )
                // Genexis: Formatprüfung vorbelegen, damit vertauschte Paare auffallen (änderbar über ⋮ → Formatprüfung)
                if (genexis.isChecked) {
                    list.rules[0] = ColumnRule(listOf("GNXS"))
                    list.rules[1] = ColumnRule(listOf("Z."))
                }
                if (trySave(list)) open(list.id)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showListMenu(list: ListInfo) {
        AlertDialog.Builder(this)
            .setTitle(list.name)
            .setItems(arrayOf("Umbenennen", if (list.closed) "Wieder öffnen" else "Abschließen", "Löschen")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(list)
                    1 -> ListStore.load(this, list.id)?.let { l ->
                        l.closed = !l.closed
                        trySave(l)
                        reload()
                    }
                    2 -> confirmDelete(list)
                }
            }
            .show()
    }

    private fun showRenameDialog(info: ListInfo) {
        val input = EditText(this).apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setText(info.name)
            selectAll()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Umbenennen")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                val list = ListStore.load(this, info.id)
                if (name.isNotEmpty() && list != null) {
                    list.name = name
                    trySave(list)
                    reload()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmDelete(list: ListInfo) {
        AlertDialog.Builder(this)
            .setTitle("Liste löschen?")
            .setMessage("\"${list.name}\" mit ${list.count} Einträgen wird endgültig gelöscht.")
            .setPositiveButton("Löschen") { _, _ ->
                ListStore.delete(this, list.id)
                reload()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}

private class ListsAdapter(
    private val onClick: (ListInfo) -> Unit,
    private val onLongClick: (ListInfo) -> Unit,
) : RecyclerView.Adapter<ListsAdapter.VH>() {

    private var items: List<ListInfo> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }

    fun submit(lists: List<ListInfo>) {
        items = lists
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_list, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val list = items[position]
        holder.title.text = if (list.closed) "✓ ${list.name}" else list.name
        holder.subtitle.text = "${list.count} Einträge · Spalten: ${list.columns.joinToString(", ")} · " +
            "geändert ${Csv.formatTime(list.updated)}" + if (list.closed) " · abgeschlossen" else ""
        holder.itemView.setOnClickListener { onClick(list) }
        holder.itemView.setOnLongClickListener { onLongClick(list); true }
    }
}
