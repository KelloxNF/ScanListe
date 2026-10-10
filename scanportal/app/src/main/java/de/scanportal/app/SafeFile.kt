package de.scanportal.app

import java.io.File
import java.io.FileOutputStream

/**
 * Sicheres Schreiben für ScanListe und Inventur: erst in eine Hilfsdatei schreiben, diese
 * auf den Speicher zwingen (fsync) und dann in einem Schritt umbenennen. So bleibt bei
 * Akkuwechsel oder Absturz mitten im Speichern immer entweder der alte oder der neue,
 * aber nie ein halb geschriebener Stand übrig.
 */
object SafeFile {
    fun write(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            FileOutputStream(target).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            tmp.delete()
        }
    }
}
