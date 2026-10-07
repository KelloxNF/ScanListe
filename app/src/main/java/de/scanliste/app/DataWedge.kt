package de.scanliste.app

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Richtet beim Start automatisch ein DataWedge-Profil für diese App ein.
 * Gescannte Barcodes kommen dann als Broadcast mit SCAN_ACTION in der App an
 * (Tastatur-Ausgabe ist im Profil abgeschaltet, damit nichts doppelt ankommt).
 */
object DataWedge {

    const val SCAN_ACTION = "de.scanliste.app.SCAN"
    const val EXTRA_DATA = "com.symbol.datawedge.data_string"
    const val EXTRA_LABEL_TYPE = "com.symbol.datawedge.label_type"

    private const val API_ACTION = "com.symbol.datawedge.api.ACTION"
    private const val PROFILE_NAME = "ScanListe"

    fun ensureProfile(ctx: Context) {
        val barcode = Bundle().apply {
            putString("PLUGIN_NAME", "BARCODE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("scanner_selection", "auto")
                putString("scanner_input_enabled", "true")
                putString("decoder_ean13", "true")
                putString("decoder_ean8", "true")
                putString("decoder_upca", "true")
                putString("decoder_upce0", "true")
                putString("decoder_code128", "true")
                putString("decoder_code39", "true")
                putString("decoder_i2of5", "true")
                putString("decoder_qrcode", "true")
                putString("decoder_datamatrix", "true")
                putString("decoder_pdf417", "true")
            })
        }

        val intentOut = Bundle().apply {
            putString("PLUGIN_NAME", "INTENT")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("intent_output_enabled", "true")
                putString("intent_action", SCAN_ACTION)
                putString("intent_delivery", "2") // 2 = Broadcast
            })
        }

        val keystroke = Bundle().apply {
            putString("PLUGIN_NAME", "KEYSTROKE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("keystroke_output_enabled", "false")
            })
        }

        val app = Bundle().apply {
            putString("PACKAGE_NAME", ctx.packageName)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }

        val profile = Bundle().apply {
            putString("PROFILE_NAME", PROFILE_NAME)
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
            putParcelableArrayList("PLUGIN_CONFIG", arrayListOf(barcode, intentOut, keystroke))
            putParcelableArray("APP_LIST", arrayOf(app))
        }

        // Auf Nicht-Zebra-Geräten passiert hier einfach nichts.
        ctx.sendBroadcast(Intent(API_ACTION).putExtra("com.symbol.datawedge.api.SET_CONFIG", profile))
    }

    /** Macht aus "LABEL-TYPE-EAN13" ein lesbares "EAN13". */
    fun prettyType(type: String?): String? = type?.removePrefix("LABEL-TYPE-")
}
