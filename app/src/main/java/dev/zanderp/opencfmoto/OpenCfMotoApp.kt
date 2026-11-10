// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Application
import org.maplibre.android.MapLibre

/** Process entry — installs crash capture before any Activity runs. */
class OpenCfMotoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Which screen was up: a crash's stack often names only framework views (LinearLayout…).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) = LogBus.log("[UI] ${a.javaClass.simpleName}")
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        try {
            LogBus.includeSecrets = AppSettings.includeSecretsInLogs(this)
        } catch (_: Exception) {
        }
        CrashGuard.install(this)
        SearchKeyGuard.install(this)
        CrashGuard.hydrateLogBus(this)
        try {
            AppSettings.applyToHolder(this)
            TurnHaptics.load(this)
            RideStop.install()
            // Watch: make sure it has the current bike photo (no-op if already sent).
            WearBikePhoto.push(this)
        } catch (_: Exception) {
        }
        // After hydrate so Share Logs still show prior crash, then stamp this process build.
        LogBus.logSessionBanner()
        try { LastExit.check(this) } catch (_: Exception) {}
        try { TripRecorder.recoverAtStart(this) } catch (_: Exception) {}
        AppHttp.init(this)
        try { WeeklySummary.ensureScheduled(this) } catch (_: Exception) {}
        ConnectSound.init(this)
        ConnectWidget.init(this)
        try {
            AnonymousTelemetry.onAppStart(this)
        } catch (_: Exception) {
        }
        try {
            MapLibre.getInstance(this)
            // After getInstance only — AppHttp network callbacks must not set the client first.
            AppHttp.onMapLibreReady()
        } catch (e: Exception) {
            android.util.Log.w("OpenCfMoto", "MapLibre init failed: $e")
        }
    }
}
