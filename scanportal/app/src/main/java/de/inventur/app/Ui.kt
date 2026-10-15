package de.inventur.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import de.scanportal.app.dp
import de.scanportal.app.dpf
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Kleine Helfer, damit die Oberflächen ohne viele XML-Dateien auskommen. */

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

/**
 * Farben. Textfarben passen sich dem Hell-/Dunkelmodus an (Colors.init in jeder Activity),
 * die Hintergründe der Rückmeldeleiste (bar*) bleiben dunkel mit weißer Schrift.
 */
object Colors {
    var ok = Color.rgb(46, 125, 50)
    var error = Color.rgb(198, 40, 40)
    var warn = Color.rgb(156, 87, 0)
    var grey = Color.rgb(110, 110, 110)
    var idle = Color.rgb(90, 100, 114)
    val brand = Color.rgb(31, 56, 100)

    val barOk = Color.rgb(46, 125, 50)
    val barError = Color.rgb(198, 40, 40)
    val barWarn = Color.rgb(156, 87, 0)
    val barIdle = Color.rgb(90, 100, 114)

    // Kachel-/Kartenfarben wie im Beispiel „Inventur Kachelansicht“ – hell/dunkel
    var appBg = Color.rgb(244, 245, 247)
    var appFg = Color.rgb(29, 35, 43)
    var appSub = Color.rgb(95, 104, 115)
    var cardBg = Color.WHITE
    var line = Color.rgb(207, 213, 220)
    var openBg = Color.WHITE
    var openEdge = Color.rgb(195, 201, 208)
    var okFill = Color.rgb(220, 239, 221)
    var okEdge = Color.rgb(46, 125, 50)
    var badFill = Color.rgb(249, 220, 220)
    var badEdge = Color.rgb(198, 40, 40)
    var estFill = Color.rgb(253, 239, 196)
    var estEdge = Color.rgb(176, 125, 0)
    var chipBg = Color.rgb(225, 229, 236)
    val accent = Color.rgb(31, 111, 209)


    fun init(ctx: Context) {
        val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (night) {
            ok = Color.rgb(129, 199, 132)
            error = Color.rgb(239, 154, 154)
            warn = Color.rgb(255, 183, 77)
            grey = Color.rgb(170, 170, 170)
            idle = Color.rgb(176, 190, 197)
            appBg = Color.rgb(22, 25, 29); appFg = Color.rgb(230, 233, 237); appSub = Color.rgb(154, 163, 174)
            cardBg = Color.rgb(32, 37, 43); line = Color.rgb(52, 59, 68)
            openBg = Color.rgb(32, 37, 43); openEdge = Color.rgb(90, 98, 108)
            okFill = Color.rgb(28, 52, 31); okEdge = Color.rgb(102, 187, 106)
            badFill = Color.rgb(61, 31, 31); badEdge = Color.rgb(239, 83, 80)
            estFill = Color.rgb(61, 51, 19); estEdge = Color.rgb(255, 183, 77)
            chipBg = Color.rgb(52, 59, 68)
        } else {
            ok = Color.rgb(46, 125, 50)
            error = Color.rgb(198, 40, 40)
            warn = Color.rgb(156, 87, 0)
            grey = Color.rgb(110, 110, 110)
            idle = Color.rgb(90, 100, 114)
            appBg = Color.rgb(244, 245, 247); appFg = Color.rgb(29, 35, 43); appSub = Color.rgb(95, 104, 115)
            cardBg = Color.WHITE; line = Color.rgb(207, 213, 220)
            openBg = Color.WHITE; openEdge = Color.rgb(195, 201, 208)
            okFill = Color.rgb(220, 239, 221); okEdge = Color.rgb(46, 125, 50)
            badFill = Color.rgb(249, 220, 220); badEdge = Color.rgb(198, 40, 40)
            estFill = Color.rgb(253, 239, 196); estEdge = Color.rgb(176, 125, 0)
            chipBg = Color.rgb(225, 229, 236)
        }
    }

    fun status(s: Status) = when (s) {
        Status.STIMMT -> ok
        Status.DIFFERENZ -> error
        Status.GESCHAETZT -> warn
        Status.NICHT_GEZAEHLT -> grey
    }
}

/** Abgerundete Fläche mit optionalem Rand – für Kacheln, Karten, Knöpfe. */
fun Context.rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dpf(radiusDp)
        setColor(fill)
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

/** Flacher Knopf mit eigener Farbe (ohne Material-Schatten). */
fun Context.flatButton(label: String, bg: Int, fg: Int, stroke: Int? = null, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(fg)
        background = rounded(bg, 12f, stroke)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

/** Standard-Antipp-Effekt (Ripple) als Vordergrund. */
fun Context.touchFeedback(): android.graphics.drawable.Drawable? {
    val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
    return ta.getDrawable(0).also { ta.recycle() }
}
