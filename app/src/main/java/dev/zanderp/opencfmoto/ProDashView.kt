// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * DashLayout.Style.PRO: a full gauge cluster drawn on one Canvas, laid out for the Zontes 368
 * panel (1024×425, ~2.4:1). Only values the phone really has are shown; the bike reports no rpm,
 * gear, coolant, fuel level or tyre pressure, so the big ring is km/h, the right gauge is the
 * bike battery (keyless box) and fuel is an estimate from the refuel log. Missing value = "—".
 */
class ProDashView(
    ctx: Context, private val accent: Int, private val style: DashLayout.Style = DashLayout.Style.PRO,
    /** Gradient end colour picked by the rider (null = single colour). */
    accent2: Int? = null,
    /** Rider's colours win over a themed template's own palette. */
    customColor: Boolean = false,
    /** Parts the rider hid for this template. */
    private val hidden: Set<DashLayout.Part> = emptySet(),
    /** Speed alert in km/h (0 = off): red number, flashing frame, and the red zone starts here. */
    private val alertKmh: Int = 0,
    private val effects: Set<DashLayout.Fx> = emptySet(),
    /** On the bike: the welcome and the needle sweep only play there, not in previews. */
    private val live: Boolean = false,
    /**
     * Second layer over the live map in the right card ([mapRect]): draws only the range strip
     * on top of the map, nothing else. The main view still draws the card's stats under the map.
     */
    private val cardStripOnly: Boolean = false,
    /** The live map covers the card's upper part: leave out the text that sat there. */
    mapInCard: Boolean = false,
) : View(ctx) {
    /** Set once the live map really sits in the card (not in thumbnails): no card, no rim, no text under it. */
    var mapInCard = mapInCard
        set(v) { if (field != v) { field = v; invalidate() } }
    private fun on(p: DashLayout.Part) = p !in hidden
    private fun fx(f: DashLayout.Fx) = f in effects
    private val redKmh = alertKmh.takeIf { it in 1 until MAX_KMH } ?: RED_KMH
    /** First frame on the bike; the welcome, then the sweep, run from here. */
    private var startAt = -1L
    private var lastFrame = 0L
    /** Speed the needle is at (eases toward the real one with [DashLayout.Fx.SMOOTH]); -1 = none yet. */
    private var needle = -1f
    /** Speed drawn this frame: the sweep while it runs, else the (smoothed) real speed. */
    private var shown: Int? = null
    private var over = false

    /** Look of one template: [main] replaces the gold, [second] is the low end of the speed arc and the volts gauge. */
    private data class Theme(val main: Int, val second: Int, val bg: Int, val cardTop: Int, val cardBottom: Int, val tabs: List<String>?, val framed: Boolean = true, val art: Int = 0, val scene: Int = 0, val fuelRow: Boolean = false,
        /** Supercar layout (art + modes left, compact ring, scenery card, chip and tile rows); [edition] heads it. */
        val supercar: Boolean = false, val edition: String? = null,
        /** Picture for the supercar badge between the bottom tiles (0 = three slanted bars). */
        val emblem: Int = 0,
        /** Map-first layout: big live map, phone telemetry column, status strip (Nav Live). */
        val navMap: Boolean = false,
        /** Map-first, map in the middle: location and speeds left, big speedo right (Nav Center). */
        val navCenter: Boolean = false,
        /** Turn-by-turn: guidance left, map centre, speed right, bike row below (Nav Pro). */
        val navPro: Boolean = false)

    data class Data(
        val speedKmh: Int? = null,
        val clock: String = "",
        val bikeName: String = "",
        val photo: Bitmap? = null,
        val bikeVolts: Float? = null,
        val phoneTempC: Int? = null,
        val phoneHot: Boolean = false,
        val tyreFrontBar: Float? = null,
        val tyreRearBar: Float? = null,
        /** 0..1 of the tank, estimated. */
        val fuel: Float? = null,
        val rangeKm: Int? = null,
        val odoKm: Double? = null,
        val tripKm: Double? = null,
        val heading: String? = null,
        val avgKmh: Int? = null,
        val rideTime: String? = null,
        val maxKmh: Int? = null,
        val maintenance: String? = null,
        val bluetoothOn: Boolean = false,
        val altitudeM: Int? = null,
        /** Low fuel: "⛽ Low fuel · PTT 2.3 km ↗"; shown in place of the bottom row. */
        val fuelStop: String? = null,
        /** "◀ 32°  0.6 G" (lean angle, sideways G). */
        val lean: String? = null,
        // Phone telemetry for the map-first layout (Nav Live).
        val phoneBattery: Int? = null,
        val phoneCharging: Boolean = false,
        val phoneMv: Int? = null,
        /** Signal of the Wi-Fi the phone is on (the bike's, when connected). */
        val wifiDbm: Int? = null,
        val storageFreePct: Int? = null,
        val cellReady: Boolean? = null,
        val pressureHpa: Float? = null,
        /** "⏸ Title — Artist" ([NowPlaying.line]); null = nothing to show. */
        val media: String? = null,
        /** Video to the dash as measured: frames and megabits per second. */
        val videoFps: Int? = null,
        val linkMbps: Float? = null,
        /** Where the bike is (street, area) for Nav Center; null = not known yet. */
        val placeRoad: String? = null,
        val placeArea: String? = null,
        /** Lean in degrees (+ = left), for Nav Center's lean arc. */
        val leanDeg: Float? = null,
        // Turn-by-turn (Nav Pro), from DashNav; null = not navigating.
        val navArrow: String? = null,
        val navTurnDist: String? = null,
        val navTurnText: String? = null,
        val navDest: String? = null,
        val navRemain: String? = null,
        val navEta: String? = null,
        val gpsAccM: Int? = null,
        /** Road grade ahead on the route (%). */
        val gradePct: Double? = null,
        /** Turn arrow picture from the phone's navigation app (Google Maps…); used instead of [navArrow]. */
        val navIcon: Bitmap? = null,
        /** GPS badge ([CustomDashUi.gpsBadge]) and its colour; null = not drawn. */
        val gps: String? = null,
        val gpsColor: Int = 0,
    )

    var data = Data()
        set(v) { field = v; invalidate() }

    private val base = when (style) {
        DashLayout.Style.CYBER -> Theme(0xFFFF2BD6.toInt(), 0xFF22E5FF.toInt(), 0xFF1C0B2A.toInt(), 0xFF1D0F33.toInt(), 0xFF0A0614.toInt(), listOf("CYBERPUNK", "ROAD", "ECO"), art = R.drawable.dash_art_cyber, scene = R.drawable.dash_scene_cyber)
        DashLayout.Style.DRAGON -> Theme(0xFFE8B04A.toInt(), 0xFFB5652B.toInt(), 0xFF241807.toInt(), 0xFF261B0C.toInt(), 0xFF120C05.toInt(), listOf("DRAGON", "GUARDIAN", "EXPLORER"), art = R.drawable.dash_art_dragon, scene = R.drawable.dash_scene_dragon)
        DashLayout.Style.RACING -> Theme(0xFFFF2A2A.toInt(), 0xFFC9CDD3.toInt(), 0xFF200A0A.toInt(), 0xFF1F1213.toInt(), 0xFF0E0909.toInt(), listOf("RACE", "SPORT", "TOURING"), art = R.drawable.dash_art_racing, scene = R.drawable.dash_scene_racing)
        DashLayout.Style.NEON -> Theme(0xFFB066FF.toInt(), 0xFF5B7CFF.toInt(), 0xFF170C2C.toInt(), 0xFF1A1236.toInt(), 0xFF0B0818.toInt(), listOf("RONIN", "SAMURAI", "NINJA"), art = R.drawable.dash_art_neon, scene = R.drawable.dash_scene_neon)
        DashLayout.Style.NEONCITY -> Theme(0xFFFF2E88.toInt(), 0xFF22E5FF.toInt(), 0xFF150A24.toInt(), 0xFF101A30.toInt(), 0xFF0A0E1C.toInt(), listOf("CYBERPUNK", "RACING", "TOURING", "ECO"), art = R.drawable.dash_art_neoncity, scene = R.drawable.dash_scene_neoncity, fuelRow = true)
        DashLayout.Style.ADVENTURE -> Theme(0xFFFFA41B.toInt(), 0xFFFFD36B.toInt(), 0xFF1C150A.toInt(), 0xFF13202A.toInt(), 0xFF0A1016.toInt(), listOf("ADVENTURE", "SPORT", "TOURING", "ECO"), art = R.drawable.dash_art_adventure, scene = R.drawable.dash_scene_adventure, fuelRow = true)
        DashLayout.Style.TOURING -> Theme(0xFFFFB81C.toInt(), 0xFFFF7A1A.toInt(), 0xFF0F1A2A.toInt(), 0xFF13233A.toInt(), 0xFF0A121E.toInt(), listOf("SPORT", "TOURING", "CITY", "ECO"), framed = false, art = R.drawable.dash_art_touring, scene = R.drawable.dash_scene_touring)
        // No artwork yet: the left side shows the Garage photo. Add art = / scene = once the images exist.
        // Anime: cut from the rider's "Bushido" mockup; no scene, the right card is the live map (or plain).
        DashLayout.Style.ANIMAL -> Theme(0xFF2EC4B6.toInt(), 0xFFFFC994.toInt(), 0xFF0E1A1C.toInt(), 0xFF12262A.toInt(), 0xFF0A1416.toInt(), listOf("WILD", "CITY", "ECO"), framed = false)
        DashLayout.Style.ANIME -> Theme(0xFFFF2B4A.toInt(), 0xFF4FA8FF.toInt(), 0xFF0B0E24.toInt(), 0xFF141A3A.toInt(), 0xFF080A1A.toInt(), listOf("BUSHIDO", "SPORT", "TOURING", "ECO"), framed = false, art = R.drawable.dash_art_anime, supercar = true, edition = "BUSHIDO", emblem = R.drawable.dash_emblem_anime)
        // Cut from the rider's "Space Edition" mockup; drawn on the Sport Touring layout (bike left, chips + tiles).
        DashLayout.Style.SPACE -> Theme(0xFF2ED3FF.toInt(), 0xFFB45CFF.toInt(), 0xFF0A1030.toInt(), 0xFF101C44.toInt(), 0xFF070C22.toInt(), listOf("SPACE", "SPORT", "TOURING", "CITY", "ECO"), art = R.drawable.dash_art_space, scene = R.drawable.dash_scene_space)
        // Cut from the rider's supercar mockups, maker logos painted out (trademarks).
        DashLayout.Style.GOLD -> Theme(0xFFFFC21A.toInt(), 0xFFFFF1B0.toInt(), 0xFF1A1405.toInt(), 0xFF1E1A10.toInt(), 0xFF0B0904.toInt(), listOf("SPORT", "TOURING", "CITY", "ECO"), art = R.drawable.dash_art_gold, scene = R.drawable.dash_scene_gold, fuelRow = true)
        // Cut from the rider's supercar-edition mockups, maker names and logos painted out (trademarks).
        DashLayout.Style.GT -> Theme(0xFFFF1E2D.toInt(), 0xFFD0D4DA.toInt(), 0xFF16070A.toInt(), 0xFF1E1013.toInt(), 0xFF0C0607.toInt(), listOf("SPORT", "TRACK", "TOURING", "ECO"), framed = false, art = R.drawable.dash_art_gt, scene = R.drawable.dash_scene_gt, supercar = true, edition = "MIDNIGHT GT")
        DashLayout.Style.PAPAYA -> Theme(0xFFFF8000.toInt(), 0xFFFFC266.toInt(), 0xFF1A0E04.toInt(), 0xFF22160C.toInt(), 0xFF0E0904.toInt(), listOf("SPORT", "TRACK", "TOURING", "ECO"), framed = false, art = R.drawable.dash_art_papaya, scene = R.drawable.dash_scene_papaya, supercar = true, edition = "PAPAYA")
        DashLayout.Style.GRAND -> Theme(0xFFD9B26A.toInt(), 0xFFF4E3B5.toInt(), 0xFF0A2219.toInt(), 0xFF103024.toInt(), 0xFF07150F.toInt(), listOf("GRAND", "SPORT", "TOURING", "ECO"), framed = false, art = R.drawable.dash_art_grand, scene = R.drawable.dash_scene_grand, supercar = true, edition = "GRAND TOURER")
        // Cut from the rider's black-and-blue hypercar mockup (maker name and badges left out); the card is for the live map.
        DashLayout.Style.NOIR -> Theme(0xFF2E9BFF.toInt(), 0xFFC9D3DE.toInt(), 0xFF05070C.toInt(), 0xFF0C1422.toInt(), 0xFF05080F.toInt(), listOf("BESPOKE", "SPORT", "TOURING", "ECO"), framed = false, art = R.drawable.dash_art_noir, supercar = true, edition = "NOIR")
        // Map first: the live map is the screen, telemetry around it (layout idea from MOTO-HUB's ride screen).
        DashLayout.Style.NAV -> Theme(0xFF3DDC97.toInt(), 0xFFB8E986.toInt(), 0xFF0B0D0F.toInt(), 0xFF15191C.toInt(), 0xFF0E1113.toInt(), null, framed = false, navMap = true)
        // From the rider's own "Navigation" mockup (bike picture cut from it).
        DashLayout.Style.NAVT -> Theme(0xFFB8F23A.toInt(), 0xFF35C8F0.toInt(), 0xFF0A0D10.toInt(), 0xFF141A1F.toInt(), 0xFF0C1014.toInt(), listOf("SPORT", "TOURING", "CITY", "ECO"), framed = false, art = R.drawable.dash_art_navpro, navMap = true, navPro = true)
        DashLayout.Style.NAVC -> Theme(0xFF9BE15D.toInt(), 0xFF4FD1E8.toInt(), 0xFF0B0D0F.toInt(), 0xFF15191C.toInt(), 0xFF0E1113.toInt(), null, framed = false, navMap = true, navCenter = true)
        DashLayout.Style.ROSSO -> Theme(0xFFFF1E1E.toInt(), 0xFFFFD21A.toInt(), 0xFF220707.toInt(), 0xFF231011.toInt(), 0xFF0E0606.toInt(), listOf("SPORT", "TOURING", "CITY", "ECO"), art = R.drawable.dash_art_rosso, scene = R.drawable.dash_scene_rosso)
        else -> Theme(accent, 0xFF4FC3F7.toInt(), 0xFF1A1A1F.toInt(), 0xFF13202E.toInt(), 0xFF0B1118.toInt(), null, framed = false)
    }
    /** Bike art on the left, chip row + trip tiles below (Sport Touring, Space Edition, Rosso Corsa). */
    private val touringLayout = style == DashLayout.Style.TOURING || style == DashLayout.Style.SPACE || style == DashLayout.Style.ROSSO
    private val supercarLayout = base.supercar
    private val navLayout = base.navMap
    private val navCenter = base.navCenter
    private val navPro = base.navPro
    private val theme = if (customColor || !style.themed) base.copy(main = accent, second = accent2 ?: base.second) else base
    /** Speed arc drawn as a gradient (themed looks, or the rider picked a gradient). */
    private val gradientArc = theme.framed || accent2 != null
    private val gold = theme.main
    /** Template artwork (cut from the ZRide mockup); the plain Pro look shows the Garage photo instead. */
    private val art = theme.art.takeIf { it != 0 }?.let { android.graphics.BitmapFactory.decodeResource(resources, it) }
    private val emblemArt = theme.emblem.takeIf { it != 0 }?.let { android.graphics.BitmapFactory.decodeResource(resources, it) }
    private val scene = theme.scene.takeIf { it != 0 && on(DashLayout.Part.SCENE) }?.let { android.graphics.BitmapFactory.decodeResource(resources, it) }
    private val second = theme.second
    private val white = Color.WHITE
    private val dim = 0xFF8F8A80.toInt()
    private val red = ContextCompat.getColor(ctx, R.color.status_error)
    private val panel = 0xFF101013.toInt()
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val boldItalic = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val pump = ContextCompat.getDrawable(ctx, R.drawable.ic_tile_fuel)?.mutate()?.apply { setTint(theme.main) }
    private val placeholder = ContextCompat.getDrawable(ctx, R.drawable.ic_ride)?.mutate()?.apply {
        setTint(0x40FFFFFF)
    }
    private val rect = RectF()
    /** Keeps only what lies under its alpha (feathers [backdrop]). */
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }

    private val labels = object {
        val tabs = theme.tabs ?: listOf(ctx.getString(R.string.pro_tab_sport), ctx.getString(R.string.pro_tab_road), ctx.getString(R.string.pro_tab_eco))
        val tyre = ctx.getString(R.string.pro_tyre)
        val phone = ctx.getString(R.string.pro_phone)
        val battery = ctx.getString(R.string.pro_battery)
        val heading = ctx.getString(R.string.pro_heading)
        val trip = ctx.getString(R.string.pro_trip)
        val odo = ctx.getString(R.string.pro_odo)
        val ride = ctx.getString(R.string.pro_ride)
        val range = ctx.getString(R.string.pro_range)
        val avg = ctx.getString(R.string.pro_avg)
        val time = ctx.getString(R.string.pro_time)
        val max = ctx.getString(R.string.pro_max)
        val maint = ctx.getString(R.string.pro_maint)
        val fuel = ctx.getString(R.string.pro_fuel)
        val alt = ctx.getString(R.string.pro_alt)
        val lean = ctx.getString(R.string.dl_w_lean)
        val charging = ctx.getString(R.string.pro_charging)
        val onBattery = ctx.getString(R.string.pro_on_battery)
        val phoneBattery = ctx.getString(R.string.pro_phone_battery)
        val batteryTemp = ctx.getString(R.string.pro_battery_temp)
        val normal = ctx.getString(R.string.pro_normal)
        val hot = ctx.getString(R.string.pro_hot)
        val storage = ctx.getString(R.string.pro_storage)
        val cell = ctx.getString(R.string.pro_cell)
        val ready = ctx.getString(R.string.pro_ready)
        val offline = ctx.getString(R.string.pro_offline)
        val pressure = ctx.getString(R.string.pro_pressure)
        val rangeShort = ctx.getString(R.string.pro_range_short)
        val location = ctx.getString(R.string.pro_location)
        val here = ctx.getString(R.string.pro_here)
        val finding = ctx.getString(R.string.pro_finding)
        val live = ctx.getString(R.string.pro_live)
        val avgShort = ctx.getString(R.string.pro_avg_short)
        val limit = ctx.getString(R.string.pro_limit)
        val speedGps = ctx.getString(R.string.pro_speed_gps)
        val navigation = ctx.getString(R.string.pro_navigation)
        val destination = ctx.getString(R.string.pro_destination)
        val noDestination = ctx.getString(R.string.pro_no_destination)
        val pickDestination = ctx.getString(R.string.pro_pick_destination)
        val grade = ctx.getString(R.string.pro_grade)
        val gpsAcc = ctx.getString(R.string.pro_gps_acc)
        val rideMode = "RIDE MODE"
    }

    /** Height of the design canvas while drawing (all sizes are fractions of it). */
    private var dh = DESIGN_H

    override fun onDraw(c: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        // Laid out for the 368's 1024×425 panel. Other dashes (125X 800×416, 150X, …) get the same
        // picture scaled to fit and centred, so nothing overlaps whatever the panel's shape.
        val s = min(vw / DESIGN_W, vh / DESIGN_H)
        val w = DESIGN_W
        val h = DESIGN_H
        dh = h
        if (cardStripOnly) {
            c.save(); c.translate((vw - w * s) / 2f, (vh - h * s) / 2f); c.scale(s, s)
            mapStrip(c, w, h)
            c.restore()
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (live && startAt < 0) startAt = now
        val t = if (live) now - startAt else Long.MAX_VALUE
        val intro = live && fx(DashLayout.Fx.INTRO) && t < INTRO_MS
        val st = t - if (fx(DashLayout.Fx.INTRO)) INTRO_MS else 0L
        var animating = intro
        val target = data.speedKmh
        shown = when {
            live && fx(DashLayout.Fx.SWEEP) && st < SWEEP_MS -> {
                animating = true
                needle = 0f  // the smoothed needle then rises from 0 when the sweep ends
                val f = st.coerceAtLeast(0L) / (SWEEP_MS / 2f)
                (MAX_KMH * (if (f < 1f) f else 2f - f)).roundToInt()
            }
            target == null -> { needle = -1f; null }
            fx(DashLayout.Fx.SMOOTH) && needle >= 0f -> {
                needle = ease(needle, target.toFloat(), (now - lastFrame).coerceIn(0L, 100L))
                if (needle != target.toFloat()) animating = true
                needle.roundToInt()
            }
            else -> { needle = target.toFloat(); target }
        }
        lastFrame = now
        over = alertKmh > 0 && (target ?: 0) >= alertKmh
        c.drawColor(0xFF050506.toInt())
        c.save()
        c.translate((vw - w * s) / 2f, (vh - h * s) / 2f)
        c.scale(s, s)
        background(c, w, h)
        topBar(c, w, h)
        if (navPro) navProLayout(c, w, h)
        else if (navCenter) navCentre(c, w, h)
        else if (navLayout) nav(c, w, h)
        else if (supercarLayout) supercar(c, w, h)
        else if (touringLayout) touring(c, w, h)
        else { leftPanel(c, w, h); gauge(c, w, h); rightPanel(c, w, h); bottomStrip(c, w, h) }
        if (over && (now / 300) % 2 == 0L) {
            // Over the speed alert: a red frame blinking round the whole panel.
            stroke.color = red; stroke.strokeWidth = h * 0.02f
            rect.set(h * 0.01f, h * 0.01f, w - h * 0.01f, h - h * 0.01f)
            c.drawRoundRect(rect, h * 0.05f, h * 0.05f, stroke)
        }
        if (intro) welcome(c, w, h, t)
        c.restore()
        if (animating) postInvalidateOnAnimation() else if (over) postInvalidateDelayed(150)
    }

    // ---------------------------------------------------------------- frame

    private fun background(c: Canvas, w: Float, h: Float) {
        fill.shader = RadialGradient(w / 2, h * 0.55f, w * 0.6f, theme.bg, 0xFF050506.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        motif(c, w, h)
        if (theme.framed) {
            // Neon frame round the whole panel: wide faint strokes under a thin bright one.
            rect.set(h * 0.012f, h * 0.012f, w - h * 0.012f, h - h * 0.012f)
            glow(c, h) { c.drawRoundRect(rect, h * 0.05f, h * 0.05f, stroke) }
        }
        // Gold glow lines: under the top bar, and the two side edges.
        stroke.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x00000000, gold, 0x00000000), null, Shader.TileMode.CLAMP)
        stroke.strokeWidth = h * 0.006f
        c.drawLine(w * 0.02f, h * 0.155f, w * 0.98f, h * 0.155f, stroke)
        stroke.shader = LinearGradient(0f, h * 0.25f, 0f, h * 0.8f, intArrayOf(0x00000000, gold, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawLine(w * 0.006f, h * 0.25f, w * 0.006f, h * 0.8f, stroke)
        c.drawLine(w * 0.994f, h * 0.25f, w * 0.994f, h * 0.8f, stroke)
        stroke.shader = null
    }

    private fun topBar(c: Canvas, w: Float, h: Float) {
        val base = h * 0.105f
        // Bike name: last word in gold italic, like "ZONTES 368G".
        val name = data.bikeName.ifBlank { "Zontes" }
        val cut = name.lastIndexOf(' ')
        val first = if (cut > 0) name.substring(0, cut).uppercase(Locale.ROOT) + " " else ""
        val last = if (cut > 0) name.substring(cut + 1) else name
        text.textSize = h * 0.07f
        text.typeface = bold; text.textAlign = Paint.Align.LEFT
        val firstW = text.measureText(first)
        text.typeface = boldItalic; text.textSize = h * 0.085f
        // Supercar: the edition on the left, the bike's name centred.
        val nameX = if (supercarLayout) w / 2 - (firstW + text.measureText(last)) / 2 else w * 0.03f
        text.typeface = bold; text.color = white; text.textSize = h * 0.07f
        c.drawText(first, nameX, base, text)
        text.typeface = boldItalic; text.color = gold; text.textSize = h * 0.085f
        c.drawText(last, nameX + firstW, base, text)
        theme.edition?.let { ed ->
            text.typeface = boldItalic; text.color = white; text.textSize = h * 0.07f
            c.drawText(ellipsize(ed, w * 0.22f), w * 0.03f, base, text)
            val ex = w * 0.03f + text.measureText(ellipsize(ed, w * 0.22f)) + h * 0.03f
            text.typeface = bold; text.textSize = h * 0.035f; text.color = gold
            c.drawText("EDITION", ex, base, text)
        }

        if (navLayout && data.videoFps != null) {
            // What reaches the dash, measured: frames and megabits per second.
            text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.045f; text.color = dim
            c.drawText("DASH ${data.videoFps} FPS · " + String.format(Locale.getDefault(), "%.1f Mbps", data.linkMbps ?: 0f), w * 0.5f, h * 0.1f, text)
        }
        if (!touringLayout && !supercarLayout && !navLayout && on(DashLayout.Part.MODE_TABS)) tabs(c, h, w / 2, h * 0.045f, w * 0.46f)

        // Right: Bluetooth state and the clock.
        text.textAlign = Paint.Align.RIGHT
        text.typeface = bold; text.textSize = h * 0.085f; text.color = white
        c.drawText(data.clock, w * 0.975f, base, text)
        val clockW = text.measureText(data.clock)
        text.textSize = h * 0.06f
        text.color = if (data.bluetoothOn) 0xFF4FC3F7.toInt() else dim
        val btX = w * 0.975f - clockW - h * 0.06f
        c.drawText("ᛒ", btX, base, text)
        // GPS badge left of Bluetooth, shrunk if it would run into the mode tabs.
        data.gps?.let { g ->
            val right = btX - text.measureText("ᛒ") - h * 0.035f
            val left = when { touringLayout || navLayout -> w * 0.5f; supercarLayout -> w * 0.66f; else -> w * 0.74f }
            text.textSize = h * 0.05f
            val tw = text.measureText(g)
            if (tw > right - left) text.textSize *= (right - left) / tw
            text.color = data.gpsColor
            c.drawText(g, right, base, text)
        }
        if (!navPro) navBanner(c, w, h)
    }

    /**
     * Navigating on any template: the next turn across the top bar's left side (over the bike's
     * name and the mode tabs; GPS, Bluetooth and clock stay). Nav Pro has its own panels instead.
     */
    private fun navBanner(c: Canvas, w: Float, h: Float) {
        val t = data.navTurnText ?: return
        val l = w * 0.012f; val r = w * 0.7f; val top = h * 0.012f; val b = h * 0.148f
        rect.set(l, top, r, b)
        fill.color = 0xFF0D1116.toInt(); c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill)  // opaque: the name under it mustn't show
        stroke.color = gold; stroke.strokeWidth = h * 0.005f; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, stroke)
        val mid = (top + b) / 2
        var x = l + h * 0.025f
        val icon = data.navIcon
        if (icon != null) {
            val s = (b - top) * 0.8f
            rect.set(x, mid - s / 2, x + s, mid + s / 2)
            fill.colorFilter = android.graphics.PorterDuffColorFilter(gold, android.graphics.PorterDuff.Mode.SRC_IN)
            c.drawBitmap(icon, null, rect, fill)
            fill.colorFilter = null
            x += s + h * 0.02f
        } else {
            text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.09f; text.color = gold
            val a = data.navArrow ?: "↑"
            c.drawText(a, x, mid + h * 0.032f, text)
            x += text.measureText(a) + h * 0.02f
        }
        val dist = data.navTurnDist.orEmpty()
        if (dist.isNotEmpty()) {
            text.textAlign = Paint.Align.LEFT; text.typeface = boldItalic; text.textSize = h * 0.062f; text.color = gold
            c.drawText(dist, x, mid + h * 0.022f, text)
            x += text.measureText(dist) + h * 0.025f
        }
        val right = listOfNotNull(data.navRemain, data.navEta).joinToString(" · ")
        text.textAlign = Paint.Align.RIGHT; text.typeface = bold; text.textSize = h * 0.036f; text.color = second
        if (right.isNotEmpty()) c.drawText(right, r - h * 0.025f, mid + h * 0.013f, text)
        val rw = if (right.isNotEmpty()) text.measureText(right) + h * 0.04f else h * 0.025f
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.042f; text.color = white
        c.drawText(ellipsize(t, r - rw - x), x, mid + h * 0.015f, text)
    }

    /**
     * Mode tabs centred on [cx] (or starting at it when [fromLeft]), at most [maxW] wide in total.
     * The selected one follows the accent (gold → 1st · blue → 2nd · green → 3rd).
     */
    private fun tabs(c: Canvas, h: Float, cx: Float, ty: Float, maxW: Float, fromLeft: Boolean = false) {
        val n = labels.tabs.size
        val sel = when (accent) { DashLayout.ACCENTS[2] -> 1; DashLayout.ACCENTS[1] -> 2; else -> 0 }
        val th = h * 0.075f
        var size = h * 0.045f
        text.typeface = boldItalic; text.textSize = size
        // Wide enough for the longest label ("CYBERPUNK", "ADVENTURE") plus the slanted ends.
        var tw = maxOf(h * 0.2f, labels.tabs.maxOf { text.measureText(it) } + th * 1.1f)
        if (tw * n > maxW) { size *= maxW / (tw * n); tw = maxW / n }
        val tx = if (fromLeft) cx else cx - tw * n / 2
        for (i in 0 until n) {
            val l = tx + i * tw
            path.reset()
            path.moveTo(l + th * 0.4f, ty); path.lineTo(l + tw, ty)
            path.lineTo(l + tw - th * 0.4f, ty + th); path.lineTo(l, ty + th); path.close()
            if (i == sel) {
                fill.shader = LinearGradient(0f, ty, 0f, ty + th, lighten(gold), gold, Shader.TileMode.CLAMP)
                c.drawPath(path, fill); fill.shader = null
            } else {
                fill.color = 0xFF1C1C21.toInt(); c.drawPath(path, fill)
            }
            text.typeface = boldItalic; text.textSize = size; text.textAlign = Paint.Align.CENTER
            text.color = if (i == sel) Color.BLACK else dim
            c.drawText(labels.tabs[i], l + tw / 2, ty + th * 0.72f, text)
        }
    }

    // ---------------------------------------------------------------- left: bike + rows

    private fun leftPanel(c: Canvas, w: Float, h: Float) {
        val l = w * 0.025f
        val r = w * 0.255f
        val top = h * if (theme.fuelRow) 0.43f else 0.52f
        val img = art ?: data.photo
        if (!on(DashLayout.Part.ART)) {
            // hidden: no art, no placeholder card
        } else if (img != null) {
            // Frameless: the art spans the left column, full strength in the empty space up top,
            // faded behind the first rows. Not stretched to full height: the art is ~2:1 and ~480 px
            // wide, so a taller crop would blow it up 3× and blur it.
            val t = h * 0.165f
            val b = top + h * 0.2f
            val (a, st) = solidThenDim((top - h * 0.05f - t) / (b - t))
            backdrop(c, img, 0f, t, w * 0.34f, b, a, st, LEFT_FEATHER)
        } else {
            // No Garage photo yet: a faint bike outline in a card instead of empty space.
            rect.set(l, h * 0.19f, r, h * if (theme.fuelRow) 0.41f else 0.47f)
            val rad = h * 0.03f
            fill.color = panel
            c.drawRoundRect(rect, rad, rad, fill)
            placeholder?.let { d ->
                val side = (rect.height() * 0.6f).toInt()
                val x = rect.centerX().toInt() - side / 2
                val y = rect.centerY().toInt() - side / 2
                d.setBounds(x, y, x + side, y + side)
                d.draw(c)
            }
            rim(c, rect, rad, h)
        }

        val tyre = if (data.tyreFrontBar != null || data.tyreRearBar != null)
            "${bar(data.tyreFrontBar)} / ${bar(data.tyreRearBar)} bar" else "— bar"
        val rows = listOfNotNull(
            Triple("(!)", tyre, labels.tyre).takeIf { on(DashLayout.Part.TYRE) },
            Triple("°C", data.phoneTempC?.let { "$it °C" } ?: "—", labels.phone).takeIf { on(DashLayout.Part.PHONE_TEMP) },
            Triple("V", data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—", labels.battery).takeIf { on(DashLayout.Part.BIKE_BATTERY) },
            Triple("", "", labels.fuel).takeIf { theme.fuelRow && on(DashLayout.Part.FUEL) },
        )
        val rowH = h * if (theme.fuelRow) 0.112f else 0.125f
        for ((i, row) in rows.withIndex()) {
            val y = top + i * rowH
            if (row.third == labels.fuel) {
                // Fuel row: pump, then E ▮▮▮▮▮ F.
                pump?.let { d -> val s = (h * 0.05f).toInt(); val x = (l + w * 0.025f - s / 2).toInt(); val yy = (y + rowH * 0.22f).toInt(); d.setBounds(x, yy, x + s, yy + s); d.draw(c) }
                fuelBar(c, l + w * 0.06f, y + rowH * 0.18f, r - w * 0.01f, y + rowH * 0.56f, h)
                text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.034f; text.color = dim
                c.drawText(ellipsize(row.third, r - l - w * 0.06f), l + w * 0.06f, y + rowH * 0.88f, text)
                continue
            }
            text.textAlign = Paint.Align.CENTER; text.typeface = bold
            text.textSize = h * 0.05f; text.color = gold
            c.drawText(row.first, l + w * 0.025f, y + rowH * 0.6f, text)
            text.textAlign = Paint.Align.LEFT
            text.textSize = h * 0.06f
            text.color = if (row.third == labels.phone && data.phoneHot) red else white
            c.drawText(row.second, l + w * 0.06f, y + rowH * 0.55f, text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.034f; text.color = dim
            c.drawText(ellipsize(row.third, r - l - w * 0.06f), l + w * 0.06f, y + rowH * 0.88f, text)
            if (i < rows.size - 1) {
                stroke.color = 0xFF2A2A30.toInt(); stroke.strokeWidth = 1f
                c.drawLine(l + w * 0.01f, y + rowH, r, y + rowH, stroke)
            }
        }
    }

    // ---------------------------------------------------------------- centre gauge

    /**
     * Round speed gauge at ([cx], [cy]) of radius [rad]; every size scales with the ring. [compact]
     * (supercar): no side gauges or lean line, the face tinted with the template.
     */
    private fun gauge(c: Canvas, w: Float, h: Float, cx: Float = w / 2, cy: Float = h * 0.53f, rad: Float = h * 0.33f, compact: Boolean = false) {
        val g = rad / 0.33f
        val start = 135f
        val sweep = 270f
        val speed = (shown ?: 0).coerceIn(0, MAX_KMH)

        // Dark face + metallic rim.
        fill.shader = RadialGradient(cx, cy, rad, if (compact) theme.cardTop else 0xFF16161B.toInt(), 0xFF070708.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, fill)
        fill.shader = null
        stroke.shader = SweepGradient(cx, cy, intArrayOf(0xFF3A3A40.toInt(), 0xFFE8E8EE.toInt(), 0xFF3A3A40.toInt(), 0xFF9A9AA2.toInt(), 0xFF3A3A40.toInt()), null)
        stroke.strokeWidth = g * 0.012f
        c.drawCircle(cx, cy, rad, stroke)
        stroke.shader = null
        if (theme.framed) glow(c, g) { c.drawCircle(cx, cy, rad * 1.03f, stroke) }
        if (style == DashLayout.Style.DRAGON) {
            // Scale ring: short gold dashes just outside the rim.
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(g * 0.02f, g * 0.012f), 0f)
            stroke.color = 0xAAE8B04A.toInt(); stroke.strokeWidth = g * 0.018f; stroke.strokeCap = Paint.Cap.BUTT
            c.drawCircle(cx, cy, rad * 1.08f, stroke)
            stroke.pathEffect = null; stroke.strokeCap = Paint.Cap.ROUND
        }

        // Red zone and live speed arc.
        val ring = rad * 0.88f
        rect.set(cx - ring, cy - ring, cx + ring, cy + ring)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = g * 0.045f
        stroke.color = 0x55FF3B30
        c.drawArc(rect, start + sweep * redKmh / MAX_KMH, sweep * (MAX_KMH - redKmh) / MAX_KMH, false, stroke)
        if (speed > 0 && fx(DashLayout.Fx.GLOW)) speedGlow(c, g, speed) { c.drawArc(rect, start, sweep * speed / MAX_KMH, false, stroke) }
        if (speed > 0) {
            stroke.color = if (speed >= redKmh) red else gold
            if (gradientArc && speed < redKmh) stroke.shader = SweepGradient(cx, cy, intArrayOf(second, gold, gold), floatArrayOf(0f, 0.4f, 1f))
                .apply { setLocalMatrix(Matrix().apply { setRotate(start, cx, cy) }) }
            c.drawArc(rect, start, sweep * speed / MAX_KMH, false, stroke)
            stroke.shader = null
        }
        stroke.strokeCap = Paint.Cap.ROUND

        // Ticks every 10 km/h, labels every 20.
        for (v in 0..MAX_KMH step 10) {
            val a = Math.toRadians((start + sweep * v / MAX_KMH).toDouble())
            val major = v % 20 == 0
            val r1 = rad * 0.97f
            val r0 = rad * if (major) 0.80f else 0.87f
            stroke.color = if (v >= redKmh) red else white
            stroke.strokeWidth = g * if (major) 0.008f else 0.004f
            c.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), stroke)
            if (major) {
                val lr = rad * 0.66f
                text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic
                text.textSize = g * 0.05f; text.color = if (v >= redKmh) red else white
                c.drawText("$v", cx + lr * cos(a).toFloat(), cy + lr * sin(a).toFloat() + text.textSize * 0.35f, text)
            }
        }

        // Speed.
        text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic
        text.textSize = g * 0.24f; text.color = if (over) red else white
        c.drawText(shown?.toString() ?: "—", cx, cy + g * 0.06f, text)
        text.typeface = bold; text.textSize = g * 0.045f; text.color = dim
        c.drawText("km/h", cx, cy + g * 0.13f, text)
        if (!compact && on(DashLayout.Part.LEAN)) data.lean?.let { text.color = gold; c.drawText(it, cx, cy - g * 0.15f, text) }

        // "Gear" box at the bottom: heading (the bike reports no gear).
        if (on(DashLayout.Part.HEADING)) headingBox(c, cx, cy, rad, g)
        if (!compact) sideGauges(c, cx, cy, rad, g)
    }

    private fun headingBox(c: Canvas, cx: Float, cy: Float, rad: Float, h: Float) {
        val bw = rad * 0.55f
        val bt = cy + rad * 0.62f
        val bb = cy + rad * 1.02f
        path.reset()
        path.moveTo(cx - bw * 0.7f, bt); path.lineTo(cx + bw * 0.7f, bt)
        path.lineTo(cx + bw, bb); path.lineTo(cx - bw, bb); path.close()
        fill.color = 0xFF0C0C0F.toInt(); c.drawPath(path, fill)
        stroke.color = 0xFF3A3A40.toInt(); stroke.strokeWidth = h * 0.004f; c.drawPath(path, stroke)
        text.typeface = boldItalic; text.textSize = h * 0.07f; text.color = gold
        c.drawText(data.heading ?: "—", cx, bt + (bb - bt) * 0.58f, text)
        text.typeface = bold; text.textSize = h * 0.03f; text.color = white
        c.drawText(ellipsize(labels.heading, bw * 1.6f), cx, bb - (bb - bt) * 0.08f, text)
    }

    /** Fuel (left, F at top) and bike battery (right, 11.5–14.5 V) beside the ring. */
    private fun sideGauges(c: Canvas, cx: Float, cy: Float, rad: Float, h: Float) {
        val sideR = rad * 1.17f
        // Canvas angles run clockwise from 3 o'clock, so 152° is lower-left and 28° lower-right.
        if (on(DashLayout.Part.FUEL)) {
            segments(c, cx, cy, sideR, 152f, 56f, data.fuel, leftSide = true) { f -> if (f < 0.15f) red else gold }
            sideLabel(c, cx, cy, sideR, 212f, "F"); sideLabel(c, cx, cy, sideR, 148f, "E")
            pump?.let { d ->
                val side = (h * 0.055f).toInt()
                val x = (cx - sideR - h * 0.1f).toInt()
                val y = (cy - side / 2f).toInt()
                d.setBounds(x, y, x + side, y + side); d.draw(c)
            }
        }
        if (on(DashLayout.Part.BIKE_BATTERY)) {
            val volts = data.bikeVolts?.let { ((it - 11.5f) / 3f).coerceIn(0f, 1f) }
            segments(c, cx, cy, sideR, 28f, 56f, volts, leftSide = false) { f -> if (f < 0.3f) red else second }
            sideLabel(c, cx, cy, sideR, -32f, "H"); sideLabel(c, cx, cy, sideR, 32f, "L")
            text.textAlign = Paint.Align.CENTER; text.textSize = h * 0.04f; text.typeface = bold
            text.color = second
            c.drawText("V", cx + sideR + h * 0.075f, cy + h * 0.015f, text)
        }
    }

    /**
     * Ten segments along an arc from [bottomDeg] going [span] degrees up the side ([leftSide]:
     * angles grow upwards on the left, shrink on the right). [value] 0..1 lights from the bottom.
     */
    private fun segments(c: Canvas, cx: Float, cy: Float, r: Float, bottomDeg: Float, span: Float, value: Float?, leftSide: Boolean, color: (Float) -> Int) {
        val n = 10
        val seg = span / n
        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = dh * 0.035f
        val lit = value?.let { (it * n).roundToInt() } ?: 0
        for (i in 0 until n) {
            val a = if (leftSide) bottomDeg + i * seg else bottomDeg - (i + 1) * seg
            stroke.color = if (value != null && i < lit) color(value) else 0xFF222228.toInt()
            c.drawArc(rect, a, seg - 1.2f, false, stroke)
        }
        stroke.strokeCap = Paint.Cap.ROUND
    }

    private fun sideLabel(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, s: String) {
        val a = Math.toRadians(deg.toDouble())
        val lr = r + dh * 0.05f
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = dh * 0.04f; text.color = white
        c.drawText(s, cx + lr * cos(a).toFloat(), cy + lr * sin(a).toFloat() + text.textSize * 0.35f, text)
    }

    // ---------------------------------------------------------------- right: ride card

    /** The right-hand card of each layout (design units); [stats] = it ends with the avg · time · max row. */
    private class Card(val l: Float, val t: Float, val r: Float, val b: Float, val stats: Boolean, val statsH: Float = 0f) {
        /** Part above the stats row: where the live map goes. */
        fun mapBottom() = if (stats) b - statsH else b
    }

    private fun card(w: Float, h: Float) = when {
        // As big as each layout allows (the live map fills the part above the stats): supercar reaches
        // the gauge's rim, classic the battery gauge's "V" and down beside the ODO · TRIP line.
        navPro -> Card(w * 0.185f, h * 0.16f, w * 0.785f, h * 0.755f, false)
        navCenter -> Card(w * 0.272f, h * 0.17f, w * 0.77f, h * 0.99f, false)
        navLayout -> Card(w * 0.01f, h * 0.17f, w * 0.725f, h * 0.845f, false)
        supercarLayout -> Card(w * 0.635f, h * 0.17f, w * 0.98f, h * 0.705f, true, h * 0.105f)  // down to the chip row
        touringLayout -> Card(w * 0.745f, h * 0.18f, w * 0.98f, h * 0.715f, false)
        else -> Card(w * 0.715f, h * 0.19f, w * 0.975f, h * 0.945f, true, h * 0.11f)  // down to just inside the neon frame
    }

    /** Where the live map sits in this view (px), or null before layout. */
    fun mapRect(): RectF? {
        if (width <= 0 || height <= 0) return null
        val s = min(width / DESIGN_W, height / DESIGN_H)
        val ox = (width - DESIGN_W * s) / 2f
        val oy = (height - DESIGN_H * s) / 2f
        val k = card(DESIGN_W, DESIGN_H)
        return RectF(ox + k.l * s, oy + k.t * s, ox + k.r * s, oy + k.mapBottom() * s)
    }

    /** Card corner radius in px, so the live map is rounded like the cards. */
    fun cardCornerPx(): Float = DESIGN_H * 0.03f * min(width / DESIGN_W, height / DESIGN_H)

    /** The template's main colour (rims, live map border). */
    val accentColor: Int get() = gold

    /** Over the live map: the range (or the most urgent maintenance) on a small dark label, top left. */
    private fun mapStrip(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        if (navPro) { turnPill(c, h, k) }
        else if (navLayout) { if (!navCenter) speedBubble(c, h, k); mediaPill(c, h, k) }
        val msg = data.rangeKm?.takeIf { on(DashLayout.Part.RANGE) }?.let { "≈ $it km · ${labels.range}" }
            ?: data.maintenance?.takeIf { on(DashLayout.Part.MAINTENANCE) }?.replace('\n', ' ')
            ?: return
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.038f; text.color = white
        val shown = ellipsize(msg, k.r - k.l - w * 0.04f)
        val x = k.l + w * 0.012f; val base = k.t + h * 0.05f
        rect.set(x - h * 0.012f, base - text.textSize * 0.95f, x + text.measureText(shown) + h * 0.012f, base + text.textSize * 0.35f)
        fill.color = 0xB3000000.toInt(); c.drawRoundRect(rect, h * 0.015f, h * 0.015f, fill)
        c.drawText(shown, x, base, text)
    }

    private fun rightPanel(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        val l = k.l; val r = k.r; val top = k.t; val bottom = k.b; val stats = k.stats; val statsH = k.statsH
        val big = data.rangeKm?.takeIf { on(DashLayout.Part.RANGE) }?.let { "≈ $it km" }
        val maint = data.maintenance?.takeIf { on(DashLayout.Part.MAINTENANCE) }
        val sc = scene
        if (mapInCard) {
            // Frameless: the live map fades into the template's own background; only the stats show.
            rect.set(l, top, r, bottom)
        } else if (sc != null) {
            // Frameless scenery: dim behind the text block up top and the stats row, full strength
            // in the empty band between them, feathered into the gauge on the left.
            val textEnd = (if (big != null && maint != null) h * 0.36f else h * 0.27f) / (bottom - top)
            val statsAt = if (stats) (bottom - top - statsH) / (bottom - top) else 0.9f
            val d = BACKDROP_DIM
            backdrop(c, sc, l - w * 0.03f, top, r + w * 0.02f, bottom,
                intArrayOf(0, d, d, 255, 255, d, d, 0),
                floatArrayOf(0f, 0.06f, textEnd, textEnd + 0.08f, statsAt - 0.06f, statsAt, 0.95f, 1f),
                floatArrayOf(0f, 0.3f, 0.92f, 1f))
            rect.set(l, top, r, bottom)
        } else {
            rect.set(l, top, r, bottom)
            val rad = h * 0.03f
            fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, theme.cardTop, theme.cardBottom, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, rad, rad, fill)
            fill.shader = null
            rim(c, rect, rad, h)
        }

        val pad = w * 0.014f
        // With the live map in the card, the map covers this text; the strip layer shows the range.
        if (!mapInCard) {
            text.textAlign = Paint.Align.LEFT
            text.typeface = bold; text.textSize = h * 0.038f; text.color = gold
            c.drawText(ellipsize(labels.ride, rect.width() * 0.6f), l + pad, rect.top + h * 0.07f, text)
            if ((style == DashLayout.Style.ADVENTURE || touringLayout) && on(DashLayout.Part.ALTITUDE)) {
                // Altitude chip, top right of the card (GPS height).
                text.textAlign = Paint.Align.RIGHT; text.color = white
                c.drawText("▲ " + (data.altitudeM?.let { String.format(Locale.getDefault(), "%,d m", it) } ?: "—"), r - pad, rect.top + h * 0.07f, text)
                text.textAlign = Paint.Align.LEFT; text.color = gold
            }

            // Big line: estimated range, else the most urgent maintenance (unless both are hidden).
            if (on(DashLayout.Part.RANGE) || on(DashLayout.Part.MAINTENANCE)) {
                text.textSize = h * 0.095f; text.color = white; text.typeface = boldItalic
                c.drawText(ellipsize(big ?: (maint?.substringBefore('\n') ?: "—"), rect.width() - 2 * pad), l + pad, rect.top + h * 0.19f, text)
                text.typeface = Typeface.DEFAULT; text.textSize = h * 0.035f; text.color = dim
                val sub = if (big != null || !on(DashLayout.Part.MAINTENANCE)) labels.range else maint?.substringAfter('\n', "")?.let { "${labels.maint} · $it" } ?: labels.maint
                c.drawText(ellipsize(sub, rect.width() - 2 * pad), l + pad, rect.top + h * 0.245f, text)
            }

            if (big != null && maint != null) {
                text.textSize = h * 0.035f; text.color = white
                c.drawText(ellipsize("${labels.maint}: ${maint.replace('\n', ' ')}", rect.width() - 2 * pad), l + pad, rect.top + h * 0.33f, text)
            }

        }

        if (!stats) return
        // Bottom row: avg · ride time · max.
        val cols = listOfNotNull(
            ((data.avgKmh?.toString() ?: "—") to labels.avg).takeIf { on(DashLayout.Part.AVG) },
            ((data.rideTime ?: "—") to labels.time).takeIf { on(DashLayout.Part.RIDE_TIME) },
            ((data.maxKmh?.toString() ?: "—") to labels.max).takeIf { on(DashLayout.Part.MAX_SPEED) },
        )
        if (cols.isEmpty()) return
        val rowTop = rect.bottom - statsH
        stroke.color = 0xFF24303C.toInt(); stroke.strokeWidth = 1f
        c.drawLine(l + pad, rowTop, r - pad, rowTop, stroke)
        val cw = rect.width() / cols.size
        for ((i, col) in cols.withIndex()) {
            val x = l + cw * i + cw / 2
            text.textAlign = Paint.Align.CENTER
            // Compact so the live map above gets the room (value + small label in ~0.1 h).
            text.typeface = bold; text.textSize = h * 0.048f; text.color = white
            c.drawText(col.first, x, rowTop + statsH * 0.52f, text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.025f; text.color = dim
            c.drawText(ellipsize(col.second, cw - pad), x, rowTop + statsH * 0.85f, text)
        }
    }

    // ---------------------------------------------------------------- bottom: ODO · TRIP

    private fun bottomStrip(c: Canvas, w: Float, h: Float) {
        data.fuelStop?.let { banner(c, w * 0.2f, h * 0.885f, w * 0.8f, h * 0.99f, h, it); return }
        val y = h * 0.955f
        val odo = data.odoKm?.let { "≈ " + String.format(Locale.getDefault(), "%,.0f km", it) } ?: "—"
        val trip = data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—"
        val left = "${labels.odo}  $odo"
        val right = "${labels.trip}  $trip"
        text.typeface = bold; text.textSize = h * 0.05f; text.color = white
        val showOdo = on(DashLayout.Part.ODO)
        val showTrip = on(DashLayout.Part.TRIP)
        if (showOdo != showTrip) {
            // Only one: centred, no divider.
            text.textAlign = Paint.Align.CENTER
            c.drawText(ellipsize(if (showOdo) left else right, w * 0.9f), w / 2, y, text)
            return
        }
        if (!showOdo) return
        text.textAlign = Paint.Align.RIGHT
        c.drawText(ellipsize(left, w * 0.45f), w / 2 - h * 0.04f, y, text)
        text.textAlign = Paint.Align.LEFT
        c.drawText(ellipsize(right, w * 0.45f), w / 2 + h * 0.04f, y, text)
        stroke.color = dim; stroke.strokeWidth = 1.5f
        c.drawLine(w / 2, y - h * 0.045f, w / 2, y + h * 0.005f, stroke)
    }

    // ---------------------------------------------------------------- helpers

    private fun rim(c: Canvas, r: RectF, rad: Float, h: Float) {
        stroke.color = (gold and 0x00FFFFFF) or 0x88000000.toInt()
        stroke.strokeWidth = h * 0.004f
        c.drawRoundRect(r, rad, rad, stroke)
    }

    /** Ridge line along the bottom with a warm sunset glow behind it. */
    private fun mountains(c: Canvas, w: Float, h: Float) {
        val ridge = floatArrayOf(0.80f, 0.73f, 0.78f, 0.69f, 0.76f, 0.71f, 0.79f, 0.72f, 0.77f, 0.70f, 0.78f)
        fill.shader = LinearGradient(0f, h * 0.6f, 0f, h * 0.8f, 0x00000000, (gold and 0x00FFFFFF) or 0x38000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.6f, w, h * 0.8f, fill)
        path.reset(); path.moveTo(0f, h)
        for ((i, y) in ridge.withIndex()) path.lineTo(w * i / (ridge.size - 1), h * y)
        path.lineTo(w, h); path.close()
        fill.shader = LinearGradient(0f, h * 0.69f, 0f, h, 0xFF101A2A.toInt(), 0xFF05080F.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
    }

    /** E [five segments] F between [l] and [r]; lit from the left by the fuel estimate. */
    private fun fuelBar(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float) {
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.04f; text.color = white
        val mid = (t + b) / 2 + text.textSize * 0.35f
        c.drawText("E", l + h * 0.02f, mid, text)
        c.drawText("F", r - h * 0.02f, mid, text)
        val x0 = l + h * 0.05f
        val segW = (r - h * 0.05f - x0) / 5
        val lit = data.fuel?.let { (it * 5).roundToInt() } ?: 0
        for (i in 0 until 5) {
            path.reset()
            val sl = x0 + i * segW
            path.moveTo(sl + h * 0.012f, t); path.lineTo(sl + segW - h * 0.006f, t)
            path.lineTo(sl + segW - h * 0.018f, b); path.lineTo(sl, b); path.close()
            fill.color = when {
                data.fuel == null || i >= lit -> 0xFF2A2A30.toInt()
                (data.fuel ?: 0f) < 0.15f -> red
                else -> gold
            }
            c.drawPath(path, fill)
        }
    }

    /** Red warning bar with [msg] centred, blinking its rim so it catches the eye. */
    private fun banner(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float, msg: String) {
        rect.set(l, t, r, b)
        val rad = h * 0.03f
        fill.color = 0xE62A0A0A.toInt(); c.drawRoundRect(rect, rad, rad, fill)
        val blink = (android.os.SystemClock.uptimeMillis() / 600) % 2 == 0L
        stroke.color = if (blink) red else (red and 0x00FFFFFF) or 0x66000000
        stroke.strokeWidth = h * 0.006f; c.drawRoundRect(rect, rad, rad, stroke)
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.055f; text.color = white
        c.drawText(ellipsize(msg, r - l - h * 0.06f), (l + r) / 2, (t + b) / 2 + text.textSize * 0.35f, text)
    }

    /** Small rounded tile: [icon] in the theme colour, [value] in white, optional [label] below. */
    private fun chip(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float, icon: String, value: String, label: String? = null) {
        rect.set(l, t, r, b)
        val rad = h * 0.025f
        fill.color = 0xCC0D1420.toInt(); c.drawRoundRect(rect, rad, rad, fill)
        rim(c, rect, rad, h)
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.045f; text.color = gold
        val ix = l + h * 0.03f
        c.drawText(icon, ix, if (label == null) (t + b) / 2 + h * 0.016f else t + (b - t) * 0.42f, text)
        val vx = ix + (if (icon.isEmpty()) 0f else text.measureText(icon) + h * 0.025f)
        text.color = white; text.textSize = h * if (label == null) 0.05f else 0.065f
        if (label == null) {
            c.drawText(ellipsize(value, r - vx - h * 0.02f), vx, (t + b) / 2 + h * 0.018f, text)
        } else {
            c.drawText(ellipsize(value, r - ix - h * 0.02f), ix, b - (b - t) * 0.16f, text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.033f; text.color = dim
            c.drawText(ellipsize(label, r - vx - h * 0.02f), vx, t + (b - t) * 0.40f, text)
        }
    }

    // ---------------------------------------------------------------- TOURING layout

    /**
     * Sport Touring: big bike art on the left, digital speed with a curved bar, chips for tyre /
     * phone / battery / fuel, a row of trip tiles at the bottom and the ride card on the right.
     */
    private fun touring(c: Canvas, w: Float, h: Float) {
        // Big bike art, frameless: nothing sits on it, so full strength with feathered edges.
        (art ?: data.photo)?.takeIf { on(DashLayout.Part.ART) }?.let {
            val (a, st) = solidThenDim(0.9f)
            backdrop(c, it, 0f, h * 0.165f, w * 0.30f, h * 0.835f, a, st, LEFT_FEATHER)
        }

        if (on(DashLayout.Part.MODE_TABS)) {
            text.textAlign = Paint.Align.LEFT; text.typeface = boldItalic; text.textSize = h * 0.038f; text.color = white
            c.drawText(ellipsize(labels.rideMode, w * 0.33f), w * 0.28f, h * 0.215f, text)
            tabs(c, h, w * 0.28f, h * 0.235f, w * 0.33f, fromLeft = true)
        }

        // Curved speed bar: from the bottom left, sweeping up to the right.
        path.reset()
        path.moveTo(w * 0.38f, h * 0.68f); path.quadTo(w * 0.69f, h * 0.68f, w * 0.71f, h * 0.35f)
        val pm = android.graphics.PathMeasure(path, false)
        val len = pm.length
        val seg = Path()
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeWidth = h * 0.05f
        stroke.color = 0xFF18202C.toInt(); c.drawPath(path, stroke)
        pm.getSegment(len * redKmh / MAX_KMH, len, seg, true)
        stroke.color = 0x55FF3B30; c.drawPath(seg, stroke)
        val speed = (shown ?: 0).coerceIn(0, MAX_KMH)
        if (speed > 0) {
            seg.reset(); pm.getSegment(0f, len * speed / MAX_KMH, seg, true)
            if (fx(DashLayout.Fx.GLOW)) { speedGlow(c, h, speed) { c.drawPath(seg, stroke) }; stroke.strokeWidth = h * 0.05f }
            stroke.color = if (speed >= redKmh) red else Color.WHITE
            if (speed < redKmh) stroke.shader = LinearGradient(w * 0.38f, 0f, w * 0.70f, 0f, gold, second, Shader.TileMode.CLAMP)
            c.drawPath(seg, stroke); stroke.shader = null
        }
        stroke.strokeCap = Paint.Cap.ROUND
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        for (v in 0..MAX_KMH step 20) {
            pm.getPosTan(len * v / MAX_KMH, pos, tan)
            // Inner side of the curve (up and to the left).
            val nx = tan[1]
            val ny = -tan[0]
            stroke.color = if (v >= redKmh) red else white; stroke.strokeWidth = h * 0.006f
            c.drawLine(pos[0] + nx * h * 0.028f, pos[1] + ny * h * 0.028f, pos[0] + nx * h * 0.05f, pos[1] + ny * h * 0.05f, stroke)
            text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic; text.textSize = h * 0.04f
            text.color = if (v >= redKmh) red else white
            c.drawText("$v", pos[0] + nx * h * 0.085f, pos[1] + ny * h * 0.085f + text.textSize * 0.35f, text)
        }

        // Digital speed.
        text.textAlign = Paint.Align.LEFT; text.typeface = boldItalic; text.textSize = h * 0.25f; text.color = if (over) red else white
        val sp = shown?.toString() ?: "—"
        c.drawText(sp, w * 0.28f, h * 0.55f, text)
        val sw = text.measureText(sp)
        text.typeface = bold; text.textSize = h * 0.05f; text.color = dim
        c.drawText("km/h", w * 0.28f + sw + h * 0.02f, h * 0.55f, text)
        if (on(DashLayout.Part.LEAN)) data.lean?.let { text.color = gold; c.drawText(it, w * 0.28f + sw + h * 0.02f, h * 0.47f, text) }

        // Heading where the mockup has the gear.
        if (on(DashLayout.Part.HEADING)) touringHeading(c, w, h)
        touringChips(c, w, h)
        rightPanel(c, w, h)
    }

    private fun touringHeading(c: Canvas, w: Float, h: Float) {
        path.reset()
        val bl = w * 0.655f; val br = w * 0.735f; val bt = h * 0.55f; val bb = h * 0.69f
        path.moveTo(bl + h * 0.03f, bt); path.lineTo(br, bt); path.lineTo(br - h * 0.03f, bb); path.lineTo(bl, bb); path.close()
        fill.color = 0xE60D1420.toInt(); c.drawPath(path, fill)
        stroke.color = (gold and 0x00FFFFFF) or 0x88000000.toInt(); stroke.strokeWidth = h * 0.004f; c.drawPath(path, stroke)
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.028f; text.color = white
        c.drawText(ellipsize(labels.heading, br - bl - h * 0.03f), (bl + br) / 2, bt + h * 0.035f, text)
        text.typeface = boldItalic; text.textSize = h * 0.07f; text.color = gold
        c.drawText(data.heading ?: "—", (bl + br) / 2, bb - h * 0.02f, text)
    }

    /** Chips (tyre · phone · battery · fuel) and the bottom tiles; hidden ones leave no gap. */
    private fun touringChips(c: Canvas, w: Float, h: Float) {
        chipRow(c, w * 0.265f, h * 0.725f, w * 0.98f, h * 0.815f, h)
        // Bottom tiles: trip · odo · altitude · avg · ride time (low fuel: the station instead).
        val tt = h * 0.84f; val tb = h * 0.975f
        data.fuelStop?.let { banner(c, w * 0.02f, tt, w * 0.98f, tb, h, it); return }
        tileRow(c, w * 0.02f, tt, w * 0.98f, tb, h, listOf(DashLayout.Part.TRIP, DashLayout.Part.ODO, DashLayout.Part.ALTITUDE, DashLayout.Part.AVG, DashLayout.Part.RIDE_TIME))
    }

    /** Tyre · phone · battery · fuel chips across [cl]..[cr]; hidden ones leave no gap. */
    private fun chipRow(c: Canvas, cl: Float, ct: Float, cr: Float, cb: Float, h: Float) {
        val gap = h * 0.015f
        val tyre = if (data.tyreFrontBar != null || data.tyreRearBar != null) "${bar(data.tyreFrontBar)} / ${bar(data.tyreRearBar)}" else "— bar"
        val chips = listOfNotNull(
            ("(!)" to tyre).takeIf { on(DashLayout.Part.TYRE) },
            ("°C" to (data.phoneTempC?.let { "$it °C" } ?: "—")).takeIf { on(DashLayout.Part.PHONE_TEMP) },
            ("V" to (data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—")).takeIf { on(DashLayout.Part.BIKE_BATTERY) },
            (FUEL_CHIP to "").takeIf { on(DashLayout.Part.FUEL) },
        )
        val cw = (cr - cl) / chips.size.coerceAtLeast(1)
        for ((i, ch) in chips.withIndex()) {
            val l = cl + i * cw
            val r = if (i == chips.size - 1) cr else l + cw - gap
            if (ch.first != FUEL_CHIP) { chip(c, l, ct, r, cb, h, ch.first, ch.second); continue }
            chip(c, l, ct, r, cb, h, "", "")
            pump?.let { d -> val s = (h * 0.045f).toInt(); val x = (l + h * 0.025f).toInt(); val y = ((ct + cb) / 2 - s / 2).toInt(); d.setBounds(x, y, x + s, y + s); d.draw(c) }
            fuelBar(c, l + h * 0.09f, ct + h * 0.025f, r - h * 0.02f, cb - h * 0.025f, h)
        }
    }

    /** Labelled tiles for [parts] across [tl]..[tr]; hidden ones leave no gap. */
    private fun tileRow(c: Canvas, tl: Float, tt: Float, tr: Float, tb: Float, h: Float, parts: List<DashLayout.Part>) {
        val gap = h * 0.015f
        val tiles = parts.mapNotNull { tile(it) }
        val tileW = (tr - tl) / tiles.size.coerceAtLeast(1)
        for ((i, t) in tiles.withIndex()) chip(c, tl + i * tileW, tt, tl + (i + 1) * tileW - gap, tb, h, t.first, t.second, t.third)
    }

    /** Icon, value and label of a tile, or null when the rider hid it. */
    private fun tile(p: DashLayout.Part): Triple<String, String, String>? = if (!on(p)) null else when (p) {
        DashLayout.Part.TRIP -> Triple("↗", data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—", labels.trip)
        DashLayout.Part.ODO -> Triple("◔", data.odoKm?.let { "≈ " + String.format(Locale.getDefault(), "%,.0f km", it) } ?: "—", labels.odo)
        DashLayout.Part.ALTITUDE -> Triple("▲", data.altitudeM?.let { String.format(Locale.getDefault(), "%,d m", it) } ?: "—", labels.alt)
        DashLayout.Part.AVG -> Triple("⌀", data.avgKmh?.let { "$it km/h" } ?: "—", labels.avg.substringBefore(" km/h"))
        DashLayout.Part.RIDE_TIME -> Triple("◷", data.rideTime ?: "—", labels.time)
        DashLayout.Part.LEAN -> Triple("∠", data.lean ?: "—", labels.lean)
        else -> null
    }

    // ---------------------------------------------------------------- NAV LIVE (map first)

    /**
     * Map first: the live map fills the left (placed over [card] by CustomDashUi), the phone's
     * telemetry runs down the right, a status strip along the bottom. Without the live map
     * (thumbnails, map hidden) the map area is a plain panel with the speed in it.
     */
    private fun nav(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        if (!mapInCard) {
            rect.set(k.l, k.t, k.r, k.b)
            fill.color = theme.cardTop; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill)
            rim(c, rect, h * 0.03f, h)
            speedBubble(c, h, k)
            mediaPill(c, h, k)
        }
        navColumn(c, w, h)
        navStrip(c, w, h)
    }

    /**
     * Map in the middle: where you are and live / average / alert speeds on the left, a big GPS
     * speedo with speed and lean arcs, trip and clock on the right.
     */
    private fun navCentre(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        if (!mapInCard) {
            rect.set(k.l, k.t, k.r, k.b)
            fill.color = theme.cardTop; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill)
            rim(c, rect, h * 0.03f, h)
            mediaPill(c, h, k)
        }
        // Left: location card.
        val l = w * 0.012f; val r = w * 0.262f
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.036f; text.color = dim
        c.drawText(labels.location, l + h * 0.01f, h * 0.215f, text)
        text.textAlign = Paint.Align.RIGHT; text.color = data.gpsColor.takeIf { it != 0 } ?: gold
        c.drawText(ellipsize(data.gps ?: "", (r - l) * 0.5f), r - h * 0.01f, h * 0.215f, text)
        navCard(c, l, h * 0.24f, r, h * 0.6f, h)
        val pad = h * 0.035f
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.032f; text.color = dim
        c.drawText(labels.here, l + pad, h * 0.3f, text)
        text.typeface = bold; text.textSize = h * 0.055f; text.color = white
        c.drawText(ellipsize(data.placeRoad ?: labels.finding, r - l - 2 * pad), l + pad, h * 0.385f, text)
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.038f; text.color = dim
        data.placeArea?.let { c.drawText(ellipsize(it, r - l - 2 * pad), l + pad, h * 0.44f, text) }
        text.typeface = bold; text.textSize = h * 0.038f; text.color = second
        val dir = listOfNotNull(data.heading?.let { "${labels.heading} $it" }, data.altitudeM?.let { "▲ $it m" }).joinToString("  ·  ")
        c.drawText(ellipsize(dir, r - l - 2 * pad), l + pad, h * 0.56f, text)
        data.fuelStop?.let { banner(c, l, h * 0.46f, r, h * 0.6f, h, it) }

        // Left: live · average · alert.
        navCard(c, l, h * 0.625f, r, h * 0.985f, h)
        val cw = (r - l) / 3
        val cols = listOf(labels.live to (data.speedKmh?.toString() ?: "—"), labels.avgShort to (data.avgKmh?.toString() ?: "—"))
        for ((i, col) in cols.withIndex()) {
            val x = l + cw * i + cw / 2
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.032f; text.color = dim
            c.drawText(col.first, x, h * 0.7f, text)
            text.typeface = bold; text.textSize = h * 0.095f; text.color = if (i == 0) gold else second
            c.drawText(col.second, x, h * 0.83f, text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.028f; text.color = dim
            c.drawText("km/h", x, h * 0.9f, text)
        }
        val lx = l + cw * 2.5f
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.032f; text.color = dim
        c.drawText(labels.limit, lx, h * 0.7f, text)
        stroke.color = if (alertKmh > 0) red else 0xFF2A2F33.toInt(); stroke.strokeWidth = h * 0.012f
        c.drawCircle(lx, h * 0.81f, h * 0.075f, stroke)
        text.typeface = bold; text.textSize = h * (if (alertKmh > 0) 0.05f else 0.035f); text.color = white
        c.drawText(if (alertKmh > 0) "$alertKmh" else "N/A", lx, h * 0.81f + text.textSize * 0.35f, text)

        // Right: big GPS speed, speed arc (left) and lean arc (right), trip and clock.
        val rl = w * 0.778f; val rr = w * 0.99f; val rc = (rl + rr) / 2
        val v = data.speedKmh
        val over = alertKmh > 0 && (v ?: 0) >= alertKmh
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.042f; text.color = gold
        c.drawText(labels.speedGps, rc, h * 0.23f, text)
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.2f; text.color = if (over) red else white
        c.drawText(v?.toString() ?: "—", rc, h * 0.43f, text)
        text.typeface = bold; text.textSize = h * 0.045f; text.color = gold
        c.drawText("km/h", rc, h * 0.5f, text)
        val acy = h * 0.69f; val ar = h * 0.13f
        rect.set(rc - ar, acy - ar, rc + ar, acy + ar)
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeWidth = h * 0.028f
        stroke.color = 0xFF22272B.toInt()
        c.drawArc(rect, 140f, 80f, false, stroke); c.drawArc(rect, 40f, -80f, false, stroke)
        val sf = ((v ?: 0) / MAX_KMH.toFloat()).coerceIn(0f, 1f)
        stroke.color = if (over || (v ?: 0) >= redKmh) red else gold
        c.drawArc(rect, 140f, 80f * sf, false, stroke)
        val lf = ((data.leanDeg?.let { kotlin.math.abs(it) } ?: 0f) / 60f).coerceIn(0f, 1f)
        stroke.color = 0xFFFFB04A.toInt()
        c.drawArc(rect, 40f, -80f * lf, false, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.026f; text.color = dim
        c.drawText(data.leanDeg?.let { CustomDashUi.leanText(it) } ?: "", rc + ar * 0.62f, acy + h * 0.01f, text)
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.03f; text.color = gold
        c.drawText(labels.trip, rl, h * 0.9f, text)
        text.textSize = h * 0.065f; text.color = white
        c.drawText(data.tripKm?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—", rl, h * 0.975f, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(data.clock, rr, h * 0.975f, text)
    }

    // ---------------------------------------------------------------- NAV PRO (turn by turn)

    /**
     * Turn-by-turn: guidance down the left (where you are, the next turn, the destination), the map
     * in the middle with the next turn along its bottom, GPS speed + lean + ride numbers on the
     * right, and the bike row (photo, phone temp, bike battery, fuel, tyres, ride mode) below.
     */
    private fun navProLayout(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        if (!mapInCard) {
            rect.set(k.l, k.t, k.r, k.b)
            fill.color = theme.cardTop; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill)
            rim(c, rect, h * 0.03f, h)
            turnPill(c, h, k)
        }
        val l = w * 0.008f; val r = w * 0.178f; val pad = h * 0.022f
        // Header.
        navCard(c, l, h * 0.16f, r, h * 0.25f, h)
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.05f; text.color = gold
        c.drawText("➤", l + pad, h * 0.22f, text)
        text.color = white; text.textSize = h * 0.038f
        c.drawText(ellipsize(labels.navigation, r - l - h * 0.09f), l + h * 0.075f, h * 0.218f, text)
        // Where you are.
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.028f; text.color = dim
        c.drawText(ellipsize(labels.here, r - l - 2 * pad), l + pad, h * 0.29f, text)
        text.typeface = bold; text.textSize = h * 0.034f; text.color = white
        c.drawText(ellipsize(data.placeRoad ?: labels.finding, r - l - 2 * pad), l + pad, h * 0.33f, text)
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.028f; text.color = dim
        data.placeArea?.let { c.drawText(ellipsize(it, r - l - 2 * pad), l + pad, h * 0.365f, text) }
        // Next turn.
        navCard(c, l, h * 0.39f, r, h * 0.575f, h)
        val turn = data.navTurnText
        if (turn != null) {
            text.typeface = bold; text.textSize = h * 0.075f; text.color = gold
            c.drawText(data.navArrow ?: "↑", l + pad, h * 0.465f, text)
            text.textSize = h * 0.048f
            c.drawText(ellipsize(data.navTurnDist ?: "", r - l - h * 0.09f), l + h * 0.08f, h * 0.46f, text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.03f; text.color = white
            val lines = wrap2(turn, r - l - 2 * pad)
            for ((i, line) in lines.withIndex()) c.drawText(line, l + pad, h * (0.51f + i * 0.037f), text)
        } else {
            text.typeface = bold; text.textSize = h * 0.032f; text.color = white
            for ((i, line) in wrap2(labels.noDestination, r - l - 2 * pad).withIndex()) c.drawText(line, l + pad, h * (0.45f + i * 0.04f), text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.026f; text.color = dim
            for ((i, line) in wrap2(labels.pickDestination, r - l - 2 * pad).withIndex()) c.drawText(line, l + pad, h * (0.52f + i * 0.034f), text)
        }
        // Destination, distance left, time left.
        navCard(c, l, h * 0.59f, r, h * 0.755f, h)
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.028f; text.color = dim
        c.drawText("⚑ " + labels.destination, l + pad, h * 0.63f, text)
        text.typeface = bold; text.textSize = h * 0.036f; text.color = white
        c.drawText(ellipsize(data.navDest ?: "—", r - l - 2 * pad), l + pad, h * 0.67f, text)
        text.textSize = h * 0.032f; text.color = second
        c.drawText(ellipsize(listOfNotNull(data.navRemain, data.navEta).joinToString("  ·  ").ifEmpty { "—" }, r - l - 2 * pad), l + pad, h * 0.725f, text)

        navProRight(c, w, h)
        navProBottom(c, w, h)
    }

    /** Next turn along the bottom of the map: arrow, distance, instruction | distance and time left. */
    private fun turnPill(c: Canvas, h: Float, k: Card) {
        val t = data.navTurnText ?: return
        val l = k.l + h * 0.03f; val r = k.r - h * 0.03f
        val b = k.b - h * 0.025f; val top = b - h * 0.095f
        rect.set(l, top, r, b)
        fill.color = 0xE6101418.toInt(); c.drawRoundRect(rect, h * 0.035f, h * 0.035f, fill)
        val y = (top + b) / 2 + h * 0.018f
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.06f; text.color = gold
        c.drawText(data.navArrow ?: "↑", l + h * 0.025f, y + h * 0.006f, text)
        text.textSize = h * 0.045f; text.color = white
        val dist = data.navTurnDist ?: ""
        c.drawText(dist, l + h * 0.1f, y, text)
        val right = listOfNotNull(data.navRemain, data.navEta).joinToString("  ·  ")
        text.textAlign = Paint.Align.RIGHT; text.typeface = bold; text.textSize = h * 0.038f; text.color = second
        c.drawText(right, r - h * 0.025f, y, text)
        val rightW = text.measureText(right)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.036f; text.color = white
        text.typeface = bold
        val x = l + h * 0.1f + run { text.textSize = h * 0.045f; text.measureText(dist) } + h * 0.025f
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.036f
        c.drawText(ellipsize(t, r - rightW - x - h * 0.06f), x, y, text)
    }

    /** Right column: GPS speed, lean gauge, trip · elapsed · heading · GPS accuracy (or grade). */
    private fun navProRight(c: Canvas, w: Float, h: Float) {
        val l = w * 0.793f; val r = w * 0.992f; val rc = (l + r) / 2
        navCard(c, l, h * 0.16f, r, h * 0.755f, h)
        val v = data.speedKmh
        val over = alertKmh > 0 && (v ?: 0) >= alertKmh
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.03f; text.color = dim
        c.drawText(labels.speedGps, l + h * 0.03f, h * 0.205f, text)
        text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic; text.textSize = h * 0.14f; text.color = if (over) red else white
        c.drawText(v?.toString() ?: "—", rc, h * 0.335f, text)
        text.typeface = boldItalic; text.textSize = h * 0.04f; text.color = gold
        c.drawText("km/h", rc, h * 0.38f, text)
        // Lean gauge: half ring green → yellow → red, filled to the lean.
        val gr = h * 0.085f; val gcy = h * 0.49f
        rect.set(rc - gr, gcy - gr, rc + gr, gcy + gr)
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeWidth = h * 0.02f
        stroke.color = 0xFF22272B.toInt(); c.drawArc(rect, 180f, 180f, false, stroke)
        val lf = ((data.leanDeg?.let { kotlin.math.abs(it) } ?: 0f) / 60f).coerceIn(0f, 1f)
        if (lf > 0f) {
            stroke.shader = SweepGradient(rc, gcy, intArrayOf(0xFF3DDC5A.toInt(), 0xFFF2E03A.toInt(), red, 0xFF3DDC5A.toInt()), floatArrayOf(0.5f, 0.75f, 1f, 1f))
            c.drawArc(rect, 180f, 180f * lf, false, stroke)
            stroke.shader = null
        }
        stroke.strokeCap = Paint.Cap.ROUND
        text.typeface = boldItalic; text.textSize = h * 0.04f; text.color = white
        c.drawText(data.leanDeg?.let { CustomDashUi.leanText(it) } ?: "—", rc, gcy - h * 0.005f, text)
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.022f; text.color = dim
        c.drawText(labels.lean, rc, gcy + h * 0.03f, text)
        // Ride numbers.
        val rows = listOf(
            labels.trip to (data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—"),
            labels.time to (data.rideTime ?: "—"),
            labels.heading to (data.heading ?: "—"),
            if (data.gradePct != null) labels.grade to String.format(Locale.getDefault(), "%.0f%%", data.gradePct)
            else labels.gpsAcc to (data.gpsAccM?.let { "±$it m" } ?: "—"),
        )
        for ((i, row) in rows.withIndex()) {
            val y = h * (0.575f + i * 0.05f)
            if (i > 0) { stroke.color = 0xFF22272B.toInt(); stroke.strokeWidth = 1f; c.drawLine(l + h * 0.02f, y - h * 0.035f, r - h * 0.02f, y - h * 0.035f, stroke) }
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.026f; text.color = dim
            c.drawText(ellipsize(row.first, (r - l) * 0.45f), l + h * 0.025f, y, text)
            text.textAlign = Paint.Align.RIGHT; text.typeface = bold; text.textSize = h * 0.034f; text.color = white
            c.drawText(ellipsize(row.second, (r - l) * 0.5f), r - h * 0.025f, y, text)
        }
    }

    /** Bike row: photo, then phone temp, bike battery, fuel, tyres, ride mode, each with a level bar. */
    private fun navProBottom(c: Canvas, w: Float, h: Float) {
        val t = h * 0.775f; val b = h * 0.99f
        (art ?: data.photo)?.takeIf { on(DashLayout.Part.ART) }?.let {
            backdrop(c, it, 0f, t, w * 0.175f, b, intArrayOf(0, 255, 255, 0), floatArrayOf(0f, 0.08f, 0.92f, 1f), floatArrayOf(0f, 0.05f, 0.9f, 1f))
        }
        val l = w * 0.185f; val r = w * 0.992f
        data.fuelStop?.let { banner(c, l, t, r, b, h, it); return }
        navCard(c, l, t, r, b, h)
        val tyre = if (data.tyreFrontBar != null || data.tyreRearBar != null) "${bar(data.tyreFrontBar)} / ${bar(data.tyreRearBar)}" else "— bar"
        val mode = labels.tabs.getOrNull(when (accent) { DashLayout.ACCENTS[2] -> 1; DashLayout.ACCENTS[1] -> 2; else -> 0 }) ?: ""
        data class Cell(val label: String, val value: String, val level: Float?, val warn: Boolean = false)
        val cells = listOfNotNull(
            Cell(labels.phone, data.phoneTempC?.let { "$it °C" } ?: "—", data.phoneTempC?.let { (it - 20) / 30f }, data.phoneHot).takeIf { on(DashLayout.Part.PHONE_TEMP) },
            Cell(labels.battery, data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—", data.bikeVolts?.let { (it - 11.5f) / 3f }).takeIf { on(DashLayout.Part.BIKE_BATTERY) },
            Cell(labels.fuel, data.fuel?.let { "${(it * 100).roundToInt()} %" } ?: "—", data.fuel, (data.fuel ?: 1f) < 0.15f).takeIf { on(DashLayout.Part.FUEL) },
            Cell(labels.tyre, tyre, null).takeIf { on(DashLayout.Part.TYRE) },
            Cell(labels.rideMode, mode, null).takeIf { on(DashLayout.Part.MODE_TABS) },
        )
        val cw = (r - l) / cells.size.coerceAtLeast(1)
        for ((i, cell) in cells.withIndex()) {
            val x = l + cw * i
            if (i > 0) { stroke.color = 0xFF2A2F33.toInt(); stroke.strokeWidth = 1f; c.drawLine(x, t + h * 0.03f, x, b - h * 0.03f, stroke) }
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.028f; text.color = dim
            c.drawText(ellipsize(cell.label, cw - h * 0.05f), x + h * 0.025f, t + h * 0.06f, text)
            text.typeface = boldItalic; text.textSize = h * (if (cell.label == labels.rideMode) 0.06f else 0.055f)
            text.color = when { cell.warn -> red; cell.label == labels.rideMode -> gold; else -> white }
            c.drawText(ellipsize(cell.value, cw - h * 0.05f), x + h * 0.025f, t + h * 0.13f, text)
            cell.level?.let { f ->
                bar(c, x + h * 0.025f, t + h * 0.16f, x + cw - h * 0.025f, t + h * 0.18f, f, if (cell.warn) red else gold)
            }
        }
    }

    /** [s] in at most two lines of [maxW] (the second ellipsized), split at spaces where possible. */
    private fun wrap2(s: String, maxW: Float): List<String> {
        if (text.measureText(s) <= maxW) return listOf(s)
        var cut = s.length
        while (cut > 1 && text.measureText(s, 0, cut) > maxW) cut--
        val space = s.lastIndexOf(' ', cut)
        if (space > cut / 2) cut = space
        return listOf(s.substring(0, cut).trimEnd(), ellipsize(s.substring(cut).trimStart(), maxW))
    }

    /** Big round speed readout over the map's bottom-left corner. */
    private fun speedBubble(c: Canvas, h: Float, k: Card) {
        val r = h * 0.1f
        val cx = k.l + r + h * 0.03f; val cy = k.b - r - h * 0.03f
        fill.color = 0xE6101418.toInt(); c.drawCircle(cx, cy, r, fill)
        stroke.color = gold; stroke.strokeWidth = h * 0.006f; c.drawCircle(cx, cy, r, stroke)
        val v = data.speedKmh
        val over = alertKmh > 0 && (v ?: 0) >= alertKmh
        text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic; text.textSize = h * 0.09f; text.color = if (over) red else white
        c.drawText(v?.toString() ?: "—", cx, cy + h * 0.02f, text)
        text.typeface = bold; text.textSize = h * 0.028f; text.color = dim
        c.drawText("km/h", cx, cy + h * 0.06f, text)
    }

    /** Now playing, at the bottom of the map beside the speed: "⏮ ⏸ ⏭" hint for the handlebar, then the track. */
    private fun mediaPill(c: Canvas, h: Float, k: Card) {
        val m = data.media ?: return
        val l = k.l + if (navCenter) h * 0.03f else h * 0.26f  // Nav Live keeps the corner for the speed
        val r = k.r - h * 0.03f
        val b = k.b - h * 0.035f; val t = b - h * 0.085f
        if (r - l < h * 0.4f) return
        rect.set(l, t, r, b)
        fill.color = 0xE6101418.toInt(); c.drawRoundRect(rect, h * 0.04f, h * 0.04f, fill)
        stroke.color = 0xFF2A2F33.toInt(); stroke.strokeWidth = h * 0.004f; c.drawRoundRect(rect, h * 0.04f, h * 0.04f, stroke)
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.04f; text.color = gold
        val keys = "◀◀  ▶▶"
        val y = (t + b) / 2 + h * 0.014f
        c.drawText(keys, l + h * 0.025f, y, text)
        val x = l + h * 0.025f + text.measureText(keys) + h * 0.03f
        text.color = white
        c.drawText(ellipsize(m, r - x - h * 0.025f), x, y, text)
    }

    /** Right column: phone battery, its temperature, then Wi-Fi · storage · cellular · air pressure. */
    private fun navColumn(c: Canvas, w: Float, h: Float) {
        val l = w * 0.74f; val r = w * 0.99f
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.038f; text.color = dim
        c.drawText(labels.phone.uppercase(Locale.getDefault()), l, h * 0.215f, text)
        text.textAlign = Paint.Align.RIGHT; text.color = gold
        c.drawText(if (data.phoneCharging) labels.charging else labels.onBattery, r, h * 0.215f, text)

        // Battery card.
        navCard(c, l, h * 0.245f, r, h * 0.455f, h)
        val pct = data.phoneBattery
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.1f
        text.color = if (pct != null && pct <= 15) red else gold
        c.drawText(pct?.let { "$it%" } ?: "—", l + h * 0.03f, h * 0.36f, text)
        bar(c, l + (r - l) * 0.55f, h * 0.29f, r - h * 0.03f, h * 0.335f, (pct ?: 0) / 100f, if (pct != null && pct <= 15) red else gold)
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.03f; text.color = dim
        c.drawText(labels.phoneBattery, l + h * 0.03f, h * 0.425f, text)
        text.textAlign = Paint.Align.RIGHT; text.color = white
        data.phoneMv?.let { c.drawText("$it mV", r - h * 0.03f, h * 0.425f, text) }

        // Temperature card.
        if (on(DashLayout.Part.PHONE_TEMP)) {
            navCard(c, l, h * 0.47f, r, h * 0.665f, h)
            val t = data.phoneTempC
            text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.09f
            text.color = if (data.phoneHot) red else gold
            c.drawText(t?.let { "$it°C" } ?: "—", l + h * 0.03f, h * 0.575f, text)
            bar(c, l + (r - l) * 0.55f, h * 0.515f, r - h * 0.03f, h * 0.56f, ((t ?: 20) - 20) / 30f, if (data.phoneHot) red else gold)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.03f; text.color = dim
            c.drawText(labels.batteryTemp, l + h * 0.03f, h * 0.64f, text)
            text.textAlign = Paint.Align.RIGHT; text.color = if (data.phoneHot) red else gold
            c.drawText(if (data.phoneHot) labels.hot else labels.normal, r - h * 0.03f, h * 0.64f, text)
        }

        // 2 × 2 small readouts.
        val cells = listOf(
            (data.wifiDbm?.let { "$it dBm" } ?: "—") to "WI-FI",
            (data.storageFreePct?.let { "$it%" } ?: "—") to labels.storage,
            (when (data.cellReady) { true -> labels.ready; false -> labels.offline; null -> "—" }) to labels.cell,
            (data.pressureHpa?.let { String.format(Locale.getDefault(), "%.1f hPa", it) } ?: data.altitudeM?.let { "$it m" } ?: "—") to (if (data.pressureHpa != null) labels.pressure else labels.alt),
        )
        val cw = (r - l) / 2
        for ((i, cell) in cells.withIndex()) {
            val x = l + cw * (i % 2) + cw / 2
            val y = h * (0.725f + (i / 2) * 0.075f)
            text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.042f
            text.color = if (cell.first == labels.ready) gold else white
            c.drawText(ellipsize(cell.first, cw - h * 0.01f), x, y, text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.026f; text.color = dim
            c.drawText(ellipsize(cell.second, cw - h * 0.01f), x, y + h * 0.03f, text)
        }
    }

    /** Bottom status strip: GPS · phone · trip · range · bike battery, label left and value right in each cell. */
    private fun navStrip(c: Canvas, w: Float, h: Float) {
        val t = h * 0.865f; val b = h * 0.99f
        data.fuelStop?.let { banner(c, w * 0.01f, t, w * 0.99f, b, h, it); return }
        fill.color = theme.cardBottom; c.drawRect(0f, t, w, b, fill)
        val cells = listOf(
            "GPS" to (data.gps ?: "—"),
            labels.phone to (data.phoneBattery?.let { "$it%" } ?: "—"),
            labels.trip to (data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—"),
            labels.rangeShort to (data.rangeKm?.let { "≈ $it km" } ?: "—"),
            labels.battery to (data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—"),
        )
        val cw = w / cells.size
        val y = (t + b) / 2 + h * 0.016f
        for ((i, cell) in cells.withIndex()) {
            val l = cw * i
            if (i > 0) { stroke.color = 0xFF2A2F33.toInt(); stroke.strokeWidth = 1f; c.drawLine(l, t + h * 0.025f, l, b - h * 0.025f, stroke) }
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.03f; text.color = dim; text.textAlign = Paint.Align.LEFT
            val lab = ellipsize(cell.first, cw * 0.42f)
            c.drawText(lab, l + h * 0.03f, y, text)
            text.typeface = bold; text.textSize = h * 0.042f; text.color = gold; text.textAlign = Paint.Align.RIGHT
            c.drawText(ellipsize(cell.second, cw * 0.55f), l + cw - h * 0.03f, y, text)
        }
    }

    private fun navCard(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float) {
        rect.set(l, t, r, b)
        fill.color = theme.cardTop; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill)
        stroke.color = 0xFF2A2F33.toInt(); stroke.strokeWidth = h * 0.004f; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, stroke)
    }

    /** Horizontal level bar, [f] 0..1 filled in [color]. */
    private fun bar(c: Canvas, l: Float, t: Float, r: Float, b: Float, f: Float, color: Int) {
        val rad = (b - t) / 3
        rect.set(l, t, r, b); fill.color = 0xFF22272B.toInt(); c.drawRoundRect(rect, rad, rad, fill)
        rect.set(l, t, l + (r - l) * f.coerceIn(0f, 1f), b); fill.color = color; c.drawRoundRect(rect, rad, rad, fill)
    }

    // ---------------------------------------------------------------- SUPERCAR layout

    /**
     * Supercar editions (Midnight GT, Papaya, Grand Tourer): bike art with the ride modes under it
     * on the left, a compact ring in the middle, the scenery card with the ride stats on the right,
     * then the chip row and a tile row split by the edition's emblem.
     */
    private fun supercar(c: Canvas, w: Float, h: Float) {
        (art ?: data.photo)?.takeIf { on(DashLayout.Part.ART) }?.let {
            val (a, st) = solidThenDim(0.75f)
            backdrop(c, it, 0f, h * 0.165f, w * 0.36f, h * 0.69f, a, st, LEFT_FEATHER)
        }
        if (on(DashLayout.Part.MODE_TABS)) {
            text.textAlign = Paint.Align.LEFT; text.typeface = boldItalic; text.textSize = h * 0.036f; text.color = white
            c.drawText(labels.rideMode, w * 0.025f, h * 0.585f, text)
            tabs(c, h, w * 0.02f, h * 0.6f, w * 0.31f, fromLeft = true)
        }
        gauge(c, w, h, cx = w / 2, cy = h * 0.42f, rad = h * 0.255f, compact = true)
        rightPanel(c, w, h)
        chipRow(c, w * 0.02f, h * 0.715f, w * 0.98f, h * 0.805f, h)
        val tt = h * 0.835f; val tb = h * 0.975f
        data.fuelStop?.let { banner(c, w * 0.02f, tt, w * 0.98f, tb, h, it); return }
        tileRow(c, w * 0.02f, tt, w * 0.425f, tb, h, listOf(DashLayout.Part.TRIP, DashLayout.Part.ODO))
        emblem(c, w * 0.435f, tt, w * 0.565f, tb, h)
        tileRow(c, w * 0.575f, tt, w * 0.98f + h * 0.015f, tb, h, listOf(DashLayout.Part.ALTITUDE, DashLayout.Part.LEAN))
    }

    /** The edition's badge between the tile groups: three slanted bars in the template colours. */
    private fun emblem(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float) {
        emblemArt?.let {
            // The template's own little picture, feathered at the sides into the background.
            backdrop(c, it, l, t, r, b, intArrayOf(0, 255, 255, 0), floatArrayOf(0f, 0.15f, 0.85f, 1f), floatArrayOf(0f, 0.18f, 0.82f, 1f))
            return
        }
        val bw = (r - l) * 0.16f; val slant = h * 0.035f
        val x0 = (l + r) / 2 - bw * 1.5f - slant / 2
        val bt = t + (b - t) * 0.22f; val bb = b - (b - t) * 0.22f
        for ((i, col) in listOf(gold, lighten(gold), second).withIndex()) {
            val x = x0 + i * bw * 1.05f
            path.reset()
            path.moveTo(x + slant, bt); path.lineTo(x + bw + slant, bt)
            path.lineTo(x + bw, bb); path.lineTo(x, bb); path.close()
            fill.color = col; c.drawPath(path, fill)
        }
        stroke.shader = LinearGradient(l, 0f, r, 0f, intArrayOf(0x00000000, gold, 0x00000000), null, Shader.TileMode.CLAMP)
        stroke.strokeWidth = h * 0.004f
        c.drawLine(l, b - h * 0.008f, r, b - h * 0.008f, stroke)
        stroke.shader = null
    }

    /**
     * [bmp] as a frameless background over [l]..[r] × [t]..[b], no card or rim. Top to bottom its
     * strength follows [alphas] at [stops] (fractions of the height): 255 over empty space,
     * [BACKDROP_DIM] where text sits. [hStops] = where the sides feather in (0→full) and out (full→0).
     */
    private fun backdrop(c: Canvas, bmp: Bitmap, l: Float, t: Float, r: Float, b: Float, alphas: IntArray, stops: FloatArray, hStops: FloatArray) {
        rect.set(l, t, r, b)
        val layer = c.saveLayer(rect, null)
        cover(c, bmp, 0f)
        // Text blocks can crowd the stops together; gradients need them non-decreasing.
        val st = stops.copyOf().also { for (i in 1 until it.size) it[i] = maxOf(it[i], it[i - 1]) }
        mask.shader = LinearGradient(0f, t, 0f, b, IntArray(alphas.size) { Color.argb(alphas[it], 0, 0, 0) }, st, Shader.TileMode.CLAMP)
        c.drawRect(rect, mask)
        mask.shader = LinearGradient(l, 0f, r, 0f, intArrayOf(0, Color.BLACK, Color.BLACK, 0), hStops, Shader.TileMode.CLAMP)
        c.drawRect(rect, mask)
        mask.shader = null
        c.restoreToCount(layer)
    }

    /** Vertical stops for art that is full strength down to [solidTo] (fraction), then dim, then gone. */
    private fun solidThenDim(solidTo: Float): Pair<IntArray, FloatArray> {
        val s = solidTo.coerceIn(0.12f, 0.9f)
        return intArrayOf(0, 255, 255, BACKDROP_DIM, BACKDROP_DIM, 0) to floatArrayOf(0f, 0.12f, s, minOf(s + 0.1f, 0.9f), 0.9f, 1f)
    }

    /** [bmp] scaled to cover [rect] (centre crop), clipped to its rounded corners. */
    private fun cover(c: Canvas, bmp: Bitmap, rad: Float) {
        val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(rect.width() / bmp.width, rect.height() / bmp.height)
        sh.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(rect.centerX() - bmp.width * scale / 2, rect.centerY() - bmp.height * scale / 2)
        })
        fill.shader = sh
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
    }

    /**
     * Halo under the speed arc: stronger the faster you go, turning red over the last
     * [GLOW_WARN_KMH] before the red zone. Leaves [stroke] at the arc's width for the arc itself.
     */
    private inline fun speedGlow(c: Canvas, h: Float, speed: Int, draw: () -> Unit) {
        val f = speed / MAX_KMH.toFloat()
        val warm = ((speed - (redKmh - GLOW_WARN_KMH)) / GLOW_WARN_KMH.toFloat()).coerceIn(0f, 1f)
        val col = androidx.core.graphics.ColorUtils.blendARGB(gold, red, warm) and 0x00FFFFFF
        val width = stroke.strokeWidth
        stroke.shader = null
        for (i in 3 downTo 1) {
            stroke.color = col or ((0x14 + 0x40 * f / i).toInt() shl 24)
            stroke.strokeWidth = width + h * 0.022f * i
            draw()
        }
        stroke.strokeWidth = width
    }

    /**
     * Welcome over the first [INTRO_MS]: the bike's name lights up in the theme colour above a
     * line of light that opens from the centre, then the dash fades in under it.
     */
    private fun welcome(c: Canvas, w: Float, h: Float, t: Long) {
        val out = ((t - (INTRO_MS - 500L)) / 500f).coerceIn(0f, 1f)
        fill.color = Color.argb((255 * (1f - out)).toInt(), 5, 5, 6)
        c.drawRect(0f, 0f, w, h, fill)
        val a = (min(t / 600f, 1f) * (1f - out) * 255).toInt()
        val name = data.bikeName.ifBlank { "Zontes" }.uppercase(Locale.ROOT)
        text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic; text.textSize = h * 0.13f
        text.color = (gold and 0x00FFFFFF) or (a shl 24)
        text.setShadowLayer(h * 0.05f, 0f, 0f, (gold and 0x00FFFFFF) or ((a * 0.8f).toInt() shl 24))
        c.drawText(ellipsize(name, w * 0.9f), w / 2, h * 0.52f, text)
        text.clearShadowLayer()
        val half = w * 0.32f * min(t / 900f, 1f)
        if (half > 1f) {
            val y = h * 0.6f
            stroke.shader = LinearGradient(w / 2 - half, y, w / 2 + half, y,
                intArrayOf(0x00000000, (lighten(gold) and 0x00FFFFFF) or (a shl 24), 0x00000000), null, Shader.TileMode.CLAMP)
            stroke.strokeWidth = h * 0.008f
            c.drawLine(w / 2 - half, y, w / 2 + half, y, stroke)
            stroke.shader = null
        }
        fill.color = Color.BLACK
    }

    /** Strokes whatever [draw] draws in the theme colour, three faint wide passes then a sharp line. */
    private inline fun glow(c: Canvas, h: Float, draw: () -> Unit) {
        for (i in 3 downTo 1) {
            stroke.color = (gold and 0x00FFFFFF) or 0x22000000; stroke.strokeWidth = h * 0.008f * i * 2
            draw()
        }
        stroke.color = lighten(gold); stroke.strokeWidth = h * 0.005f
        draw()
    }

    /** One cheap drawn motif per template, behind everything else. */
    private fun motif(c: Canvas, w: Float, h: Float) {
        when (style) {
            DashLayout.Style.ADVENTURE, DashLayout.Style.TOURING -> mountains(c, w, h)
            DashLayout.Style.CYBER, DashLayout.Style.NEONCITY -> {
                // Scan lines plus a synthwave floor grid under the gauge.
                stroke.color = 0x1422E5FF; stroke.strokeWidth = 1f
                var y = 0f
                while (y < h) { c.drawLine(0f, y, w, y, stroke); y += h * 0.02f }
                stroke.color = 0x30FF2BD6; stroke.strokeWidth = h * 0.003f
                val hor = h * 0.86f
                for (i in -8..8) c.drawLine(w / 2 + i * w * 0.02f, hor, w / 2 + i * w * 0.09f, h, stroke)
                for (k in 0..3) { val yy = hor + (h - hor) * (k * k) / 9f; c.drawLine(0f, yy, w, yy, stroke) }
            }
            DashLayout.Style.RACING -> {
                // Chequered flag fading out to the left, top right corner.
                val sq = h * 0.035f
                for (row in 0 until 4) for (col in 0 until 10) {
                    if ((row + col) % 2 == 1) continue
                    fill.color = (0xFFFFFF) or ((0x30 * col / 10) shl 24)
                    val x = w * 0.61f + col * sq
                    c.drawRect(x, row * sq, x + sq, (row + 1) * sq, fill)
                }
                // Speed streaks behind the side cards.
                stroke.color = 0x40FF2A2A; stroke.strokeWidth = h * 0.006f
                for (i in 0..4) { val y = h * (0.62f + i * 0.05f); c.drawLine(0f, y + h * 0.1f, w * 0.2f, y, stroke); c.drawLine(w, y + h * 0.1f, w * 0.8f, y, stroke) }
            }
            DashLayout.Style.NEON -> {
                // Drifting sakura petals, fixed positions so the frame never flickers.
                fill.color = 0x66FF8AD8
                for (i in 0 until 18) {
                    val px = w * ((i * 0.618f) % 1f)
                    val py = h * ((i * 0.377f + 0.1f) % 1f)
                    c.save(); c.rotate(i * 40f, px, py)
                    c.drawOval(px - h * 0.012f, py - h * 0.006f, px + h * 0.012f, py + h * 0.006f, fill)
                    c.restore()
                }
                text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.1f; text.color = 0xFFD9365E.toInt()
                if (!mapInCard) c.drawText("侍", w * 0.718f, h * 0.31f, text)  // the live map sits there
            }
            DashLayout.Style.DRAGON -> {
                // Warm cloud bands along the bottom corners.
                for ((x, dir) in listOf(0f to 1f, w to -1f)) {
                    fill.shader = RadialGradient(x, h, h * 0.45f, 0x55B5652B, 0x00000000, Shader.TileMode.CLAMP)
                    c.drawCircle(x + dir * h * 0.05f, h, h * 0.45f, fill)
                }
                fill.shader = null
            }
            DashLayout.Style.ANIMAL -> {
                // A faint trail of paw prints in the bottom corners, fixed positions.
                fill.color = (theme.main and 0x00FFFFFF) or 0x22000000
                for ((x, y, rot) in listOf(Triple(0.05f, 0.93f, -20f), Triple(0.11f, 0.84f, -10f), Triple(0.17f, 0.95f, -25f),
                    Triple(0.84f, 0.94f, 20f), Triple(0.90f, 0.85f, 15f), Triple(0.96f, 0.95f, 25f))) {
                    val px = w * x; val py = h * y; val u = h * 0.022f
                    c.save(); c.rotate(rot, px, py)
                    c.drawOval(px - u, py - u * 0.7f, px + u, py + u * 0.9f, fill)
                    for (i in 0 until 4) {
                        val a = Math.toRadians((-150 + i * 40).toDouble())
                        c.drawCircle(px + cos(a).toFloat() * u * 1.6f, py + sin(a).toFloat() * u * 1.5f, u * 0.42f, fill)
                    }
                    c.restore()
                }
            }
            DashLayout.Style.ANIME -> {
                // Sakura petals drifting over the panel and blue wave crests in the bottom corners, fixed positions.
                fill.color = 0x55FF8AD8
                for (i in 0 until 22) {
                    val px = w * ((i * 0.618f + 0.05f) % 1f)
                    val py = h * ((i * 0.377f + 0.12f) % 1f)
                    c.save(); c.rotate(i * 37f, px, py)
                    c.drawOval(px - h * 0.012f, py - h * 0.006f, px + h * 0.012f, py + h * 0.006f, fill)
                    c.restore()
                }
                stroke.color = 0x664FA8FF; stroke.strokeWidth = h * 0.006f
                for ((x0, dir) in listOf(0f to 1f, w to -1f)) for (k in 0..2) {
                    val r0 = h * (0.06f + k * 0.035f)
                    rect.set(x0 + dir * w * 0.03f - r0, h - r0 * 0.8f, x0 + dir * w * 0.03f + r0, h + r0 * 1.2f)
                    c.drawArc(rect, 180f, 180f, false, stroke)
                }
            }
            DashLayout.Style.SPACE -> {
                // Violet nebula top centre, a fixed starfield, and a glowing horizon under the tiles.
                fill.shader = RadialGradient(w * 0.55f, h * 0.1f, h * 0.7f, 0x40B45CFF, 0x00000000, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
                for (i in 0 until 70) {
                    fill.color = Color.argb(40 + (i * 37) % 150, 255, 255, 255)
                    c.drawCircle(w * ((i * 0.618f) % 1f), h * ((i * 0.381f + 0.05f) % 0.8f), h * (0.0025f + (i % 3) * 0.0015f), fill)
                }
                stroke.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x002ED3FF, 0x992ED3FF.toInt(), 0x99B45CFF.toInt(), 0x00B45CFF), null, Shader.TileMode.CLAMP)
                stroke.strokeWidth = h * 0.004f
                c.drawLine(w * 0.02f, h * 0.83f, w * 0.98f, h * 0.83f, stroke)
                stroke.shader = null
            }
            DashLayout.Style.GOLD -> {
                // Angular gold chevrons in the bottom corners, like the mockup's carbon frame.
                stroke.color = (gold and 0x00FFFFFF) or 0x66000000; stroke.strokeWidth = h * 0.006f
                for (k in 0..2) {
                    val o = k * h * 0.035f
                    c.drawLine(w * 0.01f, h * 0.80f + o, w * 0.06f + o, h * 0.98f, stroke)
                    c.drawLine(w * 0.99f, h * 0.80f + o, w * 0.94f - o, h * 0.98f, stroke)
                }
            }
            DashLayout.Style.ROSSO -> {
                // Italian tricolour, three slanted bars centred in the top bar.
                val bw = w * 0.035f; val t = h * 0.035f; val b = h * 0.12f; val x0 = w / 2 - bw * 1.5f
                for ((i, col) in listOf(0xFF1FA34A.toInt(), 0xFFF2F2F2.toInt(), 0xFFE8202A.toInt()).withIndex()) {
                    val l = x0 + i * bw
                    path.reset()
                    path.moveTo(l + h * 0.03f, t); path.lineTo(l + bw + h * 0.03f, t)
                    path.lineTo(l + bw, b); path.lineTo(l, b); path.close()
                    fill.color = col; c.drawPath(path, fill)
                }
                // Red speed streaks along the bottom corners.
                stroke.color = 0x40FF1E1E; stroke.strokeWidth = h * 0.006f
                for (i in 0..3) { val y = h * (0.86f + i * 0.035f); c.drawLine(0f, y + h * 0.06f, w * 0.18f, y, stroke); c.drawLine(w, y + h * 0.06f, w * 0.82f, y, stroke) }
            }
            DashLayout.Style.GT -> {
                // Carbon weave, plus red racing stripes cutting the top-left and bottom-right corners.
                stroke.color = 0x0EFFFFFF; stroke.strokeWidth = 1f
                var x = -h
                while (x < w) { c.drawLine(x, 0f, x + h, h, stroke); c.drawLine(x + h, 0f, x, h, stroke); x += h * 0.025f }
                for (k in 0..2) {
                    stroke.color = if (k == 1) 0x66FFFFFF else 0x99FF1E2D.toInt(); stroke.strokeWidth = h * 0.012f
                    val o = k * h * 0.03f
                    c.drawLine(w * 0.86f + o, h, w + o, h * 0.72f, stroke)
                    c.drawLine(w * 0.43f + o, h * 0.16f, w * 0.47f + o, 0f, stroke)
                }
            }
            DashLayout.Style.PAPAYA -> {
                // Glowing orange swooshes rising from the bottom corners.
                for ((x0, dir) in listOf(0f to 1f, w to -1f)) {
                    path.reset()
                    path.moveTo(x0, h * 0.82f)
                    path.quadTo(x0 + dir * w * 0.06f, h * 0.98f, x0 + dir * w * 0.2f, h)
                    glow(c, h) { c.drawPath(path, stroke) }
                }
            }
            DashLayout.Style.NOIR -> {
                // Carbon weave under blue light lines sweeping up from the bottom corners.
                stroke.color = 0x0CFFFFFF; stroke.strokeWidth = 1f
                var x = -h
                while (x < w) { c.drawLine(x, 0f, x + h, h, stroke); c.drawLine(x + h, 0f, x, h, stroke); x += h * 0.025f }
                for ((x0, dir) in listOf(0f to 1f, w to -1f)) {
                    path.reset()
                    path.moveTo(x0, h * 0.86f)
                    path.quadTo(x0 + dir * w * 0.08f, h * 0.99f, x0 + dir * w * 0.26f, h * 0.995f)
                    glow(c, h) { c.drawPath(path, stroke) }
                }
            }
            DashLayout.Style.GRAND -> {
                // Quilted leather: a faint diamond stitch over the whole panel.
                stroke.color = 0x16D9B26A; stroke.strokeWidth = 1f
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(h * 0.008f, h * 0.006f), 0f)
                var x = -h
                while (x < w) { c.drawLine(x, 0f, x + h, h, stroke); c.drawLine(x + h, 0f, x, h, stroke); x += h * 0.07f }
                stroke.pathEffect = null
            }
            else -> Unit
        }
        // Motifs leave a faint colour on [fill]; its alpha would also fade the shader-filled mode tabs.
        fill.color = Color.BLACK
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var e = s
        while (e.isNotEmpty() && text.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }

    private fun bar(v: Float?) = v?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"

    private fun lighten(c: Int): Int {
        val r = min(255, Color.red(c) + 50); val g = min(255, Color.green(c) + 50); val b = min(255, Color.blue(c) + 50)
        return Color.rgb(r, g, b)
    }

    companion object {
        const val DESIGN_W = 1024f
        const val DESIGN_H = 425f
        const val MAX_KMH = 160
        const val RED_KMH = 120
        /** Start-up needle sweep, up and back down (ms). */
        const val SWEEP_MS = 1_600L
        /** Welcome (bike name lighting up) before the sweep (ms). */
        const val INTRO_MS = 2_000L
        /** Glow turns from the theme colour to red over this many km/h before the red zone. */
        const val GLOW_WARN_KMH = 15

        /**
         * Needle [from] → [to] after [dtMs]: exponential ease (~90 ms time constant), snapping
         * once within half a km/h so the animation ends.
         */
        internal fun ease(from: Float, to: Float, dtMs: Long): Float {
            val v = to + (from - to) * kotlin.math.exp(-dtMs / 90f)  // quick: GPS already lags ~1 s
            return if (kotlin.math.abs(v - to) < 0.5f) to else v
        }
        private const val FUEL_CHIP = "fuel"
        /** Backdrop alpha (0–255) behind the left rows: art still visible, numbers still readable. Tune on the bike. */
        const val BACKDROP_DIM = 70
        /** Left-column art: thin feather at the screen edge, long fade into the gauge. */
        private val LEFT_FEATHER = floatArrayOf(0f, 0.06f, 0.55f, 1f)

        /** Litres left after [kmSinceFill] km from a full tank at [lPer100] L/100 km; null if unknown. */
        internal fun fuelLeft(tankL: Float, lPer100: Double?, kmSinceFill: Double?): Double? {
            if (tankL <= 0f || lPer100 == null || lPer100 <= 0.0 || kmSinceFill == null) return null
            return (tankL - kmSinceFill * lPer100 / 100.0).coerceIn(0.0, tankL.toDouble())
        }

        internal fun rangeKm(litres: Double, lPer100: Double): Int = (litres / lPer100 * 100.0).roundToInt()
    }
}
