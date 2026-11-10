// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    /** The destination [nav] was routed to: a new pick in the Map screen re-routes on the live dash. */
    private var routedTo: MapPlace? = null
    var progress: GpxNav.Progress? = null
        private set

    /** Where we're going, while the app is navigating; null = no navigation. */
    val destination: MapPlace?
        get() = GpxSession.destination?.takeIf { GpxSession.active && GpxSession.mode == GpxSession.Mode.NAV_TO }

    fun onFix(loc: Location) {
        val dest = destination ?: run { if (nav != null) { nav = null; routedTo = null; progress = null; onRoute(emptyList()) }; return }
        if (dest != routedTo && nav != null) { nav = null; progress = null; routedAt = 0L; onRoute(emptyList()) }
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
                    if (destination != dest) { routedAt = 0L; return@post }  // picked somewhere else meanwhile
                    offFixes = 0
                    routedTo = dest
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

        private val arrowIcons = java.util.EnumMap<GpxNav.TurnDir, Bitmap>(GpxNav.TurnDir::class.java)

        /**
         * Bold white turn arrow for [d] (the dash tints it). Drawn, not a font arrow: thin glyphs like
         * ↰ ⟳ come out faint and differ per font on the bike screen.
         */
        fun arrowIcon(d: GpxNav.TurnDir): Bitmap = synchronized(arrowIcons) { arrowIcons.getOrPut(d) { drawArrow(d) } }

        private fun drawArrow(d: GpxNav.TurnDir): Bitmap {
            val n = 128f
            val bmp = Bitmap.createBitmap(n.toInt(), n.toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = n * 0.15f; strokeJoin = Paint.Join.ROUND
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            if (d.name.endsWith("LEFT")) c.scale(-1f, 1f, n / 2, n / 2)  // left turns = right turns mirrored
            /** Shaft through [xy] (pairs, 0..1) ending in an arrowhead along its last segment. */
            fun arrow(vararg xy: Float) {
                val path = Path().apply { moveTo(xy[0] * n, xy[1] * n); for (i in 2 until xy.size step 2) lineTo(xy[i] * n, xy[i + 1] * n) }
                c.drawPath(path, line)
                val ex = xy[xy.size - 2] * n; val ey = xy[xy.size - 1] * n
                var dx = ex - xy[xy.size - 4] * n; var dy = ey - xy[xy.size - 3] * n
                val len = kotlin.math.hypot(dx, dy); dx /= len; dy /= len
                val hl = n * 0.3f; val hw = n * 0.21f
                c.drawPath(Path().apply {
                    moveTo(ex + dx * hl * 0.6f, ey + dy * hl * 0.6f)
                    lineTo(ex - dx * hl * 0.4f - dy * hw, ey - dy * hl * 0.4f + dx * hw)
                    lineTo(ex - dx * hl * 0.4f + dy * hw, ey - dy * hl * 0.4f - dx * hw)
                    close()
                }, fill)
            }
            when (d) {
                GpxNav.TurnDir.LEFT, GpxNav.TurnDir.RIGHT -> arrow(0.32f, 0.94f, 0.32f, 0.45f, 0.72f, 0.45f)
                GpxNav.TurnDir.SHARP_LEFT, GpxNav.TurnDir.SHARP_RIGHT -> arrow(0.3f, 0.94f, 0.3f, 0.22f, 0.66f, 0.62f)
                GpxNav.TurnDir.SLIGHT_LEFT, GpxNav.TurnDir.SLIGHT_RIGHT, GpxNav.TurnDir.MERGE_LEFT, GpxNav.TurnDir.MERGE_RIGHT,
                GpxNav.TurnDir.FORK_LEFT, GpxNav.TurnDir.FORK_RIGHT -> arrow(0.4f, 0.94f, 0.4f, 0.6f, 0.66f, 0.32f)
                // Thailand keeps left, so a U-turn swings right.
                GpxNav.TurnDir.UTURN -> {
                    c.drawPath(Path().apply { moveTo(0.3f * n, 0.94f * n); lineTo(0.3f * n, 0.42f * n); cubicTo(0.3f * n, 0.1f * n, 0.7f * n, 0.1f * n, 0.7f * n, 0.42f * n) }, line)
                    arrow(0.7f, 0.42f, 0.7f, 0.6f)
                }
                GpxNav.TurnDir.ROUNDABOUT -> {
                    line.strokeWidth = n * 0.11f
                    c.drawCircle(0.5f * n, 0.45f * n, 0.17f * n, line)
                    line.strokeWidth = n * 0.15f
                    c.drawLine(0.5f * n, 0.94f * n, 0.5f * n, 0.62f * n, line)
                    arrow(0.62f, 0.33f, 0.74f, 0.21f)
                }
                GpxNav.TurnDir.ARRIVE -> {
                    c.drawLine(0.32f * n, 0.94f * n, 0.32f * n, 0.1f * n, line)
                    c.drawPath(Path().apply { moveTo(0.32f * n, 0.1f * n); lineTo(0.84f * n, 0.28f * n); lineTo(0.32f * n, 0.46f * n); close() }, fill)
                }
                GpxNav.TurnDir.STRAIGHT, GpxNav.TurnDir.CONTINUE -> arrow(0.5f, 0.94f, 0.5f, 0.34f)
            }
            return bmp
        }

        /** "250 m" under a kilometre, else "1.2 km". */
        fun distance(m: Double): String =
            if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(Locale.getDefault(), "%.1f km", m / 1000)
    }
}
