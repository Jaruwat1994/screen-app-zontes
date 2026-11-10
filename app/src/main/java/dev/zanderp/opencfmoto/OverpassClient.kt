// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight Overpass helpers: nearby maxspeed (road restriction lite) and corridor POIs.
 * Phone internet required; results can be cached for the ride.
 */
object OverpassClient {
    /**
     * Public Overpass servers. The main one often answers 502/504 when busy (riders saw "502" on the
     * café / restaurant chips): every Overpass call in the app goes through [postRaw], which tries the
     * next server and starts from the last one that worked.
     */
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    @Volatile private var preferred = 0

    /** Blocking. The raw answer of the first server that gives one; throws the last error if none does. */
    fun postRaw(query: String, connectTimeoutMs: Int = 8_000, readTimeoutMs: Int = 20_000, maxBytes: Int? = null): String {
        val body = "data=" + AppHttp.encode(query)
        var last: Exception? = null
        for (k in ENDPOINTS.indices) {
            val i = (preferred + k) % ENDPOINTS.size
            val url = ENDPOINTS[i]
            val host = java.net.URI(url).host
            try {
                AppHttp.throttle(host, 1_000)  // Overpass fair use: a modest rate per server
                val text = if (maxBytes != null) AppHttp.postForm(url, body, connectTimeoutMs, readTimeoutMs, maxBytes)
                    else AppHttp.postForm(url, body, connectTimeoutMs, readTimeoutMs)
                // A busy server can answer 200 with an HTML/"rate limited" page instead of JSON.
                if (!text.trimStart().startsWith("{")) throw java.io.IOException("not JSON from $host")
                preferred = i
                return text
            } catch (e: Exception) {
                // Screen closed (executor shut down): stop, don't hammer the other servers.
                if (e is InterruptedException || e is java.io.InterruptedIOException || Thread.currentThread().isInterrupted) throw e
                last = e
                LogBus.log("[overpass] $host failed: ${e.message} — trying the next server")
            }
        }
        throw last ?: java.io.IOException("no Overpass server answered")
    }

    data class SpeedLimit(val kmh: Int?, val label: String)

    // Cache maxspeed by ~110 m cell for the session so we don't re-query the same road.
    private const val SPEED_CACHE_TTL_MS = 10 * 60 * 1000L
    private val speedCache = HashMap<String, Pair<Long, SpeedLimit>>()

    fun maxspeedAsync(
        lat: Double,
        lon: Double,
        onResult: (SpeedLimit) -> Unit,
    ) {
        thread(name = "overpass-speed") {
            onResult(runCatching { maxspeedNear(lat, lon) }.getOrElse { SpeedLimit(null, "—") })
        }
    }

