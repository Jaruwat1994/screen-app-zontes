package dev.zanderp.opencfmoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClimbTest {
    @Test
    fun wobbleDoesNotCount_realClimbAndDescentDo() {
        val c = Climb()
        var t = 0L
        // ±3 m GPS noise around 100 m: nothing climbed.
        for (alt in listOf(100.0, 103.0, 98.0, 102.0, 99.0)) c.add(alt, t++)
        assertEquals(0.0, c.gainM, 0.01)
        assertEquals(0.0, c.lossM, 0.01)
        // Up to 160 m, down to 120 m.
        for (alt in listOf(110.0, 130.0, 160.0, 140.0, 120.0)) c.add(alt, t++)
        assertEquals(60.0, c.gainM, 0.01)
        assertEquals(40.0, c.lossM, 0.01)
    }

    @Test
    fun profileKeepsOneSampleEveryIntervalAndOnlyTheLastOnes() {
        val c = Climb()
        c.add(10.0, 0); c.add(11.0, 5_000)  // second is too soon
        c.add(12.0, Climb.SAMPLE_MS)
        assertArrayEquals(floatArrayOf(10f, 12f), c.profile(), 0f)
        repeat(Climb.MAX_SAMPLES + 5) { c.add(it.toDouble(), Climb.SAMPLE_MS * (it + 2)) }
        assertEquals(Climb.MAX_SAMPLES, c.profile().size)
        assertEquals((Climb.MAX_SAMPLES + 4).toFloat(), c.profile().last(), 0f)
    }
}
