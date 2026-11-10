// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of ZRide Screen (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

/**
 * Where routing and place search go. One switch: the ZRide map server ([BuildConfig.MAP_SERVER],
 * `-PmapServer=https://…`, kit in `server/`) serves Valhalla at `/route`, Nominatim at
 * `/nominatim` and Photon at `/photon`. Without it the public OSM community servers are used, under
 * their fair-use policies (1 req/s Nominatim, no bulk use) — fine for testing, not for a paid app.
 * No demo servers (the OSRM demo is gone).
 */
object MapServer {
    private val own = BuildConfig.MAP_SERVER.trimEnd('/')

    /** True when our own server is configured. */
    val isOwn: Boolean get() = own.isNotEmpty()

    /** Valhalla `/route` endpoint. */
    val route: String get() = if (isOwn) "$own/route" else "https://valhalla1.openstreetmap.de/route"

    /** Nominatim base (append `/search?…`, `/reverse?…`). */
    val nominatim: String get() = if (isOwn) "$own/nominatim" else "https://nominatim.openstreetmap.org"

    /** Photon base (append `/api/?…`, `/reverse?…`). */
    val photon: String get() = if (isOwn) "$own/photon" else "https://photon.komoot.io"

    /**
     * Minimum gap between calls to [service]: the public servers' policies (Nominatim 1/s), or a
     * light pace on our own server.
     */
    fun throttle(service: String, publicMs: Long) {
        AppHttp.throttle(if (isOwn) "zride-$service" else service, if (isOwn) 100 else publicMs)
    }
}
