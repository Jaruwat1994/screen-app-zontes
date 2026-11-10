// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/**
 * A place shared to the app (Google Maps "Share", a geo: link): its name and, when the text or the
 * link carries them, its coordinates. Untrusted input from any app: only numbers in range are taken,
 * and only Google short links are followed.
 */
data class SharedPlace(val name: String?, val lat: Double?, val lon: Double?, val link: String?) {
    val hasPosition get() = lat != null && lon != null

    companion object {
        private val EXACT = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")  // the place itself
        private val PARAM = Regex("""[?&](?:q|query|ll|daddr|destination)=(-?\d{1,2}\.\d+)\s*(?:,|%2C)\s*(-?\d{1,3}\.\d+)""", RegexOption.IGNORE_CASE)
        private val GEO = Regex("""^geo:(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        // A dropped pin: …/maps/search/14.0123,+100.7123
        private val PATH = Regex("""/maps/(?:search|place)/(-?\d{1,2}\.\d+),(?:\+|%20|\s)*(-?\d{1,3}\.\d+)""", RegexOption.IGNORE_CASE)
        private val AT = Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")  // map centre: last resort
        private val URL_IN_TEXT = Regex("""https?://\S+""")
        private val PLACE_PATH = Regex("""/maps/place/([^/@?]+)""")
        private val GEO_QUERY = Regex("""[?&]q=([^&]+)""")
        private val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl", "g.co")
        private val GOOGLE_HOSTS = Regex("""(^|\.)google\.[a-z.]+$|(^|\.)goo\.gl$|^g\.co$""")

        /** From the shared text or a geo:/maps URL, without touching the network. */
        fun parse(text: String): SharedPlace {
            val t = text.trim()
            val link = URL_IN_TEXT.find(t)?.value
            val firstLine = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("http") && !it.startsWith("geo:", true) }
            val name = firstLine ?: link?.let { placeName(it) } ?: geoLabel(t)
            val pos = coords(t)
            return SharedPlace(name?.take(120), pos?.first, pos?.second, link)
        }

        /** Coordinates in [s] (a URL or geo: link), most exact first; null when none or out of range. */
        fun coords(s: String): Pair<Double, Double>? {
            val m = EXACT.find(s) ?: PARAM.find(s) ?: GEO.find(s)?.takeIf { it.groupValues[1].toDouble() != 0.0 || it.groupValues[2].toDouble() != 0.0 }
                ?: GEO_QUERY.find(s)?.let { q -> Regex("""^(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)""").find(decode(q.groupValues[1])) }
                ?: PATH.find(s) ?: AT.find(s) ?: return null
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
        }

        /** "…/maps/place/Wat+Arun/@…" → "Wat Arun". */
        fun placeName(url: String): String? = PLACE_PATH.find(url)?.groupValues?.get(1)?.let { decode(it) }?.takeIf { it.isNotBlank() }

        /** geo:0,0?q=Wat Arun → "Wat Arun"; geo:0,0?q=13.7,100.4(Home) → "Home". */
        private fun geoLabel(s: String): String? {
            if (!s.startsWith("geo:", true)) return null
            val q = GEO_QUERY.find(s)?.let { decode(it.groupValues[1]) } ?: return null
            Regex("""\(([^)]+)\)""").find(q)?.let { return it.groupValues[1] }
            return q.takeIf { !Regex("""^-?\d""").containsMatchIn(it) }
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        /**
         * Follows a Google short link (maps.app.goo.gl/…) to the long maps URL, which carries the
         * coordinates. Blocking: call off the main thread. Null on any problem or a non-Google hop.
         */
        fun expand(link: String): String? {
            var url = link
            if (URI(url).host?.lowercase() !in SHORT_HOSTS) return url
            repeat(5) {
                val host = URI(url).host?.lowercase() ?: return null
                if (!url.startsWith("https://") || !GOOGLE_HOSTS.containsMatchIn(host)) return null
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 6_000; readTimeout = 6_000
                    requestMethod = "GET"
                }
                try {
                    val next = c.getHeaderField("Location") ?: return url.takeIf { c.responseCode in 200..299 }
                    url = URI(url).resolve(next).toString()
                    if (coords(url) != null) return url
                } finally { c.disconnect() }
            }
            return url
        }
    }
}
