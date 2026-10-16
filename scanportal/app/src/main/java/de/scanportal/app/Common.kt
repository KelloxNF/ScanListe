package de.scanportal.app

import android.app.Activity
import androidx.core.content.FileProvider
import java.io.File
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.OutputStream

/** Gemeinsame Bausteine für ScanListe und Inventur. */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

/** Rückmeldung beim Scannen: kurzer Piep = OK, Fehlerton + Vibration = Fehler. */
class Tones(private val ctx: Context) {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

    fun ok() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }

    /** Ziel erreicht (z. B. Palette voll): auffälliger Doppelton. */
    fun done() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 600) }

    fun error() {
        tone?.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
        @Suppress("DEPRECATION")
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else ctx.getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun release() { tone?.release() }
}

/**
 * Hinweis für Bildschirme, auf denen ein Scan nirgends gespeichert wird.
 * Zeigt immer nur eine Meldung gleichzeitig, damit sich bei schnellem Scannen nichts aufstaut.
 */
class ScanHint(private val activity: Activity, private val message: () -> String) {
    private var toast: Toast? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            toast?.cancel()
            toast = Toast.makeText(activity, message(), Toast.LENGTH_LONG).also { it.show() }
        }
    }

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(DataWedge.SCAN_ACTION), ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        activity.unregisterReceiver(receiver)
        registered = false
    }
}

/** Datei erzeugen und über „Teilen“ weitergeben (E-Mail, Drive, Drucker-App …). */
object Share {
    /**
     * Schreibt [name] mit [write] in den Export-Ordner und öffnet die Teilen-Auswahl.
     * Räumt vorher nur Exporte auf, die älter als ein Tag sind – eine Mail-App liest den letzten evtl. noch.
     * Wirft bei einem Fehler (z. B. Speicher voll); der Aufrufer zeigt die Meldung.
     */
    fun send(activity: Activity, name: String, mime: String, subject: String, write: (OutputStream) -> Unit) =
        open(activity, prepare(activity, name, write), mime, subject)

    /** Datei im Export-Ordner erzeugen – darf im Hintergrund laufen. */
    fun prepare(ctx: Context, name: String, write: (OutputStream) -> Unit): File {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val limit = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        return File(dir, name).also { f -> f.outputStream().use(write) }
    }

    /** Teilen-Auswahl für eine mit [prepare] erzeugte Datei öffnen. */
    fun open(activity: Activity, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, "Teilen"))
    }
}

/**
 * CSV im Format, das deutsches Excel direkt öffnet:
 * Semikolon als Trenner, UTF-8 mit BOM (für Umlaute), CRLF als Zeilenende.
 */
object ExcelCsv {
    private val plainNumber = Regex("^[+-]?\\d+([.,]\\d+)?$")

    /** Eine Zeile aus Zellen, fertig mit Zeilenende. */
    fun line(cells: List<String>): String = cells.joinToString(";") { cell(it) } + "\r\n"

    fun write(out: OutputStream, text: String) {
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Schutz vor Formel-Einschleusung: Excel würde Zellen, die mit = + - @ beginnen,
     * als Formel ausführen (z. B. aus einem manipulierten QR-Code). Solche Werte bekommen
     * ein vorangestelltes Hochkomma. Reine Zahlen bleiben unverändert.
     */
    fun cell(raw: String): String {
        var s = raw
        if (s.isNotEmpty()) {
            val c = s[0]
            val dangerous = c in "=+-@\t\r" || c == '＝' || c == '＋' || c == '－' || c == '＠'
            if (dangerous && !plainNumber.matches(s)) s = "'$s"
        }
        return if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' || it == ',' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }
}
