package de.scanliste.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Übersicht aller Listen. */
class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ListsAdapter
    private lateinit var emptyView: TextView

    /** Scans in der Übersicht landen nirgends, darauf weisen wir deutlich hin. */
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Toast.makeText(
                this@MainActivity,
                "Scan nicht gespeichert – bitte zuerst eine Liste öffnen",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showCreateDialog() }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, scanReceiver, IntentFilter(DataWedge.SCAN_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        reload()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(scanReceiver)
    }

    private fun reload() {
        val lists = ListStore.infos(this)
        adapter.submit(lists)
        emptyView.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(id: String) {
        startActivity(Intent(this, ListActivity::class.java).putExtra(ListActivity.EXTRA_ID, id))
    }

    private fun trySave(list: ScanList): Boolean = try {
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

        nameInput.setText("Liste " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date()))
        nameInput.selectAll()

        AlertDialog.Builder(this)
            .setTitle("Neue Liste")
            .setView(view)
            .setPositiveButton("Anlegen") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "Neue Liste" }
                val columns = columnsInput.text.toString()
                    .split(',', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { listOf("Seriennummer") }
                val list = ScanList(
                    id = ListStore.newId(),
                    name = name,
                    columns = columns.toMutableList(),
                    withTimestamp = timestamp.isChecked,
                )
                if (trySave(list)) open(list.id)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showListMenu(list: ListInfo) {
        AlertDialog.Builder(this)
            .setTitle(list.name)
            .setItems(arrayOf("Umbenennen", "Löschen")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(list)
                    1 -> confirmDelete(list)
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
        holder.title.text = list.name
        holder.subtitle.text = "${list.count} Einträge · Spalten: ${list.columns.joinToString(", ")} · " +
            "geändert ${Csv.formatTime(list.updated)}"
        holder.itemView.setOnClickListener { onClick(list) }
        holder.itemView.setOnLongClickListener { onLongClick(list); true }
    }
}