    fun corridorPoisAsync(
        points: List<GpxPoint>,
        onResult: (List<MapPlace>) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "overpass-poi") {
            try {
                onResult(corridorPois(points))
            } catch (e: Exception) {
                onError(AppHttp.userMessage(e, R.string.f1_poi_failed))
            }
        }
    }

    /**
     * Real "what's near me" category search: finds OSM features carrying a tag (e.g. amenity=fuel)
     * within a radius of the rider, nearest first. This is what the category chips need — actual
     * fuel stations / cafes around you, not a text search for the word "fuel".
     */
    fun nearbyByTagAsync(
        tag: String,
        lat: Double,
        lon: Double,
        radiusM: Int = 6_000,
        maxResults: Int = 30,
        onResult: (List<MapPlace>) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "overpass-nearby") {
            try {
                onResult(nearbyByTag(tag, lat, lon, radiusM, maxResults))
            } catch (e: Exception) {
                onError(AppHttp.userMessage(e, R.string.f1_search_failed))
            }
        }
    }

    fun nearbyByTag(
        tag: String,
        lat: Double,
        lon: Double,
        radiusM: Int = 6_000,
        maxResults: Int = 30,
    ): List<MapPlace> {
        val key = tag.substringBefore('=').trim()
        val value = tag.substringAfter('=', "").trim()
        if (key.isEmpty() || value.isEmpty()) return emptyList()
        // Photon answers in ~3 s; busy Overpass took 30 s+ for the same box (riders saw 502 / timeouts).
        runCatching { photonNearby(key, value, lat, lon, radiusM * 8 / 1000, maxResults) }
            .onFailure { LogBus.log("[nearby] photon failed: ${it.message} — falling back to Overpass") }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Widen the search if the immediate area comes up empty (e.g. fuel out on the open road).
        for (r in intArrayOf(radiusM, radiusM * 3, radiusM * 8)) {
            val found = queryNearby(key, value, lat, lon, r, maxResults)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun queryNearby(
        key: String,
        value: String,
        lat: Double,
        lon: Double,
        radiusM: Int,
        maxResults: Int,
    ): List<MapPlace> {
        val q = """
            [out:json][timeout:25];
            (
              node["$key"="$value"](around:$radiusM,$lat,$lon);
              way["$key"="$value"](around:$radiusM,$lat,$lon);
            );
            out center ${maxResults * 2};
        """.trimIndent()
        val els = post(q).optJSONArray("elements") ?: return emptyList()
        val scored = ArrayList<Pair<MapPlace, Double>>(els.length())
        for (i in 0 until els.length()) {
            val o = els.getJSONObject(i)
            val plat: Double
            val plon: Double
            when {
                o.has("lat") && o.has("lon") -> {
                    plat = o.getDouble("lat"); plon = o.getDouble("lon")
                }
                o.has("center") -> {
                    val c = o.getJSONObject("center")
                    plat = c.getDouble("lat"); plon = c.getDouble("lon")
                }
                else -> continue
            }
            val tags = o.optJSONObject("tags")
            val brand = tags?.optString("brand")?.takeIf { it.isNotBlank() }
                ?: tags?.optString("operator")?.takeIf { it.isNotBlank() }
            val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                ?: brand
                ?: value.replace('_', ' ').replaceFirstChar { it.uppercase() }
            val street = listOfNotNull(
                tags?.optString("addr:street")?.takeIf { it.isNotBlank() },
                tags?.optString("addr:housenumber")?.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            val subtitle = listOfNotNull(
                brand?.takeIf { it != name },
                street.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            scored.add(
                MapPlace(
                    name = name,
                    lat = plat,
                    lon = plon,
                    category = value,
                    subtitle = subtitle,
                ) to approxMetres(lat, lon, plat, plon),
            )
        }
        return scored
            .sortedBy { it.second }
            .map { it.first }
            .distinctBy { "${"%.5f".format(it.lat)},${"%.5f".format(it.lon)}" }
            .take(maxResults)
    }

    /**
     * Blocking. OSM features tagged [key]=[value] nearest the rider, from Photon's reverse geocoder
     * (komoot): far faster than the public Overpass servers. Nearest first; at most [limit] (Photon caps 50).
     */
    fun photonNearby(key: String, value: String, lat: Double, lon: Double, radiusKm: Int, limit: Int): List<MapPlace> {
        val url = "${MapServer.photon}/reverse?lat=$lat&lon=$lon&radius=${radiusKm.coerceAtLeast(1)}" +
            "&limit=${limit.coerceIn(1, 50)}&osm_tag=${AppHttp.encode("$key:$value")}"
        MapServer.throttle("photon", 300)
        val feats = JSONObject(AppHttp.getText(url, readTimeoutMs = 12_000)).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Pair<MapPlace, Double>>(feats.length())
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val plon = c.optDouble(0); val plat = c.optDouble(1)
            if (plat.isNaN() || plon.isNaN()) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() }
            val street = listOfNotNull(s("housenumber"), s("street")).joinToString(" ")
            out += MapPlace(
                name = s("name") ?: value.replace('_', ' ').replaceFirstChar { it.uppercase() },
                lat = plat, lon = plon, category = value,
                subtitle = listOfNotNull(street.takeIf { it.isNotBlank() }, s("district") ?: s("city")).joinToString(" · "),
            ) to approxMetres(lat, lon, plat, plon)
        }
        return out.sortedBy { it.second }.map { it.first }
    }

    fun maxspeedNear(lat: Double, lon: Double, radiusM: Int = 25): SpeedLimit {
        val key = "%.3f,%.3f".format(lat, lon)
        synchronized(speedCache) {
            speedCache[key]?.let { (ts, v) ->
                if (System.currentTimeMillis() - ts < SPEED_CACHE_TTL_MS) return v
            }
        }
        val q = """
            [out:json][timeout:12];
            way(around:$radiusM,$lat,$lon)["maxspeed"];
            out tags center 1;
        """.trimIndent()
        val els = post(q).optJSONArray("elements")
        val result = if (els == null || els.length() == 0) {
            SpeedLimit(null, "—")
        } else {
            val tags = els.getJSONObject(0).optJSONObject("tags")
            val raw = tags?.optString("maxspeed")?.trim().orEmpty()
            if (raw.isEmpty()) SpeedLimit(null, "—") else SpeedLimit(parseMaxspeedKmh(raw), raw)
        }
        synchronized(speedCache) { speedCache[key] = System.currentTimeMillis() to result }
        return result
    }

    fun corridorPois(points: List<GpxPoint>, maxResults: Int = 40): List<MapPlace> {
        if (points.isEmpty()) return emptyList()
        val sample = subsample(points, 60)
        var minLat = sample.first().lat
        var maxLat = sample.first().lat
        var minLon = sample.first().lon
        var maxLon = sample.first().lon
        for (p in sample) {
            minLat = min(minLat, p.lat)
            maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon)
            maxLon = max(maxLon, p.lon)
        }
        // ~300 m pad
        val pad = 0.003
        val south = minLat - pad
        val north = maxLat + pad
        val west = minLon - pad
        val east = maxLon + pad
        val q = """
            [out:json][timeout:25];
            (
              node["amenity"~"fuel|parking|cafe|restaurant|hospital|atm"]($south,$west,$north,$east);
              node["tourism"~"hotel|viewpoint"]($south,$west,$north,$east);
            );
            out body $maxResults;
        """.trimIndent()
        val els = post(q).optJSONArray("elements") ?: return emptyList()
        return buildList {
            for (i in 0 until els.length()) {
                val o = els.getJSONObject(i)
                if (!o.has("lat") || !o.has("lon")) continue
                val tags = o.optJSONObject("tags") ?: continue
                val kind = tags.optString("amenity").ifBlank { tags.optString("tourism") }
                val name = tags.optString("name").ifBlank { kind.replaceFirstChar { it.uppercase() } }
                add(
                    MapPlace(
                        name = name,
                        lat = o.getDouble("lat"),
                        lon = o.getDouble("lon"),
                        category = kind,
                        subtitle = "OSM $kind",
                    ),
                )
            }
        }.distinctBy { "${"%.5f".format(it.lat)},${"%.5f".format(it.lon)}" }
            .take(maxResults)
    }

    fun parseMaxspeedKmh(raw: String): Int? {
        val s = raw.lowercase().trim()
        when (s) {
            "none", "signals", "variable", "walk" -> return null
        }
        if (s.endsWith("mph")) {
            val n = s.removeSuffix("mph").trim().toDoubleOrNull() ?: return null
            return (n * 1.60934).toInt()
        }
        val digits = s.takeWhile { it.isDigit() || it == '.' }
        return digits.toDoubleOrNull()?.toInt()
    }

    /** Pick the posted limit closest in bearing / distance for display (km/h). */
    fun formatLimit(limit: SpeedLimit, units: MapUnits): String {
        val kmh = limit.kmh ?: return "—"
        return when (units) {
            MapUnits.METRIC -> "$kmh"
            MapUnits.IMPERIAL -> "${(kmh * 0.621371).toInt()}"
        }
    }

    private fun subsample(points: List<GpxPoint>, max: Int): List<GpxPoint> {
        if (points.size <= max) return points
        val step = points.size / max
        return points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
    }

    private fun post(query: String): JSONObject = JSONObject(postRaw(query))

    /** Rough metres between two lat/lon — used by tests / callers. */
    fun approxMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = abs(lat1 - lat2) * 111_320.0
        val dLon = abs(lon1 - lon2) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return kotlin.math.hypot(dLat, dLon)
    }
}
