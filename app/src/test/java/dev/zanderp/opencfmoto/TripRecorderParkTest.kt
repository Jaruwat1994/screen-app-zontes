package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderParkTest {
    private fun moving(speedMs: Float, anchorDistM: Float?, accuracyM: Float = 10f) =
        TripRecorder.isMoving(speedMs, TripRecorder.leftSpot(anchorDistM, accuracyM))

    @Test
    fun parkedDriftDoesNotCount_pullingAwayDoes() {
        // Parked: fix wandered 8 m, chip says 3.6 km/h — still inside 15 m + 10 m accuracy.
        assertFalse(moving(1.0f, 8f))
        // Pulled away 40 m from the spot.
        assertTrue(moving(5f, 40f))
        // Already riding (no anchor).
        assertTrue(moving(3f, null))
        // Below MIN_MOVING_MS, even off the spot.
        assertFalse(moving(0.5f, null))
        // First fix of a leg anchors on itself (0 m), jitter speed doesn't count.
        assertFalse(moving(2f, 0f))
    }
}
