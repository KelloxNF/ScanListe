package de.scanliste.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Speichert jede Liste als JSON-Datei im privaten App-Speicher. */
object ListStore {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "lists").apply { mkdirs() }

    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    fun newId(): String = UUID.randomUUID().toString()

    fun all(ctx: Context): List<ScanList> =
        dir(ctx).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            ?.sortedByDescending { it.updated }
            ?: emptyList()

    fun load(ctx: Context, id: String): ScanList? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun save(ctx: Context, list: ScanList) {
        val target = file(ctx, list.id)
        val tmp = File(target.parentFile, "${list.id}.tmp")
        tmp.writeText(toJson(list).toString())
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
    }

    private fun toJson(list: ScanList): JSONObject = JSONObject().apply {
        put("id", list.id)
        put("name", list.name)
        put("columns", JSONArray(list.columns))
        put("withTimestamp", list.withTimestamp)
        put("created", list.created)
        put("updated", list.updated)
        put("rows", JSONArray().apply {
            list.rows.forEach { row ->
                put(JSONObject().apply {
                    put("cells", JSONArray(row.cells))
                    put("time", row.time)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): ScanList {
        val columns = o.getJSONArray("columns").let { a -> MutableList(a.length()) { a.getString(it) } }
        val rowsJson = o.getJSONArray("rows")
        val rows = MutableList(rowsJson.length()) { i ->
            val r = rowsJson.getJSONObject(i)
            val cells = r.getJSONArray("cells").let { a -> MutableList(a.length()) { a.getString(it) } }
            while (cells.size < columns.size) cells.add("")
            ScanRow(cells, r.optLong("time"))
        }
        return ScanList(
            id = o.getString("id"),
            name = o.getString("name"),
            columns = columns,
            withTimestamp = o.optBoolean("withTimestamp", true),
            rows = rows,
            created = o.optLong("created"),
            updated = o.optLong("updated"),
        )
    }
}
