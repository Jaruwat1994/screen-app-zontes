// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Spoken cues for Map / GPX — turns, waypoints, off-track.
 */
class GpxVoice(context: Context) : TextToSpeech.OnInitListener {
    private val res = context.applicationContext.resources
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    // Google/Waze-style: one heads-up ("in 300 m, turn right onto X") then one at the
    // junction ("turn right"). Track which maneuver already got each phase so we don't spam.
    private var preparedIdx = Int.MIN_VALUE
    private var executedIdx = Int.MIN_VALUE
    private var lastWptKey: String? = null
    private var lastOffTrackSpeakMs = 0L
    private var lastJoinDir: GpxNav.TurnDir? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(0.95f)
        }
    }

    fun onProgress(
        p: GpxNav.Progress?,
        units: MapUnits = MapUnits.METRIC,
        speedMps: Float = 0f,
    ) {
        if (!ready || p == null) return
        // Off the road: join-route compass cues ([onJoinRoute]) own the mic.
        if (p.offTrackM > GpxNav.OFF_ROUTE_GUIDE_M) return
        lastJoinDir = null

        val turn = p.upcomingTurn
        if (turn != null) {
            val d = turn.distanceFromRiderM
            // Heads-up further out at speed, execute nearer to the junction.
            val prepareM = (speedMps * 14.0).coerceIn(150.0, 700.0)
            val executeM = (speedMps * 1.6).coerceIn(25.0, 60.0)
            val phrase = turn.instr.takeIf { it.isNotBlank() } ?: dirPhrase(turn.dir)
            when {
                // At the junction: short, no distance — "turn right onto X" / "turn right".
                d <= executeM -> {
                    if (executedIdx != turn.index) {
                        executedIdx = turn.index
                        preparedIdx = turn.index // don't fire the heads-up afterwards
                        speak(phrase.replaceFirstChar { it.uppercase() })
                    }
                }
                // Heads-up once when we first cross the prepare distance.
                d <= prepareM -> {
                    if (preparedIdx != turn.index) {
                        preparedIdx = turn.index
                        val dist = GpxNav.roundedSpokenDistance(d, units)
                        speak(res.getString(R.string.hc_dash_tts_in_dist, dist, phrase))
                    }
                }
            }
        }
        val wpt = p.nextWaypoint
        val wptM = p.nextWaypointM
        if (wpt != null && wptM != null && wptM <= 120) {
            val key = "${wpt.lat},${wpt.lon}"
            if (key != lastWptKey) {
                lastWptKey = key
                val name = wpt.name?.takeIf { it.isNotBlank() } ?: res.getString(R.string.hc_dash_tts_waypoint)
                speak(res.getString(R.string.hc_dash_tts_approaching, name))
            }
        }
    }

    /**
     * Speak compass guidance while off the road. Fires when the turn band changes, or
     * every ~18s as a nudge if still off route facing the same way.
     */
    fun onJoinRoute(dir: GpxNav.TurnDir, offTrackM: Double, units: MapUnits) {
        if (!ready) return
        val now = System.currentTimeMillis()
        val changed = dir != lastJoinDir
        val due = now - lastOffTrackSpeakMs > 18_000L
        if (!changed && !due) return
        lastJoinDir = dir
        lastOffTrackSpeakMs = now
        val dist = GpxNav.formatDistance(offTrackM, units)
        speak("${GpxNav.joinRouteSpeak(dir).replaceFirstChar { it.uppercase() }}, $dist")
    }

    private fun dirPhrase(dir: GpxNav.TurnDir): String = when (dir) {
        GpxNav.TurnDir.LEFT -> res.getString(R.string.hc_dash_tts_dir_left)
        GpxNav.TurnDir.RIGHT -> res.getString(R.string.hc_dash_tts_dir_right)
        GpxNav.TurnDir.SHARP_LEFT -> res.getString(R.string.hc_dash_tts_dir_sharp_left)
        GpxNav.TurnDir.SHARP_RIGHT -> res.getString(R.string.hc_dash_tts_dir_sharp_right)
        GpxNav.TurnDir.SLIGHT_LEFT -> res.getString(R.string.hc_dash_tts_dir_slight_left)
        GpxNav.TurnDir.SLIGHT_RIGHT -> res.getString(R.string.hc_dash_tts_dir_slight_right)
        GpxNav.TurnDir.UTURN -> res.getString(R.string.hc_dash_tts_dir_uturn)
        GpxNav.TurnDir.ROUNDABOUT -> res.getString(R.string.hc_dash_tts_dir_roundabout)
        GpxNav.TurnDir.MERGE_LEFT -> res.getString(R.string.hc_dash_tts_dir_merge_left)
        GpxNav.TurnDir.MERGE_RIGHT -> res.getString(R.string.hc_dash_tts_dir_merge_right)
        GpxNav.TurnDir.FORK_LEFT -> res.getString(R.string.hc_dash_tts_dir_fork_left)
        GpxNav.TurnDir.FORK_RIGHT -> res.getString(R.string.hc_dash_tts_dir_fork_right)
        GpxNav.TurnDir.ARRIVE -> res.getString(R.string.hc_dash_tts_dir_arrive)
        GpxNav.TurnDir.STRAIGHT, GpxNav.TurnDir.CONTINUE -> res.getString(R.string.hc_dash_tts_dir_straight)
    }

    fun speak(text: String) {
        if (!ready) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gpx-${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }
}
