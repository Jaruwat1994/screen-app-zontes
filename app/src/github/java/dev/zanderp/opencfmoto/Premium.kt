// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context

/** GitHub (AGPL source) build: every template is free, no billing. The play build sells [Premium] via Google Play. */
object Premium {
    fun unlocked(ctx: Context): Boolean = true
    fun refresh(ctx: Context) = Unit
    fun purchase(activity: Activity, onResult: (Boolean) -> Unit) = onResult(true)
    fun requireOrPrompt(activity: Activity, onUnlocked: () -> Unit) = onUnlocked()
}
