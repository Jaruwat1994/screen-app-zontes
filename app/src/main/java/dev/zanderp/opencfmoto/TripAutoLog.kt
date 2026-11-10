// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Keeps the shared [TripRecorder] aligned with whatever ride surface is live:
 *  - Android Auto projection ([Phase.STREAMING]), and/or
 *  - the built-in map UI on the dash / phone ([mapUiLive]).
 *
 * Same store, same min-distance filter, same idle split as a projected AA ride — so Map / GPX / Nav
 * journeys show up next to AA rides in Saved trips. Honour Setup ▸ *Log trips automatically*.
 */
object TripAutoLog {
    /**
     * True while a map / dash surface ([GpxDashUi], [CustomDashUi]) is bound, and for [GPS_GRACE_MS]
     * after the last one goes, so a reconnect continues the same trip.
     */
    @Volatile var mapUiLive: Boolean = false
        private set
    private var surfaces = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var appCtx: Context? = null
    private val endGrace = Runnable { synchronized(this) { if (surfaces == 0) appCtx?.let { endNow(it) } } }

    /**
     * Counted: the phone map preview closing must not end the GPS that My Dash still uses. When the
     * last surface goes (bike Wi-Fi drop, dash rebuilt) the location service stays up for
     * [GPS_GRACE_MS]: Android 12+ won't let it start again from the background, so a reconnect with
     * the phone pocketed got no GPS until the rider opened the app (2-3 min of "—").
     */
    @Synchronized
    fun setMapUiLive(context: Context, live: Boolean) {
        appCtx = context.applicationContext
        surfaces = if (live) surfaces + 1 else (surfaces - 1).coerceAtLeast(0)
        main.removeCallbacks(endGrace)
        if (live) {
            mapUiLive = true
            if (!RideGpsService.running) RideGpsService.start(context)
            sync(context)
        } else if (surfaces == 0) {
            main.postDelayed(endGrace, GPS_GRACE_MS)  // ponytail: fixed grace; a stop at the pump longer than this still loses GPS on reconnect
        }
    }

    /** The rider pressed Stop (or the grace ran out): GPS service off, trip saved now. */
    @Synchronized
    fun endNow(context: Context) {
        main.removeCallbacks(endGrace)
        surfaces = 0
        mapUiLive = false
        RideGpsService.stop(context)
        sync(context)
    }

    private const val GPS_GRACE_MS = 10 * 60_000L

    /**
     * Start recording when a ride surface is up; stop+save when none are. Safe to call often
     * (AA watchdog tick, map bind/release, session teardown).
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!AppSettings.logTrips(app)) {
            val rec = TripLogger.current
            if (rec != null && rec.recording) rec.stopAndSave()
            return
        }
        val shouldRecord =
            ConnectionState.phase == Phase.STREAMING || mapUiLive
        val rec = TripLogger.get(app)
        if (shouldRecord) {
            if (!rec.recording && rec.start()) {
                val via = when {
                    ConnectionState.phase == Phase.STREAMING && mapUiLive -> "AA + map"
                    mapUiLive -> "map"
                    else -> "AA"
                }
                LogBus.log("[trip] auto-logging this ride ($via)")
            }
        } else if (rec.recording) {
            rec.stopAndSave()
        }
    }
}
