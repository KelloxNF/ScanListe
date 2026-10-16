package de.scanportal.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Sperrmodus fürs Lager: Die App wird mit Androids „App anpinnen“ festgeheftet, damit Aushilfen nicht
 * versehentlich in andere Apps oder die Geräte-Einstellungen wechseln. Beenden, Inventur-Einstellungen und
 * Datensicherung einspielen verlangen dann eine PIN.
 *
 * Gilt nur für dieses Gerät (eigene SharedPreferences „kiosk“, nicht in der Datensicherung).
 * Ohne Geräteverwaltung (MDM) kann Android das Loslösen nicht ganz verbieten; mit der Android-Option
 * „Vor dem Loslösen nach PIN fragen“ ist dafür die Geräte-PIN nötig.
 */
object Kiosk {
    private const val PREFS = "kiosk"
    private const val KEY_ON = "on"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"

    /** Beim App-Start und nach dem Teilen soll wieder angeheftet werden – nicht bei jedem Bildschirmwechsel. */
    @Volatile private var relockWanted = true

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context) = prefs(ctx).getBoolean(KEY_ON, false)

    fun isPinned(ctx: Context): Boolean =
        ctx.getSystemService(ActivityManager::class.java)?.lockTaskModeState?.let { it != ActivityManager.LOCK_TASK_MODE_NONE } ?: false

    // ------------------------------------------------------------ PIN (reine Funktionen, testbar)

    /** 4 bis 8 Ziffern. */
    fun validPin(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

    fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun checkPin(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val h = p.getString(KEY_HASH, null) ?: return false
        return hash(pin, p.getString(KEY_SALT, "") ?: "") == h
    }

    private fun enable(ctx: Context, pin: String) {
        val salt = newSalt()
        prefs(ctx).edit().putBoolean(KEY_ON, true).putString(KEY_SALT, salt).putString(KEY_HASH, hash(pin, salt)).commit()
    }

    private fun disable(ctx: Context) {
        prefs(ctx).edit().clear().commit()
    }

    // ------------------------------------------------------------ Anheften

    /** Bei jedem Wechsel auf einen Bildschirm der App (über [Callbacks]). */
    fun onResume(activity: Activity) {
        if (!isOn(activity)) return
        if (isPinned(activity)) { relockWanted = false; return }
        if (!relockWanted) return
        relockWanted = false   // wer das Anheften ablehnt, wird nicht bei jedem Bildschirm erneut gefragt
        runCatching { activity.startLockTask() }
    }

    /**
     * Vor dem Teilen in andere Apps (E-Mail, Drive …): Anheften kurz lösen, sonst blockiert Android
     * den Wechsel. Kommt man zurück in die App, wird wieder angeheftet.
     */
    fun beforeExternal(activity: Activity) {
        if (!isOn(activity) || !isPinned(activity)) return
        runCatching { activity.stopLockTask() }
        relockWanted = true
    }

    // ------------------------------------------------------------ Dialoge

    private fun pinField(activity: Activity, hint: String) = EditText(activity).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        maxLines = 1
    }

    private fun box(activity: Activity, vararg views: android.view.View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
        views.forEach { addView(it) }
    }

    private fun note(activity: Activity, text: String) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 0, 0, activity.dp(8))
    }

    /** Führt [action] aus – im Sperrmodus erst nach richtiger PIN. */
    fun requirePin(activity: Activity, what: String, action: () -> Unit) {
        if (!isOn(activity)) { action(); return }
        val f = pinField(activity, "PIN")
        AlertDialog.Builder(activity)
            .setTitle("PIN für „$what“")
            .setView(box(activity, note(activity, "Der Sperrmodus ist an."), f))
            .setPositiveButton("OK") { _, _ ->
                if (checkPin(activity, f.text.toString())) action()
                else Toast.makeText(activity, "Falsche PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Menüpunkt „Sperrmodus …“ auf der Startseite. */
    fun showSettings(activity: Activity) {
        if (isOn(activity)) showOn(activity) else showOff(activity)
    }

    private fun showOff(activity: Activity) {
        val pin1 = pinField(activity, "PIN (4–8 Ziffern)")
        val pin2 = pinField(activity, "PIN wiederholen")
        AlertDialog.Builder(activity)
            .setTitle("Sperrmodus einschalten")
            .setView(box(
                activity,
                note(activity, "Die App wird festgeheftet: Home, Zurück zur Übersicht und andere Apps sind gesperrt. " +
                    "Teilen per E-Mail usw. funktioniert weiter. Beenden, Inventur-Einstellungen und Datensicherung einspielen verlangen die PIN.\n\n" +
                    "Tipp: In den Android-Einstellungen „Vor dem Loslösen nach PIN fragen“ aktivieren – sonst lässt sich das Anheften mit der Systemgeste lösen.\n\n" +
                    "PIN gut notieren – ohne sie lässt sich der Sperrmodus in der App nicht ausschalten."),
                pin1, pin2,
            ))
            .setPositiveButton("Einschalten") { _, _ ->
                val p = pin1.text.toString()
                when {
                    !validPin(p) -> Toast.makeText(activity, "Die PIN muss 4 bis 8 Ziffern haben.", Toast.LENGTH_LONG).show()
                    p != pin2.text.toString() -> Toast.makeText(activity, "Die beiden PINs sind verschieden.", Toast.LENGTH_LONG).show()
                    else -> {
                        enable(activity, p)
                        relockWanted = true
                        onResume(activity)
                        // Hat Android das Anheften nicht gestartet (Funktion in den Einstellungen aus), Hinweis zeigen
                        activity.window.decorView.postDelayed({ if (!activity.isFinishing && !isPinned(activity)) pinningHint(activity) }, 1500)
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showOn(activity: Activity) {
        val pinned = isPinned(activity)
        val b = AlertDialog.Builder(activity)
            .setTitle("Sperrmodus")
            .setMessage(
                if (pinned) "Der Sperrmodus ist an, die App ist angeheftet."
                else "Der Sperrmodus ist an, die App ist aber gerade nicht angeheftet (z. B. über die Systemgeste gelöst).",
            )
            .setPositiveButton("Ausschalten …") { _, _ ->
                requirePin(activity, "Sperrmodus ausschalten") {
                    if (isPinned(activity)) runCatching { activity.stopLockTask() }
                    disable(activity)
                    Toast.makeText(activity, "Sperrmodus aus", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Schließen", null)
        if (!pinned) b.setNeutralButton("Jetzt anheften") { _, _ ->
            relockWanted = true
            onResume(activity)
            activity.window.decorView.postDelayed({ if (!activity.isFinishing && !isPinned(activity)) pinningHint(activity) }, 1500)
        }
        b.show()
    }

    private fun pinningHint(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("App anheften nicht möglich")
            .setMessage("Android hat die App nicht angeheftet. Bitte in den Android-Einstellungen unter Sicherheit → „App anpinnen“ (auch „Bildschirm fixieren“) einschalten und dort „Vor dem Loslösen nach PIN fragen“ aktivieren. Danach im Menü „Sperrmodus …“ → „Jetzt anheften“.")
            .setPositiveButton("Einstellungen öffnen") { _, _ ->
                runCatching { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }
            .setNegativeButton("Später", null)
            .show()
    }

    /** Hängt [onResume] an alle Bildschirme der App. */
    class Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = onResume(activity)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}

/** App-Klasse: meldet den Sperrmodus für alle Bildschirme an. */
class ScanPortalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(Kiosk.Callbacks())
    }
}
