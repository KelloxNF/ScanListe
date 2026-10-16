package de.scanportal.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Startbildschirm des Scan-Portals: zwei Kacheln für ScanListe und Inventur. */
class PortalActivity : AppCompatActivity() {
    /** Scans auf diesem Bildschirm landen nirgends – darauf deutlich hinweisen. */
    private val scanHint = de.scanportal.app.ScanHint(this) { "Scan nicht gespeichert – bitte zuerst „Scannen“ oder „Inventur“ öffnen" }

    override fun onPause() {
        super.onPause()
        scanHint.unregister()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataWedge.ensureProfile(this)
        title = getString(R.string.app_name)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(
            tile(
                "Scannen", "Listen mit dem Scanner füllen und als CSV exportieren",
                R.drawable.ic_tile_scan, Color.rgb(31, 95, 173),
            ) { startActivity(Intent(this, de.scanliste.app.MainActivity::class.java)) },
        )
        root.addView(
            tile(
                "Inventur", "Jahresinventur je Kunde zählen, Anschreiben und Zählliste erstellen",
                R.drawable.ic_tile_inventur, Color.rgb(31, 56, 100),
            ) { startActivity(Intent(this, de.inventur.app.MainActivity::class.java)) },
        )
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        root.addView(TextView(this).apply {
            text = "Scan-Portal $version"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, 0)
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        scanHint.register()
    }

    // ---------------------------------------------------------------- Datensicherung

    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) background({ contentResolver.openOutputStream(uri, "wt")?.use { Backup.write(this, it) } }) { r ->
            r.onSuccess { toast("Datensicherung gespeichert") }.onFailure { toast("Sicherung fehlgeschlagen: ${it.message}") }
        }
    }

    /**
     * Sichern und Prüfen laufen im Hintergrund: Bei vielen großen Listen dauert das ein paar Sekunden,
     * und die Oberfläche soll dabei nicht einfrieren.
     */
    private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        toast("Bitte warten …")
        Thread {
            val r = runCatching(work)
            runOnUiThread { if (!isFinishing && !isDestroyed) done(r) }
        }.start()
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) restore(uri)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun backupName() =
        "ScanPortal_Sicherung_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date()) + ".zip"

    private fun createBackup() {
        AlertDialog.Builder(this)
            .setTitle("Datensicherung erstellen")
            .setMessage("Alle Listen, Inventuren, Barcode-Zuordnungen, eigenen Bezeichnungen und Einstellungen in eine Datei. Diese z. B. per USB auf den PC kopieren.")
            .setPositiveButton("Auf dem Gerät speichern …") { _, _ -> saveBackup.launch(backupName()) }
            .setNeutralButton("Teilen …") { _, _ ->
                background({ Share.prepare(this, backupName()) { Backup.write(this, it) } }) { r ->
                    r.onSuccess { Share.open(this, it, "application/zip", "Scan-Portal Datensicherung") }
                        .onFailure { toast("Sicherung fehlgeschlagen: ${it.message}") }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun restore(uri: Uri) {
        background({
            contentResolver.openInputStream(uri)?.use { Backup.stage(this, it) } ?: throw IllegalArgumentException("Datei nicht lesbar.")
        }) { r -> r.onSuccess { confirmRestore(it) }.onFailure { toast(it.message ?: "Datei nicht lesbar.") } }
    }

    private fun confirmRestore(summary: Backup.Summary) {
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(summary.created))
        AlertDialog.Builder(this)
            .setTitle("Datensicherung einspielen?")
            .setMessage(
                "Sicherung vom $date (Version ${summary.version}): ${summary.lists} Listen, ${summary.inventories} Inventuren.\n\n" +
                    "ACHTUNG: Alle aktuellen Daten auf diesem Gerät werden durch die Sicherung ersetzt. " +
                    "Wer unsicher ist, erstellt vorher eine Datensicherung des aktuellen Stands.",
            )
            .setPositiveButton("Ersetzen") { _, _ ->
                runCatching { Backup.apply(this) }
                    .onSuccess { toast("Datensicherung eingespielt: ${summary.lists} Listen, ${summary.inventories} Inventuren") }
                    .onFailure { toast("Einspielen fehlgeschlagen, alte Daten bleiben erhalten: ${it.message}") }
            }
            .setNegativeButton("Abbrechen") { _, _ -> Backup.discard(this) }
            .setOnCancelListener { Backup.discard(this) }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Datensicherung erstellen …")
        menu.add(0, 2, 0, "Datensicherung einspielen …")
        menu.add(0, 3, 0, "Sperrmodus …")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> createBackup()
            2 -> Kiosk.requirePin(this, "Datensicherung einspielen") { openBackup.launch(arrayOf("*/*")) }
            3 -> Kiosk.showSettings(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun tile(title: String, subtitle: String, icon: Int, color: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(color)
            }
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = obtainStyledAttributes(attrs)
            foreground = ta.getDrawable(0)
            ta.recycle()
            isClickable = true
            isFocusable = true
            elevation = dp(3).toFloat()
            contentDescription = title
            setOnClickListener { onClick() }

            addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(TextView(context).apply {
                text = title
                textSize = 32f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(4))
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 15f
                setTextColor(Color.argb(220, 255, 255, 255))
                gravity = Gravity.CENTER
            })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(0, dp(8), 0, dp(8))
            }
        }
    }
}
