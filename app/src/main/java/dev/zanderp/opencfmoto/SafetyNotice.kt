// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of ZRide Screen (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity

/**
 * Riding-safety terms, accepted once (bump [VERSION] when the text changes materially): no phone
 * handling while riding, routes are advice (map data can miss a no-motorcycle road), crash detection
 * is an aid. Shown before the other start-up notices.
 */
object SafetyNotice {
    private const val VERSION = 1
    private const val KEY = "safety_accepted_v"

    /** Shows the notice if not accepted yet; true when it is showing. */
    fun maybeShow(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences("safety", Activity.MODE_PRIVATE)
        if (prefs.getInt(KEY, 0) >= VERSION) return false
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.safety_title)
            .setMessage(R.string.safety_body)
            .setCancelable(false)
            .setPositiveButton(R.string.safety_accept) { _, _ ->
                prefs.edit().putInt(KEY, VERSION).apply()
                LogBus.log("[safety] terms v$VERSION accepted")
            }
            .show()
        return true
    }
}
