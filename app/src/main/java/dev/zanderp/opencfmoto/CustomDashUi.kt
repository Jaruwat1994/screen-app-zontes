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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import dev.zanderp.opencfmoto.aa.AaInput
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
    private var speedKmh: Int? = null
    private var gpsTries = 0
    private var maintText = "—"
    private var maintAt = 0L
    private var running = false
    private var phoneHot = false
    private var phoneTempC: Int? = null
    private var phoneLevel: Int? = null
    private var phoneCharging = false
    private var phoneMv: Int? = null
    private var pressureHpa: Float? = null
    // Slow-changing phone stats for Nav Live, re-read every 10 s.
    private var sysAt = 0L
    private var wifiDbm: Int? = null
    private var storageFree: Int? = null
    private var cellReady: Boolean? = null
    // Music and the video link, re-read once a second.
    private var track: NowPlaying.Track? = null
    private var trackAt = 0L
    private var linkAt = 0L
    private var linkFrames = 0L
    private var linkBytes = 0L
    private var videoFps: Int? = null
    private var linkMbps: Float? = null
    // Street name for Nav Center: looked up when the bike has moved 150 m, at most every 30 s.
    private var place: Pair<String, String?>? = null
    private var placeFrom: Location? = null
    private var placeAt = 0L
    private var placeBusy = false
    // Turn-by-turn (Nav Pro): the route's remaining line for the map, and the destination pinned on it.
    private var routeLine: List<Pair<Double, Double>> = emptyList()
    private var routeDirty = false
    private var mapDest: MapPlace? = null
    /** Turn-by-turn on every template while the app's Map screen navigates (when [DashLayout.Layout.navOnDash]). */
    private val dashNav: DashNav? = if (projected && dash.navOnDash)
        DashNav(main) { pts -> routeLine = pts; routeDirty = true; mapDirty = true } else null
    /** Grid: the next turn floating over the tiles (PRO templates draw their own banner). */
    private var gridNav: TextView? = null
    private var pro: ProDashView? = null
    /** Range strip drawn over the live map in the PRO card (on the bike only). */
    private var proStrip: ProDashView? = null
    private var photo: android.graphics.Bitmap? = null
    private var book: Pair<Long, ProBook>? = null
    private var sats = 0
    private var gpsBadgeView: TextView? = null
    private var gpsLevel = -1
    private var nightVeil: View? = null

    // Lean angle: gyroscope yaw rate about the vertical × GPS speed (see [leanDeg]).
    private var sensors: SensorManager? = null
    /** Gravity in the phone's frame (points up); any mounting orientation works. */
    private val up = FloatArray(3)
    private var hasUp = false
    /** Turn rate about the vertical (rad/s, + = turning left), low-passed. */
    private var yawRate = 0f
    private var maxLeanL = 0f
    private var maxLeanR = 0f
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GRAVITY -> { e.values.copyInto(up, 0, 0, 3); hasUp = true }
                Sensor.TYPE_PRESSURE -> pressureHpa = e.values[0]
                Sensor.TYPE_GYROSCOPE -> if (hasUp) {
                    val g = kotlin.math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
                    if (g < 1f) return
                    val w = (e.values[0] * up[0] + e.values[1] * up[1] + e.values[2] * up[2]) / g
                    yawRate += (w - yawRate) * YAW_SMOOTH
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    // Low fuel: the nearest station, looked up off the main thread.
    private var station: FuelStations.Station? = null
    private var stationAt = 0L
    private var stationFrom: Location? = null
    private var stationBusy = false
    /** Station currently pinned on the map (null = none). */
    private var mapStation: FuelStations.Station? = null
    private var nightAt = 0L

    // Map (MAP widget and/or full screen on the handlebar's ▼). Only built when projected.
    private var map: DashMapController? = null
    /** Holds the live map; moves between the MAP tile and full screen. */
    private val mapHost = FrameLayout(ctx)
    private var mapTile: FrameLayout? = null
    private var mapFull = false
    private var mapZoom = 16.0
    private var mapDirty = true
    /** Speed over the full-screen map (the gauges are covered). */
    private var mapSpeed: TextView? = null
    /** Grid's right-hand column; the handlebar's ▲ hides it so the first tile fills the panel. */
    private var side: View? = null

    private val keySink: (Int) -> Unit = { code -> main.post { if (running) onKey(code) } }
    private val scrollSink: (Int) -> Unit = { d -> main.post { if (running) zoomMap(d) } }

    /** Satellites used in the fix, for the GPS badge. */
    private val gnss = object : GnssStatusCompat.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
            sats = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    /** What the PRO gauges need from the maintenance book, re-read every 30 s. */
    private class ProBook(val odoKm: Double?, val appKm: Double, val lastFillKm: Double?, val lPer100: Double?)

    private val listener = LocationListener { loc ->
        val prev = lastLoc
        // A coarse network fix must not overwrite a fresh GPS one.
        if (loc.provider == LocationManager.NETWORK_PROVIDER && prev?.provider == LocationManager.GPS_PROVIDER &&
            loc.time - prev.time < 5_000L) return@LocationListener
        if (prev == null) LogBus.log("[DASH] first fix: ${loc.provider} hasSpeed=${loc.hasSpeed()}")
        speedKmh = speedKmh(prev, loc)
        lastLoc = loc
        mapDirty = true
        // GPS course is only meaningful when moving; keep the last one when stopped.
        if (loc.hasBearing() && loc.speed > 1.2f) heading = loc.bearing
        dashNav?.onFix(loc)
        // Draw the new speed now, not at the next 500 ms tick (that alone lagged up to half a second).
        if (running) refresh()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (projected && lm == null) subscribeGps()  // permission may arrive after bind
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
            subscribeGps()
            startLean()
            MapInputBridge.keySink = keySink
            MapInputBridge.scrollSink = scrollSink
            LogBus.log("[DASH] handlebar: ▼/OK map full screen · ▲ hide panel · knob zoom · back = normal")
        }
        placeMap()  // the editor's live preview shows the real map too (at SAMPLE_LAT/LON)
        main.post(ticker)
    }

    /**
     * GPS + network (like the Map dash), seeded with the last known fix so the gauges move before
     * the first satellite lock. Sets [lm] only once something is subscribed; the ticker retries.
     */
    private fun subscribeGps() {
        val log = gpsTries++ == 0
        val granted = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!granted) { if (log) LogBus.log("[DASH] no location permission — speed waits for it"); return }
        try {
            val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
                m.isProviderEnabled(it) && runCatching {
                    m.requestLocationUpdates(it, if (it == LocationManager.GPS_PROVIDER) 0L else 2_000L, 0f, listener, Looper.getMainLooper())  // GPS: as fast as the chip gives
                }.isSuccess
            }
            if (providers.isEmpty()) { if (log) LogBus.log("[DASH] location is off — speed waits for it"); return }
            lm = m
            runCatching { LocationManagerCompat.registerGnssStatusCallback(m, ContextCompat.getMainExecutor(ctx), gnss) }
            LogBus.log("[DASH] speed from $providers")
            providers.mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.takeIf { lastLoc == null && System.currentTimeMillis() - it.time < 60_000L }  // an old fix would show an old speed
                ?.let { listener.onLocationChanged(it) }
        } catch (e: Exception) {
            if (log) LogBus.log("[DASH] no GPS: $e")
        }
    }

    fun release() {
        running = false
        main.removeCallbacks(ticker)
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        lm?.let { runCatching { LocationManagerCompat.unregisterGnssStatusCallback(it, gnss) } }
        lm = null
        if (MapInputBridge.keySink === keySink) MapInputBridge.keySink = null
        if (MapInputBridge.scrollSink === scrollSink) MapInputBridge.scrollSink = null
        sensors?.let { runCatching { it.unregisterListener(sensorListener) } }
        if (sensors != null) LogBus.log("[DASH] max lean L ${maxLeanL.roundToInt()}° R ${maxLeanR.roundToInt()}°")
        sensors = null
        map?.let { runCatching { it.onPause(); it.onDestroy() } }
        map = null
        if (projected) TripAutoLog.setMapUiLive(ctx, false)
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        root.setBackgroundColor(Color.BLACK)
        if (dash.style.isPro) {
            photo = BikeMemory.selected(ctx)?.photoPath?.let { decodePhoto(it) }
            pro = ProDashView(ctx, dash.accent, dash.style, dash.accent2, dash.customColor, dash.hidden, dash.speedAlert, dash.effects, live = projected,
                mapInCard = DashLayout.Part.LIVE_MAP !in dash.hidden).also { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            // Live map in the right card: the map goes between the dash and this strip layer.
            if (DashLayout.Part.LIVE_MAP !in dash.hidden) {
                mapZoom = when (dash.style) {  // the map-first layouts have a big map: see more around
                    DashLayout.Style.NAV -> 16.0
                    DashLayout.Style.NAVC -> 15.5
                    DashLayout.Style.NAVT -> 16.5
                    else -> CARD_ZOOM
                }
                proStrip = ProDashView(ctx, dash.accent, dash.style, dash.accent2, dash.customColor, dash.hidden, cardStripOnly = true)
                    .also { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            }
        } else gridTiles()
        mapSpeed = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.16f))
            includeFontPadding = false
            val p = px(0.02f).roundToInt()
            setPadding(p * 2, p, p * 2, p)
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = px(0.04f) }
            visibility = View.GONE
        }
        root.addView(mapSpeed, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { val m = px(0.03f).roundToInt(); setMargins(m, m, m, m) })
        // GPS badge in the top-right corner (PRO draws its own in the top bar unless the map covers it).
        if (pro == null) {
            gridNav = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.065f))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = px(0.02f).roundToInt()
                val p = px(0.02f).roundToInt()
                setPadding(p * 2, p, p * 2, p)
                background = GradientDrawable().apply {
                    setColor(0xF20D1116.toInt()); cornerRadius = px(0.04f); setStroke(maxOf(1, px(0.006f).roundToInt()), dash.accent)
                }
                visibility = View.GONE
            }
            root.addView(gridNav, FrameLayout.LayoutParams(MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                .apply { val m = px(0.02f).roundToInt(); setMargins(m, m, m, m) })
        }
        gpsBadgeView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.05f))
            typeface = Typeface.DEFAULT_BOLD
            val p = px(0.012f).roundToInt()
            setPadding(p * 2, p, p * 2, p)
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = px(0.03f) }
        }
        root.addView(gpsBadgeView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { val m = px(0.02f).roundToInt(); setMargins(m, m, m, m) })
        // Night: a dark veil over everything so the panel doesn't dazzle (on the bike only).
        if (projected && dash.nightDim) {
            nightVeil = View(ctx).apply { setBackgroundColor(Color.argb(NIGHT_DIM, 0, 0, 0)); isClickable = false }
            root.addView(nightVeil, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun gridTiles() {
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
        side = grid
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
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val value = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            val size = when {
                w == Widget.MAINTENANCE || w == Widget.MEDIA -> if (hero) 0.09f else 0.055f
                w == Widget.FUEL -> if (hero) 0.15f else 0.075f
                hero -> 0.36f
                else -> 0.13f
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size))
            gravity = Gravity.CENTER
            maxLines = if (w == Widget.MAINTENANCE || w == Widget.FUEL || w == Widget.MEDIA) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        box.addView(label)
        box.addView(value)
        values[w] = value
        return FrameLayout(ctx).apply {
            val m = px(0.012f).roundToInt()
            addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(m, m, m, m) })
            if (w == Widget.MAP) mapTile = this  // the live map goes over the placeholder when projected
        }
    }

    private fun labelOf(w: Widget): String {
        val name = ctx.getString(w.label)
        return if (w == Widget.SPEED || w == Widget.MAX_SPEED) "$name · km/h" else name
    }

    // ---------------------------------------------------------------- values

    private fun refresh() {
        val snap = TripLogger.current?.snapshot()?.takeIf { it.recording }
        val (gpsText, level) = gps()
        val showGps = DashLayout.Part.GPS !in dash.hidden
        pro?.let { p ->  // values is empty for PRO
            val d = (if (projected) withNav(proLive(snap)) else proSample()).copy(gps = gpsText.takeIf { showGps }, gpsColor = gpsColor(level))
            p.data = d
            proStrip?.data = d
        }
        proStrip?.visibility = if (!mapFull && mapHost.parent != null) View.VISIBLE else View.GONE
        gpsBadgeView?.apply {
            text = gpsText; setTextColor(gpsColor(level))
            visibility = if (!showGps || (pro != null && !mapFull)) View.GONE else View.VISIBLE
            // Grid: bottom-left of the big tile (top-right would cover a tile's label); over the full map: top-right.
            val g = if (mapFull || pro != null) Gravity.TOP or Gravity.END else Gravity.BOTTOM or Gravity.START
            (layoutParams as? FrameLayout.LayoutParams)?.takeIf { it.gravity != g }?.let { it.gravity = g; layoutParams = it }
        }
        nightVeil?.let { v ->
            val now = System.currentTimeMillis()
            if (now - nightAt > 60_000L) { nightAt = now; v.visibility = if (NightPrefs.isNightNow(ctx)) View.VISIBLE else View.GONE }
        }
        mapSpeed?.apply { visibility = if (mapFull) View.VISIBLE else View.GONE; text = "${shownKmh() ?: "—"} km/h" }
        gridNav?.let { v ->
            val nav = navNow()
            v.visibility = if (nav == null) View.GONE else View.VISIBLE
            if (nav != null) {
                val sz = px(0.1f).roundToInt()
                val icon = nav.icon?.let { b -> android.graphics.drawable.BitmapDrawable(ctx.resources, b).apply { setBounds(0, 0, sz, sz); setTint(dash.accent) } }
                v.setCompoundDrawables(icon, null, null, null)
                v.text = listOfNotNull(nav.arrow?.takeIf { icon == null }, nav.dist.takeIf { it.isNotBlank() }, nav.text).joinToString("  ") +
                    listOfNotNull(nav.remain, nav.eta).joinToString(" · ").let { if (it.isEmpty()) "" else "  ·  $it" }
            }
        }
        feedMap()
        for ((w, tv) in values) {
            tv.text = if (projected) live(w, snap) else sample(w)
            val hot = when (w) {
                Widget.PHONE -> phoneHot
                Widget.SPEED -> dash.speedAlert > 0 && (shownKmh() ?: 0) >= dash.speedAlert
                Widget.FUEL -> projected && fuelLow(fuelNow(snap).first)
                else -> false
            }
            tv.setTextColor(if (hot) ContextCompat.getColor(ctx, R.color.status_error) else Color.WHITE)
        }
    }

    private fun live(w: Widget, snap: TripRecorder.Stats?): String = when (w) {
        Widget.SPEED -> shownKmh()?.toString() ?: "—"
        Widget.CLOCK -> clock()
        Widget.TRIP -> snap?.let { String.format(Locale.getDefault(), "%.1f km", it.distanceMeters / 1000.0) } ?: "—"
        Widget.RIDE_TIME -> snap?.let { hm(it.movingTimeMs) } ?: "—"
        Widget.HEADING -> heading?.let { compass(it) } ?: "—"
        Widget.ALTITUDE -> lastLoc?.takeIf { it.hasAltitude() }?.let { "${it.altitude.roundToInt()} m" } ?: "—"
        Widget.MAX_SPEED -> snap?.let { (it.maxSpeedMs * 3.6f).roundToInt().toString() } ?: "—"
        Widget.PHONE -> phone()
        Widget.MAINTENANCE -> maintenance()
        Widget.MAP -> MAP_ICON
        Widget.LEAN -> lean()?.let { leanText(it) } ?: "—"
        Widget.MEDIA -> mediaLine() ?: "—"
        Widget.FUEL -> fuelText(snap)
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
        Widget.MAP -> MAP_ICON
        Widget.LEAN -> leanText(24f)
        Widget.MEDIA -> "⏸ Song — Artist"
        Widget.FUEL -> "60%\n≈ 270 km"
    }

    // ---------------------------------------------------------------- lean angle

    private fun startLean() {
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val gyro = m.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val grav = m.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gyro == null || grav == null) { LogBus.log("[DASH] no gyroscope — no lean angle"); return }
        m.registerListener(sensorListener, gyro, SensorManager.SENSOR_DELAY_GAME, main)
        m.registerListener(sensorListener, grav, SensorManager.SENSOR_DELAY_GAME, main)
        m.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { m.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL, main) }
        sensors = m
    }

    /** Lean now (degrees, + = left), null without gyroscope or GPS speed; tracks the ride's max. */
    private fun lean(): Float? {
        if (sensors == null) return null
        val v = speedKmh ?: return null
        val d = leanDeg(v / 3.6f, yawRate)
        if (d > maxLeanL) maxLeanL = d
        if (-d > maxLeanR) maxLeanR = -d
        return d
    }

    /** Speed as the dash shows it: 0 when stopped or when fixes stopped coming at low speed (see [displaySpeed]). */
    private fun shownKmh(): Int? = displaySpeed(speedKmh, lastLoc?.let { (android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L })

    /** Turn-by-turn fields for Nav Pro from [dashNav]; unchanged [d] when not navigating. */
    /** What the dash shows while navigating, from whichever source is guiding. */
    private data class NavInfo(
        val arrow: String?, val icon: android.graphics.Bitmap?, val dist: String, val text: String,
        val dest: String? = null, val remain: String? = null, val eta: String? = null, val grade: Double? = null,
    )

    /**
     * The next turn: the app's own navigation ([dashNav], from the Map screen) first, else the
     * phone's navigation app (Google Maps, Waze… via [PhoneNav]); null when nobody navigates.
     */
    private fun navNow(): NavInfo? {
        val n = dashNav
        val dest = n?.destination
        if (n != null && dest != null) {
            val p = n.progress ?: return NavInfo("↑", null, "", ctx.getString(R.string.pro_routing), dest.name)
            if (p.remainM < DashNav.ARRIVED_M) return NavInfo("⚑", null, "", ctx.getString(R.string.pro_arrived), dest.name)
            val cue = p.upcomingTurn
            val thai = Locale.getDefault().language == "th"
            val say = cue?.let { (if (thai) it.instrTh else it.instr).ifBlank { it.shortLabel } } ?: ctx.getString(R.string.pro_follow_route)
            return NavInfo(
                cue?.dir?.let { DashNav.arrow(it) } ?: "↑", null,
                cue?.let { DashNav.distance(it.distanceFromRiderM) } ?: "", say, dest.name,
                DashNav.distance(p.remainM),
                p.etaSec?.let { ctx.getString(R.string.pro_minutes, ((it + 59) / 60).toInt()) },
                p.gradePct,
            )
        }
        if (!projected) return null
        val t = PhoneNav.current() ?: return null
        val (dist, say) = PhoneNav.split(t.title, t.text)
        return NavInfo(if (t.icon == null) "↑" else null, t.icon, dist, say.ifBlank { t.title }, eta = t.sub)
    }

    private fun withNav(d: ProDashView.Data): ProDashView.Data {
        val nav = navNow() ?: return d
        return d.copy(
            navArrow = nav.arrow, navIcon = nav.icon, navTurnDist = nav.dist, navTurnText = nav.text,
            navDest = nav.dest, navRemain = nav.remain, navEta = nav.eta, gradePct = nav.grade,
        )
    }

    /** Street and area of where the bike is (Nav Center), off the main thread. */
    private fun findPlace() {
        val here = lastLoc ?: return
        val now = System.currentTimeMillis()
        if (placeBusy || now - placeAt < 30_000L || (placeFrom?.distanceTo(here) ?: Float.MAX_VALUE) < 150f) return
        placeBusy = true
        placeAt = now
        Thread {
            val found = runCatching { TripNames.street(here.latitude, here.longitude) }.getOrNull()
            main.post { placeBusy = false; placeFrom = here; if (found != null) place = found }
        }.start()
    }

    /** Now-playing line, or null when nothing plays and no title is known. Re-read once a second. */
    private fun mediaLine(): String? {
        val now = System.currentTimeMillis()
        if (now - trackAt > 1_000L) { trackAt = now; track = runCatching { NowPlaying.current(ctx) }.getOrNull() }
        val t = track ?: return null
        if (t.title.isNullOrBlank() && !t.playing) return null
        return NowPlaying.line(t)
    }

    /** Frames and megabits per second actually sent to the dash, over the last second or so. */
    private fun linkStats() {
        val now = System.currentTimeMillis()
        val dt = now - linkAt
        if (dt < 1_000L) return
        val f = LinkStats.frames; val b = LinkStats.bytes
        if (linkAt > 0L) {
            videoFps = ((f - linkFrames) * 1000 / dt).toInt()
            linkMbps = (b - linkBytes) * 8f / dt / 1000f
        }
        linkAt = now; linkFrames = f; linkBytes = b
    }

    /** Wi-Fi signal, free storage and mobile data for Nav Live; cheap but not every tick. */
    @Suppress("DEPRECATION")  // connectionInfo / allNetworks: still the simple way on API 29
    private fun sysStats() {
        val now = System.currentTimeMillis()
        if (now - sysAt < 10_000L) return
        sysAt = now
        wifiDbm = runCatching {
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.rssi?.takeIf { it in -126..-1 }
        }.getOrNull()
        storageFree = runCatching { android.os.StatFs(ctx.filesDir.path).let { (it.availableBytes * 100 / it.totalBytes).toInt() } }.getOrNull()
        cellReady = runCatching {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) && it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } == true
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- fuel estimate

    /** Litres left (null = unknown: no tank size or no refuel logged) and the L/100 km used for it. */
    private fun fuelNow(snap: TripRecorder.Stats?): Pair<Double?, Double> {
        val b = proBook()
        val tripKm = snap?.let { it.distanceMeters / 1000.0 }
        val sinceFill = b.lastFillKm?.let { b.appKm + (tripKm ?: 0.0) - it }
        val l100 = b.lPer100 ?: TYPICAL_L_PER_100
        return ProDashView.fuelLeft(dash.tankLiters, l100, sinceFill) to l100
    }

    private fun fuelLow(left: Double?) = left != null && dash.tankLiters > 0f && left / dash.tankLiters < LOW_FUEL

    /** Grid "Fuel" widget: "60%" over "≈ 270 km", or the nearest station when low. */
    private fun fuelText(snap: TripRecorder.Stats?): String {
        val (left, l100) = fuelNow(snap)
        left ?: return "—"
        val pct = (left / dash.tankLiters * 100).roundToInt()
        val low = fuelLow(left)
        trackStation(low)
        val second = if (low && station != null) stopText(station).substringAfter(" · ") else "≈ ${ProDashView.rangeKm(left, l100)} km"
        return "$pct%\n$second"
    }

    /** Low fuel: look up the nearest station; otherwise forget it (and its map pin). */
    private fun trackStation(low: Boolean) {
        if (low) findStation() else if (station != null) { station = null; mapDirty = true }
    }

    // ---------------------------------------------------------------- low fuel → nearest station

    /** Looks up the nearest station (again after 5 min or 3 km); keeps the old one meanwhile. */
    private fun findStation() {
        val here = lastLoc ?: return
        val now = System.currentTimeMillis()
        if (stationBusy || (now - stationAt < 5 * 60_000L && (stationFrom?.distanceTo(here) ?: 0f) < 3_000f)) return
        stationBusy = true
        stationAt = now
        Thread {
            val found = runCatching { FuelStations.near(here).stations.minByOrNull { it.distanceM } }
                .onFailure { LogBus.log("[DASH] fuel stations: ${it.message}") }.getOrNull()
            main.post {
                stationBusy = false
                stationFrom = here
                if (found != null) { station = found; LogBus.log("[DASH] low fuel → ${found.brand.ifBlank { "station" }} ${found.distanceM.roundToInt()} m") }
            }
        }.start()
    }

    /** "⛽ Low fuel · PTT 2.3 km ↗", the arrow relative to the way the bike points. */
    private fun stopText(s: FuelStations.Station?): String {
        val low = ctx.getString(R.string.pro_fuel_low)
        val here = lastLoc
        if (s == null || here == null) return "⛽ $low"
        val there = Location("").apply { latitude = s.lat; longitude = s.lon }
        val km = String.format(Locale.getDefault(), "%.1f", here.distanceTo(there) / 1000f)
        val brg = here.bearingTo(there)
        val dir = heading?.let { arrow(brg - it) } ?: compass(brg).substringBefore(' ')
        return "⛽ $low · ${s.brand.ifBlank { ctx.getString(R.string.pro_fuel_station) }} $km km $dir"
    }

    // ---- (map + handlebar)------------------------------------------------------------ map + handlebar

    /** Puts the live map where it belongs now: full screen, the MAP tile, or nowhere. */
    private fun placeMap() {
        (mapHost.parent as? android.view.ViewGroup)?.removeView(mapHost)
        val tile = mapTile
        val strip = proStrip
        mapHost.clipToOutline = false
        mapHost.foreground = null
        pro?.mapInCard = false
        when {
            mapFull -> root.addView(mapHost, root.indexOfChild(mapSpeed).coerceAtLeast(0), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            tile != null -> tile.addView(mapHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                val m = px(0.012f).roundToInt(); setMargins(m, m, m, m)
            })
            strip != null -> {
                // PRO card: the dash must be laid out first to know where its card is.
                val p = pro ?: return
                val r = p.mapRect() ?: run { root.post { if (running && !mapFull) placeMap() }; return }
                // Whole card area for the map (a fade ate half of it), rounded, with a thin rim in the template colour.
                val radius = p.cardCornerPx()
                mapHost.background = GradientDrawable().apply { setColor(0xFF101418.toInt()); cornerRadius = radius }
                mapHost.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                mapHost.clipToOutline = true
                mapHost.foreground = GradientDrawable().apply {
                    cornerRadius = radius
                    setStroke(maxOf(1, (radius * MAP_RIM).roundToInt()), (p.accentColor and 0x00FFFFFF) or 0xB3000000.toInt())
                }
                p.mapInCard = true
                root.addView(mapHost, root.indexOfChild(strip), FrameLayout.LayoutParams(r.width().roundToInt(), r.height().roundToInt()).apply {
                    leftMargin = r.left.roundToInt(); topMargin = r.top.roundToInt()
                })
            }
            else -> return
        }
        if (map == null) map = runCatching { DashMapController(ctx, mapHost).also { it.onResume() } }
            .onFailure { LogBus.log("[DASH] map failed: $it") }.getOrNull()
        mapDirty = true
    }

    private fun feedMap() {
        val m = map ?: return
        val l = lastLoc ?: if (projected) return else Location("").apply { latitude = SAMPLE_LAT; longitude = SAMPLE_LON }
        if (!mapDirty || mapHost.parent == null) return
        mapDirty = false
        m.follow(l.latitude, l.longitude, heading ?: 0f, mapZoom, headingUp = true, moving = (speedKmh ?: 0) > 3)
        // Navigating: the route and its destination win over the fuel stop.
        if (routeDirty) { routeDirty = false; m.setRouteRemain(routeLine) }
        val dest = dashNav?.destination
        if (dest != mapDest) { mapDest = dest; m.setDestination(dest); mapStation = null }
        if (dest != null) return
        // Low fuel: the station as the destination pin, with a dashed line to it.
        val s = station
        if (s !== mapStation) {
            mapStation = s
            m.setDestination(s?.let { MapPlace(it.brand.ifBlank { ctx.getString(R.string.pro_fuel_station) }, it.lat, it.lon) })
            if (s == null) m.clearToDest()
        }
        if (s != null) m.setToDest(l.latitude, l.longitude, s.lat, s.lon)
    }

    private fun zoomMap(delta: Int) {
        if (mapHost.parent == null) return
        mapZoom = (mapZoom + delta).coerceIn(12.0, 18.0)
        mapDirty = true
        feedMap()
    }

    private fun onKey(code: Int) {
        when (code) {
            AaInput.KEY_DOWN, AaInput.KEY_ENTER -> { mapFull = !mapFull; placeMap() }
            AaInput.KEY_UP -> side?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            AaInput.KEY_BACK -> { side?.visibility = View.VISIBLE; if (mapFull) { mapFull = false; placeMap() } }
            // Music from the handlebar: ◀ / ▶ change track, play/pause if the bike sends it.
            AaInput.KEY_LEFT, AaInput.KEY_PREVIOUS -> { NowPlaying.press(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); trackAt = 0L }
            AaInput.KEY_RIGHT, AaInput.KEY_NEXT -> { NowPlaying.press(ctx, android.view.KeyEvent.KEYCODE_MEDIA_NEXT); trackAt = 0L }
            AaInput.KEY_PLAY_PAUSE -> { NowPlaying.press(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); trackAt = 0L }
            else -> return
        }
        LogBus.log("[DASH] key $code → map ${if (mapFull) "full" else "off"}, panel ${if (side?.visibility == View.GONE) "hidden" else "shown"}")
        refresh()
    }

    // ---------------------------------------------------------------- PRO gauges

    private fun proLive(snap: TripRecorder.Stats?): ProDashView.Data {
        phone()
        val tripKm = snap?.let { it.distanceMeters / 1000.0 }
        val b = proBook()
        val (left, l100) = fuelNow(snap)
        val volts = PkeBattery.last(ctx)?.takeIf { System.currentTimeMillis() - it.atMs < 10 * 60_000L }?.volts
        val low = fuelLow(left) && DashLayout.Part.FUEL !in dash.hidden
        trackStation(low)
        val leanNow = lean()
        sysStats()
        linkStats()
        if (dash.style == DashLayout.Style.NAVC || dash.style == DashLayout.Style.NAVT) findPlace()
        return ProDashView.Data(
            speedKmh = shownKmh(),
            clock = clock(),
            bikeName = BikeMemory.selected(ctx)?.name.orEmpty(),
            photo = photo,
            bikeVolts = volts,
            phoneTempC = phoneTempC,
            phoneHot = phoneHot,
            fuel = left?.let { (it / dash.tankLiters).toFloat() },
            rangeKm = left?.let { ProDashView.rangeKm(it, l100) },
            odoKm = b.odoKm?.let { it + (tripKm ?: 0.0) },
            tripKm = tripKm,
            heading = heading?.let { compass(it).substringBefore(' ') },
            avgKmh = snap?.takeIf { it.movingTimeMs > 60_000L }?.let { (it.distanceMeters / (it.movingTimeMs / 1000.0) * 3.6).roundToInt() },
            rideTime = snap?.let { hm(it.movingTimeMs) },
            maxKmh = snap?.let { (it.maxSpeedMs * 3.6f).roundToInt() },
            maintenance = maintenance().takeIf { it != "—" },
            fuelStop = if (low) stopText(station) else null,
            phoneBattery = phoneLevel, phoneCharging = phoneCharging, phoneMv = phoneMv,
            media = mediaLine(), videoFps = videoFps, linkMbps = linkMbps,
            placeRoad = place?.first, placeArea = place?.second, leanDeg = leanNow,
            gpsAccM = lastLoc?.takeIf { it.hasAccuracy() }?.accuracy?.roundToInt(),
            wifiDbm = wifiDbm, storageFreePct = storageFree, cellReady = cellReady, pressureHpa = pressureHpa,
            lean = leanNow?.let { leanText(it) + "  " + String.format(Locale.getDefault(), "%.1f G", kotlin.math.abs(lateralG((speedKmh ?: 0) / 3.6f, yawRate))) },
            bluetoothOn = bluetoothOn(),
            altitudeM = lastLoc?.takeIf { it.hasAltitude() }?.altitude?.roundToInt(),
        )
    }

    private fun proSample() = ProDashView.Data(
        speedKmh = 72, clock = clock(), bikeName = BikeMemory.selected(ctx)?.name ?: "Zontes 368G", photo = photo,
        bikeVolts = 13.7f, phoneTempC = 36, fuel = 0.6f, rangeKm = 270, odoKm = 4863.0, tripKm = 92.4,
        heading = "NE", avgKmh = 54, rideTime = "1:42", maxKmh = 108, maintenance = maintenance().takeIf { it != "—" },
        bluetoothOn = true, altitudeM = 214, lean = leanText(24f) + "  0.4 G",
        phoneBattery = 63, phoneCharging = true, phoneMv = 4037, wifiDbm = -58, storageFreePct = 36, cellReady = true, pressureHpa = 1008.2f,
        media = "⏸ Song — Artist", videoFps = 30, linkMbps = 4.0f,
        placeRoad = "ถนนราชดำเนินกลาง", placeArea = "พระนคร", leanDeg = 24f, gpsAccM = 3,
    ).let { d ->
        // Sample guidance only on Nav Pro: elsewhere the banner appears just while navigating.
        if (dash.style != DashLayout.Style.NAVT) d else d.copy(
            navArrow = "↱", navTurnDist = "250 m", navTurnText = "เลี้ยวขวาเข้าสู่ ถ.วิภาวดีรังสิต", navDest = "นครพนม",
            navRemain = "12.4 km", navEta = ctx.getString(R.string.pro_minutes, 18),
        )
    }

    private fun proBook(): ProBook {
        val now = System.currentTimeMillis()
        book?.takeIf { now - it.first < 30_000L }?.let { return it.second }
        val b = Maintenance.load(ctx)
        val lastFill = b.log.filter { it.kind == "fuel" }.maxByOrNull { it.at }?.km
        return ProBook(b.odometer, b.appKm, lastFill, Maintenance.consumption(b)?.first).also { book = now to it }
    }

    private fun bluetoothOn(): Boolean = try {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter?.isEnabled == true
    } catch (_: Exception) { false }

    /** Garage photo, downsampled to about the dash panel's size. */
    private fun decodePhoto(path: String): android.graphics.Bitmap? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 480) sample *= 2
        android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }

    /** The GPS badge for this tick; logs when it changes colour so a field log shows why speed was "—". */
    private fun gps(): Pair<String, Int> {
        if (!projected) return gpsBadge(true, 1_000L, 4, true, 11)
        val m = lm
        val on = m != null && listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        val l = lastLoc
        val age = l?.let { (android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L }
        val b = gpsBadge(on, age, l?.takeIf { it.hasAccuracy() }?.accuracy?.roundToInt(), l?.provider == LocationManager.GPS_PROVIDER, sats)
        if (b.second != gpsLevel) { gpsLevel = b.second; LogBus.log("[DASH] gps ${b.first}") }
        return b
    }

    private fun gpsColor(level: Int) = when (level) {
        0 -> 0xFF5BD47A.toInt()
        1 -> 0xFFF5B80C.toInt()
        else -> ContextCompat.getColor(ctx, R.color.status_error)
    }

    private fun clock() = android.text.format.DateFormat.getTimeFormat(ctx).format(Date())

    private fun phone(): String {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "—"
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
        phoneHot = tempC >= HOT_C
        phoneTempC = tempC
        phoneLevel = if (level >= 0) level * 100 / scale else null
        phoneCharging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        phoneMv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }
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

        /** Live map in the PRO card: rim width as a share of the card's corner radius (thin). */
        private const val MAP_RIM = 0.13f
        /** Map zoom in the small card (closer, readable streets); the knob still zooms. */
        private const val CARD_ZOOM = 17.0

        /** Where the editor's preview map is centred (Bangkok, Democracy Monument). */
        private const val SAMPLE_LAT = 13.7567
        private const val SAMPLE_LON = 100.5018

        /** Below this share of the tank, the dash shows the nearest station. */
        const val LOW_FUEL = 0.15
        /** Low-pass on the yaw rate per gyro sample (~50 Hz → ~0.3 s). */
        private const val YAW_SMOOTH = 0.15f

        /**
         * Lean angle (degrees, + = left) of a bike turning at [yawRadS] (rad/s, + = left) at
         * [speedMs]: tan(lean) = v·ω / g. Works whatever way the phone is mounted, and a long
         * corner can't fool it the way gravity alone does. Walking pace and below → 0.
         */
        internal fun leanDeg(speedMs: Float, yawRadS: Float): Float {
            if (speedMs < 1.5f) return 0f
            return Math.toDegrees(kotlin.math.atan(lateralG(speedMs, yawRadS).toDouble())).toFloat().coerceIn(-65f, 65f)
        }

        /** Sideways acceleration in G of the same turn (v·ω / g). */
        internal fun lateralG(speedMs: Float, yawRadS: Float): Float = speedMs * yawRadS / 9.81f

        /** "◀ 32°" (left) · "32° ▶" (right) · "0°" when nearly upright. */
        internal fun leanText(deg: Float): String {
            val n = kotlin.math.abs(deg).roundToInt()
            return when {
                n < 3 -> "0°"
                deg > 0 -> "◀ $n°"
                else -> "$n° ▶"
            }
        }

        private val ARROWS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

        /** Arrow for a direction [rel] degrees clockwise from straight ahead. */
        internal fun arrow(rel: Float): String = ARROWS[(((rel % 360f + 360f) % 360f + 22.5f) / 45f).toInt() % 8]

        /** Night veil alpha (0–255) over the dash. Tune on the bike. */
        const val NIGHT_DIM = 110

        /** Under the live map in the MAP tile; what the preview shows. */
        private const val MAP_ICON = "\uD83D\uDDFA"

        // ponytail: one typical figure until the rider logs two full-tank refuels; per-model table if riders ask.
        /** Small-cc scooter/ADV consumption used until the refuel log gives the real one. */
        const val TYPICAL_L_PER_100 = 3.3

        private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /** Below this the bike counts as stopped: phone GPS keeps reporting 2–5 km/h for seconds after a stop. */
        const val STOP_KMH = 4
        /** No new fix for this long: the last speed is no longer true. */
        const val SPEED_STALE_MS = 3_000L

        /**
         * What the speedo shows for [kmh] from a fix [ageMs] old: 0 under [STOP_KMH]; once fixes stop
         * coming, 0 if it was slow (stopped at lights) or "—" (null) if it was fast (tunnel, lost GPS)
         * instead of freezing on the last number.
         */
        internal fun displaySpeed(kmh: Int?, ageMs: Long?): Int? = when {
            kmh == null || ageMs == null -> kmh
            ageMs > SPEED_STALE_MS -> if (kmh < 25) 0 else null
            kmh < STOP_KMH -> 0
            else -> kmh
        }

        /** km/h from the fix; when the chip gives no speed, from the distance to the previous fix. */
        internal fun speedKmh(prev: Location?, loc: Location): Int {
            if (loc.hasSpeed()) return (loc.speed * 3.6f).roundToInt()
            val dt = prev?.let { (loc.time - it.time) / 1000f } ?: return 0
            return if (dt in 0.5f..10f) (prev.distanceTo(loc) / dt * 3.6f).roundToInt() else 0
        }

        /**
         * GPS badge text + level (0 good · 1 weak · 2 none). [ageMs] = age of the last fix (null = none
         * yet), [sats] = satellites used in the fix. Kept short: it sits in the PRO top bar.
         */
        internal fun gpsBadge(on: Boolean, ageMs: Long?, accM: Int?, fromGps: Boolean, sats: Int): Pair<String, Int> {
            val acc = accM?.let { "±${it.coerceAtMost(999)}m" } ?: ""
            return when {
                !on -> "GPS OFF" to 2
                ageMs == null -> "GPS \uD83D\uDEF0$sats" to 2                 // searching: satellites so far
                ageMs > STALE_MS -> "GPS ${ageMs / 1000}s" to (if (ageMs > 30_000L) 2 else 1)
                !fromGps -> "NET $acc".trim() to 1
                else -> "$acc \uD83D\uDEF0$sats".trim() to (if ((accM ?: 999) <= GOOD_ACC_M) 0 else 1)
            }
        }

        /** A fix older than this is shown as its age (frozen speed vs. no GPS). */
        const val STALE_MS = 5_000L
        /** Accuracy that still counts as a good fix (same order as TripRecorder's 35 m). */
        const val GOOD_ACC_M = 25

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
