package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadOfTest {
    @Test
    fun roadFromInstructions() {
        assertEquals("Thornton Rd", ProDashView.roadOf("Turn right onto Thornton Rd"))
        assertEquals("ถ.วิภาวดีรังสิต", ProDashView.roadOf("เลี้ยวขวาเข้าสู่ ถ.วิภาวดีรังสิต"))
        assertEquals("Purley Way", ProDashView.roadOf("Purley Way"))  // Google Maps often sends just the road
        assertEquals("Thornton Rd", ProDashView.roadOf("Turn right · Thornton Rd"))
        assertEquals("A23", ProDashView.roadOf("Head north toward A23."))
    }
}
