package de.inventur.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Kleine Helfer, damit die Oberflächen ohne viele XML-Dateien auskommen. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.vbox(padding: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.text(s: String = "", sizeSp: Float = 15f, bold: Boolean = false, color: Int? = null): TextView =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (color != null) setTextColor(color)
    }

fun Context.label(s: String): TextView = text(s, 13f, bold = true).apply {
    setPadding(0, dp(12), 0, dp(2))
}

fun Context.field(hint: String = "", number: Boolean = false, signed: Boolean = false, multiLine: Boolean = false): EditText =
    EditText(this).apply {
        this.hint = hint
        inputType = when {
            number && signed -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            number -> InputType.TYPE_CLASS_NUMBER
            multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        if (!multiLine) maxLines = 1
        textSize = 16f
    }

fun Context.button(s: String, onClick: () -> Unit): Button = Button(this).apply {
    text = s
    isAllCaps = false
    textSize = 16f
    setOnClickListener { onClick() }
}

fun View.lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): View {
    layoutParams = LinearLayout.LayoutParams(width, height, weight)
    return this
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

object Colors {
    val ok = Color.rgb(46, 125, 50)
    val error = Color.rgb(198, 40, 40)
    val idle = Color.rgb(90, 100, 114)
    val warn = Color.rgb(156, 87, 0)
    val grey = Color.rgb(110, 110, 110)
    val brand = Color.rgb(31, 56, 100)
    val okBg = Color.argb(60, 46, 125, 50)
    val diffBg = Color.argb(70, 230, 120, 0)
    val estBg = Color.argb(70, 242, 169, 0)

    fun status(s: Status) = when (s) {
        Status.STIMMT -> ok
        Status.DIFFERENZ -> error
        Status.GESCHAETZT -> warn
        Status.NICHT_GEZAEHLT -> grey
    }
}

/** Piepton und Vibration als Rückmeldung beim Scannen. */
class Feedback(private val ctx: Context) {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

    fun ok() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }

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

/** Schreibt eine Datei in den Export-Cache und öffnet „Teilen“ (E-Mail, Drive, Drucker-App …). */
object Share {
    fun file(ctx: Context, name: String): File {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        return File(dir, name)
    }

    fun clear(ctx: Context) {
        File(ctx.cacheDir, "export").listFiles()?.forEach { it.delete() }
    }

    fun send(activity: Activity, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, "Teilen"))
    }
}
