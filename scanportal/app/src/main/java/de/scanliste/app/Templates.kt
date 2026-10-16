package de.scanliste.app

import android.content.Context
import de.scanportal.app.SafeFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Vorlage für neue Listen: Spalten, Zeitstempel, Multidata-QR-Code, Formatprüfung und Zielmenge.
 * [baseName] ist der Name neuer Listen ohne laufende Nummer, z. B. „Palette“ → „Palette 5“.
 */
data class ListTemplate(
    val name: String,
    val baseName: String,
    val columns: List<String>,
    val withTimestamp: Boolean,
    val genexis: Boolean,
    val rules: List<ColumnRule?>,
    val target: Int?,
) {
    /** Neue, leere Liste nach dieser Vorlage. */
    fun create(id: String, listName: String) = ScanList(
        id = id,
        name = listName,
        columns = columns.toMutableList(),
        withTimestamp = withTimestamp,
        genexis = genexis && columns.size == 2,
        rules = MutableList(columns.size) { rules.getOrNull(it) },
        target = target,
    )

    companion object {
        /** Einstellungen einer Liste als Vorlage (ohne Werte und ohne Soll-Liste). */
        fun of(list: ScanList, name: String) = ListTemplate(
            name = name,
            baseName = Naming.base(list.name),
            columns = list.columns.toList(),
            withTimestamp = list.withTimestamp,
            genexis = list.genexis,
            rules = list.rules.toList(),
            target = list.target,
        )
    }
}

/** Laufende Nummern in Listennamen: „Palette 4“ → „Palette 5“. */
object Naming {
    private val numbered = Regex("^(.*?)(\\d+)\\s*$")
    private val clock = Regex("\\d{1,2}:\\d{2}$")

    /** Name ohne laufende Nummer am Ende („Palette 4“ → „Palette“). */
    fun base(name: String): String = if (clock.containsMatchIn(name.trim())) name.trim() else numbered.matchEntire(name.trim())?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: name.trim()

    /**
     * Nächster freier Name nach [name]: Zahl am Ende +1 (führende Nullen bleiben: „P009“ → „P010“),
     * ohne Zahl wird „ 2“ angehängt. Namen aus [taken] werden übersprungen (Groß-/Kleinschreibung egal).
     */
    fun next(name: String, taken: Collection<String>): String {
        val used = taken.map { it.trim().lowercase() }.toHashSet()
        // Endet der Name auf eine Uhrzeit (Vorgabe „Liste 16.10.2026 09:30“), keine Minute hochzählen
        val m = if (clock.containsMatchIn(name.trim())) null else numbered.matchEntire(name.trim())
        val prefix = m?.groupValues?.get(1) ?: (name.trim() + " ")
        val digits = m?.groupValues?.get(2)
        var n = digits?.toLong()?.plus(1) ?: 2L
        while (true) {
            val num = if (digits != null) n.toString().padStart(digits.length, '0') else n.toString()
            val candidate = prefix + num
            if (candidate.lowercase() !in used) return candidate
            n++
        }
    }

    /** Erster freier Name „[base] n“ ab der höchsten vorhandenen Nummer. */
    fun firstFree(base: String, taken: Collection<String>): String {
        val b = base.trim()
        val pattern = Regex("^" + Regex.escape(b) + "\\s*(\\d+)\\s*$", RegexOption.IGNORE_CASE)
        val max = taken.mapNotNull { pattern.matchEntire(it.trim())?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull() ?: 0L
        return next("$b $max", taken)
    }
}

/** Vorlagen in vorlagen.json (gehört zur Datensicherung). */
object TemplateStore {
    const val FILE = "vorlagen.json"
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun all(ctx: Context): List<ListTemplate> = runCatching {
        val f = file(ctx)
        if (!f.isFile) emptyList() else fromJson(JSONArray(f.readText()))
    }.getOrDefault(emptyList())

    /** Speichert [t]; eine Vorlage gleichen Namens wird ersetzt. Wirft bei Speicherfehler. */
    fun put(ctx: Context, t: ListTemplate) {
        val list = all(ctx).filterNot { it.name.equals(t.name, ignoreCase = true) } + t
        SafeFile.write(file(ctx), toJson(list.sortedBy { it.name.lowercase() }).toString())
    }

    fun remove(ctx: Context, name: String) {
        SafeFile.write(file(ctx), toJson(all(ctx).filterNot { it.name == name }).toString())
    }

    internal fun toJson(list: List<ListTemplate>) = JSONArray().apply {
        list.forEach { t ->
            put(JSONObject().apply {
                put("name", t.name)
                put("baseName", t.baseName)
                put("columns", JSONArray(t.columns))
                put("withTimestamp", t.withTimestamp)
                put("genexis", t.genexis)
                put("rules", JSONArray().apply {
                    t.rules.forEach { r ->
                        put(if (r == null) JSONObject.NULL else JSONObject().apply {
                            put("prefixes", JSONArray(r.prefixes))
                            r.length?.let { put("length", it) }
                        })
                    }
                })
                t.target?.let { put("target", it) }
            })
        }
    }

    internal fun fromJson(a: JSONArray): List<ListTemplate> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val cols = o.getJSONArray("columns").let { c -> List(c.length()) { c.getString(it) } }
        if (cols.isEmpty()) return@mapNotNull null
        ListTemplate(
            name = o.getString("name"),
            baseName = o.optString("baseName", o.getString("name")),
            columns = cols,
            withTimestamp = o.optBoolean("withTimestamp", true),
            genexis = o.optBoolean("genexis"),
            rules = List(cols.size) { j ->
                o.optJSONArray("rules")?.optJSONObject(j)?.let { r ->
                    ColumnRule(
                        r.optJSONArray("prefixes")?.let { p -> List(p.length()) { p.getString(it) } } ?: emptyList(),
                        if (r.has("length")) r.optInt("length") else null,
                    )
                }
            },
            target = if (o.has("target")) o.optInt("target") else null,
        )
    }
}
