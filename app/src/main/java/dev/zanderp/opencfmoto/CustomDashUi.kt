// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.zanderp.opencfmoto.DashLayout.Widget
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The rider's widget screen ([DashLayout]) as plain Views. On the bike it lives in VideoPipeline's
 * own-content Presentation ([projected] = true: listens to GPS and records the ride); in
 * [DashLayoutActivity] it is the live preview with sample values.
 *
 * Sizes are fractions of the panel height, so the preview (drawn at the dash's pixel size, then
 * scaled) looks exactly like the dash whatever the phone's density.
 */
@SuppressLint("MissingPermission")
class CustomDashUi(
    private val ctx: Context,
    private val dash: DashLayout.Layout,
    private val heightPx: Int,
    private val projected: Boolean,
) {
    val root = FrameLayout(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val values = LinkedHashMap<Widget, TextView>()
    private var lastLoc: Location? = null
    private var heading: Float? = null
    private var lm: LocationManager? = null
    private var maintText = "—"
    private var maintAt = 0L
    private var running = false
    private var phoneHot = false

    private val listener = LocationListener { loc ->
        lastLoc = loc
        // GPS course is only meaningful when moving; keep the last one when stopped.
        if (loc.hasBearing() && loc.speed > 1.2f) heading = loc.bearing
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            main.postDelayed(this, 500)
        }
    }

    init {
        build()
        refresh()
    }

    fun bind() {
        running = true
        if (projected) {
            TripAutoLog.setMapUiLive(ctx, true)  // same trip recording as the Map dash
            try {
                val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
                lm = m
            } catch (e: Exception) {
                LogBus.log("[DASH] no GPS: $e")
            }
        }
        main.post(ticker)
    }

    fun release() {
        running = false
        main.removeCallbacks(ticker)
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        lm = null
        if (projected) TripAutoLog.setMapUiLive(ctx, false)
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        root.setBackgroundColor(Color.BLACK)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val ws = dash.widgets
        row.addView(tile(ws.first(), hero = true), LinearLayout.LayoutParams(0, MATCH_PARENT, 0.42f))
        val rest = ws.drop(1)
        if (rest.isEmpty()) return
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (pair in rest.chunked(2)) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { r.addView(tile(it, hero = false), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
            if (pair.size == 1) r.addView(View(ctx), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            grid.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        row.addView(grid, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.58f))
    }

    private fun px(f: Float) = heightPx * f

    private fun tile(w: Widget, hero: Boolean): View {
        val pad = px(0.03f).roundToInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xFF10140F.toInt())
                cornerRadius = px(0.035f)
                setStroke(maxOf(1, px(0.004f).roundToInt()), 0xFF2D3B31.toInt())
            }
        }
        val label = TextView(ctx).apply {
            text = labelOf(w)
            setTextColor(dash.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(if (hero) 0.07f else 0.052f))
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val value = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            val size = when {
                w == Widget.MAINTENANCE -> if (hero) 0.09f else 0.055f
                hero -> 0.36f
                else -> 0.13f
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size))
            gravity = Gravity.CENTER
            maxLines = if (w == Widget.MAINTENANCE) 2 else 1
            includeFontPadding = false
        }
        box.addView(label)
        box.addView(value)
        values[w] = value
        return FrameLayout(ctx).apply {
            val m = px(0.012f).roundToInt()
            addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(m, m, m, m) })
        }
    }

    private fun labelOf(w: Widget): String {
        val name = ctx.getString(w.label)
        return if (w == Widget.SPEED || w == Widget.MAX_SPEED) "$name · km/h" else name
    }

    // ---------------------------------------------------------------- values

    private fun refresh() {
        val snap = TripLogger.current?.snapshot()?.takeIf { it.recording }
        for ((w, tv) in values) {
            tv.text = if (projected) live(w, snap) else sample(w)
            if (w == Widget.PHONE) tv.setTextColor(if (phoneHot) ContextCompat.getColor(ctx, R.color.status_error) else Color.WHITE)
        }
    }

    private fun live(w: Widget, snap: TripRecorder.Stats?): String = when (w) {
        Widget.SPEED -> lastLoc?.let { (it.speed * 3.6f).roundToInt().toString() } ?: "—"
        Widget.CLOCK -> clock()
        Widget.TRIP -> snap?.let { String.format(Locale.getDefault(), "%.1f km", it.distanceMeters / 1000.0) } ?: "—"
        Widget.RIDE_TIME -> snap?.let { hm(it.movingTimeMs) } ?: "—"
        Widget.HEADING -> heading?.let { compass(it) } ?: "—"
        Widget.ALTITUDE -> lastLoc?.takeIf { it.hasAltitude() }?.let { "${it.altitude.roundToInt()} m" } ?: "—"
        Widget.MAX_SPEED -> snap?.let { (it.maxSpeedMs * 3.6f).roundToInt().toString() } ?: "—"
        Widget.PHONE -> phone()
        Widget.MAINTENANCE -> maintenance()
    }

    private fun sample(w: Widget): String = when (w) {
        Widget.SPEED -> "72"
        Widget.CLOCK -> clock()
        Widget.TRIP -> String.format(Locale.getDefault(), "%.1f km", 12.4)
        Widget.RIDE_TIME -> "0:38"
        Widget.HEADING -> compass(48f)
        Widget.ALTITUDE -> "214 m"
        Widget.MAX_SPEED -> "96"
        Widget.PHONE -> phone()
        Widget.MAINTENANCE -> maintenance()
    }

    private fun clock() = android.text.format.DateFormat.getTimeFormat(ctx).format(Date())

    private fun phone(): String {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "—"
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
        phoneHot = tempC >= HOT_C
        return "${level * 100 / scale}% · ${tempC}°C"
    }

    private fun maintenance(): String {
        val now = System.currentTimeMillis()
        if (now - maintAt > 30_000L) {  // reads prefs: not every tick
            maintAt = now
            maintText = Maintenance.mostUrgent(ctx)?.let { "${it.task.name}\n${Maintenance.statusText(ctx, it)}" } ?: "—"
        }
        return maintText
    }

    companion object {
        /** Battery temperature that turns the phone widget red (Thai sun on a handlebar mount). */
        const val HOT_C = 42

        private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        internal fun compass(deg: Float): String {
            val d = ((deg % 360f) + 360f) % 360f
            return "${POINTS[((d + 22.5f) / 45f).toInt() % 8]} ${d.roundToInt() % 360}°"
        }

        internal fun hm(ms: Long): String {
            val min = ms / 60_000L
            return String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60)
        }
    }
}
