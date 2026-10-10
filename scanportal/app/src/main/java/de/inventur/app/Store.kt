package de.inventur.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Speichert jede Inventur als JSON-Datei im privaten App-Speicher (inventuren/<id>.json). */
object InventoryStore {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "inventuren").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    fun newId(): String = UUID.randomUUID().toString()

    fun all(ctx: Context): List<Inventory> =
        (dir(ctx).listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedWith(compareByDescending<Inventory> { it.stichtag }.thenBy { it.customer.name.lowercase() })

    fun load(ctx: Context, id: String): Inventory? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    /** Wirft eine Exception, wenn nicht gespeichert werden konnte (z. B. Speicher voll). */
    fun save(ctx: Context, inv: Inventory) {
        inv.updated = System.currentTimeMillis()
        writeAtomic(file(ctx, inv.id), toJson(inv).toString())
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
    }

    /** Bekannte Kunden aus früheren Inventuren (neueste Adresse gewinnt). */
    fun customers(ctx: Context): List<Customer> =
        all(ctx).sortedByDescending { it.updated }
            .distinctBy { it.customer.name.trim().lowercase() }
            .map { it.customer }
            .sortedBy { it.name.lowercase() }

    fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    fun toJson(inv: Inventory): JSONObject = JSONObject().apply {
        put("id", inv.id)
        put("customer", JSONObject().apply {
            put("name", inv.customer.name)
            put("street", inv.customer.street)
            put("city", inv.customer.city)
        })
        put("stichtag", inv.stichtag)
        put("letterDate", inv.letterDate)
        put("counter", inv.counter)
        put("writer", inv.writer)
        put("place", inv.place)
        put("timeFrom", inv.timeFrom)
        put("timeTo", inv.timeTo)
        put("blind", inv.blind)
        put("closed", inv.closed)
        put("created", inv.created)
        put("updated", inv.updated)
        put("articles", JSONArray().apply {
            inv.articles.forEach { a ->
                put(JSONObject().apply {
                    put("sku", a.sku); put("name", a.name); put("soll", a.soll); put("technik", a.technik)
                })
            }
        })
        put("counts", JSONArray().apply {
            inv.counts.forEach { c ->
                put(JSONObject().apply {
                    put("sku", c.sku); put("zone", c.zone); put("qty", c.qty); put("time", c.time)
                })
            }
        })
        put("estimated", JSONArray(inv.estimated.toList()))
        put("notes", JSONObject(inv.notes as Map<*, *>))
    }

    fun fromJson(o: JSONObject): Inventory {
        val c = o.getJSONObject("customer")
        val arts = o.getJSONArray("articles")
        val cnts = o.getJSONArray("counts")
        val est = o.optJSONArray("estimated") ?: JSONArray()
        val notesJson = o.optJSONObject("notes") ?: JSONObject()
        val notes = mutableMapOf<String, String>()
        notesJson.keys().forEach { k -> notes[k] = notesJson.optString(k) }
        return Inventory(
            id = o.getString("id"),
            customer = Customer(c.optString("name"), c.optString("street"), c.optString("city")),
            stichtag = o.optString("stichtag"),
            letterDate = o.optString("letterDate", o.optString("stichtag")),
            counter = o.optString("counter"),
            writer = o.optString("writer"),
            place = o.optString("place"),
            timeFrom = o.optString("timeFrom"),
            timeTo = o.optString("timeTo"),
            blind = o.optBoolean("blind"),
            closed = o.optBoolean("closed"),
            articles = MutableList(arts.length()) { i ->
                val a = arts.getJSONObject(i)
                Article(a.getString("sku"), a.optString("name"), a.optInt("soll"), a.optInt("technik"))
            },
            counts = MutableList(cnts.length()) { i ->
                val x = cnts.getJSONObject(i)
                CountEntry(x.getString("sku"), x.optString("zone"), x.optInt("qty"), x.optLong("time"))
            },
            estimated = MutableList(est.length()) { est.getString(it) }.toMutableSet(),
            notes = notes,
            created = o.optLong("created"),
            updated = o.optLong("updated"),
        )
    }
}

/**
 * Merkt sich je Kunde, welcher Barcode zu welchen Artikelnummern gehört.
 * Ein Barcode kann mehreren Artikeln gehören (z. B. Neu- und Refware mit gleicher EAN).
 */
object BarcodeStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "barcodes.json")

    /** Im Speicher gehalten, damit nicht bei jedem Scan die Datei gelesen wird. */
    private var cache: JSONObject? = null

    private fun read(ctx: Context): JSONObject = cache
        ?: runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject()).also { cache = it }

    private fun write(ctx: Context, root: JSONObject) {
        InventoryStore.writeAtomic(file(ctx), root.toString())
        cache = root
    }

    private fun key(customer: String) = customer.trim().lowercase()

    /** Zuordnungen mitnehmen, wenn ein Kunde umbenannt wird. */
    fun renameCustomer(ctx: Context, from: String, to: String) {
        if (key(from) == key(to)) return
        val root = read(ctx)
        val old = root.optJSONObject(key(from)) ?: return
        if (root.has(key(to))) return
        root.put(key(to), old); root.remove(key(from))
        write(ctx, root)
    }

    fun lookup(ctx: Context, customer: String, barcode: String): List<String> {
        val c = read(ctx).optJSONObject(key(customer)) ?: return emptyList()
        val a = c.optJSONArray(barcode.trim()) ?: return emptyList()
        return List(a.length()) { a.getString(it) }
    }

    fun assign(ctx: Context, customer: String, barcode: String, skus: List<String>) {
        val root = read(ctx)
        val c = root.optJSONObject(key(customer)) ?: JSONObject().also { root.put(key(customer), it) }
        if (skus.isEmpty()) c.remove(barcode.trim()) else c.put(barcode.trim(), JSONArray(skus))
        write(ctx, root)
    }

    fun clear(ctx: Context, customer: String) {
        val root = read(ctx)
        root.remove(key(customer))
        write(ctx, root)
    }

    fun count(ctx: Context, customer: String): Int = read(ctx).optJSONObject(key(customer))?.length() ?: 0
}

/**
 * Eigene Artikelbezeichnungen je Kunde (Artikelnummer → Bezeichnung).
 * Werden bei jedem neuen Import dieses Kunden automatisch wieder angewendet.
 */
object NameStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "namen.json")

    private fun read(ctx: Context): JSONObject =
        runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())

    private fun key(customer: String) = customer.trim().lowercase()

    fun all(ctx: Context, customer: String): Map<String, String> {
        val c = read(ctx).optJSONObject(key(customer)) ?: return emptyMap()
        val out = HashMap<String, String>()
        c.keys().forEach { k -> out[k] = c.optString(k) }
        return out
    }

    fun put(ctx: Context, customer: String, sku: String, name: String?) {
        val root = read(ctx)
        val c = root.optJSONObject(key(customer)) ?: JSONObject().also { root.put(key(customer), it) }
        if (name.isNullOrBlank()) c.remove(sku) else c.put(sku, name.trim())
        InventoryStore.writeAtomic(file(ctx), root.toString())
    }

    /** Eigene Bezeichnungen mitnehmen, wenn ein Kunde umbenannt wird. */
    fun renameCustomer(ctx: Context, from: String, to: String) {
        if (key(from) == key(to)) return
        val root = read(ctx)
        val old = root.optJSONObject(key(from)) ?: return
        if (root.has(key(to))) return
        root.put(key(to), old); root.remove(key(from))
        InventoryStore.writeAtomic(file(ctx), root.toString())
    }

    /** Wendet gespeicherte Bezeichnungen auf importierte Artikel an. */
    fun apply(ctx: Context, customer: String, articles: List<Article>): List<Article> {
        val names = all(ctx, customer)
        if (names.isEmpty()) return articles
        return articles.map { a -> names[a.sku]?.takeIf { it.isNotBlank() }?.let { a.copy(name = it) } ?: a }
    }
}

/** Feste Angaben für Anschreiben und Zählung. */
object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun sender(ctx: Context): String = prefs(ctx).getString("sender", "") ?: ""
    fun footer(ctx: Context): String = prefs(ctx).getString("footer", "") ?: ""
    fun ceo(ctx: Context): String = prefs(ctx).getString("ceo", "") ?: ""
    const val DEFAULT_ZONES = "Versand,Lager,Außenlager"

    fun zones(ctx: Context): List<String> =
        (prefs(ctx).getString("zones", DEFAULT_ZONES) ?: DEFAULT_ZONES)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("A") }

    /**
     * Einmalige Umstellung der alten Bereichsnamen A/B/C auf Versand/Lager/Außenlager –
     * in den Einstellungen und in bereits gespeicherten Zählungen.
     */
    fun migrateZones(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("zones_migrated_v2", false)) return
        val current = p.getString("zones", null)
        if (current == null || current.replace(" ", "") == "A,B,C") {
            val map = mapOf("A" to "Versand", "B" to "Lager", "C" to "Außenlager")
            p.edit().putString("zones", DEFAULT_ZONES).apply()
            for (inv in InventoryStore.all(ctx)) {
                if (inv.counts.none { it.zone in map }) continue
                val renamed = inv.counts.map { c -> map[c.zone]?.let { c.copy(zone = it) } ?: c }
                inv.replaceCounts(renamed)
                runCatching { InventoryStore.save(ctx, inv) }
            }
        }
        p.edit().putBoolean("zones_migrated_v2", true).apply()
    }

    fun save(ctx: Context, sender: String, footer: String, ceo: String, zones: String) {
        prefs(ctx).edit()
            .putString("sender", sender.trim())
            .putString("footer", footer.trim())
            .putString("ceo", ceo.trim())
            .putString("zones", zones)
            .apply()
    }
}
