// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import kotlin.concurrent.thread

/**
 * Road-snapped navigate-to. The name and the [Route] types stay from the OSRM days (every router
 * returns them); the routes now come from Valhalla's motorcycle profile on [MapServer] — the public
 * OSRM demo server (car profile, "demo use only") is no longer called.
 * Needs phone internet before / during link. Offline GraphHopper packs replace this when installed.
 */
object OsrmRouter {
    /** One turn lane from OSRM intersections: which turns it allows + whether it's usable here. */
    data class Lane(val valid: Boolean, val indications: List<String>)

    /** A single guidance step: the maneuver to take and the road you travel afterwards. */
    data class RouteStep(
        val maneuverType: String,
        val modifier: String?,
        val roadName: String,
        val maneuverLat: Double,
        val maneuverLon: Double,
        val distanceM: Double,
        val exit: Int?,
        val lanes: List<Lane>?,
    )

    data class Route(
        val points: List<GpxPoint>,
        val distanceM: Double,
        val durationSec: Double,
        val steps: List<RouteStep> = emptyList(),
        /** Chip label: Fast / Fun / Alt / Circuit. */
        val label: String = "",
    ) {
        fun withLabel(label: String): Route = copy(label = label)
    }

    fun routeAsync(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        onResult: (Route) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "osrm") {
            try {
                onResult(route(fromLat, fromLon, toLat, toLon))
            } catch (e: Exception) {
                onError(AppHttp.userMessage(e, R.string.f1_route_failed))
            }
        }
    }

    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route =
        routes(fromLat, fromLon, toLat, toLon, alternatives = 0).first()

    /**
     * One or several motorcycle routes through optional [vias] (between start and end).
     * [alternatives] = extra options wanted; Valhalla gives up to 3, and none when there are vias.
     */
    fun routes(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        alternatives: Int = 3,
        vias: List<FunRoutePlanner.LatLon> = emptyList(),
        label: String = "",
    ): List<Route> = ValhallaRouter.routes(
        fromLat, fromLon, toLat, toLon,
        vias = vias,
        alternatives = if (vias.isEmpty()) alternatives.coerceIn(0, 3) else 0,
        label = label,
    )

    fun toTrack(name: String, route: Route): GpxTrack =
        GpxTrack(name = name, points = route.points, waypoints = emptyList())
}
