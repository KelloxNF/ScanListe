package de.scanliste.app

import de.scanportal.app.R
import de.scanportal.app.DataWedge
import de.scanportal.app.Tones
import de.scanportal.app.dp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Eine Liste als Tabelle; hier wird gescannt. */
class ListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "list_id"
    }

    private lateinit var list: ScanList
    private lateinit var adapter: RowAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var input: EditText
    private lateinit var tones: Tones
    private var receiverRegistered = false

    /**
     * Was „Letzten Scan rückgängig“ zurücknimmt, jüngster Scan zuletzt:
     * 0 = ein einzelner Wert, n > 0 = n Zeilen aus einem Genexis-Scan. Gilt für diese Sitzung.
     */
    private val undoStack = ArrayDeque<Int>()

    /** Liste wurde gespeichert, die Kurzinfo für die Übersicht aber noch nicht. */
    private var infoDirty = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = intent.getStringExtra(DataWedge.EXTRA_DATA) ?: return
            handleValue(data, DataWedge.prettyType(intent.getStringExtra(DataWedge.EXTRA_LABEL_TYPE)))
        }
    }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) exportTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = intent.getStringExtra(EXTRA_ID)?.let { ListStore.load(this, it) }
        if (loaded == null) {
            finish()
            return
        }
        list = loaded

        setContentView(R.layout.activity_list)
        title = list.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        statusText = findViewById(R.id.status)
        feedbackText = findViewById(R.id.feedback)
        recycler = findViewById(R.id.recycler)
        input = findViewById(R.id.input_manual)

        Table.fillHeader(findViewById(R.id.header), list)
        adapter = RowAdapter(list) { pos -> confirmDeleteRow(pos) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<Button>(R.id.button_add).setOnClickListener { submitManual() }
        input.setOnEditorActionListener { _, actionId, event ->
            when {
                actionId == EditorInfo.IME_ACTION_DONE -> { submitManual(); true }
                event != null && event.keyCode == KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) submitManual()
                    true
                }
                else -> false
            }
        }

        tones = Tones(this)
        updateStatus()
        scrollToEnd()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, scanReceiver, IntentFilter(DataWedge.SCAN_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun onPause() {
        super.onPause()
        if (infoDirty) { ListStore.saveInfo(this, list); infoDirty = false }
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
    }

    override fun onDestroy() {
        if (::tones.isInitialized) tones.release()
        super.onDestroy()
    }

    // ---------- Scannen ----------

    private fun submitManual() {
        val text = input.text.toString()
        if (text.isBlank()) return
        val added = handleValue(text, null)
        if (added) input.text.clear() else input.selectAll()
    }

    /** Gibt true zurück, wenn der Wert übernommen wurde. */
    private fun handleValue(raw: String, type: String?): Boolean {
        if (list.genexis) {
            val parts = ScanList.splitValues(raw)
            if (parts.size > 1) return handlePairs(parts)
        }
        return when (val result = list.add(raw)) {
            is AddResult.Empty -> false
            is AddResult.Duplicate -> {
                showFeedback(
                    "✗ Doppelt – \"${raw.trim()}\" steht schon in Zeile ${result.row + 1}" +
                        colSuffix(result.col),
                    error = true,
                )
                tones.error()
                recycler.scrollToPosition(result.row)
                false
            }
            is AddResult.Added -> {
                if (!persist()) {
                    // Nicht gespeichert: Wert wieder herausnehmen, damit Anzeige und Datei übereinstimmen.
                    list.undoLast()
                    adapter.notifyDataSetChanged()
                    updateStatus()
                    return false
                }
                pushUndo(0)
                adapter.notifyDataSetChanged()
                val typeInfo = if (type != null) " ($type)" else ""
                showFeedback("✓ ${raw.trim()}$typeInfo", error = false)
                tones.ok()
                scrollToEnd()
                updateStatus()
                true
            }
        }
    }

    /** Genexis-Scan: QR-Code mit mehreren Werten paarweise auf Zeilen verteilen. */
    private fun handlePairs(parts: List<String>): Boolean {
        return when (val r = list.addPairs(parts)) {
            is PairResult.Rejected -> {
                showFeedback("✗ ${r.message}", error = true)
                tones.error()
                r.row?.let { recycler.scrollToPosition(it) }
                return false
            }
            is PairResult.Added -> {
                if (r.rows == 0) {
                    showFeedback("✗ Alle ${r.skipped} Paare dieses QR-Codes sind schon erfasst.", error = true)
                    tones.error()
                    return false
                }
                if (!persist()) {
                    list.removeLastRows(r.rows)
                    adapter.notifyDataSetChanged()
                    updateStatus()
                    return false
                }
                pushUndo(r.rows)
                adapter.notifyDataSetChanged()
                val range = if (r.rows == 1) "Zeile ${r.firstRow + 1}" else "Zeilen ${r.firstRow + 1}–${r.firstRow + r.rows}"
                val skip = if (r.skipped > 0) " · ${r.skipped} schon erfasste übersprungen" else ""
                showFeedback("✓ ${r.rows} ${if (r.rows == 1) "Paar" else "Paare"} → $range$skip", error = false)
                tones.ok()
                scrollToEnd()
                updateStatus()
                return true
            }
        }
    }

    private fun pushUndo(entry: Int) {
        undoStack.addLast(entry)
        if (undoStack.size > 500) undoStack.removeFirst()
    }

    /** Speichert die Liste. Bei einem Fehler (z. B. Speicher voll) Meldung statt Absturz. */
    private fun persist(): Boolean = try {
        ListStore.save(this, list, withInfo = false)
        infoDirty = true
        true
    } catch (e: Exception) {
        showFeedback("✗ Nicht gespeichert – ist der Gerätespeicher voll?", error = true)
        tones.error()
        false
    }

    private fun colSuffix(col: Int) = if (list.columns.size > 1) ", Spalte \"${list.columns[col]}\"" else ""

    private fun updateStatus() {
        val (r, c) = list.nextSlot()
        val next = if (list.columns.size > 1) "Zeile ${r + 1} → ${list.columns[c]}" else "Zeile ${r + 1}"
        statusText.text = (if (list.genexis) "Genexis-Scan · " else "") +
            "${list.valueCount()} Einträge · Nächster Scan: $next"
    }

    private fun showFeedback(text: String, error: Boolean) {
        feedbackText.text = text
        feedbackText.setBackgroundColor(
            ContextCompat.getColor(this, if (error) R.color.feedback_error else R.color.feedback_ok),
        )
    }

    private fun scrollToEnd() {
        if (list.rows.isNotEmpty()) recycler.scrollToPosition(list.rows.size - 1)
    }


    // ---------- Bearbeiten ----------

    private fun confirmDeleteRow(pos: Int) {
        if (pos !in list.rows.indices) return
        val values = list.rows[pos].cells.filter { it.isNotEmpty() }.joinToString(", ")
        AlertDialog.Builder(this)
            .setTitle("Zeile ${pos + 1} löschen?")
            .setMessage(values)
            .setPositiveButton("Löschen") { _, _ ->
                list.removeRow(pos)
                undoStack.clear()   // Zeilennummern haben sich verschoben
                persist()
                adapter.notifyDataSetChanged()
                updateStatus()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun undo() {
        val rowsOfScan = undoStack.removeLastOrNull() ?: 0
        val message = if (rowsOfScan > 0) {
            val n = list.removeLastRows(rowsOfScan)
            "↶ Genexis-Scan entfernt: $n ${if (n == 1) "Paar" else "Paare"}"
        } else {
            val removed = list.undoLast()
            if (removed == null) {
                Toast.makeText(this, "Liste ist leer", Toast.LENGTH_SHORT).show()
                return
            }
            "↶ Entfernt: $removed"
        }
        adapter.notifyDataSetChanged()
        updateStatus()
        if (persist()) showFeedback(message, error = false)
    }

    private fun toggleGenexis() {
        list.genexis = !list.genexis
        if (!persist()) { list.genexis = !list.genexis; return }
        invalidateOptionsMenu()
        updateStatus()
        showFeedback(
            if (list.genexis) "Genexis-Scan an: QR-Codes werden paarweise auf Zeilen verteilt" else "Genexis-Scan aus",
            error = false,
        )
    }

    // ---------- Export ----------

    private fun exportTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { Csv.write(it, list) }
            Toast.makeText(this, "CSV gespeichert", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Speichern fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun share() {
        try {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            // Nur ältere Exporte löschen – eine Mail-App liest den letzten evtl. noch
            val limit = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
            val file = File(dir, Csv.fileName(list))
            file.outputStream().use { Csv.write(it, list) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, list.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "CSV teilen"))
        } catch (e: Exception) {
            Toast.makeText(this, "Teilen fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Menü ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.list_menu, menu)
        menu.findItem(R.id.action_genexis)?.apply {
            isVisible = list.columns.size >= 2   // ein Paar braucht zwei Spalten
            isChecked = list.genexis
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_undo -> { undo(); true }
        R.id.action_export -> { exportLauncher.launch(Csv.fileName(list)); true }
        R.id.action_share -> { share(); true }
        R.id.action_genexis -> { toggleGenexis(); true }
        R.id.action_manual -> {
            input.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

/** Baut die Tabellenzeilen (feste Spaltenbreiten, damit Kopf und Zeilen übereinanderliegen). */
object Table {
    private const val NR_WIDTH = 52
    private const val CELL_WIDTH = 190
    private const val TIME_WIDTH = 160

    private fun cell(ctx: Context, widthDp: Int, bold: Boolean = false, alignEnd: Boolean = false) =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10))
            textSize = 15f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (alignEnd) gravity = Gravity.END
            setTextIsSelectable(false)
        }

    fun makeRow(ctx: Context, list: ScanList): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(cell(ctx, NR_WIDTH, alignEnd = true))
        list.columns.forEach { _ -> addView(cell(ctx, CELL_WIDTH)) }
        if (list.withTimestamp) addView(cell(ctx, TIME_WIDTH))
    }

    fun fillHeader(header: LinearLayout, list: ScanList) {
        val ctx = header.context
        header.removeAllViews()
        header.addView(cell(ctx, NR_WIDTH, bold = true, alignEnd = true).apply { text = "Nr" })
        list.columns.forEach { name -> header.addView(cell(ctx, CELL_WIDTH, bold = true).apply { text = name }) }
        if (list.withTimestamp) header.addView(cell(ctx, TIME_WIDTH, bold = true).apply { text = "Zeit" })
    }
}

private class RowAdapter(
    private val list: ScanList,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<RowAdapter.VH>() {

    class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)

    override fun getItemCount() = list.rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(Table.makeRow(parent.context, list))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.row.context
        val row = list.rows[position]
        val (nextRow, nextCol) = list.nextSlot()
        val stripe = ContextCompat.getColor(ctx, R.color.row_stripe)
        val highlight = ContextCompat.getColor(ctx, R.color.next_cell)

        holder.row.setBackgroundColor(if (position % 2 == 1) stripe else Color.TRANSPARENT)
        (holder.row.getChildAt(0) as TextView).text = (position + 1).toString()
        row.cells.forEachIndexed { i, value ->
            val tv = holder.row.getChildAt(1 + i) as TextView
            tv.text = value
            tv.setBackgroundColor(if (position == nextRow && i == nextCol) highlight else Color.TRANSPARENT)
        }
        if (list.withTimestamp) {
            (holder.row.getChildAt(1 + list.columns.size) as TextView).text = Csv.formatTime(row.time)
        }
        holder.row.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }
    }
}
