package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class DashcamTest {
    @Test fun urls() {
        assertEquals("http://192.168.3.1:7686/a.mp4", Dashcam.fullUrl("192.168.3.1:7686/a.mp4"))
        assertEquals("http://192.168.3.1:7686/a.mp4", Dashcam.fullUrl("/a.mp4"))
        assertEquals("http://x/a.mp4", Dashcam.fullUrl("http://x/a.mp4"))
        assertNull(Dashcam.fullUrl(""))
    }

    @Test fun takenAt() {
        val c = Calendar.getInstance().apply { timeInMillis = Dashcam.takenAt("F_20261106_143512_0001.mp4")!! }
        assertEquals(2026, c.get(Calendar.YEAR)); assertEquals(14, c.get(Calendar.HOUR_OF_DAY)); assertEquals(35, c.get(Calendar.MINUTE))
        assertEquals(Dashcam.takenAt("F_20261106_143512.mp4"), Dashcam.takenAt("20261106143512.mp4"))
        assertEquals(Dashcam.takenAt("F_20261106_143512.mp4"), Dashcam.takenAt("2026-11-06_14-35-12_F.mp4"))
        assertNull(Dashcam.takenAt("clip_0001.mp4"))
    }
}
