// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

/**
 * Height climbed and descended on this ride from GPS altitude, plus a short profile for the dash
 * (Touring Nav). GPS height wobbles by several metres, so a change only counts once it is
 * [STEP_M] away from the last counted height; the wobble in between never adds up.
 */
class Climb {
    var gainM = 0.0; private set
    var lossM = 0.0; private set
    private var ref: Double? = null
    private val samples = ArrayDeque<Float>()
    private var lastSampleMs: Long? = null
    /** Same array until a new sample: the dash skips redrawing when its data is unchanged. */
    private var profileCache: FloatArray? = null

    fun add(altM: Double, nowMs: Long) {
        val r = ref
        when {
            r == null -> ref = altM
            altM - r >= STEP_M -> { gainM += altM - r; ref = altM }
            r - altM >= STEP_M -> { lossM += r - altM; ref = altM }
        }
        if (lastSampleMs.let { it == null || nowMs - it >= SAMPLE_MS }) {
            lastSampleMs = nowMs
            samples.addLast(altM.toFloat())
            if (samples.size > MAX_SAMPLES) samples.removeFirst()
            profileCache = null
        }
    }

    /** Heights, oldest first: one every [SAMPLE_MS], the last [MAX_SAMPLES] (20 minutes). */
    fun profile(): FloatArray = profileCache ?: samples.toFloatArray().also { profileCache = it }

    companion object {
        const val STEP_M = 5.0
        const val SAMPLE_MS = 20_000L
        const val MAX_SAMPLES = 60
    }
}
