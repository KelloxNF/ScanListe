package de.scanportal.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Startbildschirm des Scan-Portals: zwei Kacheln für ScanListe und Inventur. */
class PortalActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
