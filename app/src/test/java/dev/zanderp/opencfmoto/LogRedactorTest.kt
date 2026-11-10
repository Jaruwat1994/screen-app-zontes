package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun redactsUrlPassword() {
        val out = LogRedactor.redact("qr ssid=CFMOTO-x&pwd=supersecret&modelid=1")
        assertTrue(out.contains("pwd=«redacted»"))
        assertFalse(out.contains("supersecret"))
        assertTrue(out.contains("ssid=CFMOTO-x"))
    }

    @Test
    fun masksSerialTail() {
        val out = LogRedactor.redact("""{"HUID":"CRCP230501740"}""")
        assertTrue(out.contains("CRCP"))
        assertFalse(out.contains("CRCP230501740"))
    }

    @Test
    fun redactsWifiQrPasswordAndPrecisePositions() {
        val wifi = LogRedactor.redact("scanned WIFI:S:Home;T:WPA;P:hunter22;;")
        assertFalse(wifi.contains("hunter22"))
        assertTrue(wifi.contains("S:Home"))
        assertEquals("dest 13.75,100.50 'Home'", LogRedactor.redact("dest 13.756331,100.501765 'Home'"))
        assertEquals("4.0 Mbps 13.7 V", LogRedactor.redact("4.0 Mbps 13.7 V"))  // ordinary numbers stay
    }

    @Test
    fun updateCheckerVersionCompare() {
        assertTrue(UpdateChecker.isNewer("v1.0.16", "1.0.15"))
        assertFalse(UpdateChecker.isNewer("1.0.15", "1.0.16"))
        assertTrue(UpdateChecker.isNewer("0.1.2.1", "0.1.2"))
    }
}
