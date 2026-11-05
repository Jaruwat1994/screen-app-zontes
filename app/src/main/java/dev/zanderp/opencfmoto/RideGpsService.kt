// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service of type `location` while our own Map / My-dash screen is on the bike, plus
 * the same CPU + Wi-Fi locks [AndroidAutoService] holds in AA mode. Without them, once the phone
 * screen times out Android stops GPS and naps the CPU: the dash freezes on a static frame with
 * speed "—" (368G log 4 Nov 2026: heartbeats 5 s → 12 s, ~2 fps of identical 919 B frames).
 * It does no work itself; it keeps the app's GPS listeners, ticker and encoder running.
 */
class RideGpsService : Service() {
    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_ride_gps_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_mirror_title))
            .setContentText(getString(R.string.notif_ride_gps_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            if (wake == null) wake = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ZRide:RideGps").apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
            @Suppress("DEPRECATION") // still the reliable screen-off, no-power-save mode (as in AndroidAutoService)
            if (wifi == null) wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZRide:RideGps").apply { setReferenceCounted(false); acquire() }
            LogBus.log("[GPS] ride GPS + wake/Wi-Fi locks held for the dash")
        } catch (e: Exception) {
            // Typically: started while the app was in the background (Android 14 forbids that).
            LogBus.log("[GPS] can't keep GPS alive in the background: $e")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "zride_ride_gps"
        private const val NOTIF_ID = 4417

        fun start(ctx: Context) {
            val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
            if (!granted) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RideGpsService::class.java))
            } catch (e: Exception) {
                LogBus.log("[GPS] can't start ride GPS service: $e")
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, RideGpsService::class.java)) } catch (_: Exception) {}
        }
    }
}
