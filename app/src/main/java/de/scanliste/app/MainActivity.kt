package de.scanliste.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
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

    private lateinit var adapter: ListsAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DataWedge.ensureProfile(this)

        emptyView = findViewById(R.id.empty)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        adapter = ListsAdapter(
            onClick = { open(it) },
            onLongClick = { showListMenu(it) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        recycler.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showCreateDialog() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val lists = ListStore.all(this)
        adapter.submit(lists)
        emptyView.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(list: ScanList) {
        startActivity(Intent(this, ListActivity::class.java).putExtra(ListActivity.EXTRA_ID, list.id))
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
                    .ifEmpty { listOf("Code") }
                val list = ScanList(
                    id = ListStore.newId(),
                    name = name,
                    columns = columns.toMutableList(),
                    withTimestamp = timestamp.isChecked,
                )
                ListStore.save(this, list)
                open(list)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showListMenu(list: ScanList) {
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

    private fun showRenameDialog(list: ScanList) {
        val input = EditText(this).apply {
            setText(list.name)
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
                if (name.isNotEmpty()) {
                    list.name = name
                    ListStore.save(this, list)
                    reload()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmDelete(list: ScanList) {
        AlertDialog.Builder(this)
            .setTitle("Liste löschen?")
            .setMessage("\"${list.name}\" mit ${list.valueCount()} Einträgen wird endgültig gelöscht.")
            .setPositiveButton("Löschen") { _, _ ->
                ListStore.delete(this, list.id)
                reload()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}

private class ListsAdapter(
    private val onClick: (ScanList) -> Unit,
    private val onLongClick: (ScanList) -> Unit,
) : RecyclerView.Adapter<ListsAdapter.VH>() {

    private var items: List<ScanList> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }

    fun submit(lists: List<ScanList>) {
        items = lists
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_list, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val list = items[position]
        holder.title.text = list.name
        holder.subtitle.text = "${list.valueCount()} Einträge · Spalten: ${list.columns.joinToString(", ")} · " +
            "geändert ${Csv.formatTime(list.updated)}"
        holder.itemView.setOnClickListener { onClick(list) }
        holder.itemView.setOnLongClickListener { onLongClick(list); true }
    }
}
