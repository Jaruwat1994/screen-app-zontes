// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat

/**
 * Music on the dash: what's playing (title/artist need "Notification access" for [MediaListener],
 * Android's only door to other apps' media sessions) and prev / play-pause / next for the handlebar,
 * which works without any permission.
 */
object NowPlaying {
    data class Track(val title: String?, val artist: String?, val playing: Boolean)

    /** The rider allowed notification access, so titles can be read. */
    fun canReadTitles(ctx: Context) = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

    fun current(ctx: Context): Track {
        val am = ctx.getSystemService(AudioManager::class.java)
        val playing = runCatching { am.isMusicActive }.getOrDefault(false)
        if (!canReadTitles(ctx)) return Track(null, null, playing)
        val sessions = runCatching {
            ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(ctx, MediaListener::class.java))
        }.getOrNull().orEmpty()
        val s = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        val md = s?.metadata
        return Track(
            md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            playing || s?.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    /** Sends a media key (KEYCODE_MEDIA_NEXT…) to whatever app is playing. */
    fun press(ctx: Context, keyCode: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    /** "⏸ Title — Artist" (⏸ while playing, ▶ when paused). */
    internal fun line(t: Track): String {
        val name = listOfNotNull(t.title?.takeIf { it.isNotBlank() }, t.artist?.takeIf { it.isNotBlank() }).joinToString(" — ")
        return (if (t.playing) "⏸ " else "▶ ") + name
    }
}

/**
 * Turn-by-turn from the phone's navigation app (Google Maps, Waze…), read from its ongoing
 * navigation notification so any dash template can show it. Kept in memory only.
 */
object PhoneNav {
    /** Navigation apps whose ongoing notification carries the next turn. */
    val APPS = setOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps", "com.sygic.aura")

    data class Turn(val app: String, val title: String, val text: String, val sub: String?, val icon: android.graphics.Bitmap?, val at: Long)

    @Volatile var latest: Turn? = null

    /** The current turn while the app is navigating (notification still up, updated recently). */
    fun current(): Turn? = latest?.takeIf { System.currentTimeMillis() - it.at < STALE_MS }

    /** A navigation notification posts updates every few seconds; older than this = navigation over. */
    const val STALE_MS = 120_000L

    /** "250 m", "1.2 km", "300 ม.", "1.5 กม."… — a title that is only a distance. */
    private val DISTANCE = Regex("^\\s*[0-9][0-9.,]*\\s*(m|km|ม\\.?|กม\\.?|เมตร|กิโลเมตร|ft|mi|yd)\\s*$", RegexOption.IGNORE_CASE)

    /** Splits a notification into (distance, instruction): the title is often just the distance. */
    internal fun split(title: String, text: String): Pair<String, String> =
        if (DISTANCE.matches(title)) title.trim() to text.trim()
        else "" to listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ").trim()
}

/**
 * Lets Android show [NowPlaying] the media sessions, and hands navigation apps' ongoing
 * notifications to [PhoneNav]. Every other notification is ignored: nothing else is read,
 * stored or sent.
 */
class MediaListener : NotificationListenerService() {
    /** Bound (app start, or access just granted): pick up a navigation already running, so its exit button is known. */
    override fun onListenerConnected() {
        runCatching { activeNotifications }.getOrNull()?.forEach { onNotificationPosted(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PhoneNav.APPS || !sbn.isOngoing) return
        val e = sbn.notification.extras
        val title = e.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = e.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val sub = e.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()
        val icon = runCatching {
            sbn.notification.getLargeIcon()?.loadDrawable(this)?.let { d ->
                android.graphics.Bitmap.createBitmap(d.intrinsicWidth.coerceIn(1, 256), d.intrinsicHeight.coerceIn(1, 256), android.graphics.Bitmap.Config.ARGB_8888)
                    .also { b -> d.setBounds(0, 0, b.width, b.height); d.draw(android.graphics.Canvas(b)) }
            }
        }.getOrNull()
        PhoneNav.latest = PhoneNav.Turn(sbn.packageName, title, text, sub, icon, System.currentTimeMillis())
        if (sbn.key != loggedNav) {
            loggedNav = sbn.key
            LogBus.log("[NAV] ${sbn.packageName} navigating on the phone → turns on the dash")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == PhoneNav.latest?.app && sbn.isOngoing) PhoneNav.latest = null
        if (sbn.key == loggedNav) loggedNav = null
    }

    private companion object {
        /** Notification already logged (one line per navigation, not per update). */
        @Volatile var loggedNav: String? = null
    }
}
