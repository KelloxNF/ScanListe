package de.scanportal.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Datensicherung ohne Cloud: alle Listen, Inventuren, Barcode-Zuordnungen, eigene Bezeichnungen
 * und Einstellungen in EINER Zip-Datei, die man z. B. per USB auf den PC kopiert – und wieder einspielen.
 */
object Backup {
    private const val MARKER = "scanportal-sicherung.json"
    private val DIRS = listOf("lists", "inventuren")
    private val FILES = listOf("barcodes.json", "namen.json", "vorlagen.json")
    private const val PREFS = "settings"

    /** Nur diese Namen werden beim Einspielen angenommen – Schutz vor manipulierten Zip-Dateien. */
    private val allowed = Regex("^(lists/(meta/)?[A-Za-z0-9-]+\\.json|inventuren/[A-Za-z0-9-]+\\.json|barcodes\\.json|namen\\.json|vorlagen\\.json|scanportal-sicherung\\.json)$")
    private const val MAX_BYTES = 300L * 1024 * 1024

    /** Inhalt einer Sicherung, für die Rückfrage vor dem Einspielen. */
    data class Summary(val created: Long, val version: String, val lists: Int, val inventories: Int)

    fun write(ctx: Context, out: OutputStream) {
        val prefs = JSONObject()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            when (v) {
                is String -> prefs.put(k, JSONObject().put("s", v))
                is Boolean -> prefs.put(k, JSONObject().put("b", v))
                is Int -> prefs.put(k, JSONObject().put("i", v))
                is Long -> prefs.put(k, JSONObject().put("l", v))
            }
        }
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        ZipOutputStream(out).use { zip ->
            val marker = JSONObject().put("app", "Scan-Portal").put("version", version)
                .put("created", System.currentTimeMillis()).put("settings", prefs)
            zip.putNextEntry(ZipEntry(MARKER)); zip.write(marker.toString().toByteArray()); zip.closeEntry()
            for (d in DIRS) {
                val base = File(ctx.filesDir, d)
                base.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(ctx.filesDir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            for (n in FILES) {
                val f = File(ctx.filesDir, n)
                if (f.isFile) {
                    zip.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
    }

    private fun staging(ctx: Context) = File(ctx.filesDir, "restore-tmp")

    /**
     * Entpackt eine Sicherung in einen Zwischenordner und prüft sie. Wirft mit verständlicher
     * Meldung, wenn es keine Scan-Portal-Sicherung ist. Die aktuellen Daten bleiben unberührt.
     */
    fun stage(ctx: Context, input: InputStream): Summary {
        val dir = staging(ctx)
        dir.deleteRecursively(); dir.mkdirs()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (!allowed.matches(e.name)) continue   // Fremdes ignorieren, nie außerhalb schreiben
                val target = File(dir, e.name)
                target.parentFile?.mkdirs()
                target.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalArgumentException("Die Datei ist zu groß für eine Scan-Portal-Sicherung.")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
        val marker = File(dir, MARKER)
        if (!marker.isFile) {
            dir.deleteRecursively()
            throw IllegalArgumentException("Das ist keine Scan-Portal-Sicherung.")
        }
        val m = JSONObject(marker.readText())
        return Summary(
            created = m.optLong("created"),
            version = m.optString("version"),
            lists = File(dir, "lists").listFiles { f -> f.isFile && f.extension == "json" }?.size ?: 0,
            inventories = File(dir, "inventuren").listFiles { f -> f.isFile && f.extension == "json" }?.size ?: 0,
        )
    }

    /** Verwirft eine geprüfte, aber nicht eingespielte Sicherung. */
    fun discard(ctx: Context) { staging(ctx).deleteRecursively() }

    /**
     * Ersetzt alle Daten durch die zuvor mit [stage] geprüfte Sicherung. Die bisherigen Daten werden
     * erst gelöscht, wenn alles an seinem Platz ist; geht unterwegs etwas schief, kommen sie zurück.
     */
    fun apply(ctx: Context) {
        val dir = staging(ctx)
        val marker = File(dir, MARKER)
        require(marker.isFile) { "Keine geprüfte Sicherung vorhanden." }
        val old = File(ctx.filesDir, "restore-old").apply { deleteRecursively(); mkdirs() }
        val names = DIRS + FILES
        val moved = ArrayList<String>()      // alte Daten, die nach „restore-old“ verschoben wurden
        val installed = ArrayList<String>()  // Teile der Sicherung, die schon an ihrem Platz sind
        try {
            for (n in names) {
                val cur = File(ctx.filesDir, n)
                if (cur.exists()) {
                    if (!cur.renameTo(File(old, n))) throw IllegalStateException("Konnte $n nicht verschieben.")
                    moved += n
                }
            }
            for (n in names) {
                val src = File(dir, n)
                if (src.exists()) {
                    if (!src.renameTo(File(ctx.filesDir, n))) throw IllegalStateException("Konnte $n nicht einspielen.")
                    installed += n
                }
            }
        } catch (e: Exception) {
            // Zurück zum alten Stand
            for (n in installed) File(ctx.filesDir, n).deleteRecursively()
            for (n in moved) File(old, n).renameTo(File(ctx.filesDir, n))
            throw e
        }
        // Einstellungen übernehmen
        val prefs = JSONObject(marker.readText()).optJSONObject("settings") ?: JSONObject()
        val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        prefs.keys().forEach { k ->
            val v = prefs.getJSONObject(k)
            when {
                v.has("s") -> ed.putString(k, v.getString("s"))
                v.has("b") -> ed.putBoolean(k, v.getBoolean("b"))
                v.has("i") -> ed.putInt(k, v.getInt("i"))
                v.has("l") -> ed.putLong(k, v.getLong("l"))
            }
        }
        ed.commit()
        old.deleteRecursively()
        dir.deleteRecursively()
        de.inventur.app.BarcodeStore.invalidate()
    }
}
