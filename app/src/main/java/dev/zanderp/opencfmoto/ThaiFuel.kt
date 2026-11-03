// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Petrol in Thailand: stations near the rider from OpenStreetMap (Overpass) and today's
 * แก๊สโซฮอล์ 95 retail price from Bangchak's public price board.
 *
 * Thai pump prices are set nationally and the big brands sit within a few satang of each other,
 * so every station shows the same reference price, labelled as such (not a per-pump price).
 */
object ThaiFuel {
    private const val PRICE_URL = "https://oil-price.bangchak.co.th/ApiOilPrice2/th"
    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    private const val PRICE_CACHE_MS = 3 * 3600_000L
    private const val STATIONS_CACHE_MS = 30 * 60_000L

    /** Rough box round Thailand; outside it [FuelStations] keeps the Spanish source. */
    fun covers(lat: Double, lon: Double) = lat in 5.5..20.6 && lon in 97.3..105.7

    class Price(val name: String, val baht: Double, val date: String)

    @Volatile private var price: Pair<Long, Price>? = null
    private val stationCache = HashMap<String, Pair<Long, List<FuelStations.Station>>>()

    /** Blocking. Today's Gasohol 95 price, or null when the board can't be read. */
    fun gasohol95(): Price? {
        price?.let { if (System.currentTimeMillis() - it.first < PRICE_CACHE_MS) return it.second }
        return try {
            parsePrice(AppHttp.getText(PRICE_URL, readTimeoutMs = 15_000))?.also { price = System.currentTimeMillis() to it }
        } catch (e: Exception) {
            LogBus.log("[FUEL-TH] price board failed: ${e.message}")
            price?.second
        }
    }

    /** Bangchak's board: a one-item array whose "OilList" is itself a JSON string. */
    internal fun parsePrice(body: String): Price? {
        val o = JSONArray(body).optJSONObject(0) ?: return null
        val list = JSONArray(o.optString("OilList", "[]"))
        for (i in 0 until list.length()) {
            val oil = list.getJSONObject(i)
            val name = oil.optString("OilName")
            // "แก๊สโซฮอล์ 95 S EVO" — not E20 / E85 / the 98 premium.
            if (name.contains("แก๊สโซฮอล์") && name.contains("95") && !name.contains("E20") && !name.contains("E85")) {
                val baht = oil.optDouble("PriceToday", Double.NaN)
                if (!baht.isNaN()) return Price(name, baht, o.optString("OilPriceDate"))
            }
        }
        return null
    }

    /** Blocking (network): stations within 10 km, 25 km when fewer than 3; cheapest = nearest here. */
    fun near(here: Location): FuelStations.Result {
        val p = gasohol95()
        for (radius in intArrayOf(10, 25)) {
            val found = stations(here, radius)
            if (found.size >= 3 || radius == 25) {
                val priced = found.map { it.copy(price = p?.baht ?: Double.NaN) }.sortedBy { it.distanceM }
                return FuelStations.Result(priced, radius, p?.date.orEmpty(), outsideSpain = false, thai = true, referenceBaht = p?.baht)
            }
        }
        error("unreachable")
    }

    private fun stations(here: Location, radiusKm: Int): List<FuelStations.Station> {
        val key = String.format(Locale.ROOT, "%.2f_%.2f_%d", here.latitude, here.longitude, radiusKm)
        synchronized(stationCache) {
            stationCache[key]?.let { if (System.currentTimeMillis() - it.first < STATIONS_CACHE_MS) return it.second }
        }
        val q = String.format(
            Locale.ROOT, "[out:json][timeout:25];nwr[\"amenity\"=\"fuel\"](around:%d,%.5f,%.5f);out center tags;",
            radiusKm * 1000, here.latitude, here.longitude,
        )
        var body: String? = null
        var last: Exception? = null
        for (url in OVERPASS) {
            try {
                AppHttp.throttle(java.net.URI(url).host, 1_000)
                body = AppHttp.postForm(url, "data=" + AppHttp.encode(q), connectTimeoutMs = 8_000, readTimeoutMs = 20_000)
                break
            } catch (e: Exception) {
                last = e
                LogBus.log("[FUEL-TH] stations from ${java.net.URI(url).host} failed: ${e.message}")
            }
        }
        val list = parseStations(body ?: throw (last ?: java.io.IOException("no Overpass")), here)
        synchronized(stationCache) { stationCache[key] = System.currentTimeMillis() to list }
        return list
    }

    internal fun parseStations(body: String, here: Location?): List<FuelStations.Station> {
        val arr = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<FuelStations.Station>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val c = e.optJSONObject("center")
            val lat = c?.optDouble("lat") ?: e.optDouble("lat", Double.NaN)
            val lon = c?.optDouble("lon") ?: e.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val t = e.optJSONObject("tags") ?: JSONObject()
            fun tag(vararg k: String) = k.firstNotNullOfOrNull { t.optString(it).takeIf { v -> v.isNotBlank() } }.orEmpty()
            val dist = FloatArray(1)
            if (here != null) Location.distanceBetween(here.latitude, here.longitude, lat, lon, dist)
            out += FuelStations.Station(
                lat, lon, Double.NaN,
                brand = tag("brand:th", "brand", "name:th", "name", "operator"),
                address = listOf(tag("addr:housenumber"), tag("addr:street")).filter { it.isNotBlank() }.joinToString(" "),
                town = tag("addr:subdistrict", "addr:district", "addr:city", "addr:province"),
                hours = tag("opening_hours").let { if (it == "24/7") "24 ชม." else it },
                distanceM = dist[0],
            )
        }
        return out
    }
}
