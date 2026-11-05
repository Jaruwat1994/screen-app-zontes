// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.location.Location
import android.os.Handler
import java.util.Locale

/**
 * Turn-by-turn on My Dash (Nav Pro): routes from the bike to the destination the rider picked in the
 * app's Map screen ([GpxSession] NAV_TO), follows progress with [GpxNav] and re-routes when the
 * bike leaves the line. [onRoute] gets the remaining line for the map.
 */
class DashNav(private val main: Handler, private val onRoute: (List<Pair<Double, Double>>) -> Unit) {
    private var nav: GpxNav? = null
    private var points: List<GpxPoint> = emptyList()
    private var routing = false
    private var routedAt = 0L
    private var offFixes = 0
    var progress: GpxNav.Progress? = null
        private set

    /** Where we're going, while the app is navigating; null = no navigation. */
    val destination: MapPlace?
        get() = GpxSession.destination?.takeIf { GpxSession.active && GpxSession.mode == GpxSession.Mode.NAV_TO }

    fun onFix(loc: Location) {
        val dest = destination ?: run { if (nav != null) { nav = null; progress = null; onRoute(emptyList()) }; return }
        val n = nav ?: return route(loc, dest)
        val p = n.progress(loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed else 0f) ?: return
        progress = p
        onRoute(points.drop(p.nearestIndex).map { it.lat to it.lon })
        offFixes = if (p.offTrackM > OFF_TRACK_M) offFixes + 1 else 0
        if (offFixes >= OFF_TRACK_FIXES) route(loc, dest)
    }

    private fun route(loc: Location, dest: MapPlace) {
        val now = System.currentTimeMillis()
        if (routing || now - routedAt < REROUTE_GAP_MS) return
        routing = true
        routedAt = now
        LogBus.log("[DASH] route → ${dest.name}")
        OsrmRouter.routeAsync(loc.latitude, loc.longitude, dest.lat, dest.lon,
            onResult = { r ->
                main.post {
                    routing = false
                    offFixes = 0
                    points = r.points
                    nav = GpxNav(OsrmRouter.toTrack(dest.name, r), r.steps)
                    onRoute(points.map { it.lat to it.lon })
                    LogBus.log("[DASH] route ${(r.distanceM / 1000).let { String.format(Locale.ROOT, "%.1f", it) }} km, ${r.steps.size} steps")
                }
            },
            onError = { e -> main.post { routing = false; LogBus.log("[DASH] route failed: $e") } },
        )
    }

    companion object {
        /** Off the line by more than this for [OFF_TRACK_FIXES] fixes in a row → new route. */
        const val OFF_TRACK_M = 50.0
        const val OFF_TRACK_FIXES = 3
        const val REROUTE_GAP_MS = 15_000L
        /** Closer than this to the destination = arrived. */
        const val ARRIVED_M = 30.0

        /** Arrow for a turn on the dash. */
        fun arrow(d: GpxNav.TurnDir): String = when (d) {
            GpxNav.TurnDir.LEFT, GpxNav.TurnDir.SHARP_LEFT -> "↰"
            GpxNav.TurnDir.RIGHT, GpxNav.TurnDir.SHARP_RIGHT -> "↱"
            GpxNav.TurnDir.SLIGHT_LEFT, GpxNav.TurnDir.MERGE_LEFT, GpxNav.TurnDir.FORK_LEFT -> "↖"
            GpxNav.TurnDir.SLIGHT_RIGHT, GpxNav.TurnDir.MERGE_RIGHT, GpxNav.TurnDir.FORK_RIGHT -> "↗"
            GpxNav.TurnDir.UTURN -> "↶"
            GpxNav.TurnDir.ROUNDABOUT -> "⟳"
            GpxNav.TurnDir.ARRIVE -> "⚑"
            GpxNav.TurnDir.STRAIGHT, GpxNav.TurnDir.CONTINUE -> "↑"
        }

        /** "250 m" under a kilometre, else "1.2 km". */
        fun distance(m: Double): String =
            if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(Locale.getDefault(), "%.1f km", m / 1000)
    }
}
