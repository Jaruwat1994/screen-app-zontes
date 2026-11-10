// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * "Share → Navigate with ZRide" from Google Maps (or any geo: link): navigates there with our own
 * route, so the dash map gets the blue line that Google Maps can't hand over. No position in the
 * shared text → the short link is expanded; still nothing → the Map screen searches the name.
 */
class ShareToNavActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.take(2_000)
        if (text.isNullOrBlank()) { finish(); return }
        val p = SharedPlace.parse(text)
        LogBus.log("[SHARE] place '${p.name}' position=${p.hasPosition} link=${p.link != null}")
        val lat = p.lat; val lon = p.lon
        if (lat != null && lon != null) return go(p.name, lat, lon)
        val link = p.link ?: return search(p.name)
        Toast.makeText(this, R.string.share_finding, Toast.LENGTH_SHORT).show()
        thread(name = "share-expand", isDaemon = true) {
            val url = runCatching { SharedPlace.expand(link) }.onFailure { LogBus.log("[SHARE] link expand failed: $it") }.getOrNull()
            val pos = url?.let { SharedPlace.coords(it) }
            val name = p.name ?: url?.let { SharedPlace.placeName(it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (pos != null) go(name, pos.first, pos.second) else search(name)
            }
        }
    }

    private fun go(name: String?, lat: Double, lon: Double) {
        GpxActivity.startNavTo(this, MapPlace(name ?: getString(R.string.share_pin), lat, lon))
        finish()
    }

    private fun search(name: String?) {
        // Google Maps place links carry no position (only Google's place id): say how to share one that does.
        Toast.makeText(this, R.string.share_no_position, Toast.LENGTH_LONG).show()
        if (!name.isNullOrBlank()) GpxActivity.startSearch(this, name)
        finish()
    }
}
