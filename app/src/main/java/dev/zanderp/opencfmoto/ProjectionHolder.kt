package dev.zanderp.opencfmoto

import android.media.projection.MediaProjection

/**
 * Holds the active MediaProjection (full-screen capture) for the video pipeline to pick up.
 * Null = "render our own Presentation" mode; non-null = "mirror the whole device screen".
 */
object ProjectionHolder {
    /** Setting a different one (or null after Stop) releases the old capture [display]. */
    @Volatile var projection: MediaProjection? = null
        set(v) {
            if (v !== field) { try { display?.release() } catch (_: Exception) {}; display = null }
            field = v
        }

    /**
     * The capture display of [projection]. API 34+ lets a projection create only one, so it is kept
     * across bike-link restarts (a Wi-Fi blip rebuilds the video pipeline) and re-pointed at the new
     * encoder instead of created again — that second create threw and left the mirror dead.
     */
    @Volatile var display: android.hardware.display.VirtualDisplay? = null
}
