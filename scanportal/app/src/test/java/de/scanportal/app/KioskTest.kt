package de.scanportal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PIN des Sperrmodus: Format und Prüfsumme. */
class KioskTest {
    @Test fun pinFormat() {
        assertTrue(Kiosk.validPin("1234"))
        assertTrue(Kiosk.validPin("12345678"))
        assertFalse(Kiosk.validPin("123"))
        assertFalse(Kiosk.validPin("123456789"))
        assertFalse(Kiosk.validPin("12a4"))
        assertFalse(Kiosk.validPin(""))
    }

    @Test fun hashIsStableAndSalted() {
        assertEquals(Kiosk.hash("1234", "s"), Kiosk.hash("1234", "s"))
        assertNotEquals(Kiosk.hash("1234", "s"), Kiosk.hash("1234", "t"))
        assertNotEquals(Kiosk.hash("1234", "s"), Kiosk.hash("1235", "s"))
        assertEquals(64, Kiosk.hash("1234", "s").length)
    }
}
