// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * The rider's own dash screen ([CustomDashUi]): which widgets, in which order, and the accent
 * colour. The first widget is drawn large; the rest fill a two-column grid beside it.
 */
object DashLayout {
    private const val PREFS = "dash_layout"
    const val MAX_WIDGETS = 7

    enum class Widget(val label: Int) {
        SPEED(R.string.dl_w_speed),
        CLOCK(R.string.dl_w_clock),
        TRIP(R.string.dl_w_trip),
        RIDE_TIME(R.string.dl_w_ride_time),
        HEADING(R.string.dl_w_heading),
        ALTITUDE(R.string.dl_w_altitude),
        MAX_SPEED(R.string.dl_w_max_speed),
        PHONE(R.string.dl_w_phone),
        MAINTENANCE(R.string.dl_w_maintenance),
    }

    /** ARGB accents the rider can pick; the first is the app's brand gold. */
    val ACCENTS = intArrayOf(
        0xFFD1A955.toInt(), 0xFF5BD47A.toInt(), 0xFF4FC3F7.toInt(),
        0xFFFF8A65.toInt(), 0xFFFF6B5E.toInt(), 0xFFF1F4EC.toInt(),
    )

    data class Layout(val widgets: List<Widget>, val accent: Int)

    val DEFAULT = Layout(
        listOf(Widget.SPEED, Widget.CLOCK, Widget.TRIP, Widget.RIDE_TIME, Widget.HEADING, Widget.PHONE),
        ACCENTS[0],
    )

    /** Stored form → layout. Unknown names (from a newer/older app) are dropped, never crash. */
    internal fun parse(widgets: String?, accent: Int?): Layout {
        if (widgets == null) return DEFAULT
        val list = widgets.split(',').mapNotNull { n -> Widget.entries.firstOrNull { it.name == n.trim() } }
            .distinct().take(MAX_WIDGETS)
        return Layout(list.ifEmpty { DEFAULT.widgets }, accent ?: DEFAULT.accent)
    }

    internal fun format(l: Layout): String = l.widgets.joinToString(",") { it.name }

    fun load(ctx: Context): Layout {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return parse(p.getString("widgets", null), if (p.contains("accent")) p.getInt("accent", 0) else null)
    }

    fun save(ctx: Context, l: Layout) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("widgets", format(l)).putInt("accent", l.accent).apply()
    }
}
