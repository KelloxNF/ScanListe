package de.scanliste.app

import de.scanportal.app.SafeFile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Kurzinfo zu einer Liste für die Übersicht, ohne die ganze Liste zu laden. */
data class ListInfo(
    val id: String,
    val name: String,
    val columns: List<String>,
    val count: Int,
    val updated: Long,
)

/**
 * Speichert jede Liste als JSON-Datei im privaten App-Speicher (lists/<id>.json)
 * und daneben eine kleine Kurzinfo (lists/meta/<id>.json), damit die Übersicht
 * auch bei vielen großen Listen sofort lädt.
 */
object ListStore {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "lists").apply { mkdirs() }

    private fun metaDir(ctx: Context): File = File(dir(ctx), "meta").apply { mkdirs() }

    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    private fun metaFile(ctx: Context, id: String) = File(metaDir(ctx), "$id.json")

    fun newId(): String = UUID.randomUUID().toString()

    /** Kurzinfos aller Listen, neueste zuerst. Lädt nur die kleinen Meta-Dateien. */
    fun infos(ctx: Context): List<ListInfo> {
        val files = dir(ctx).listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            val id = f.nameWithoutExtension
            val meta = metaFile(ctx, id)
            val fromMeta = if (meta.exists() && meta.lastModified() >= f.lastModified()) readMeta(meta) else null
            fromMeta ?: rebuildMeta(ctx, f)
        }.sortedByDescending { it.updated }
    }

    fun load(ctx: Context, id: String): ScanList? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    /**
     * Wirft eine Exception, wenn nicht gespeichert werden konnte (z. B. Speicher voll).
     * Beim Scannen ohne Kurzinfo ([withInfo] = false) – die wird dann einmal beim Verlassen
     * der Liste geschrieben ([saveInfo]); das spart je Scan einen Schreibvorgang.
     */
    fun save(ctx: Context, list: ScanList, withInfo: Boolean = true) {
        writeAtomic(file(ctx, list.id), toJson(list).toString())
        if (withInfo) saveInfo(ctx, list)
    }

    /** Kurzinfo für die Übersicht aktualisieren. Fehlt sie oder ist sie veraltet, wird sie beim Laden neu gebaut. */
    fun saveInfo(ctx: Context, list: ScanList) {
        runCatching { writeMeta(ctx, list) }
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
        metaFile(ctx, id).delete()
    }

    // ---------- intern ----------

    private fun writeAtomic(target: File, text: String) = SafeFile.write(target, text)

    private fun infoOf(list: ScanList) = ListInfo(list.id, list.name, list.columns.toList(), list.valueCount(), list.updated)

    private fun writeMeta(ctx: Context, list: ScanList) {
        val o = JSONObject().apply {
            put("id", list.id)
            put("name", list.name)
            put("columns", JSONArray(list.columns))
            put("count", list.valueCount())
            put("updated", list.updated)
        }
        writeAtomic(metaFile(ctx, list.id), o.toString())
    }

    private fun readMeta(f: File): ListInfo? = runCatching {
        val o = JSONObject(f.readText())
        val cols = o.getJSONArray("columns").let { a -> List(a.length()) { a.getString(it) } }
        ListInfo(o.getString("id"), o.getString("name"), cols, o.getInt("count"), o.getLong("updated"))
    }.getOrNull()

    private fun rebuildMeta(ctx: Context, f: File): ListInfo? {
        val list = runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() ?: return null
        runCatching { writeMeta(ctx, list) }
        return infoOf(list)
    }

    private fun toJson(list: ScanList): JSONObject = JSONObject().apply {
        put("id", list.id)
        put("name", list.name)
        put("columns", JSONArray(list.columns))
        put("withTimestamp", list.withTimestamp)
        put("genexis", list.genexis)
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
            genexis = o.optBoolean("genexis"),
        )
    }
}
