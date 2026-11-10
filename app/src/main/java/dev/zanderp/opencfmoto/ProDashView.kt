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
        /** Left art strength, 0–255: a soft picture shows as a faint backdrop instead of a blurry photo. */
        val artAlpha: Int = 255,
        /** [art] is a faint full-screen background (at [artAlpha]) instead of the picture on the left. */
        val fullArt: Boolean = false,
        /** Picture for the supercar badge between the bottom tiles (0 = three slanted bars). */
        val emblem: Int = 0,
        /** Map-first layout: big live map, phone telemetry column, status strip (Nav Live). */
        val navMap: Boolean = false,
        /** Map-first, map in the middle: location and speeds left, big speedo right (Nav Center). */
        val navCenter: Boolean = false,
        /** Turn-by-turn: guidance left, map centre, speed right, bike row below (Nav Pro). */
        val navPro: Boolean = false,
        /** Speed cluster left, live map right, ride tiles under the map (ADV Nav). */
        val adv: Boolean = false,
        /** Round gauge + bike left, map centre, guidance right, height strip and bike row below (Touring Nav). */
        val tourNav: Boolean = false,
        /** The rider's "3D Terrain Explorer" mockup as the whole background ([art]); live values drawn on it. */
        val terrain: Boolean = false,
        /** Same idea from the rider's "Smart Riding System" mockup: map centre, phone cards right (Smart Ride). */
        val smart: Boolean = false,
        /** The rider's "368G" mockup: speed bar round the left, live map top right (Race Bar). */
        val raceBar: Boolean = false,
        /** The rider's green "ride / live" mockup: big map, phone cards right (Moto Live). */
        val lime: Boolean = false)

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
        /** Metres climbed and descended this ride, and recent heights oldest first (Touring Nav). */
        /** Posted limit of the road the bike is on (OpenStreetMap maxspeed), km/h; null = unknown. */
        val speedLimit: Int? = null,
        /** "⛅ 28°" or "🌧 30'" (rain due in 30 min) from [RainCheck]; [weatherRain] draws it blue. */
        val weather: String? = null,
        val weatherRain: Boolean = false,
        /** A service that is due or soon: shown across the top bar for the first seconds of a ride. */
        val maintAlert: String? = null,
        val maintOverdue: Boolean = false,
        val climbM: Int? = null,
        val descentM: Int? = null,
        val altProfile: FloatArray? = null,
    )

    /** Unchanged data (parked: same speed, same minute) draws nothing new, so the encoder idles too. */
    var data = Data()
        set(v) { if (v == field) return; field = v; invalidate() }

    /** The welcome is still playing (or about to, before the first frame): the live map must wait, it sits above this view. */
    val introRunning: Boolean
        get() = live && fx(DashLayout.Fx.INTRO) && (startAt < 0 || android.os.SystemClock.uptimeMillis() - startAt < INTRO_MS)

    private val base = when (style) {
        DashLayout.Style.CYBER -> Theme(0xFFFF2BD6.toInt(), 0xFF22E5FF.toInt(), 0xFF1C0B2A.toInt(), 0xFF1D0F33.toInt(), 0xFF0A0614.toInt(), listOf("CYBERPUNK", "ROAD", "ECO"), art = R.drawable.dash_art_cyber, scene = R.drawable.dash_scene_cyber, artAlpha = 70, fullArt = true)
        DashLayout.Style.DRAGON -> Theme(0xFFE8B04A.toInt(), 0xFFB5652B.toInt(), 0xFF241807.toInt(), 0xFF261B0C.toInt(), 0xFF120C05.toInt(), listOf("DRAGON", "GUARDIAN", "EXPLORER"), art = R.drawable.dash_art_dragon, scene = R.drawable.dash_scene_dragon, artAlpha = 70, fullArt = true)
        DashLayout.Style.RACING -> Theme(0xFFFF2A2A.toInt(), 0xFFC9CDD3.toInt(), 0xFF200A0A.toInt(), 0xFF1F1213.toInt(), 0xFF0E0909.toInt(), listOf("RACE", "SPORT", "TOURING"), art = R.drawable.dash_art_racing, scene = R.drawable.dash_scene_racing, artAlpha = 80)
        DashLayout.Style.NEON -> Theme(0xFFB066FF.toInt(), 0xFF5B7CFF.toInt(), 0xFF170C2C.toInt(), 0xFF1A1236.toInt(), 0xFF0B0818.toInt(), listOf("RONIN", "SAMURAI", "NINJA"), art = R.drawable.dash_art_neon, scene = R.drawable.dash_scene_neon, artAlpha = 70, fullArt = true)
        DashLayout.Style.NEONCITY -> Theme(0xFFFF2E88.toInt(), 0xFF22E5FF.toInt(), 0xFF150A24.toInt(), 0xFF101A30.toInt(), 0xFF0A0E1C.toInt(), listOf("CYBERPUNK", "RACING", "TOURING", "ECO"), art = R.drawable.dash_art_neoncity, scene = R.drawable.dash_scene_neoncity, fuelRow = true, artAlpha = 70, fullArt = true)
        DashLayout.Style.ADVENTURE -> Theme(0xFFFFA41B.toInt(), 0xFFFFD36B.toInt(), 0xFF1C150A.toInt(), 0xFF13202A.toInt(), 0xFF0A1016.toInt(), listOf("ADVENTURE", "SPORT", "TOURING", "ECO"), art = R.drawable.dash_art_adventure, scene = R.drawable.dash_scene_adventure, fuelRow = true)
        DashLayout.Style.TOURING -> Theme(0xFFFFB81C.toInt(), 0xFFFF7A1A.toInt(), 0xFF0F1A2A.toInt(), 0xFF13233A.toInt(), 0xFF0A121E.toInt(), listOf("SPORT", "TOURING", "CITY", "ECO"), framed = false, art = R.drawable.dash_art_touring, scene = R.drawable.dash_scene_touring)
        // No artwork yet: the left side shows the Garage photo. Add art = / scene = once the images exist.
        // Anime: cut from the rider's "Bushido" mockup; no scene, the right card is the live map (or plain).
        DashLayout.Style.ANIMAL -> Theme(0xFF2EC4B6.toInt(), 0xFFFFC994.toInt(), 0xFF0E1A1C.toInt(), 0xFF12262A.toInt(), 0xFF0A1416.toInt(), listOf("WILD", "CITY", "ECO"), framed = false)
        DashLayout.Style.ANIME -> Theme(0xFFFF2B4A.toInt(), 0xFF4FA8FF.toInt(), 0xFF0B0E24.toInt(), 0xFF141A3A.toInt(), 0xFF080A1A.toInt(), listOf("BUSHIDO", "SPORT", "TOURING", "ECO"), framed = false, art = R.drawable.dash_art_anime, supercar = true, edition = "BUSHIDO", emblem = R.drawable.dash_emblem_anime)
        // Cut from the rider's "Space Edition" mockup; drawn on the Sport Touring layout (bike left, chips + tiles).
        DashLayout.Style.SPACE -> Theme(0xFF2ED3FF.toInt(), 0xFFB45CFF.toInt(), 0xFF0A1030.toInt(), 0xFF101C44.toInt(), 0xFF070C22.toInt(), listOf("SPACE", "SPORT", "TOURING", "CITY", "ECO"), art = R.drawable.dash_art_space, scene = R.drawable.dash_scene_space, artAlpha = 70, fullArt = true)
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
        // From the rider's 368G "ADV" mockup: blue frame, red speed band, green heading.
        DashLayout.Style.ADVNAV -> Theme(0xFF2E7BFF.toInt(), 0xFF3DEB5A.toInt(), 0xFF070B14.toInt(), 0xFF0C1626.toInt(), 0xFF070C16.toInt(), listOf("SPORT", "TOURING", "CITY", "ECO"), framed = false, adv = true)
        // From the rider's "Touring Edition" mockup (bike cut from it, decals painted out).
        DashLayout.Style.TOURNAV -> Theme(0xFFFF8A1F.toInt(), 0xFF4FC3F7.toInt(), 0xFF0A0806.toInt(), 0xFF16110C.toInt(), 0xFF0B0907.toInt(), listOf("TOURING", "SPORT", "CITY", "ECO"), framed = false, art = R.drawable.dash_art_tournav, edition = "TOURING EDITION", tourNav = true)
        // The rider's "3D Terrain Explorer" mockup (docs/border-racing.png), values painted out by scripts/terrain_plate.py.
        DashLayout.Style.TERRAIN -> Theme(0xFFFF8A1F.toInt(), 0xFF2ED3F0.toInt(), 0xFF050506.toInt(), 0xFF111316.toInt(), 0xFF0A0A0C.toInt(), listOf("TOURING", "SPORT", "CITY", "ECO"), framed = false, art = R.drawable.dash_art_terrain, terrain = true)
        // The rider's "Smart Riding System" mockup (docs/borer-zride.png), values and the other app's name painted out by scripts/smart_plate.py.
        DashLayout.Style.SMART -> Theme(0xFF22C8F0.toInt(), 0xFFFF8A1F.toInt(), 0xFF05080C.toInt(), 0xFF0C141C.toInt(), 0xFF070B10.toInt(), null, framed = false, art = R.drawable.dash_art_smart, smart = true)
        // The rider's "368G" mockup (docs/boder-xadv.png), values painted out by scripts/racebar_plate.py.
        DashLayout.Style.RACEBAR -> Theme(0xFF22D3EE.toInt(), 0xFFFFC21A.toInt(), 0xFF000000.toInt(), 0xFF0E1012.toInt(), 0xFF060708.toInt(), listOf("TOURING", "SPORT", "CITY", "ECO"), framed = false, art = R.drawable.dash_art_racebar, raceBar = true)
        // The rider's green "ride / live" mockup (docs/border-moto.png), the other app's name painted out by scripts/lime_plate.py.
        DashLayout.Style.LIME -> Theme(0xFFA6F01C.toInt(), 0xFF2EEB6E.toInt(), 0xFF020403.toInt(), 0xFF0A100A.toInt(), 0xFF050805.toInt(), null, framed = false, art = R.drawable.dash_art_lime, lime = true)
        // The rider's sport-cluster mockup (docs/SCR-20261109-jxwk.png): amber line and heading, blue lean bar.
        DashLayout.Style.AASPORT -> Theme(0xFFF5A623.toInt(), 0xFF4FC3F7.toInt(), 0xFF0A0B0D.toInt(), 0xFF0E1013.toInt(), 0xFF050506.toInt(), null, framed = false)
        // The rider's round-gauge mockup (docs/image copy.png): amber arc, blue fuel percent.
        DashLayout.Style.AAGAUGE -> Theme(0xFFFFB300.toInt(), 0xFF29B6F6.toInt(), 0xFF050506.toInt(), 0xFF0B0C0E.toInt(), 0xFF040405.toInt(), null, framed = false)
        // The rider's orange "368G" mockup (third AA one): orange name, line and fuel, amber heading.
        DashLayout.Style.AARACE -> Theme(0xFFFF9A1A.toInt(), 0xFFFF9A1A.toInt(), 0xFF050506.toInt(), 0xFF0C0C0E.toInt(), 0xFF040405.toInt(), null, framed = false)
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
    private val advLayout = base.adv
    private val tourNavLayout = base.tourNav
    private val terrainLayout = base.terrain
    private val smartLayout = base.smart
    private val raceBarLayout = base.raceBar
    private val limeLayout = base.lime
    /** Android Auto + side panel ([DashLayout.Style.AASIDE]). */
    private val aaSideLayout = style == DashLayout.Style.AASIDE
    private val aaCluster = style == DashLayout.Style.AACLUSTER
    private val aaBar = style == DashLayout.Style.AABAR
    private val aaRight = style == DashLayout.Style.AARIGHT
    private val aaSport = style == DashLayout.Style.AASPORT
    private val aaGauge = style == DashLayout.Style.AAGAUGE
    private val aaRace = style == DashLayout.Style.AARACE
    /** AASIDE on the bike: this view is only the panel, drawn at its own size (the compositor puts AA beside it). */
    var sideOnly = false
    /** AASIDE in the editor: the panel's share of the width; the rest shows an Android Auto stand-in. */
    var sideFrac = DashLayout.SIDE_DEFAULT
    /** Editor: this template runs Android Auto in its map card; show a stand-in there. */
    var aaCard = false
    /** The rider's whole mockup is the background: no drawn background or top bar. */
    private val plateLayout = terrainLayout || smartLayout || raceBarLayout || limeLayout
    private val theme = if (customColor || !style.themed) base.copy(main = accent, second = accent2 ?: base.second) else base
    /** Speed arc drawn as a gradient (themed looks, or the rider picked a gradient). */
    private val gradientArc = theme.framed || accent2 != null
    private val gold = theme.main
    /** Template artwork (cut from the ZRide mockup); the plain Pro look shows the Garage photo instead. */
    /** Mode tab the rider's colour lights up (blue = second, green = third, else first). */
    private val modeIndex = when (accent) { DashLayout.ACCENTS[2] -> 1; DashLayout.ACCENTS[1] -> 2; else -> 0 }
    /** Neon on its NINJA tab gets the ninja picture instead of the ronin. */
    private val artRes = if (style == DashLayout.Style.NEON && modeIndex == 2) R.drawable.dash_art_neon_ninja else theme.art
    private val art = artRes.takeIf { it != 0 }?.let { scrub(android.graphics.BitmapFactory.decodeResource(resources, it)) }

    /**
     * Mockup backgrounds print each part's frame / icon into the picture: (parts, box in the art's own
     * 1024×425 px). A box goes once every part in its set is hidden, so no empty frame is left behind.
     */
    private fun artSlots(): List<Pair<Set<DashLayout.Part>, IntArray>> = when (style) {
        DashLayout.Style.RACEBAR -> listOf(
            setOf(DashLayout.Part.MODE_TABS) to intArrayOf(54, 335, 117, 370),
            setOf(DashLayout.Part.PHONE_TEMP) to intArrayOf(309, 330, 335, 372),
            setOf(DashLayout.Part.BIKE_BATTERY) to intArrayOf(506, 332, 550, 366),
            setOf(DashLayout.Part.TYRE) to intArrayOf(727, 333, 765, 370),
            setOf(DashLayout.Part.FUEL) to intArrayOf(438, 269, 467, 300),
            setOf(DashLayout.Part.HEADING) to intArrayOf(514, 152, 614, 242),
            setOf(DashLayout.Part.LEAN) to intArrayOf(631, 203, 1004, 312),
        )
        DashLayout.Style.TERRAIN -> listOf(
            setOf(DashLayout.Part.MODE_TABS) to intArrayOf(15, 345, 200, 401),
            setOf(DashLayout.Part.PHONE_TEMP) to intArrayOf(218, 352, 246, 382),
            setOf(DashLayout.Part.BIKE_BATTERY) to intArrayOf(343, 355, 382, 380),
            setOf(DashLayout.Part.FUEL) to intArrayOf(476, 351, 508, 378),
            setOf(DashLayout.Part.TYRE) to intArrayOf(629, 352, 666, 380),
            setOf(DashLayout.Part.TRIP, DashLayout.Part.ODO) to intArrayOf(801, 345, 1020, 401, 1),  // right edge glows orange: fill from the left only
            setOf(DashLayout.Part.ALTITUDE) to intArrayOf(269, 305, 320, 332),
        )
        else -> emptyList()
    }

    /** [b] with the frames of hidden parts painted out: each row bridged from the pixel left of the box to the one right of it (a 5th box value: the left one only). */
    private fun scrub(b: Bitmap): Bitmap {
        val boxes = artSlots().filter { (parts, _) -> parts.none { on(it) } }.map { it.second }
        if (boxes.isEmpty()) return b
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val sx = out.width / DESIGN_W; val sy = out.height / DESIGN_H
        for (box in boxes) {
            val l = (box[0] * sx).toInt().coerceIn(1, out.width - 2); val r = (box[2] * sx).toInt().coerceIn(l, out.width - 2)
            val t = (box[1] * sy).toInt().coerceIn(0, out.height - 1); val bt = (box[3] * sy).toInt().coerceIn(t, out.height - 1)
            val row = IntArray(r - l + 1)
            for (y in t..bt) {
                val a = out.getPixel(l - 1, y); val z = if (box.size > 4) a else out.getPixel(r + 1, y)
                for (i in row.indices) {
                    val f = (i + 1f) / (row.size + 1f)
                    fun mix(sh: Int) = (((a shr sh) and 0xFF) * (1 - f) + ((z shr sh) and 0xFF) * f).toInt() shl sh
                    row[i] = (0xFF shl 24) or mix(16) or mix(8) or mix(0)
                }
                out.setPixels(row, 0, row.size, l, y, row.size, 1)
            }
        }
        return out
    }
    private val emblemArt = theme.emblem.takeIf { it != 0 }?.let { android.graphics.BitmapFactory.decodeResource(resources, it) }
    private val scene = theme.scene.takeIf { it != 0 && on(DashLayout.Part.SCENE) }?.let { android.graphics.BitmapFactory.decodeResource(resources, it) }
    private val second = theme.second
    private val white = Color.WHITE
    private val dim = 0xFF8F8A80.toInt()
    private val red = ContextCompat.getColor(ctx, R.color.status_error)
    private val panel = 0xFF101013.toInt()
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val boldItalic = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)

    // FILTER_BITMAP: art is ~480 px wide and drawn larger on the dash; unfiltered it scales blocky.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val pump = ContextCompat.getDrawable(ctx, R.drawable.ic_tile_fuel)?.mutate()?.apply { setTint(theme.main) }
    /** No template art and no Garage photo (หน้าปัด Pro): a Zontes faded behind the left rows. */
    private val fallbackArt by lazy { android.graphics.BitmapFactory.decodeResource(resources, R.drawable.dash_art_navpro) }
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
        val media = ctx.getString(R.string.dl_w_media)
        val remain = ctx.getString(R.string.pro_remain)
        val eta = ctx.getString(R.string.pro_eta)
        val climb = ctx.getString(R.string.pro_climb)
        val descent = ctx.getString(R.string.pro_descent)
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
        if (aaSideLayout && sideOnly) {
            // Off-screen on the bike: AaSidePanel redraws it ~10×/s, so no invalidate loop here.
            dh = vh
            side(c, vw, vh)
            if (over) speedAlarm(c, vw, vh, now)  // the panel beside Android Auto blinks too
            return
        }
        c.drawColor(0xFF050506.toInt())
        c.save()
        c.translate((vw - w * s) / 2f, (vh - h * s) / 2f)
        c.scale(s, s)
        if (!plateLayout && !aaSideLayout && !aaCluster && !aaBar && !aaRight && !aaSport && !aaGauge && !aaRace) { background(c, w, h); topBar(c, w, h) }  // these draw their own
        if (aaSideLayout) side(c, w * sideFrac, h)
        else if (aaCluster) cluster(c, w, h)
        else if (aaBar) aaBarLayout(c, w, h)
        else if (aaRight) aaRightLayout(c, w, h)
        else if (aaSport) aaSportLayout(c, w, h)
        else if (aaGauge) aaGaugeLayout(c, w, h)
        else if (aaRace) aaRaceLayout(c, w, h)
        else if (terrainLayout) terrain(c, w, h)
        else if (smartLayout) smart(c, w, h)
        else if (raceBarLayout) raceBar(c, w, h)
        else if (limeLayout) lime(c, w, h)
        else if (navPro) navProLayout(c, w, h)
        else if (advLayout) adv(c, w, h)
        else if (tourNavLayout) tourNav(c, w, h)
        else if (navCenter) navCentre(c, w, h)
        else if (navLayout) nav(c, w, h)
        else if (supercarLayout) supercar(c, w, h)
        else if (touringLayout) touring(c, w, h)
        else { leftPanel(c, w, h); gauge(c, w, h); rightPanel(c, w, h); bottomStrip(c, w, h) }
        if (over) speedAlarm(c, w, h, now)
        if (aaCard) card(w, h).let { k -> c.save(); c.clipRect(k.l, k.t, k.r, k.mapBottom()); aaStandIn(c, k.l, k.r, k.mapBottom(), k.t); c.restore() }
        if (intro) welcome(c, w, h, t)
        c.restore()
        // On the bike every frame is also an H.264 encode: animate at the dash's frame rate, not the phone's 60-120 Hz.
        if (animating) { if (live) postInvalidateDelayed(1000L / BikeProfileHolder.active.videoFrameRate.coerceIn(10, 30)) else postInvalidateOnAnimation() }
        else if (over) postInvalidateDelayed(150)
        else if (data.fuelStop != null) postInvalidateDelayed(600)  // the low-fuel banner blinks (data alone no longer redraws)
    }

    // ---------------------------------------------------------------- frame

    private fun background(c: Canvas, w: Float, h: Float) {
        fill.shader = RadialGradient(w / 2, h * 0.55f, w * 0.6f, theme.bg, 0xFF050506.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        if (theme.fullArt && on(DashLayout.Part.ART)) art?.let {
            rect.set(0f, 0f, w, h)
            fill.alpha = theme.artAlpha; c.drawBitmap(it, null, rect, fill); fill.alpha = 255
        }
        if (!theme.fullArt) motif(c, w, h)  // a full-screen picture is the decoration; petals / scan lines / 侍 only cluttered it
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
        // Small: the rider knows their bike, the room goes to the ride (and the turn banner covers it while navigating).
        text.textSize = h * 0.05f
        text.typeface = bold; text.textAlign = Paint.Align.LEFT
        val firstW = text.measureText(first)
        text.typeface = boldItalic; text.textSize = h * 0.062f
        // Supercar: the edition on the left, the bike's name centred.
        val nameX = if (supercarLayout) w / 2 - (firstW + text.measureText(last)) / 2 else w * 0.03f
        text.typeface = bold; text.color = white; text.textSize = h * 0.05f
        c.drawText(first, nameX, base, text)
        text.typeface = boldItalic; text.color = gold; text.textSize = h * 0.062f
        c.drawText(last, nameX + firstW, base, text)
        if (tourNavLayout) theme.edition?.let { ed ->
            text.typeface = boldItalic; text.color = gold; text.textSize = h * 0.032f
            c.drawText(ed, nameX + firstW, h * 0.147f, text)
        } else theme.edition?.let { ed ->
            text.typeface = boldItalic; text.color = white; text.textSize = h * 0.07f
            c.drawText(ellipsize(ed, w * 0.22f), w * 0.03f, base, text)
            val ex = w * 0.03f + text.measureText(ellipsize(ed, w * 0.22f)) + h * 0.03f
            text.typeface = bold; text.textSize = h * 0.035f; text.color = gold
            c.drawText("EDITION", ex, base, text)
        }

        if (navLayout && !navPro && data.videoFps != null) {
            // What reaches the dash, measured: frames and megabits per second.
            text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.045f; text.color = dim
            c.drawText("DASH ${data.videoFps} FPS · " + String.format(Locale.getDefault(), "%.1f Mbps", data.linkMbps ?: 0f), w * 0.5f, h * 0.1f, text)
        }
        if (!touringLayout && !supercarLayout && !navLayout && !advLayout && !tourNavLayout && on(DashLayout.Part.MODE_TABS)) tabs(c, h, w / 2, h * 0.045f, w * 0.46f)

        if (tourNavLayout) {
            // Dark pill behind GPS · Bluetooth · clock, like the mockup's status bar.
            rect.set(w * 0.67f, h * 0.022f, w * 0.993f, h * 0.135f)
            fill.color = 0xE6100D0A.toInt(); c.drawRoundRect(rect, h * 0.056f, h * 0.056f, fill)
            rim(c, rect, h * 0.056f, h)
        }
        // Right: Bluetooth state and the clock.
        text.textAlign = Paint.Align.RIGHT
        text.typeface = bold; text.textSize = h * 0.085f; text.color = white
        c.drawText(data.clock, w * 0.975f, base, text)
        val clockW = text.measureText(data.clock)
        text.textSize = h * 0.06f
        text.color = if (data.bluetoothOn) 0xFF4FC3F7.toInt() else dim
        val btX = w * 0.975f - clockW - h * 0.06f
        c.drawText("ᛒ", btX, base, text)
        // Then, leftwards: speed-limit sign, weather, GPS badge (shrunk into what's left before the tabs / name).
        var right = btX - text.measureText("ᛒ") - h * 0.035f
        val left = when { touringLayout || navLayout || advLayout -> w * 0.5f; tourNavLayout -> w * 0.685f; supercarLayout -> w * 0.66f; else -> w * 0.74f }
        data.speedLimit?.let { limitSign(c, h, right, it); right -= h * 0.13f }
        data.weather?.let { wx ->
            text.textAlign = Paint.Align.RIGHT; text.typeface = bold; text.textSize = h * 0.05f
            val ww = text.measureText(wx)
            if (right - ww >= left) {
                text.color = if (data.weatherRain) 0xFF4FC3F7.toInt() else white
                c.drawText(wx, right, base, text)
                right -= ww + h * 0.035f
            }
        }
        data.gps?.takeIf { right - left > h * 0.08f }?.let { g ->
            text.textAlign = Paint.Align.RIGHT; text.typeface = bold; text.textSize = h * 0.05f
            val tw = text.measureText(g)
            if (tw > right - left) text.textSize *= (right - left) / tw
            text.color = data.gpsColor
            c.drawText(g, right, base, text)
        }
        if (!navPro && !tourNavLayout && !advLayout) navBanner(c, w, h)  // these show the turn in their own panels
        if (data.navTurnText == null) maintBanner(c, w, h)
    }

    /** Start of a ride: the service that's due (red) or soon (amber) over the top bar's left side for [MAINT_SHOW_MS]. */
    private fun maintBanner(c: Canvas, w: Float, h: Float) {
        val msg = data.maintAlert ?: return
        if (!live || startAt < 0) return
        val since = android.os.SystemClock.uptimeMillis() - startAt - INTRO_MS
        if (since < 0 || since > MAINT_SHOW_MS) return
        postInvalidateDelayed(MAINT_SHOW_MS - since + 50)  // redraw once more to take it away
        val col = if (data.maintOverdue) red else 0xFFFFB020.toInt()
        rect.set(w * 0.012f, h * 0.012f, w * 0.62f, h * 0.148f)
        fill.color = 0xFF14100A.toInt(); c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill)
        stroke.color = col; stroke.strokeWidth = h * 0.006f; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, stroke)
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.048f; text.color = col
        c.drawText(ellipsize(msg, rect.width() - h * 0.06f), rect.left + h * 0.03f, rect.centerY() + text.textSize * 0.36f, text)
    }

    /** Round road sign (red ring, the limit) ending at [right] in the top bar; turns red when riding over it. */
    private fun limitSign(c: Canvas, h: Float, right: Float, kmh: Int) {
        val r = h * 0.05f; val cx = right - r; val cy = h * 0.075f
        val speeding = (shown ?: 0) > kmh + LIMIT_TOLERANCE_KMH
        fill.color = if (speeding) red else white; c.drawCircle(cx, cy, r, fill)
        stroke.color = if (speeding) white else 0xFFE0201B.toInt(); stroke.strokeWidth = r * 0.22f
        c.drawCircle(cx, cy, r * 0.89f, stroke)
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.color = if (speeding) white else Color.BLACK
        text.textSize = r * if (kmh >= 100) 0.8f else 0.95f
        c.drawText(kmh.toString(), cx, cy + text.textSize * 0.36f, text)
    }

    /** The turn picture in the square at [l],[t] of side [s], tinted [color]; the arrow character only if there's no picture. */
    private fun turnIcon(c: Canvas, l: Float, t: Float, s: Float, color: Int) {
        val icon = data.navIcon
        if (icon != null) {
            rect.set(l, t, l + s, t + s)
            fill.colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            c.drawBitmap(icon, null, rect, fill); fill.colorFilter = null
        } else {
            text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = s; text.color = color
            c.drawText(data.navArrow ?: "↑", l + s / 2, t + s * 0.85f, text)
        }
    }

    /**
     * Navigating on any template: the next turn across the top bar's left side (over the bike's
     * name and the mode tabs; GPS, Bluetooth and clock stay). Nav Pro has its own panels instead.
     */
    private fun navBanner(c: Canvas, w: Float, h: Float) {
        val t = data.navTurnText ?: return
        val l = w * 0.012f; val r = w * 0.7f; val top = h * 0.012f; val b = h * 0.17f
        rect.set(l, top, r, b)
        fill.color = 0xFF0D1116.toInt(); c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill)  // opaque: the name under it mustn't show
        stroke.color = gold; stroke.strokeWidth = h * 0.005f; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, stroke)
        // Read at a glance while riding: big arrow and distance, then the instruction.
        val mid = (top + b) / 2
        var x = l + h * 0.025f
        val s = (b - top) * 0.88f
        turnIcon(c, x, mid - s / 2, s, gold)
        x += s + h * 0.02f
        val dist = data.navTurnDist.orEmpty()
        if (dist.isNotEmpty()) {
            text.textAlign = Paint.Align.LEFT; text.typeface = boldItalic; text.textSize = h * 0.1f; text.color = gold
            c.drawText(dist, x, mid + h * 0.036f, text)
            x += text.measureText(dist) + h * 0.03f
        }
        val right = listOfNotNull(data.navRemain, data.navEta).joinToString(" · ")
        text.textAlign = Paint.Align.RIGHT; text.typeface = bold; text.textSize = h * 0.05f; text.color = second
        if (right.isNotEmpty()) c.drawText(right, r - h * 0.025f, mid + h * 0.018f, text)
        val rw = if (right.isNotEmpty()) text.measureText(right) + h * 0.04f else h * 0.025f
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.066f; text.color = white
        // Too long for the room: the road alone (the arrow already says which way) rather than a cut-off sentence.
        val room = r - rw - x
        val say = if (text.measureText(t) <= room) t else roadOf(t).ifBlank { t }
        c.drawText(ellipsize(say, room), x, mid + h * 0.024f, text)
    }

    /**
     * Mode tabs centred on [cx] (or starting at it when [fromLeft]), at most [maxW] wide in total.
     * The selected one follows the accent (gold → 1st · blue → 2nd · green → 3rd).
     */
    private fun tabs(c: Canvas, h: Float, cx: Float, ty: Float, maxW: Float, fromLeft: Boolean = false) {
        val n = labels.tabs.size
        val sel = modeIndex
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
        val img = if (theme.fullArt) null else art ?: data.photo  // full-screen art: nothing extra on the left
        // Hidden picture, or only the faint fallback behind them: the rows sit up top, no gap.
        val top = h * if (!on(DashLayout.Part.ART) || img == null) 0.19f else if (theme.fuelRow) 0.43f else 0.52f
        if (!on(DashLayout.Part.ART)) {
            // hidden: no art at all
        } else if (img != null) {
            // Frameless: the art spans the left column, full strength in the empty space up top,
            // faded behind the first rows. Not stretched to full height: the art is ~2:1 and ~480 px
            // wide, so a taller crop would blow it up 3× and blur it.
            val t = h * 0.165f
            val b = top + h * 0.2f
            val (a, st) = solidThenDim((top - h * 0.05f - t) / (b - t))
            backdrop(c, img, 0f, t, w * 0.38f, b, IntArray(a.size) { a[it] * theme.artAlpha / 255 }, st, TO_GAUGE)  // reaches the gauge: no dark gap
        } else if (!theme.fullArt) {
            // ponytail: fixed faint alpha; a per-template "art strength" setting if riders want it brighter.
            backdrop(c, fallbackArt, 0f, h * 0.165f, w * 0.38f, h * 0.95f, intArrayOf(0, 70, 70, 0), floatArrayOf(0f, 0.12f, 0.85f, 1f), TO_GAUGE)
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

        // Halo: the template colour glows out of the rim and over the art beside it, so picture and
        // gauge read as one piece.
        fill.shader = RadialGradient(cx, cy, rad * 1.5f,
            intArrayOf(0x00000000, (gold and 0x00FFFFFF) or 0x66000000, (gold and 0x00FFFFFF) or 0x1F000000, 0x00000000),
            floatArrayOf(0.6f, 0.67f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 1.5f, fill)
        fill.shader = null

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
            // Red by the app's own level (the dash only runs with the bike on: the running one), not by bar fill.
            val low = (data.bikeVolts ?: 99f) < PkeBattery.RED_RUNNING_V
            segments(c, cx, cy, sideR, 28f, 56f, volts, leftSide = false) { if (low) red else second }
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
        // Android Auto templates: AA covers the whole card, a small margin keeps the rim and the speed-alert frame visible.
        aaCluster -> Card(w * 0.22f, h * 0.03f, w * 0.78f, h * 0.97f, false)
        aaBar -> Card(h * 0.025f, h * 0.025f, w - h * 0.025f, h * 0.775f, false)
        aaRight -> Card(h * 0.025f, h * 0.025f, w * (1 - sideFrac) - h * 0.015f, h - h * 0.025f, false)
        aaSport -> Card(w * 0.555f, h * 0.15f, w - h * 0.025f, h * 0.88f, false)
        aaGauge -> Card(w * 0.64f, h * 0.15f, w - h * 0.025f, h * 0.75f, false)
        aaRace -> Card(w * 0.665f, h * 0.155f, w - h * 0.025f, h * 0.725f, false)
        aaSideLayout -> Card(w * sideFrac + h * 0.015f, h * 0.025f, w - h * 0.025f, h - h * 0.025f, false)
        // As big as each layout allows (the live map fills the part above the stats): supercar reaches
        // the gauge's rim, classic the battery gauge's "V" and down beside the ODO · TRIP line.
        navPro -> Card(w * 0.185f, h * 0.16f, w * 0.785f, h * 0.755f, false)
        terrainLayout -> Card(w * 0.2f, h * 0.104f, w * 0.982f, h * 0.59f, false)  // the mockup's black frame
        smartLayout -> Card(w * 0.186f, h * 0.142f, w * 0.726f, h * 0.688f, false)  // inside the cyan frame
        raceBarLayout -> Card(w * 1046 / 1672f, h * 144 / 941f, w * 1627 / 1672f, h * 431 / 941f, false)  // the mockup's turn + little map card
        limeLayout -> Card(w * 16 / 1674f, h * 121 / 940f, w * 1206 / 1674f, h * 686 / 940f, false)  // inside the green frame
        advLayout -> Card(w * 0.375f, h * 0.17f, w * 0.99f, h * 0.775f, false)
        tourNavLayout -> Card(w * 0.228f, h * 0.17f, w * 0.775f, h * 0.645f, false)
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
    fun cardCornerPx(): Float = DESIGN_H * (if (smartLayout) 0.065f else if (limeLayout) 0.04f else 0.03f) * min(width / DESIGN_W, height / DESIGN_H)  // Smart Ride's frame is very round

    /** The template's main colour (rims, live map border). */
    val accentColor: Int get() = gold

    /** Over the live map: the range (or the most urgent maintenance) on a small dark label, top left. */
    private fun mapStrip(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        if (navPro) { turnPill(c, h, k) }
        else if (advLayout) { turnPill(c, h, k); compass(c, h, k) }
        else if (tourNavLayout) compass(c, h, k, left = true)
        else if (navLayout) { if (!navCenter) speedBubble(c, h, k); mediaPill(c, h, k) }
        else if (smartLayout || limeLayout) { speedBubble(c, h, k); turnPill(c, h, Card(k.l + h * 0.25f, k.t, k.r, k.b, false)); return }  // the mockups had no speed
        else if (raceBarLayout) { raceTurn(c, h, k); return }
        // Android Auto templates on the ZRide map (motorcycle route): Android Auto showed the turns
        // itself, so put Google Maps' / ZRide's next turn over the map.
        else if (aaCluster || aaBar || aaRight || aaSport || aaGauge || aaRace || aaSideLayout) { turnPill(c, h, k); return }
        // Navigating: the road to turn into, top left like Google Maps, only over the big maps (Nav Live,
        // Nav Center). Elsewhere the turn already sits in the banner or the template's own panel, and a
        // second copy covered half of the small map.
        if (navLayout && !navPro && roadPill(c, h, k, k.l + h * 0.025f)) return
        if (tourNavLayout) return  // guidance sits in the right column
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

    /**
     * Blue "road to turn into" pill over the map's top left: turn icon (or arrow) and the road from
     * the guidance ([roadOf]). Google Maps sends no turn position, so it can't sit on the turn itself.
     * False when not navigating.
     */
    private fun roadPill(c: Canvas, h: Float, k: Card, l: Float): Boolean {
        val road = data.navTurnText?.let { roadOf(it) }?.takeIf { it.isNotBlank() } ?: return false
        val t = k.t + h * 0.025f; val b = t + h * 0.115f; val mid = (t + b) / 2
        val ic = (b - t) * 0.72f; val pad = h * 0.022f
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.058f
        val shown = ellipsize(road, (k.r - l) * 0.6f)
        rect.set(l, t, l + pad + ic + pad * 0.7f + text.measureText(shown) + pad, b)
        fill.color = 0xF21A5FD0.toInt(); c.drawRoundRect(rect, (b - t) / 2, (b - t) / 2, fill)
        turnIcon(c, l + pad, mid - ic / 2, ic, white)
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.058f; text.color = white
        c.drawText(shown, l + pad + ic + pad * 0.7f, mid + text.textSize * 0.36f, text)
        return true
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
        } else if (!on(DashLayout.Part.LIVE_MAP)) {
            // The rider took the map (and scenery) out: no card at all, the template's background shows through.
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
        // Big bike art, frameless: nothing sits on it, so full strength with feathered edges (none over a full-screen picture).
        (if (theme.fullArt) null else art ?: data.photo)?.takeIf { on(DashLayout.Part.ART) }?.let {
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
            if (on(DashLayout.Part.LIVE_MAP)) { fill.color = theme.cardTop; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill); rim(c, rect, h * 0.03f, h) }  // map hidden: no empty frame
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
            if (on(DashLayout.Part.LIVE_MAP)) { fill.color = theme.cardTop; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill); rim(c, rect, h * 0.03f, h) }  // map hidden: no empty frame
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
        val dir = listOfNotNull(data.heading?.takeIf { on(DashLayout.Part.HEADING) }?.let { "${labels.heading} $it" }, data.altitudeM?.takeIf { on(DashLayout.Part.ALTITUDE) }?.let { "▲ $it m" }).joinToString("  ·  ")
        c.drawText(ellipsize(dir, r - l - 2 * pad), l + pad, h * 0.56f, text)
        data.fuelStop?.let { banner(c, l, h * 0.46f, r, h * 0.6f, h, it) }

        // Left: live · average · alert.
        navCard(c, l, h * 0.625f, r, h * 0.985f, h)
        val cw = (r - l) / 3
        val cols = listOf(labels.live to (data.speedKmh?.toString() ?: "—"), labels.avgShort to (data.avgKmh?.toString() ?: "—"))
        for ((i, col) in cols.withIndex()) {
            if (i == 1 && !on(DashLayout.Part.AVG)) continue
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
        c.drawArc(rect, 140f, 80f, false, stroke)
        if (on(DashLayout.Part.LEAN)) c.drawArc(rect, 40f, -80f, false, stroke)
        val sf = ((v ?: 0) / MAX_KMH.toFloat()).coerceIn(0f, 1f)
        stroke.color = if (over || (v ?: 0) >= redKmh) red else gold
        c.drawArc(rect, 140f, 80f * sf, false, stroke)
        val lf = ((data.leanDeg?.takeIf { on(DashLayout.Part.LEAN) }?.let { kotlin.math.abs(it) } ?: 0f) / 60f).coerceIn(0f, 1f)
        stroke.color = 0xFFFFB04A.toInt()
        if (lf > 0f) c.drawArc(rect, 40f, -80f * lf, false, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.026f; text.color = dim
        c.drawText(data.leanDeg?.takeIf { on(DashLayout.Part.LEAN) }?.let { CustomDashUi.leanText(it) } ?: "", rc + ar * 0.62f, acy + h * 0.01f, text)
        if (on(DashLayout.Part.TRIP)) {
            text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.03f; text.color = gold
            c.drawText(labels.trip, rl, h * 0.9f, text)
            text.textSize = h * 0.065f; text.color = white
            c.drawText(data.tripKm?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—", rl, h * 0.975f, text)
        }
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
            if (on(DashLayout.Part.LIVE_MAP)) { fill.color = theme.cardTop; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill); rim(c, rect, h * 0.03f, h) }  // map hidden: no empty frame
            turnPill(c, h, k)
        }
        // One see-through column down to the bottom edge (it used to be three small cards over the
        // bike photo): the bike picture sits faded behind the lower half, the text on top of it.
        val l = w * 0.008f; val r = w * 0.178f; val pad = h * 0.022f
        fun y(v: Float) = h * (0.16f + (v - 0.16f) * 1.36f)  // the old 0.16..0.755 spread to 0.16..0.97
        val z = 1.15f  // room for bigger text
        rect.set(l, h * 0.16f, r, h * 0.99f)
        fill.color = (theme.cardTop and 0x00FFFFFF) or 0x8C000000.toInt(); c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill)
        (art ?: data.photo)?.takeIf { on(DashLayout.Part.ART) }?.let {
            backdrop(c, it, l, h * 0.42f, r, h * 0.99f, intArrayOf(0, 85, 85, 0), floatArrayOf(0f, 0.35f, 0.9f, 1f), floatArrayOf(0f, 0.04f, 0.9f, 1f))
        }
        rect.set(l, h * 0.16f, r, h * 0.99f)
        stroke.color = 0xFF2A2F33.toInt(); stroke.strokeWidth = h * 0.004f; c.drawRoundRect(rect, h * 0.03f, h * 0.03f, stroke)
        stroke.strokeWidth = 1f
        for (v in floatArrayOf(0.255f, 0.38f, 0.583f)) c.drawLine(l + pad, y(v), r - pad, y(v), stroke)
        // Header.
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.05f * z; text.color = gold
        c.drawText("➤", l + pad, y(0.225f), text)
        text.color = white; text.textSize = h * 0.038f * z
        c.drawText(ellipsize(labels.navigation, r - l - h * 0.1f), l + h * 0.085f, y(0.222f), text)
        // Where you are.
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.028f * z; text.color = dim
        c.drawText(ellipsize(labels.here, r - l - 2 * pad), l + pad, y(0.29f), text)
        text.typeface = bold; text.textSize = h * 0.034f * z; text.color = white
        c.drawText(ellipsize(data.placeRoad ?: labels.finding, r - l - 2 * pad), l + pad, y(0.33f), text)
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.028f * z; text.color = dim
        data.placeArea?.let { c.drawText(ellipsize(it, r - l - 2 * pad), l + pad, y(0.365f), text) }
        // Next turn.
        val turn = data.navTurnText
        if (turn != null) {
            val s = h * 0.11f
            turnIcon(c, l + pad, y(0.465f) - s * 0.88f, s, gold)
            text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.065f * z; text.color = gold
            c.drawText(ellipsize(data.navTurnDist ?: "", r - l - s - 3 * pad), l + pad * 2 + s, y(0.46f), text)
            text.textSize = h * 0.04f * z; text.color = white
            val lines = wrap2(turn, r - l - 2 * pad)
            for ((i, line) in lines.withIndex()) c.drawText(line, l + pad, y(0.505f + i * 0.042f), text)
        } else {
            text.typeface = bold; text.textSize = h * 0.032f * z; text.color = white
            for ((i, line) in wrap2(labels.noDestination, r - l - 2 * pad).withIndex()) c.drawText(line, l + pad, y(0.45f + i * 0.04f), text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.026f * z; text.color = dim
            for ((i, line) in wrap2(labels.pickDestination, r - l - 2 * pad).withIndex()) c.drawText(line, l + pad, y(0.52f + i * 0.034f), text)
        }
        // Destination, distance left, time left.
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.028f * z; text.color = dim
        c.drawText("⚑ " + labels.destination, l + pad, y(0.63f), text)
        text.typeface = bold; text.textSize = h * 0.036f * z; text.color = white
        c.drawText(ellipsize(data.navDest ?: "—", r - l - 2 * pad), l + pad, y(0.67f), text)
        text.textSize = h * 0.032f * z; text.color = second
        c.drawText(ellipsize(listOfNotNull(data.navRemain, data.navEta).joinToString("  ·  ").ifEmpty { "—" }, r - l - 2 * pad), l + pad, y(0.725f), text)

        navProRight(c, w, h)
        navProBottom(c, w, h)
    }

    /** Next turn along the bottom of the map: arrow, distance, instruction | distance and time left. */
    private fun turnPill(c: Canvas, h: Float, k: Card) {
        val t = data.navTurnText ?: return
        val l = k.l + h * 0.03f; val r = k.r - h * 0.03f
        val b = k.b - h * 0.025f; val top = b - h * 0.13f
        rect.set(l, top, r, b)
        fill.color = 0xE6101418.toInt(); c.drawRoundRect(rect, h * 0.035f, h * 0.035f, fill)
        val mid = (top + b) / 2
        val s = (b - top) * 0.8f
        turnIcon(c, l + h * 0.02f, mid - s / 2, s, gold)
        var x = l + h * 0.04f + s
        val dist = data.navTurnDist ?: ""
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.07f; text.color = gold
        c.drawText(dist, x, mid + h * 0.025f, text)
        if (dist.isNotEmpty()) x += text.measureText(dist) + h * 0.025f
        val right = listOfNotNull(data.navRemain, data.navEta).joinToString("  ·  ")
        text.textAlign = Paint.Align.RIGHT; text.typeface = bold; text.textSize = h * 0.045f; text.color = second
        c.drawText(right, r - h * 0.025f, mid + h * 0.016f, text)
        val rightW = text.measureText(right)
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.052f; text.color = white
        val room = r - rightW - x - h * 0.05f
        val say = if (text.measureText(t) <= room) t else roadOf(t).ifBlank { t }
        c.drawText(ellipsize(say, room), x, mid + h * 0.019f, text)
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
        if (on(DashLayout.Part.LEAN)) {
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
        }
        // Ride numbers.
        val rows = listOfNotNull(
            (labels.trip to (data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—")).takeIf { on(DashLayout.Part.TRIP) },
            (labels.time to (data.rideTime ?: "—")).takeIf { on(DashLayout.Part.RIDE_TIME) },
            (labels.heading to (data.heading ?: "—")).takeIf { on(DashLayout.Part.HEADING) },
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

    /** Bike row: phone temp, bike battery, fuel, tyres, ride mode, each with a level bar. */
    private fun navProBottom(c: Canvas, w: Float, h: Float) {
        val t = h * 0.775f; val b = h * 0.99f
        val l = w * 0.185f; val r = w * 0.992f  // the bike picture now sits behind the left column (navProLayout)
        data.fuelStop?.let { banner(c, l, t, r, b, h, it); return }
        val tyre = if (data.tyreFrontBar != null || data.tyreRearBar != null) "${bar(data.tyreFrontBar)} / ${bar(data.tyreRearBar)}" else "— bar"
        val mode = labels.tabs.getOrNull(modeIndex) ?: ""
        data class Cell(val label: String, val value: String, val level: Float?, val warn: Boolean = false)
        val cells = listOfNotNull(
            Cell(labels.phone, data.phoneTempC?.let { "$it °C" } ?: "—", data.phoneTempC?.let { (it - 20) / 30f }, data.phoneHot).takeIf { on(DashLayout.Part.PHONE_TEMP) },
            Cell(labels.battery, data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—", data.bikeVolts?.let { (it - 11.5f) / 3f }, (data.bikeVolts ?: 99f) < PkeBattery.RED_RUNNING_V).takeIf { on(DashLayout.Part.BIKE_BATTERY) },
            Cell(labels.fuel, data.fuel?.let { "${(it * 100).roundToInt()} %" } ?: "—", data.fuel, (data.fuel ?: 1f) < 0.15f).takeIf { on(DashLayout.Part.FUEL) },
            Cell(labels.tyre, tyre, null).takeIf { on(DashLayout.Part.TYRE) },
            Cell(labels.rideMode, mode, null).takeIf { on(DashLayout.Part.MODE_TABS) },
        )
        if (cells.isEmpty()) return  // every cell hidden: no empty card
        navCard(c, l, t, r, b, h)
        val cw = (r - l) / cells.size
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

    // ---------------------------------------------------------------- ADV NAV layout

    private val advRed = 0xFFFF2A2A.toInt()
    private val advWarn = 0xFFFFC51A.toInt()
    /** Tile icons under the map (null = a text glyph); loaded only for this template. */
    private val advIcons = if (base.adv) listOf(R.drawable.ic_nav_arrow, 0, R.drawable.ic_power, R.drawable.ic_tile_fuel, R.drawable.ic_speed)
        .map { id -> id.takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it)?.mutate() } } else emptyList()

    /**
     * ADV Nav, from the rider's 368G mockup: speed cluster left, live map right, ride tiles under
     * the map. The mockup's rpm, gear, ABS/TCS, side stand and coolant lamps have no source on the
     * bike link, so their places carry GPS speed, heading, lean, trip and phone numbers instead.
     */
    private fun adv(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        val rad = h * 0.03f
        rect.set(k.l, k.t, k.r, k.b)
        if (on(DashLayout.Part.LIVE_MAP)) {  // map hidden: no empty frame
            if (!mapInCard) { fill.color = theme.cardTop; c.drawRoundRect(rect, rad, rad, fill) }
            glow(c, h) { c.drawRoundRect(rect, rad, rad, stroke) }
        }  // lit frame round the map
        advBand(c, w, h)
        advCluster(c, w, h)
        advTiles(c, h, k)
    }

    /** Speed band rising from the bottom left like the mockup's rev band: km/h scale outside, red zone on top. */
    private fun advBand(c: Canvas, w: Float, h: Float) {
        val bx = w * 0.07f; val by = h * 0.8f; val rw = w * 0.12f; val rv = h * 0.5f
        val cx = bx + rw
        rect.set(bx, by - rv, bx + 2 * rw, by + rv)
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeWidth = h * 0.085f
        stroke.color = 0xFF141922.toInt(); c.drawArc(rect, 180f, 90f, false, stroke)
        val redF = redKmh / MAX_KMH.toFloat()
        stroke.color = (advRed and 0x00FFFFFF) or 0x44000000
        c.drawArc(rect, 180f + 90f * redF, 90f * (1 - redF), false, stroke)
        val v = (shown ?: 0).coerceIn(0, MAX_KMH)
        if (v > 0) {
            if (fx(DashLayout.Fx.GLOW)) speedGlow(c, h, v) { c.drawArc(rect, 180f, 90f * v / MAX_KMH, false, stroke) }
            // Blue low, red from the red zone up (the band is vertical-ish: the gradient runs bottom → top).
            val p = kotlin.math.sin(Math.toRadians(90.0 * redF)).toFloat()
            stroke.shader = if (v >= redKmh && alertKmh > 0) null else LinearGradient(0f, by, 0f, by - rv,
                intArrayOf(gold, lighten(gold), advRed, advRed), floatArrayOf(0f, p * 0.92f, p, 1f), Shader.TileMode.CLAMP)
            stroke.color = advRed
            c.drawArc(rect, 180f, 90f * v / MAX_KMH, false, stroke)
            stroke.shader = null
        }
        stroke.strokeCap = Paint.Cap.ROUND
        // Scale: a tick and a number every 40 km/h, on the outside of the band.
        text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic; text.textSize = h * 0.065f
        for (s in 0..MAX_KMH step 40) {
            val a = Math.toRadians(180.0 + 90.0 * s / MAX_KMH)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            stroke.color = white; stroke.strokeWidth = h * 0.006f
            c.drawLine(cx + (rw + h * 0.045f) * ca, by + (rv + h * 0.045f) * sa, cx + (rw + h * 0.07f) * ca, by + (rv + h * 0.07f) * sa, stroke)
            text.color = if (s >= redKmh) advRed else white
            c.drawText(s.toString(), cx + (rw + h * 0.12f) * ca, by + (rv + h * 0.11f) * sa + text.textSize * 0.35f, text)
        }
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.032f; text.color = dim
        c.drawText("km/h · GPS", w * 0.06f, h * 0.885f, text)
    }

    /** Warning pill, ride mode, max, the big speed, heading and lean, trip rows, then fuel and phone heat. */
    private fun advCluster(c: Canvas, w: Float, h: Float) {
        val sx = w * 0.285f; val r = w * 0.36f
        // Yellow pill like the mockup's SIDE STAND lamp, for warnings the app really has.
        val warn = data.fuelStop ?: if (data.phoneHot) "${labels.phone} ${labels.hot}" else null
        warn?.let {
            text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.036f
            val s = ellipsize(it, r - w * 0.235f - h * 0.04f)
            rect.set(w * 0.235f, h * 0.185f, r, h * 0.245f)  // clear of the band's "160"
            fill.color = advWarn; c.drawRoundRect(rect, h * 0.015f, h * 0.015f, fill)
            text.color = Color.BLACK; c.drawText(s, rect.centerX(), rect.centerY() + text.textSize * 0.35f, text)
        }
        if (on(DashLayout.Part.MODE_TABS)) {
            val mode = labels.tabs.getOrNull(modeIndex) ?: ""
            text.textAlign = Paint.Align.RIGHT; text.typeface = boldItalic; text.textSize = h * 0.075f; text.color = advRed
            c.drawText(ellipsize(mode, w * 0.16f), r, h * 0.33f, text)
        }
        if (on(DashLayout.Part.MAX_SPEED)) {
            text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.05f; text.color = white
            c.drawText("▲ ${data.maxKmh ?: "---"} km/h", sx, h * 0.405f, text)
        }
        val v = shown
        text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic; text.textSize = h * 0.21f
        text.color = if (over) red else white
        c.drawText(v?.toString() ?: "—", sx, h * 0.6f, text)
        text.typeface = bold; text.textSize = h * 0.055f; text.color = lighten(gold)
        c.drawText("km/h", sx, h * 0.665f, text)
        // Big green letter where the mockup's gear sits: the heading.
        if (on(DashLayout.Part.HEADING)) {
            text.typeface = boldItalic; text.textSize = h * 0.1f; text.color = second
            c.drawText(data.heading ?: "—", sx + w * 0.02f, h * 0.775f, text)
        }
        if (on(DashLayout.Part.LEAN)) {
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.028f; text.color = dim
            c.drawText(labels.lean, w * 0.215f, h * 0.715f, text)
            text.typeface = bold; text.textSize = h * 0.042f; text.color = advWarn
            c.drawText(data.leanDeg?.let { CustomDashUi.leanText(it) } ?: "—", w * 0.215f, h * 0.765f, text)
        }
        val rows = listOfNotNull(
            (labels.trip to listOfNotNull(data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—",
                data.rideTime?.takeIf { on(DashLayout.Part.RIDE_TIME) }).joinToString("  ")).takeIf { on(DashLayout.Part.TRIP) },
            (labels.odo to (data.odoKm?.let { String.format(Locale.getDefault(), "%,.0f km", it) } ?: "—")).takeIf { on(DashLayout.Part.ODO) },
        )
        for ((i, row) in rows.withIndex()) {
            val y = h * (0.835f + i * 0.048f)
            text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.032f; text.color = dim
            c.drawText(row.first, w * 0.2f, y, text)
            text.textAlign = Paint.Align.RIGHT; text.textSize = h * 0.036f; text.color = white
            c.drawText(ellipsize(row.second, w * 0.11f), r, y, text)
        }
        // Thin lit line closing the cluster, then fuel (left) and phone heat (right) under it.
        stroke.shader = LinearGradient(w * 0.01f, 0f, r, 0f, intArrayOf(0x00000000, gold, 0x00000000), null, Shader.TileMode.CLAMP)
        stroke.strokeWidth = h * 0.005f; c.drawLine(w * 0.01f, h * 0.91f, r, h * 0.91f, stroke)
        stroke.shader = null
        if (on(DashLayout.Part.FUEL)) {
            pump?.let { it.setBounds((w * 0.012f).toInt(), (h * 0.925f).toInt(), (w * 0.012f + h * 0.055f).toInt(), (h * 0.98f).toInt()); it.draw(c) }
            fuelBar(c, w * 0.012f + h * 0.065f, h * 0.932f, w * 0.2f, h * 0.975f, h)
        }
        if (on(DashLayout.Part.PHONE_TEMP)) {
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.03f; text.color = dim
            c.drawText(labels.phone, w * 0.215f, h * 0.966f, text)
            text.textAlign = Paint.Align.RIGHT; text.typeface = bold; text.textSize = h * 0.045f
            text.color = if (data.phoneHot) red else white
            c.drawText(data.phoneTempC?.let { "$it°C" } ?: "—", r, h * 0.97f, text)
        }
    }

    /** Five tiles under the map in the mockup's menu style, each with a live value (the dash has no touch menu). */
    private fun advTiles(c: Canvas, h: Float, k: Card) {
        val t = h * 0.805f; val b = h * 0.985f; val gap = h * 0.02f
        // The icon rides with its tile: a hidden tile drops out and the rest re-flow (like tileRow).
        data class Tile(val glyph: String?, val value: String, val label: String, val icon: android.graphics.drawable.Drawable?)
        val tiles = listOfNotNull(
            Tile(null, data.navTurnDist ?: data.navRemain ?: "—", labels.navigation, advIcons.getOrNull(0)),
            Tile("♫", data.media?.substringAfter(' ') ?: "—", labels.media, advIcons.getOrNull(1)),
            Tile(null, data.phoneBattery?.let { "$it%" + if (data.phoneCharging) " ⚡" else "" } ?: "—", labels.phoneBattery, advIcons.getOrNull(2)),
            Tile(null, data.rangeKm?.let { "≈$it km" } ?: "—", labels.rangeShort, advIcons.getOrNull(3)).takeIf { on(DashLayout.Part.RANGE) },
            Tile(null, data.avgKmh?.toString() ?: "—", labels.avg, advIcons.getOrNull(4)).takeIf { on(DashLayout.Part.AVG) },
        )
        val tw = (k.r - k.l - gap * (tiles.size - 1)) / tiles.size
        val rad = h * 0.025f
        for ((i, tile) in tiles.withIndex()) {
            val l = k.l + i * (tw + gap); val r = l + tw
            rect.set(l, t, r, b)
            // First tile lit blue like the mockup's Home key; the rest dark glass.
            fill.shader = if (i == 0) LinearGradient(0f, t, 0f, b, (gold and 0x00FFFFFF) or 0x99000000.toInt(), 0xFF0A1630.toInt(), Shader.TileMode.CLAMP)
                else LinearGradient(0f, t, 0f, b, theme.cardTop, theme.cardBottom, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, rad, rad, fill); fill.shader = null
            stroke.color = if (i == 0) lighten(gold) else 0xFF26303D.toInt(); stroke.strokeWidth = h * 0.004f
            c.drawRoundRect(rect, rad, rad, stroke)
            // Icon + value on one line, label under.
            val cx = (l + r) / 2; val y = t + h * 0.085f; val ic = h * 0.05f
            text.typeface = bold; text.textSize = h * 0.045f
            val value = ellipsize(tile.value, tw - ic - h * 0.05f)
            val total = ic + h * 0.012f + text.measureText(value)
            val x0 = cx - total / 2
            val icon = tile.icon
            if (icon != null) {
                icon.setTint(if (i == 0) white else gold)
                icon.setBounds(x0.toInt(), (y - ic * 0.85f).toInt(), (x0 + ic).toInt(), (y + ic * 0.15f).toInt()); icon.draw(c)
            } else tile.glyph?.let {
                text.textAlign = Paint.Align.CENTER; text.color = if (i == 0) white else gold
                c.drawText(it, x0 + ic / 2, y, text)
            }
            text.textAlign = Paint.Align.LEFT; text.color = white
            c.drawText(value, x0 + ic + h * 0.012f, y, text)
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.03f
            text.color = if (i == 0) white else dim
            c.drawText(ellipsize(tile.label, tw - h * 0.03f), cx, b - h * 0.03f, text)
        }
    }

    /** Compass disc in the map's top-right corner: the heading with a red north tick. */
    private fun compass(c: Canvas, h: Float, k: Card, left: Boolean = false) {
        val heading = data.heading?.takeIf { on(DashLayout.Part.HEADING) } ?: return
        val r = h * 0.055f; val cx = if (left) k.l + r + h * 0.025f else k.r - r - h * 0.025f; val cy = k.t + r + h * 0.025f
        fill.color = 0xE60B111A.toInt(); c.drawCircle(cx, cy, r, fill)
        stroke.color = 0xFF5A6575.toInt(); stroke.strokeWidth = h * 0.004f; c.drawCircle(cx, cy, r, stroke)
        path.reset(); path.moveTo(cx, cy - r * 0.95f); path.lineTo(cx - r * 0.18f, cy - r * 0.65f); path.lineTo(cx + r * 0.18f, cy - r * 0.65f); path.close()
        fill.color = advRed; c.drawPath(path, fill)
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.042f; text.color = white
        c.drawText(heading, cx, cy + h * 0.025f, text)
    }

    // ---------------------------------------------------------------- TOURING NAV layout

    /**
     * Touring Nav, from the rider's "Touring Edition" mockup: round gauge over the bike left, live
     * map centre, guidance right, a height strip under the map and the bike row along the bottom.
     * The mockup's rpm, gear, engine temperature and tyre pressures have no source on the bike link:
     * the ring shows GPS speed, the gear box the heading, and the row phone heat and lean instead.
     */
    /**
     * 3D Terrain: the rider's mockup is the whole picture ([art], squashed to the panel with every value
     * painted out); this draws the live values where the mockup had them. Positions are the mockup's
     * own (x / 1671, y / 941), so they line up with the picture.
     */
    private fun terrain(c: Canvas, w: Float, h: Float) {
        art?.let { rect.set(0f, 0f, w, h); c.drawBitmap(it, null, rect, fill) }
        fun x(v: Int) = w * v / 1671f
        fun y(v: Int) = h * v / 941f
        fun put(s: String, px: Float, py: Float, size: Float, color: Int, face: Typeface = bold, align: Paint.Align = Paint.Align.LEFT, maxW: Float = w) {
            text.textAlign = align; text.typeface = face; text.textSize = h * size; text.color = color
            c.drawText(ellipsize(s, maxW), px, py, text)
        }
        // Status pill: GPS, weather, clock (Bluetooth, Wi-Fi and signal icons are in the picture).
        data.gps?.let { put(it, x(840), y(52), 0.045f, data.gpsColor, maxW = x(1120) - x(840)) }
        data.weather?.let { put(it, x(1370), y(52), 0.045f, if (data.weatherRain) 0xFF4FC3F7.toInt() else white, maxW = x(1515) - x(1370)) }
        put(data.clock, x(1648), y(56), 0.062f, white, align = Paint.Align.RIGHT)
        // Left column: speed (the bike below is in the picture), then lean and average speed.
        gauge(c, w, h, cx = x(165), cy = y(300), rad = h * 0.16f, compact = true)
        val lean = data.leanDeg?.takeIf { on(DashLayout.Part.LEAN) }?.let { CustomDashUi.leanText(it) }
        val avg = data.avgKmh?.takeIf { on(DashLayout.Part.AVG) }?.let { "${labels.avgShort} $it" }
        listOfNotNull(lean, avg).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { put(it, x(165), y(520), 0.034f, white, align = Paint.Align.CENTER, maxW = x(290)) }
        // Turn pill: next turn | destination | distance left | arrival.
        val lbl = 0.03f; val ly = y(600); val vy = y(645)
        val turn = data.navTurnText
        if (turn != null) {
            turnIcon(c, x(372), y(570), h * 0.105f, gold)
            put(data.navTurnDist ?: "", x(462), y(650), 0.07f, gold, maxW = x(622) - x(462))
            text.typeface = bold; text.textSize = h * 0.037f
            for ((i, line) in wrap2(turn, x(812) - x(628)).withIndex()) put(line, x(628), y(612 + i * 42), 0.037f, white)
        } else {
            put(labels.noDestination, x(380), ly, 0.034f, white, maxW = x(812) - x(380))
            put(labels.pickDestination, x(380), vy, 0.026f, dim, Typeface.DEFAULT, maxW = x(812) - x(380))
        }
        put(labels.destination, x(948), ly, lbl, white, Typeface.DEFAULT, maxW = x(1085) - x(948))
        put(data.navDest ?: "—", x(948), vy, 0.04f, second, maxW = x(1085) - x(948))
        put(labels.remain, x(1175), ly, lbl, white, Typeface.DEFAULT, maxW = x(1320) - x(1175))
        put(data.navRemain ?: "—", x(1175), vy, 0.045f, white, maxW = x(1320) - x(1175))
        put(labels.eta, x(1405), ly, lbl, white, Typeface.DEFAULT, maxW = x(1612) - x(1405))
        put(data.navEta ?: "—", x(1405), vy, 0.045f, white, maxW = x(1612) - x(1405))
        // Height row: now, the last 20 minutes, climbed / descended.
        if (on(DashLayout.Part.ALTITUDE)) {
            put(labels.alt, x(538), y(695), lbl, white, Typeface.DEFAULT)
            put(data.altitudeM?.let { String.format(Locale.getDefault(), "%,d m", it) } ?: "—", x(538), y(735), 0.045f, white, maxW = x(668) - x(538))
            profile(c, h, x(680), y(672), x(1180), y(742))
            put(labels.climb, x(1295), y(702), 0.034f, gold)
            put(labels.descent, x(1298), y(743), 0.034f, second)
            put(data.climbM?.let { "+$it m" } ?: "—", x(1480), y(702), 0.042f, white, align = Paint.Align.RIGHT)
            put(data.descentM?.let { "−$it m" } ?: "—", x(1480), y(743), 0.042f, white, align = Paint.Align.RIGHT)
        }
        // Bottom row, as in the mockup (its ENG TEMP is the phone's heat: the bike doesn't report the engine's).
        data.fuelStop?.let { banner(c, x(12), y(772), x(1660), y(885), h, it); return }
        if (on(DashLayout.Part.MODE_TABS)) {
            val mode = labels.tabs.getOrNull(modeIndex) ?: ""
            put(labels.rideMode, x(145), y(812), 0.03f, white)
            put(mode, x(145), y(852), 0.055f, 0xFFFFE14D.toInt(), boldItalic, maxW = x(318) - x(145))
        }
        val vb = y(845)
        fun cell(label: String, value: String, lx: Int, warn: Boolean, maxR: Int) {
            put(label, x(lx), y(805), 0.027f, white, maxW = x(maxR) - x(lx))
            put(value, x(lx), vb, 0.048f, if (warn) red else white, maxW = x(maxR) - x(lx))
        }
        if (on(DashLayout.Part.PHONE_TEMP)) {
            cell(labels.phone, data.phoneTempC?.let { "$it °C" } ?: "—", 415, data.phoneHot, 530)
            slantBar(c, x(362), y(857), x(518), y(871), data.phoneTempC?.let { (it - 20) / 30f }, if (data.phoneHot) red else gold)
        }
        if (on(DashLayout.Part.BIKE_BATTERY)) {
            val low = (data.bikeVolts ?: 99f) < PkeBattery.RED_RUNNING_V
            cell(labels.battery, data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—", 640, low, 750)
            slantBar(c, x(566), y(857), x(735), y(871), data.bikeVolts?.let { (it - 11.5f) / 3f }, if (low) red else gold)
        }
        if (on(DashLayout.Part.FUEL)) {
            val low = (data.fuel ?: 1f) < 0.15f
            cell(labels.fuel, data.fuel?.let { "${(it * 100).roundToInt()} %" } ?: "—", 846, low, 990)
            slantBar(c, x(782), y(857), x(978), y(871), data.fuel, if (low) red else gold)
        }
        if (on(DashLayout.Part.TYRE)) {
            val tyre = if (data.tyreFrontBar != null || data.tyreRearBar != null) "F ${bar(data.tyreFrontBar)}  R ${bar(data.tyreRearBar)}" else "— bar"
            cell(labels.tyre, tyre, 1101, false, 1300)
            slantBar(c, x(1032), y(857), x(1155), y(871), data.tyreFrontBar?.let { it / 2.9f }, gold)
            slantBar(c, x(1180), y(857), x(1295), y(871), data.tyreRearBar?.let { it / 2.9f }, gold)
        }
        if (on(DashLayout.Part.TRIP)) {
            put(labels.trip, x(1397), y(812), 0.027f, white)
            put(data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—", x(1397), y(850), 0.042f, white, maxW = x(1520) - x(1397))
        }
        if (on(DashLayout.Part.ODO)) {
            put(labels.odo, x(1536), y(812), 0.027f, white)
            put(data.odoKm?.let { String.format(Locale.getDefault(), "%,.0f km", it) } ?: "—", x(1536), y(850), 0.042f, white, maxW = x(1660) - x(1536))
        }
    }

    /** Smart Ride: the rider's mockup as the background ([art]); the phone and link values where it had them (x / 1672, y / 941). */
    private fun smart(c: Canvas, w: Float, h: Float) {
        art?.let { rect.set(0f, 0f, w, h); c.drawBitmap(it, null, rect, fill) }
        fun x(v: Int) = w * v / 1672f
        fun y(v: Int) = h * v / 941f
        fun put(s: String, px: Float, py: Float, size: Float, color: Int, face: Typeface = bold, align: Paint.Align = Paint.Align.LEFT, maxW: Float = w) {
            text.textAlign = align; text.typeface = face; text.textSize = h * size; text.color = color
            c.drawText(ellipsize(s, maxW), px, py, text)
        }
        val green = 0xFF3DEB5A.toInt()
        // Header: the app (small), GPS, what reaches the dash, clock.
        put("ZRIDE", x(160), y(62), 0.06f, white, boldItalic)
        text.textSize = h * 0.06f
        put(" SCREEN", x(160) + text.measureText("ZRIDE"), y(62), 0.06f, second, boldItalic)
        data.gps?.let { put(it, x(740), y(60), 0.045f, data.gpsColor, maxW = x(1130) - x(740)) }
        data.videoFps?.let { put("$it FPS", x(1190), y(60), 0.042f, white, maxW = x(1345) - x(1190)) }
        put(data.clock, x(1635), y(68), 0.072f, white, align = Paint.Align.RIGHT)
        // Phone cards, right.
        put(if (data.phoneCharging) labels.charging else labels.onBattery, x(1630), y(170), 0.04f, if (data.phoneCharging) green else dim, align = Paint.Align.RIGHT, maxW = x(1630) - x(1440))
        val batt = data.phoneBattery
        put(batt?.let { "$it%" } ?: "—", x(1300), y(268), 0.075f, white)
        bar(c, x(1462), y(230), x(1630), y(260), (batt ?: 0) / 100f, if ((batt ?: 100) < 20) red else green)
        put(labels.phoneBattery, x(1288), y(302), 0.028f, dim, Typeface.DEFAULT, maxW = x(1500) - x(1288))
        data.phoneMv?.let { put("$it mV", x(1632), y(302), 0.032f, white, align = Paint.Align.RIGHT) }
        val temp = data.phoneTempC
        put(temp?.let { "$it°C" } ?: "—", x(1345), y(405), 0.07f, white)
        bar(c, x(1500), y(364), x(1630), y(396), temp?.let { (it - 20) / 30f } ?: 0f, if (data.phoneHot) red else green)
        put(if (data.phoneHot) labels.hot else labels.normal, x(1632), y(438), 0.034f, if (data.phoneHot) red else green, align = Paint.Align.RIGHT)
        put(data.wifiDbm?.let { "$it dBm" } ?: "—", x(1340), y(528), 0.042f, white, maxW = x(1460) - x(1340))
        put(data.storageFreePct?.let { "$it%" } ?: "—", x(1636), y(528), 0.045f, white, align = Paint.Align.RIGHT)
        val cell = data.cellReady
        put(when (cell) { true -> labels.ready; false -> labels.offline; null -> "—" }, x(1325), y(652), 0.045f, if (cell == false) red else green, maxW = x(1440) - x(1325))
        put(data.pressureHpa?.let { String.format(Locale.getDefault(), "%.0f hPa", it) } ?: "—", x(1640), y(648), 0.038f, white, align = Paint.Align.RIGHT, maxW = x(1640) - x(1525))  // clear of the gauge icon
        // Now playing, right of the handlebar keys.
        data.media?.let { put(it, x(1000), y(735), 0.034f, white, maxW = x(1185) - x(1000)) }
        // Bottom row: GPS, phone, mobile data, link (the TBOX cell is in the picture).
        val vy = y(874)
        data.gps?.let { put(it, x(178), vy, 0.045f, data.gpsColor, maxW = x(330) - x(178)) } ?: put("—", x(178), vy, 0.045f, white)
        put(batt?.let { "$it%" } ?: "—", x(473), vy, 0.048f, green)
        put(when (cell) { true -> labels.ready; false -> labels.offline; null -> "—" }, x(812), vy, 0.048f, if (cell == false) red else green, maxW = x(990) - x(812))
        put(data.linkMbps?.let { String.format(Locale.getDefault(), "%.1f MBPS", it) } ?: "—", x(1120), vy, 0.048f, green, maxW = x(1310) - x(1120))
    }

    /** [s] at [px], [py] in [size] × h, shrunk (not cut) to fit [maxW]. */
    private fun putFit(c: Canvas, s: String, px: Float, py: Float, size: Float, h: Float, color: Int, face: Typeface, align: Paint.Align, maxW: Float) {
        text.textAlign = align; text.typeface = face; text.textSize = h * size; text.color = color
        val tw = text.measureText(s)
        if (tw > maxW) text.textSize *= maxW / tw
        c.drawText(s, px, py, text)
    }

    private val segPath = Path()
    private val pathMeasure = android.graphics.PathMeasure()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    /**
     * Race Bar, from the rider's "368G" mockup ([art], every value painted out; x / 1672, y / 941). The bike
     * sends no rpm, gear or engine heat: the rpm bar is a 0–200 km/h speed bar, the gear box the heading,
     * the rpm card the lean angle and ENG TEMP the phone's heat. The live map fills the turn card.
     */
    private fun raceBar(c: Canvas, w: Float, h: Float) {
        art?.let { rect.set(0f, 0f, w, h); c.drawBitmap(it, null, rect, fill) }
        fun x(v: Int) = w * v / 1672f
        fun y(v: Int) = h * v / 941f
        fun put(s: String, px: Float, py: Float, size: Float, color: Int, face: Typeface = bold, align: Paint.Align = Paint.Align.LEFT, maxW: Float = w) {
            text.textAlign = align; text.typeface = face; text.textSize = h * size; text.color = color
            c.drawText(ellipsize(s, maxW), px, py, text)
        }
        val yellow = second
        // Header: the bike's own name, make white and model yellow like the mockup.
        val name = data.bikeName.ifBlank { "Zontes" }.uppercase(Locale.ROOT)
        val make = name.substringBefore(' ', "").let { if (it.isEmpty()) "" else "$it " }
        put(make, x(118), y(93), 0.08f, white, boldItalic)
        text.textSize = h * 0.08f
        val mw = text.measureText(make)
        put(name.substring(make.length), x(118) + mw, y(93), 0.08f, yellow, boldItalic, maxW = x(520) - x(118) - mw)
        // Status: GPS, weather, clock (Bluetooth, Wi-Fi and signal icons are in the picture).
        data.gps?.let { put(it, x(815), y(90), 0.045f, data.gpsColor, maxW = x(1150) - x(815)) }
        put(data.clock, x(1625), y(95), 0.07f, white, align = Paint.Align.RIGHT)
        val clockL = x(1625) - text.measureText(data.clock) - h * 0.02f  // "13:35 น." is wider than the mockup's
        data.weather?.let { put(it, x(1360), y(90), 0.045f, if (data.weatherRain) 0xFF4FC3F7.toInt() else white, maxW = clockL - x(1360)) }
        // Speed bar where the mockup had the rpm: up the left, round the corner, along the top.
        path.reset(); path.moveTo(x(116), y(650)); path.lineTo(x(146), y(345)); path.quadTo(x(162), y(250), x(300), y(250)); path.lineTo(x(992), y(250))
        pathMeasure.setPath(path, false)
        val len = pathMeasure.length
        val n = 40; val gap = len / n * 0.2f
        val lit = (((shown ?: 0) / RACE_MAX_KMH.toFloat()) * n).roundToInt()
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeWidth = h * 0.07f
        for (i in 0 until n) {
            segPath.reset()
            pathMeasure.getSegment(i * len / n + gap / 2, (i + 1) * len / n - gap / 2, segPath, true)
            val hot = i >= n * 0.8f
            stroke.color = when { i < lit && hot -> red; i < lit -> white; hot -> 0xFF4A1218.toInt(); else -> 0xFF3A3A3E.toInt() }
            c.drawPath(segPath, stroke)
        }
        text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic; text.textSize = h * 0.05f
        for (j in 0..10) {
            pathMeasure.getPosTan(len * j / 10f, pos, tan)
            val off = h * 0.075f  // outside the bar: left of the climb, above the top
            text.color = if (j >= 8) red else white
            c.drawText("${j * RACE_MAX_KMH / 10}", pos[0] + tan[1] * off, pos[1] - tan[0] * off + text.textSize * 0.35f, text)
        }
        put("km/h", x(100), y(668), 0.026f, dim)
        // Big speed.
        put(shown?.toString() ?: "—", x(700), y(525), 0.27f, if (over) red else white, boldItalic, Paint.Align.RIGHT)
        put("km/h", x(708), y(522), 0.07f, white, boldItalic)
        // Gear box: the heading.
        if (on(DashLayout.Part.HEADING)) {
            putFit(c, data.heading ?: "—", x(922), y(455), 0.14f, h, yellow, boldItalic, Paint.Align.CENTER, x(980) - x(865))
            putFit(c, labels.heading, x(922), y(508), 0.034f, h, white, bold, Paint.Align.CENTER, x(985) - x(860))
        }
        // Trip, odo, fuel.
        if (on(DashLayout.Part.TRIP)) {
            put(labels.trip, x(258), y(615), 0.03f, white, Typeface.DEFAULT, maxW = x(440) - x(258))
            put(data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—", x(258), y(664), 0.06f, white, maxW = x(440) - x(258))
        }
        if (on(DashLayout.Part.ODO)) {
            put(labels.odo, x(493), y(615), 0.03f, white, Typeface.DEFAULT, maxW = x(675) - x(493))
            put(data.odoKm?.let { String.format(Locale.getDefault(), "%,.0f km", it) } ?: "—", x(493), y(664), 0.06f, white, maxW = x(675) - x(493))
        }
        if (on(DashLayout.Part.FUEL)) {
            val low = (data.fuel ?: 1f) < 0.15f
            slantBar(c, x(778), y(618), x(910), y(648), data.fuel, if (low) red else gold)
            put(data.fuel?.let { "${(it * 100).roundToInt()}%" } ?: "—", x(1002), y(650), 0.055f, if (low) red else white, align = Paint.Align.RIGHT)
        }
        // RPM card: the lean angle, on a 0–50° bar (red from 40°).
        if (on(DashLayout.Part.LEAN)) {
            put(labels.lean, x(1066), y(512), 0.04f, white, maxW = x(1200) - x(1066))
            putFit(c, data.leanDeg?.let { CustomDashUi.leanText(it) } ?: "—", x(1062), y(622), 0.13f, h, white, boldItalic, Paint.Align.LEFT, x(1198) - x(1062))
            text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic; text.textSize = h * 0.042f; text.color = white
            for (j in 0..5) c.drawText("${j * 10}", x(1222) + (x(1597) - x(1222)) * j / 5f, y(532), text)
            val segs = 20
            val f = ((data.leanDeg?.let { kotlin.math.abs(it) } ?: 0f) / 50f).coerceIn(0f, 1f)
            val litN = (f * segs).roundToInt()
            val l0 = x(1208); val r0 = x(1605); val b0 = y(604)
            val sw = (r0 - l0) / segs; val skew = h * 0.012f
            for (i in 0 until segs) {
                val xl = l0 + i * sw; val top = y(584) + (y(546) - y(584)) * i / (segs - 1f)
                path.reset(); path.moveTo(xl + skew, top); path.lineTo(xl + sw * 0.8f + skew, top); path.lineTo(xl + sw * 0.8f, b0); path.lineTo(xl, b0); path.close()
                val hot = i >= segs * 0.8f
                fill.color = when { i < litN && hot -> red; i < litN -> white; hot -> 0xFF4A1218.toInt(); else -> 0xFF2E2E32.toInt() }
                c.drawPath(path, fill)
            }
        }
        // Bottom row (a low-fuel stop takes its place).
        data.fuelStop?.let { banner(c, x(70), y(728), x(1610), y(835), h, it); return }
        if (on(DashLayout.Part.MODE_TABS)) {
            val mode = labels.tabs.getOrNull(modeIndex) ?: ""
            put(labels.rideMode, x(228), y(762), 0.03f, white, maxW = x(420) - x(228))
            put(mode, x(228), y(814), 0.06f, yellow, boldItalic, maxW = x(420) - x(228))
        }
        fun cell(label: String, value: String, lx: Int, warn: Boolean, maxR: Int) {
            put(label, x(lx), y(762), 0.03f, white, maxW = x(maxR) - x(lx))
            put(value, x(lx), y(814), 0.06f, if (warn) red else white, maxW = x(maxR) - x(lx))
        }
        if (on(DashLayout.Part.PHONE_TEMP)) cell(labels.phone, data.phoneTempC?.let { "$it °C" } ?: "—", 592, data.phoneHot, 750)
        if (on(DashLayout.Part.BIKE_BATTERY)) {
            cell(labels.battery, data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—", 944, (data.bikeVolts ?: 99f) < PkeBattery.RED_RUNNING_V, 1110)
        }
        if (on(DashLayout.Part.TYRE)) {
            val tyre = if (data.tyreFrontBar != null || data.tyreRearBar != null) "F ${bar(data.tyreFrontBar)}  R ${bar(data.tyreRearBar)} bar" else "— bar"
            cell(labels.tyre, tyre, 1303, false, 1610)
        }
    }

    /**
     * Android Auto + side panel: clock, big speed, fuel, then the ticked items as rows, [pw] wide.
     * Sizes are fractions of [h] so any panel width / dash height works; rows shrink to fit, and what
     * still doesn't fit is left off (the rider unticks something to make room).
     */
    private fun side(c: Canvas, pw: Float, h: Float, leftEdge: Boolean = false, reserve: Float = 0f) {
        val p = h * 0.035f
        val r = pw - p * 1.3f
        fill.shader = LinearGradient(0f, 0f, 0f, h, theme.cardTop, 0xFF050506.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, pw, h, fill)
        fill.shader = null
        // A thin glowing edge where Android Auto starts.
        stroke.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x00000000, gold, 0x00000000), null, Shader.TileMode.CLAMP)
        stroke.strokeWidth = h * 0.006f
        val edge = if (leftEdge) stroke.strokeWidth else pw - stroke.strokeWidth
        c.drawLine(edge, 0f, edge, h, stroke)
        stroke.shader = null
        // Top: clock, then GPS or the weather on the right.
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.075f; text.color = white
        c.drawText(data.clock, p, p + h * 0.065f, text)
        val clockR = p + text.measureText(data.clock) + h * 0.02f
        status()?.let { (s, col) -> putFit(c, s, r, p + h * 0.06f, 0.04f, h, col, bold, Paint.Align.RIGHT, r - clockR) }
        // Speed.
        val speed = shown?.toString() ?: "—"
        putFit(c, speed, pw / 2, h * 0.325f, 0.22f, h, if (over) red else white, boldItalic, Paint.Align.CENTER, pw - p * 2)
        putFit(c, "km/h", pw / 2, h * 0.385f, 0.042f, h, dim, bold, Paint.Align.CENTER, pw - p * 2)
        var y = h * 0.42f
        if (on(DashLayout.Part.FUEL)) y = fuelBlock(c, p, r, y, h)
        val rows = DashLayout.SIDE_PARTS.filter { it != DashLayout.Part.FUEL && it != DashLayout.Part.GPS && on(it) }.mapNotNull { sideRow(it) }
        rowList(c, rows, p, r, y, h - p * 0.6f - reserve, h)
    }

    /** GPS badge (if ticked) or the weather, with its colour; null = neither. */
    private fun status(): Pair<String, Int>? = data.gps?.takeIf { on(DashLayout.Part.GPS) }?.let { it to data.gpsColor }
        ?: data.weather?.let { it to if (data.weatherRain) 0xFF4FC3F7.toInt() else dim }

    /** FUEL label, percent and a slanted level bar across [l]..[r] from [y]; returns the y below it. */
    private fun fuelBlock(c: Canvas, l: Float, r: Float, y: Float, h: Float): Float {
        val low = (data.fuel ?: 1f) < 0.15f
        putFit(c, labels.fuel, l, y + h * 0.04f, 0.036f, h, dim, bold, Paint.Align.LEFT, (r - l) * 0.6f)
        putFit(c, data.fuel?.let { "${(it * 100).roundToInt()}%" } ?: "—", r, y + h * 0.04f, 0.04f, h, if (low) red else white, bold, Paint.Align.RIGHT, (r - l) * 0.4f)
        slantBar(c, l, y + h * 0.058f, r, y + h * 0.085f, data.fuel, if (low) red else gold)
        return y + h * 0.11f
    }

    /** [rows] as label · value boxes in [l]..[r], [top]..[bottom]; they shrink to fit, what still doesn't fit is left off. */
    private fun rowList(c: Canvas, rows: List<Triple<String, String, Boolean>>, l: Float, r: Float, top: Float, bottom: Float, h: Float) {
        if (rows.isEmpty()) return
        val avail = bottom - top
        val n = rows.size.coerceAtMost((avail / (h * 0.07f)).toInt().coerceAtLeast(1))
        val rowH = (avail / n).coerceAtMost(h * 0.13f)
        val gap = rowH * 0.12f
        var y = top
        for ((label, value, warn) in rows.take(n)) {
            rect.set(l, y, r, y + rowH - gap)
            fill.color = 0x66101418; c.drawRoundRect(rect, rowH * 0.18f, rowH * 0.18f, fill)
            val mid = y + (rowH - gap) / 2
            val half = (r - l) - h * 0.03f
            putFit(c, label, l + h * 0.015f, mid + rowH * 0.14f, (rowH * 0.44f) / h, h, 0xFFC4C0B6.toInt(), bold, Paint.Align.LEFT, half * 0.48f)
            putFit(c, value, r - h * 0.015f, mid + rowH * 0.18f, (rowH * 0.56f) / h, h, if (warn) red else white, bold, Paint.Align.RIGHT, half * 0.5f)
            y += rowH
        }
    }

    /** Label, value and "warn" (red) of a side-panel row; null = nothing to show. */
    private fun sideRow(p: DashLayout.Part): Triple<String, String, Boolean>? = when (p) {
        DashLayout.Part.TRIP -> Triple(labels.trip, data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—", false)
        DashLayout.Part.ODO -> Triple(labels.odo, data.odoKm?.let { String.format(Locale.getDefault(), "%,.0f km", it) } ?: "—", false)
        DashLayout.Part.RIDE_TIME -> Triple(labels.time, data.rideTime ?: "—", false)
        DashLayout.Part.AVG -> Triple(labels.avgShort, data.avgKmh?.let { "$it km/h" } ?: "—", false)
        DashLayout.Part.MAX_SPEED -> Triple(labels.max, data.maxKmh?.let { "$it km/h" } ?: "—", false)
        DashLayout.Part.RANGE -> Triple(labels.rangeShort, data.rangeKm?.let { "$it km" } ?: "—", false)
        DashLayout.Part.ALTITUDE -> Triple(labels.alt, data.altitudeM?.let { String.format(Locale.getDefault(), "%,d m", it) } ?: "—", false)
        DashLayout.Part.HEADING -> Triple(labels.heading, data.heading ?: "—", false)
        DashLayout.Part.LEAN -> Triple(labels.lean, data.leanDeg?.let { CustomDashUi.leanText(it) } ?: "—", false)
        DashLayout.Part.BIKE_BATTERY -> Triple(labels.battery, data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—", (data.bikeVolts ?: 99f) < PkeBattery.RED_RUNNING_V)
        DashLayout.Part.PHONE_TEMP -> Triple(labels.phone, data.phoneTempC?.let { "$it °C" } ?: "—", data.phoneHot)
        DashLayout.Part.TYRE -> Triple(labels.tyre, if (data.tyreFrontBar != null || data.tyreRearBar != null) "${bar(data.tyreFrontBar)} / ${bar(data.tyreRearBar)}" else "—", false)
        DashLayout.Part.MAINTENANCE -> data.maintenance?.let { Triple(labels.maint, it, false) }
        else -> null
    }

    /** Editor only: where Android Auto goes, a dark map with a blue route, so the split reads at a glance. */
    private fun aaStandIn(c: Canvas, l: Float, w: Float, h: Float, top: Float = 0f) {
        if (top > 0f) { c.translate(0f, top); aaStandIn(c, l, w, h - top); c.translate(0f, -top); return }
        rect.set(l, 0f, w, h)
        fill.color = 0xFF1C232B.toInt(); c.drawRect(rect, fill)
        stroke.shader = null; stroke.color = 0xFF2E3944.toInt(); stroke.strokeWidth = h * 0.03f
        val cw = w - l
        c.drawLine(l, h * 0.3f, w, h * 0.42f, stroke)
        c.drawLine(l + cw * 0.35f, 0f, l + cw * 0.55f, h, stroke)
        c.drawLine(l + cw * 0.8f, 0f, l + cw * 0.7f, h, stroke)
        path.reset(); path.moveTo(l + cw * 0.5f, h * 0.95f); path.lineTo(l + cw * 0.47f, h * 0.36f); path.lineTo(l + cw * 0.76f, h * 0.4f); path.lineTo(l + cw * 0.74f, h * 0.05f)
        stroke.color = 0xFF4A8BFF.toInt(); stroke.strokeWidth = h * 0.035f; c.drawPath(path, stroke)
        putFit(c, "Android Auto", l + cw / 2, h * 0.62f, 0.07f, h, white, bold, Paint.Align.CENTER, cw * 0.9f)
        putFit(c, "Google Maps · Waze", l + cw / 2, h * 0.7f, 0.04f, h, dim, bold, Paint.Align.CENTER, cw * 0.9f)
    }

    /** Background of the Android Auto templates: the side panel's dark fade over the whole dash. */
    private fun aaBack(c: Canvas, w: Float, h: Float) {
        fill.shader = LinearGradient(0f, 0f, 0f, h, theme.cardTop, 0xFF050506.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    /** Dark card where Android Auto goes and its glowing rim; the glow shows in the margin round it. */
    private fun aaFrame(c: Canvas, k: Card, h: Float) {
        val rad = h * 0.03f  // = cardCornerPx
        rect.set(k.l, k.t, k.r, k.b)
        fill.color = 0xFF0B0E12.toInt(); c.drawRoundRect(rect, rad, rad, fill)
        stroke.shader = null
        glow(c, h) { c.drawRoundRect(rect, rad, rad, stroke) }
    }

    /** Low-fuel banner for a narrow column: each " · " part of [msg] on its own line. */
    private fun stackBanner(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float, msg: String) {
        banner(c, l, t, r, b, h, "")
        val lines = msg.split(" · ").take(3)
        val lh = min(h * 0.07f, (b - t) / (lines.size + 0.5f))
        val top = (t + b) / 2 - lines.size * lh / 2
        for ((i, s) in lines.withIndex()) putFit(c, s, (l + r) / 2, top + lh * (i + 0.78f), lh * 0.78f / h, h, white, bold, Paint.Align.CENTER, r - l - h * 0.04f)
    }

    /**
     * AACLUSTER, like a car's cluster: round speedo with the ride stats under it on the left, Android Auto
     * in the middle ([card]), clock, fuel and the other ticked rows on the right. Hidden rows leave no gap.
     */
    private fun cluster(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        aaBack(c, w, h)
        aaFrame(c, k, h)
        val m = h * 0.03f
        val gap = h * 0.035f
        // Left: speedo, then ride time · avg · max · lean (low fuel: the station instead).
        val lr = k.l - gap
        val left = CLUSTER_LEFT.filter { on(it) }.mapNotNull { sideRow(it) }
        val rad = min(h * 0.235f, (lr - m) / 2 * 0.92f)
        val cx = (m + lr) / 2
        val cy = if (left.isEmpty() && data.fuelStop == null) h * 0.5f else h * 0.31f
        gauge(c, w, h, cx, cy, rad, compact = true)
        val y0 = cy + rad * 1.02f + h * 0.04f
        data.fuelStop?.let { stackBanner(c, m, y0, lr, h - m, h, it) } ?: rowList(c, left, m, lr, y0, h - m, h)
        // Right: clock, GPS / weather, fuel, the rest of the ticked rows.
        val rl = k.r + gap; val rr = w - m
        putFit(c, data.clock, (rl + rr) / 2, h * 0.13f, 0.11f, h, white, bold, Paint.Align.CENTER, rr - rl)
        status()?.let { (s, col) -> putFit(c, s, (rl + rr) / 2, h * 0.195f, 0.04f, h, col, bold, Paint.Align.CENTER, rr - rl) }
        var y = h * 0.23f
        if (on(DashLayout.Part.FUEL)) y = fuelBlock(c, rl, rr, y, h)
        val rows = DashLayout.SIDE_PARTS.filter { it !in CLUSTER_LEFT && it != DashLayout.Part.FUEL && it != DashLayout.Part.GPS && it != DashLayout.Part.HEADING && on(it) }
            .mapNotNull { sideRow(it) }
        rowList(c, rows, rl, rr, y, h - m, h)
    }

    /**
     * AABAR: Android Auto across the top ([card]); along the bottom the speed, a tile per ticked part
     * (what doesn't fit is left off, hidden ones leave no gap) and the clock.
     */
    private fun aaBarLayout(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        aaBack(c, w, h)
        aaFrame(c, k, h)
        val t = k.b + h * 0.025f; val b = h - h * 0.025f
        // Speed, km/h beside it.
        putFit(c, shown?.toString() ?: "—", w * 0.13f, b - h * 0.012f, 0.17f, h, if (over) red else white, boldItalic, Paint.Align.RIGHT, w * 0.115f)
        putFit(c, "km/h", w * 0.136f, b - h * 0.02f, 0.04f, h, dim, bold, Paint.Align.LEFT, w * 0.045f)
        // Clock, GPS / weather under it.
        val cr = w - h * 0.04f; val cl = w * 0.85f
        putFit(c, data.clock, cr, t + h * 0.095f, 0.1f, h, white, bold, Paint.Align.RIGHT, cr - cl)
        status()?.let { (s, col) -> putFit(c, s, cr, b - h * 0.008f, 0.035f, h, col, bold, Paint.Align.RIGHT, cr - cl) }
        // Tiles (low fuel: the station instead).
        val tl = w * 0.19f; val tr = w * 0.835f
        data.fuelStop?.let { banner(c, tl, t, tr, b, h, it); return }
        val parts = DashLayout.SIDE_PARTS.filter { it != DashLayout.Part.GPS && on(it) }
        val tiles = parts.filter { it == DashLayout.Part.FUEL || sideRow(it) != null }
        val n = tiles.size.coerceAtMost(((tr - tl) / (h * 0.25f)).toInt())
        if (n == 0) return
        val gap = h * 0.015f
        val tw = (tr - tl + gap) / n
        val pad = h * 0.02f
        for ((i, p) in tiles.take(n).withIndex()) {
            val l = tl + i * tw; val r = l + tw - gap
            rect.set(l, t, r, b)
            fill.color = 0xCC0D1420.toInt(); c.drawRoundRect(rect, h * 0.025f, h * 0.025f, fill)
            rim(c, rect, h * 0.025f, h)
            if (p == DashLayout.Part.FUEL) {
                val low = (data.fuel ?: 1f) < 0.15f
                putFit(c, labels.fuel, l + pad, t + h * 0.045f, 0.03f, h, dim, bold, Paint.Align.LEFT, r - l - 2 * pad)
                putFit(c, data.fuel?.let { "${(it * 100).roundToInt()}%" } ?: "—", l + pad, t + h * 0.11f, 0.06f, h, if (low) red else white, bold, Paint.Align.LEFT, r - l - 2 * pad)
                slantBar(c, l + pad, b - h * 0.04f, r - pad, b - h * 0.018f, data.fuel, if (low) red else gold)
            } else {
                val (label, value, warn) = sideRow(p) ?: continue
                putFit(c, label, l + pad, t + h * 0.045f, 0.03f, h, dim, bold, Paint.Align.LEFT, r - l - 2 * pad)
                putFit(c, value, l + pad, b - h * 0.025f, 0.065f, h, if (warn) red else white, bold, Paint.Align.LEFT, r - l - 2 * pad)
            }
        }
    }

    /** AARIGHT: AASIDE mirrored, Android Auto left ([card]) and the same panel on the right, [sideFrac] wide. */
    private fun aaRightLayout(c: Canvas, w: Float, h: Float) {
        aaBack(c, w, h)
        aaFrame(c, card(w, h), h)
        val x = w * (1 - sideFrac)
        c.save(); c.translate(x, 0f)
        side(c, w - x, h, leftEdge = true, reserve = if (data.fuelStop != null) h * 0.21f else 0f)
        c.restore()
        val p = h * 0.035f
        data.fuelStop?.let { stackBanner(c, x + p, h * 0.79f, w - p * 1.3f, h - p * 0.6f, h, it) }
    }

    /**
     * AASPORT, from the rider's sport-cluster mockup: big speed, heading box, a rising speed bar (the bike
     * sends no rpm), lean, ride stats and phone / battery / fuel on the left; Android Auto in [card] on the right.
     */
    private fun aaSportLayout(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        aaBack(c, w, h)
        aaFrame(c, k, h)
        val l = w * 0.035f; val r = k.l - w * 0.03f
        val loc = Locale.getDefault()
        sportTop(c, w, h, l)
        // Speed, km/h beside it; heading in the box on the right (where the mockup had the gear).
        val sp = shown?.toString() ?: "—"
        putFit(c, sp, l, h * 0.43f, 0.3f, h, if (over) red else white, boldItalic, Paint.Align.LEFT, (r - l) * 0.6f)
        putFit(c, "km/h", l + text.measureText(sp) + h * 0.025f, h * 0.42f, 0.045f, h, dim, bold, Paint.Align.LEFT, h * 0.2f)
        if (on(DashLayout.Part.HEADING)) {
            rect.set(r - h * 0.24f, h * 0.17f, r, h * 0.41f)
            fill.color = 0xFF1A1C20.toInt(); c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill)
            putFit(c, data.heading ?: "—", rect.centerX(), h * 0.355f, 0.17f, h, gold, boldItalic, Paint.Align.CENTER, rect.width() * 0.85f)
        }
        // Speed bar: slanted segments rising to the right, 10 km/h each, red from the alert / red zone on.
        val speed = (shown ?: 0).coerceIn(0, MAX_KMH)
        val bt = h * 0.53f; val bb = h * 0.655f
        val n = MAX_KMH / 10
        val sw = (r - l) / n
        for (i in 0 until n) {
            val sx = l + i * sw
            val hot = i * 10 >= redKmh
            val lit = i * 10 < speed
            val st = bb - (bb - bt) * (0.3f + 0.7f * (i + 1) / n)
            path.reset(); path.moveTo(sx + sw * 0.25f, st); path.lineTo(sx + sw * 0.95f, st); path.lineTo(sx + sw * 0.7f, bb); path.lineTo(sx, bb); path.close()
            fill.color = when { lit && hot -> red; lit -> 0xFFE8E8EA.toInt(); hot -> 0xFF3A1216.toInt(); else -> 0xFF26282C.toInt() }
            c.drawPath(path, fill)
        }
        val now20 = speed / 20 * 20
        for (v in 0..MAX_KMH step 20) {
            val vx = l + (r - l) * v / MAX_KMH
            val col = when { v >= redKmh -> red; v <= speed -> white; else -> 0xFF6A6D72.toInt() }
            putFit(c, "$v", vx, h * 0.505f, if (v == now20 && speed > 0) 0.065f else 0.045f, h, col, boldItalic, Paint.Align.CENTER, w)
        }
        stroke.color = 0xFF55585D.toInt(); stroke.strokeWidth = h * 0.003f
        c.drawLine(l, h * 0.675f, r, h * 0.675f, stroke)
        for (v in 0..MAX_KMH step 10) { val vx = l + (r - l) * v / MAX_KMH; c.drawLine(vx, h * 0.675f, vx, h * (if (v % 20 == 0) 0.695f else 0.687f), stroke) }
        // Low fuel: the station instead of the rows.
        data.fuelStop?.let { banner(c, l, h * 0.71f, r, h * 0.9f, h, it); sportFooter(c, w, h, l); return }
        // Lean: a blue bar and the angle, like the mockup's throttle row.
        if (on(DashLayout.Part.LEAN)) {
            putFit(c, labels.lean, l, h * 0.745f, 0.035f, h, dim, bold, Paint.Align.LEFT, w * 0.08f)
            val bl = l + text.measureText(labels.lean) + h * 0.03f
            val vr = r - h * 0.14f
            bar(c, bl, h * 0.725f, vr - h * 0.03f, h * 0.742f, kotlin.math.abs(data.leanDeg ?: 0f) / 60f, second)
            putFit(c, data.leanDeg?.let { CustomDashUi.leanText(it) } ?: "—", r, h * 0.755f, 0.06f, h, second, boldItalic, Paint.Align.RIGHT, h * 0.17f)
        }
        fun pair(x0: Float, y: Float, label: String, value: String, warn: Boolean = false) = pairAt(c, x0, y, label, value, warn, h)
        // Ride time · max, average on the right.
        var px = l
        if (on(DashLayout.Part.RIDE_TIME)) px = pair(px, h * 0.815f, labels.time, data.rideTime ?: "—")
        if (on(DashLayout.Part.MAX_SPEED)) pair(px, h * 0.815f, labels.max, data.maxKmh?.toString() ?: "—")  // the label already says km/h
        if (on(DashLayout.Part.AVG)) {
            putFit(c, data.avgKmh?.toString() ?: "—", r, h * 0.815f, 0.05f, h, white, bold, Paint.Align.RIGHT, w)
            putFit(c, labels.avgShort, r - text.measureText(data.avgKmh?.toString() ?: "—") - h * 0.015f, h * 0.815f, 0.033f, h, dim, bold, Paint.Align.RIGHT, w)
        }
        // Phone temperature · bike battery, then the fuel estimate as E ▮▮▮ F.
        px = l
        if (on(DashLayout.Part.PHONE_TEMP)) px = pair(px, h * 0.885f, labels.phone, data.phoneTempC?.let { "$it°C" } ?: "—", data.phoneHot)
        if (on(DashLayout.Part.BIKE_BATTERY)) px = pair(px, h * 0.885f, labels.battery, data.bikeVolts?.let { String.format(loc, "%.1fV", it) } ?: "—", (data.bikeVolts ?: 99f) < PkeBattery.RED_RUNNING_V)
        if (on(DashLayout.Part.FUEL) && r - px > h * 0.3f) {
            val low = (data.fuel ?: 1f) < 0.15f
            putFit(c, "E", px, h * 0.885f, 0.033f, h, dim, bold, Paint.Align.LEFT, w)
            putFit(c, "F", r, h * 0.885f, 0.033f, h, dim, bold, Paint.Align.RIGHT, w)
            val fl = px + h * 0.045f; val fr = r - h * 0.045f
            val segs = 10; val gap = h * 0.008f; val segW = (fr - fl - gap * (segs - 1)) / segs
            val lit = data.fuel?.let { (it.coerceIn(0f, 1f) * segs).roundToInt() } ?: 0
            for (i in 0 until segs) {
                rect.set(fl + i * (segW + gap), h * 0.862f, fl + i * (segW + gap) + segW, h * 0.878f)
                fill.color = if (i < lit) (if (low) red else 0xFFE8E8EA.toInt()) else 0xFF2A2C30.toInt()
                c.drawRoundRect(rect, h * 0.004f, h * 0.004f, fill)
            }
        }
        sportFooter(c, w, h, l)
    }

    /** Sport templates' top bar: speed-limit sign at [l]; trip · odo · range right, a thin amber line under it. */
    private fun sportTop(c: Canvas, w: Float, h: Float, l: Float) {
        val loc = Locale.getDefault()
        data.speedLimit?.let { limitSign(c, h, l + h * 0.1f, it) }
        var x = w - h * 0.04f
        val top = h * 0.09f
        fun stat(label: String, value: String) {
            putFit(c, "km", x, top, 0.032f, h, dim, bold, Paint.Align.RIGHT, w); x -= text.measureText("km") + h * 0.012f
            putFit(c, value, x, top, 0.055f, h, white, bold, Paint.Align.RIGHT, w); x -= text.measureText(value) + h * 0.02f
            putFit(c, label, x, top, 0.032f, h, dim, bold, Paint.Align.RIGHT, w); x -= text.measureText(label) + h * 0.07f
        }
        if (on(DashLayout.Part.RANGE)) data.rangeKm?.let { stat(labels.rangeShort, "$it") }
        if (on(DashLayout.Part.ODO)) stat(labels.odo, data.odoKm?.let { String.format(loc, "%06.0f", it) } ?: "—")
        if (on(DashLayout.Part.TRIP)) stat(labels.trip, data.tripKm?.let { String.format(loc, "%.1f", it) } ?: "—")
        stroke.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(gold, 0x55000000 or (gold and 0xFFFFFF)), null, Shader.TileMode.CLAMP)
        stroke.strokeWidth = h * 0.004f
        c.drawLine(h * 0.03f, h * 0.125f, w - h * 0.03f, h * 0.125f, stroke)
        stroke.shader = null
    }

    /** Small grey [label] then [value] from [x0] on baseline [y]; returns where the next pair starts. */
    private fun pairAt(c: Canvas, x0: Float, y: Float, label: String, value: String, warn: Boolean, h: Float): Float {
        putFit(c, label, x0, y, 0.033f, h, dim, bold, Paint.Align.LEFT, h * 3)
        val vx = x0 + text.measureText(label) + h * 0.015f
        putFit(c, value, vx, y, 0.05f, h, if (warn) red else white, bold, Paint.Align.LEFT, h * 3)
        return vx + text.measureText(value) + h * 0.05f
    }

    /**
     * AAGAUGE, from the rider's round-gauge mockup: a big open speed arc with the heading box under it,
     * a lean column (the bike sends no rpm), status and fuel panels below; Android Auto in [card] on the right.
     */
    private fun aaGaugeLayout(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        aaBack(c, w, h)
        aaFrame(c, k, h)
        sportTop(c, w, h, w * 0.035f)
        // Amber stripes "///" on the line, as in the mockup.
        fill.color = gold
        for (i in 0 until 3) {
            val sx = w * 0.115f + i * h * 0.03f
            path.reset(); path.moveTo(sx + h * 0.03f, h * 0.1f); path.lineTo(sx + h * 0.048f, h * 0.1f); path.lineTo(sx + h * 0.018f, h * 0.135f); path.lineTo(sx, h * 0.135f); path.close()
            c.drawPath(path, fill)
        }
        // Speed arc: 220° open at the bottom (ends clear of the panels), thick amber up to the speed, red zone at the end, labels outside.
        val cx = w * 0.25f; val cy = h * 0.575f; val rad = h * 0.36f
        val start = 160f; val sweep = 220f
        val speed = (shown ?: 0).coerceIn(0, MAX_KMH)
        stroke.strokeCap = Paint.Cap.BUTT
        rect.set(cx - rad * 1.08f, cy - rad * 1.08f, cx + rad * 1.08f, cy + rad * 1.08f)
        stroke.color = 0xFF5A5D63.toInt(); stroke.strokeWidth = h * 0.006f
        c.drawArc(rect, start - 6f, sweep * 0.6f, false, stroke)  // thin outer rim
        val ring = rad * 0.93f
        rect.set(cx - ring, cy - ring, cx + ring, cy + ring)
        stroke.strokeWidth = h * 0.06f
        stroke.color = 0xFF1E2024.toInt(); c.drawArc(rect, start, sweep, false, stroke)
        stroke.color = 0x66FF1E2D; c.drawArc(rect, start + sweep * redKmh / MAX_KMH, sweep * (MAX_KMH - redKmh) / MAX_KMH, false, stroke)
        if (speed > 0) {
            if (fx(DashLayout.Fx.GLOW)) speedGlow(c, h, speed) { c.drawArc(rect, start, sweep * speed / MAX_KMH, false, stroke) }
            stroke.color = if (speed >= redKmh) red else gold  // after the glow: it leaves its own see-through colour
            c.drawArc(rect, start, sweep * speed / MAX_KMH, false, stroke)
        }
        stroke.strokeCap = Paint.Cap.ROUND
        for (v in 0..MAX_KMH step 10) {
            val a = Math.toRadians((start + sweep * v / MAX_KMH).toDouble())
            val major = v % 20 == 0
            val r0 = rad * if (major) 0.84f else 0.88f; val r1 = rad * 1.02f
            stroke.color = if (v >= redKmh) red else 0xFFD8D8DC.toInt(); stroke.strokeWidth = h * if (major) 0.007f else 0.003f
            c.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), stroke)
            if (major) {
                val lr = rad * 1.17f
                putFit(c, "$v", cx + lr * cos(a).toFloat(), cy + lr * sin(a).toFloat() + h * 0.017f, 0.05f, h, if (v >= redKmh) red else white, boldItalic, Paint.Align.CENTER, w)
            }
        }
        putFit(c, shown?.toString() ?: "—", cx, cy + h * 0.005f, 0.22f, h, if (over) red else white, boldItalic, Paint.Align.CENTER, rad * 1.4f)
        putFit(c, "km/h", cx, cy + h * 0.065f, 0.045f, h, dim, bold, Paint.Align.CENTER, w)
        // Heading box under the speed (where the mockup had the gear).
        if (on(DashLayout.Part.HEADING)) {
            val bt = h * 0.655f; val bb = h * 0.75f; val bw = w * 0.085f
            path.reset(); path.moveTo(cx - bw * 0.7f, bt); path.lineTo(cx + bw * 0.7f, bt); path.lineTo(cx + bw, bb); path.lineTo(cx - bw, bb); path.close()
            fill.color = 0xFF121317.toInt(); c.drawPath(path, fill)
            stroke.color = 0xFF3A3D42.toInt(); stroke.strokeWidth = h * 0.004f; c.drawPath(path, stroke)
            putFit(c, data.heading ?: "—", cx, bb - h * 0.017f, 0.075f, h, gold, boldItalic, Paint.Align.CENTER, bw * 1.3f)
        }
        // Lean column: label, the angle big, then a segment ladder 0–60° (red from 45°).
        if (on(DashLayout.Part.LEAN)) {
            val ll = w * 0.49f; val lr = w * 0.6f
            putFit(c, labels.lean, ll, h * 0.205f, 0.035f, h, white, bold, Paint.Align.LEFT, lr - ll)
            val deg = kotlin.math.abs(data.leanDeg ?: 0f)
            putFit(c, data.leanDeg?.let { "${deg.roundToInt()}°" } ?: "—", ll, h * 0.32f, 0.11f, h, white, boldItalic, Paint.Align.LEFT, lr - ll)
            val segs = 12; val st = h * 0.36f; val sb = h * 0.735f
            val sh = (sb - st) / segs
            val lit = (deg / 5f).roundToInt()
            val bl = ll + h * 0.01f; val br = lr - h * 0.06f
            for (i in 0 until segs) {
                val y1 = sb - i * sh; val y0 = y1 - sh * 0.78f
                val hot = i >= 9
                rect.set(bl + (segs - i) * h * 0.002f, y0, br, y1)
                fill.color = when { i < lit && hot -> red; i < lit -> 0xFFE8E8EA.toInt(); hot -> 0xFF4A1218.toInt(); else -> 0xFF2A2C30.toInt() }
                c.drawRect(rect, fill)
                if (i % 2 == 0) putFit(c, "${i * 5}", br + h * 0.012f, y1 - sh * 0.1f, 0.032f, h, if (hot) red else 0xFFC8C8CC.toInt(), boldItalic, Paint.Align.LEFT, w)
            }
            putFit(c, "60", br + h * 0.012f, st + sh * 0.2f, 0.032f, h, red, boldItalic, Paint.Align.LEFT, w)
        }
        // Panels: phone · battery · GPS · Bluetooth left, fuel right (low fuel: the station across both).
        val pt = h * 0.775f; val pb = h * 0.875f; val mid = w * 0.535f
        data.fuelStop?.let { banner(c, h * 0.025f, pt, w - h * 0.025f, pb, h, it) } ?: run {
            chamfer(c, h * 0.02f, pt, mid - h * 0.01f, pb, h)
            statusRow(c, h * 0.07f, mid - h * 0.06f, pt, pb, h)
            chamfer(c, mid + h * 0.01f, pt, w - h * 0.02f, pb, h)
            fuelRow(c, mid + h * 0.07f, w - h * 0.06f, pt, pb, h)
        }
        // Footer: weather left, ride time · average · max in the middle, the clock right.
        stroke.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(gold, 0xFF2A2C30.toInt(), 0xFF2A2C30.toInt()), floatArrayOf(0f, 0.1f, 1f), Shader.TileMode.CLAMP)
        stroke.strokeWidth = h * 0.006f
        c.drawLine(h * 0.03f, h * 0.9f, w - h * 0.03f, h * 0.9f, stroke)
        stroke.shader = null
        val fy = h * 0.97f
        data.weather?.let { putFit(c, it, h * 0.04f, fy, 0.05f, h, if (data.weatherRain) 0xFF4FC3F7.toInt() else white, bold, Paint.Align.LEFT, w * 0.18f) }
        var px = w * 0.27f
        if (on(DashLayout.Part.RIDE_TIME)) px = pairAt(c, px, fy, labels.time, data.rideTime ?: "—", false, h)
        if (on(DashLayout.Part.AVG)) px = pairAt(c, px, fy, labels.avgShort, data.avgKmh?.toString() ?: "—", false, h)
        if (on(DashLayout.Part.MAX_SPEED)) pairAt(c, px, fy, labels.max, data.maxKmh?.toString() ?: "—", false, h)
        putFit(c, data.clock, w - h * 0.04f, fy, 0.075f, h, white, bold, Paint.Align.RIGHT, w * 0.2f)
    }


    /** Dark panel with cut corners (left / right points), [edge] outline. */
    private fun chamfer(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float, edge: Int = 0xFF3A3D42.toInt()) {
        val cut = (b - t) / 2
        path.reset(); path.moveTo(l + cut, t); path.lineTo(r - cut, t); path.lineTo(r, t + cut); path.lineTo(r - cut, b); path.lineTo(l + cut, b); path.lineTo(l, t + cut); path.close()
        fill.color = 0xFF0C0D10.toInt(); c.drawPath(path, fill)
        stroke.shader = null; stroke.color = edge; stroke.strokeWidth = h * 0.004f; c.drawPath(path, stroke)
    }

    /** Phone temperature · bike battery · GPS (or weather) · Bluetooth across [l]..[r], split by thin lines. */
    private fun statusRow(c: Canvas, l: Float, r: Float, t: Float, b: Float, h: Float) {
        val y = (t + b) / 2 + h * 0.018f
        val items = buildList {
            if (on(DashLayout.Part.PHONE_TEMP)) add(Triple(labels.phone, data.phoneTempC?.let { "$it°C" } ?: "—", if (data.phoneHot) red else white))
            if (on(DashLayout.Part.BIKE_BATTERY)) add(Triple("", data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1fV", it) } ?: "—", if ((data.bikeVolts ?: 99f) < PkeBattery.RED_RUNNING_V) red else white))
            status()?.let { (s, col) -> add(Triple("", s, col)) }
            add(Triple("", "ᛒ", if (data.bluetoothOn) 0xFF4FC3F7.toInt() else dim))
        }
        val iw = (r - l) / items.size
        for ((i, it) in items.withIndex()) {
            val (label, value, col) = it
            val x0 = l + i * iw
            if (i > 0) { stroke.color = 0xFF3A3D42.toInt(); stroke.strokeWidth = h * 0.003f; c.drawLine(x0, t + h * 0.025f, x0, b - h * 0.025f, stroke) }
            if (label.isEmpty()) putFit(c, value, x0 + iw / 2, y, 0.05f, h, col, bold, Paint.Align.CENTER, iw - h * 0.03f)
            else {
                putFit(c, label, x0 + h * 0.015f, y, 0.03f, h, dim, bold, Paint.Align.LEFT, iw * 0.45f)
                putFit(c, value, x0 + iw - h * 0.015f, y, 0.05f, h, col, bold, Paint.Align.RIGHT, iw * 0.55f)
            }
        }
    }

    /** Pump, E ▰▰▰▱ F and the percent in [second] across [l]..[r] (red when low). */
    private fun fuelRow(c: Canvas, l: Float, r: Float, t: Float, b: Float, h: Float) {
        if (!on(DashLayout.Part.FUEL)) return
        val y = (t + b) / 2 + h * 0.018f
        val low = (data.fuel ?: 1f) < 0.15f
        pump?.let { d -> d.setTint(if (low) red else white); d.setBounds(l.toInt(), (t + h * 0.022f).toInt(), (l + h * 0.058f).toInt(), (b - h * 0.022f).toInt()); d.draw(c); d.setTint(gold) }
        putFit(c, "E", l + h * 0.09f, y, 0.04f, h, white, bold, Paint.Align.LEFT, h)
        putFit(c, data.fuel?.let { "${(it * 100).roundToInt()}%" } ?: "—", r, y + h * 0.008f, 0.07f, h, if (low) red else second, boldItalic, Paint.Align.RIGHT, h * 0.2f)
        val fe = r - h * 0.2f
        putFit(c, "F", fe, y, 0.04f, h, white, bold, Paint.Align.RIGHT, h)
        slantBar(c, l + h * 0.14f, (t + b) / 2 - h * 0.012f, fe - h * 0.05f, (t + b) / 2 + h * 0.012f, data.fuel, if (low) red else white, n = 8)
    }

    /**
     * AARACE, from the rider's "368G" mockup: bike name on a slanted plate, a segmented speed band sweeping
     * up from the bottom left (white → amber → red; the bike sends no rpm), big speed, heading box, status
     * row, fuel and ride-stat panels; Android Auto in [card] on the right.
     */
    private fun aaRaceLayout(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        aaBack(c, w, h)
        aaFrame(c, k, h)
        // Name plate: "ZONTES" white, the model in the theme colour, three stripes after the slant.
        val pr = w * 0.4f
        path.reset(); path.moveTo(0f, 0f); path.lineTo(pr, 0f); path.lineTo(pr - h * 0.06f, h * 0.125f); path.lineTo(0f, h * 0.125f); path.close()
        fill.color = 0xFF16171B.toInt(); c.drawPath(path, fill)
        fill.color = 0xFF2E3036.toInt()
        for (i in 1..3) {
            val sx = pr + i * h * 0.035f
            path.reset(); path.moveTo(sx, 0f); path.lineTo(sx + h * 0.014f, 0f); path.lineTo(sx + h * 0.014f - h * 0.06f, h * 0.125f); path.lineTo(sx - h * 0.06f, h * 0.125f); path.close()
            c.drawPath(path, fill)
        }
        val name = data.bikeName.ifBlank { "Zontes" }
        val cut = name.lastIndexOf(' ')
        val first = if (cut > 0) name.substring(0, cut).uppercase(Locale.ROOT) + " " else ""
        val last = if (cut > 0) name.substring(cut + 1) else name
        val nx = w * 0.045f
        putFit(c, first, nx, h * 0.095f, 0.065f, h, white, boldItalic, Paint.Align.LEFT, pr * 0.55f)
        val lx = nx + text.measureText(first)
        putFit(c, last, lx, h * 0.1f, 0.085f, h, gold, boldItalic, Paint.Align.LEFT, pr - h * 0.1f - lx)
        sportTop(c, w, h, pr + h * 0.06f)
        // Speed band: 5 km/h blocks along a curve, thicker to the right, labels on the outside.
        path.reset()
        path.moveTo(w * 0.035f, h * 0.6f)
        path.cubicTo(w * 0.07f, h * 0.4f, w * 0.15f, h * 0.29f, w * 0.3f, h * 0.255f)
        path.lineTo(w * 0.625f, h * 0.205f)
        val pm = android.graphics.PathMeasure(path, false)
        val len = pm.length
        val speed = (shown ?: 0).coerceIn(0, MAX_KMH)
        val n = MAX_KMH / 5
        val a = FloatArray(2); val ta = FloatArray(2); val b2 = FloatArray(2); val tb = FloatArray(2)
        for (i in 0 until n) {
            val d0 = len * i / n; val d1 = len * (i + 0.82f) / n
            pm.getPosTan(d0, a, ta); pm.getPosTan(d1, b2, tb)
            val t0 = h * (0.045f + 0.06f * i / n); val t1 = h * (0.045f + 0.06f * (i + 1) / n)
            // Inner side (down / right of the curve): normal (-ty, tx).
            path.reset()
            path.moveTo(a[0], a[1]); path.lineTo(b2[0], b2[1])
            path.lineTo(b2[0] - tb[1] * t1, b2[1] + tb[0] * t1); path.lineTo(a[0] - ta[1] * t0, a[1] + ta[0] * t0); path.close()
            val v = i * 5
            val hot = v >= redKmh
            val lit = v < speed
            fill.color = when {
                !lit -> if (hot) 0xFF3A1216.toInt() else 0xFF24262A.toInt()
                hot -> red
                v < 60 -> 0xFFEDEDEF.toInt()
                else -> androidx.core.graphics.ColorUtils.blendARGB(0xFFFFD21A.toInt(), 0xFFFF5A1A.toInt(), ((v - 60f) / (redKmh - 60f).coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            c.drawPath(path, fill)
        }
        for (v in 0..MAX_KMH step 20) {
            pm.getPosTan(len * v / MAX_KMH, a, ta)
            val ox = ta[1]; val oy = -ta[0]  // outer side
            stroke.color = if (v >= redKmh) red else white; stroke.strokeWidth = h * 0.005f
            c.drawLine(a[0], a[1], a[0] + ox * h * 0.02f, a[1] + oy * h * 0.02f, stroke)
            putFit(c, "$v", a[0] + ox * h * 0.05f, a[1] + oy * h * 0.05f + h * 0.016f, 0.048f, h, if (v >= redKmh) red else white, boldItalic, Paint.Align.CENTER, w)
        }
        putFit(c, "km/h", w * 0.62f, h * 0.355f, 0.035f, h, dim, boldItalic, Paint.Align.RIGHT, w)
        // Speed, km/h beside it; heading box right of it (where the mockup had the gear).
        putFit(c, shown?.toString() ?: "—", w * 0.39f, h * 0.575f, 0.24f, h, if (over) red else white, boldItalic, Paint.Align.RIGHT, w * 0.27f)
        putFit(c, "km/h", w * 0.4f, h * 0.565f, 0.06f, h, white, boldItalic, Paint.Align.LEFT, w * 0.07f)
        if (on(DashLayout.Part.HEADING)) {
            val bl = w * 0.485f; val br = w * 0.63f; val bt = h * 0.37f; val bb = h * 0.59f; val sk = h * 0.05f
            path.reset(); path.moveTo(bl + sk, bt); path.lineTo(br, bt); path.lineTo(br - sk, bb); path.lineTo(bl, bb); path.close()
            fill.color = 0xFF16171B.toInt(); c.drawPath(path, fill)
            stroke.color = 0xFF3A3D42.toInt(); stroke.strokeWidth = h * 0.004f; c.drawPath(path, stroke)
            putFit(c, data.heading ?: "—", (bl + br) / 2, h * 0.5f, 0.13f, h, gold, boldItalic, Paint.Align.CENTER, (br - bl) * 0.7f)
            putFit(c, labels.heading, (bl + br) / 2, h * 0.565f, 0.04f, h, white, boldItalic, Paint.Align.CENTER, (br - bl) * 0.7f)
        }
        // Status row under the speed, its lower edge lit in the theme colour.
        chamfer(c, w * 0.09f, h * 0.635f, w * 0.635f, h * 0.725f, h, gold)
        statusRow(c, w * 0.12f, w * 0.6f, h * 0.635f, h * 0.725f, h)
        // Fuel left, average · max · ride time right (low fuel: the station across both).
        val pt = h * 0.765f; val pb = h * 0.875f; val mid = w * 0.41f
        data.fuelStop?.let { banner(c, h * 0.025f, pt, w - h * 0.025f, pb, h, it) } ?: run {
            chamfer(c, h * 0.02f, pt, mid - h * 0.01f, pb, h)
            fuelRow(c, h * 0.07f, mid - h * 0.06f, pt, pb, h)
            chamfer(c, mid + h * 0.01f, pt, w - h * 0.02f, pb, h)
            val stats = buildList {
                if (on(DashLayout.Part.AVG)) add(labels.avgShort to (data.avgKmh?.let { "$it km/h" } ?: "—"))
                if (on(DashLayout.Part.MAX_SPEED)) add(labels.max to (data.maxKmh?.toString() ?: "—"))
                if (on(DashLayout.Part.RIDE_TIME)) add(labels.time to (data.rideTime ?: "—"))
            }
            val sl = mid + h * 0.07f; val sr = w - h * 0.07f
            for ((i, st) in stats.withIndex()) {
                val sw = (sr - sl) / stats.size; val x0 = sl + i * sw
                if (i > 0) { stroke.color = 0xFF3A3D42.toInt(); stroke.strokeWidth = h * 0.003f; c.drawLine(x0, pt + h * 0.02f, x0, pb - h * 0.02f, stroke) }
                putFit(c, st.first, x0 + sw / 2, pt + h * 0.04f, 0.03f, h, dim, bold, Paint.Align.CENTER, sw - h * 0.03f)
                putFit(c, st.second, x0 + sw / 2, pb - h * 0.018f, 0.06f, h, white, boldItalic, Paint.Align.CENTER, sw - h * 0.03f)
            }
        }
        // Footer: weather, lean · altitude, the clock.
        stroke.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFF2A2C30.toInt(), gold, 0xFF2A2C30.toInt()), null, Shader.TileMode.CLAMP)
        stroke.strokeWidth = h * 0.004f
        c.drawLine(h * 0.03f, h * 0.9f, w - h * 0.03f, h * 0.9f, stroke)
        stroke.shader = null
        val fy = h * 0.97f
        data.weather?.let { putFit(c, it, h * 0.04f, fy, 0.05f, h, if (data.weatherRain) 0xFF4FC3F7.toInt() else white, bold, Paint.Align.LEFT, w * 0.18f) }
        var px = w * 0.27f
        if (on(DashLayout.Part.LEAN)) px = pairAt(c, px, fy, labels.lean, data.leanDeg?.let { CustomDashUi.leanText(it) } ?: "—", false, h)
        if (on(DashLayout.Part.ALTITUDE)) pairAt(c, px, fy, labels.alt, data.altitudeM?.let { String.format(Locale.getDefault(), "%,d m", it) } ?: "—", false, h)
        putFit(c, data.clock, w - h * 0.04f, fy, 0.075f, h, white, bold, Paint.Align.RIGHT, w * 0.2f)
    }

    /** AASPORT's bottom line: GPS / weather left, the clock right under Android Auto. */
    private fun sportFooter(c: Canvas, w: Float, h: Float, l: Float) {
        status()?.let { (s, col) -> putFit(c, s, l, h * 0.965f, 0.04f, h, col, bold, Paint.Align.LEFT, w * 0.45f) }
        putFit(c, data.clock, w - h * 0.04f, h * 0.97f, 0.075f, h, white, bold, Paint.Align.RIGHT, w * 0.2f)
    }

    /**
     * Over the speed alert: the whole dash tinted red and glowing in from the edges, and a thick glowing red
     * frame blinking on and off ([ALARM_BLINK_MS]) so it catches the eye at a glance. The centre stays dark
     * enough to read the speed.
     */
    private fun speedAlarm(c: Canvas, w: Float, h: Float, now: Long) {
        val lit = (now / ALARM_BLINK_MS) % 2 == 0L
        val k = if (lit) 1f else 0.45f
        fill.color = Color.argb((55 * k).toInt(), 0xD0, 0x10, 0x1C); c.drawRect(0f, 0f, w, h, fill)
        // Elliptical vignette: dark middle, deep red edges, solid red in the corners.
        fill.shader = RadialGradient(w / 2, h / 2, h * 0.62f, intArrayOf(0, Color.argb((190 * k).toInt(), 0xB0, 0x0C, 0x18), Color.argb((250 * k).toInt(), 0xE0, 0x12, 0x22)), floatArrayOf(0.35f, 0.72f, 1f), Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(Matrix().apply { setScale(w / h, 1f, w / 2, h / 2) }) }
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        if (!lit) return
        // Blinking frame: wide see-through strokes for the glow, then a solid bright one on the edge.
        stroke.shader = null
        val inset = h * 0.025f
        rect.set(inset, inset, w - inset, h - inset)
        for ((width, alpha) in listOf(0.09f to 0x40, 0.06f to 0x80)) {
            stroke.color = (ALARM_RED and 0xFFFFFF) or (alpha shl 24); stroke.strokeWidth = h * width
            c.drawRoundRect(rect, h * 0.05f, h * 0.05f, stroke)
        }
        stroke.color = ALARM_RED; stroke.strokeWidth = h * 0.035f
        c.drawRoundRect(rect, h * 0.05f, h * 0.05f, stroke)
    }

    /** Race Bar: the next turn over the left of the live map, as in the mockup; the whole map shows otherwise. */
    private fun raceTurn(c: Canvas, h: Float, k: Card) {
        val t = data.navTurnText ?: return
        val r = k.l + (k.r - k.l) * 0.62f
        fill.shader = LinearGradient(k.l, 0f, r, 0f, intArrayOf(0xF2050607.toInt(), 0xE6050607.toInt(), 0), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        rect.set(k.l, k.t, r, k.b); c.drawRoundRect(rect, h * 0.03f, h * 0.03f, fill); fill.shader = null
        val pad = h * 0.025f; val s = h * 0.11f
        turnIcon(c, k.l + pad, k.t + pad, s, gold)
        val tx = k.l + pad + s + h * 0.02f
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.09f; text.color = white
        c.drawText(ellipsize(data.navTurnDist ?: "", r - tx), tx, k.t + pad + s * 0.8f, text)
        text.textSize = h * 0.048f
        for ((i, line) in wrap2(t, r - k.l - pad * 2).withIndex()) c.drawText(line, k.l + pad, k.t + pad + s + h * 0.05f + i * h * 0.05f, text)
        val eta = listOfNotNull(data.navRemain, data.navEta).joinToString("  ·  ")
        text.typeface = bold; text.textSize = h * 0.036f; text.color = second
        c.drawText(ellipsize(eta, r - k.l - pad * 2), k.l + pad, k.b - h * 0.022f, text)
    }

    /** Moto Live: the rider's green mockup as the background ([art]); the phone and link values where it had them (x / 1674, y / 940). */
    private fun lime(c: Canvas, w: Float, h: Float) {
        art?.let { rect.set(0f, 0f, w, h); c.drawBitmap(it, null, rect, fill) }
        fun x(v: Int) = w * v / 1674f
        fun y(v: Int) = h * v / 940f
        fun put(s: String, px: Float, py: Float, size: Float, color: Int, face: Typeface = bold, align: Paint.Align = Paint.Align.LEFT, maxW: Float = w) {
            text.textAlign = align; text.typeface = face; text.textSize = h * size; text.color = color
            c.drawText(ellipsize(s, maxW), px, py, text)
        }
        val green = second
        // Header: the bike's name (no badge: the rider wants no icon here).
        put(data.bikeName.ifBlank { "Zontes" }.uppercase(Locale.ROOT), x(40), y(56), 0.075f, white, maxW = x(480) - x(40))
        put("RIDE / LIVE", x(40), y(90), 0.034f, 0xFFB8B8B8.toInt())
        data.gps?.let { put(it, x(760), y(72), 0.045f, data.gpsColor, maxW = x(1180) - x(760)) }
        data.videoFps?.let { put("$it FPS", x(1200), y(72), 0.042f, 0xFFB8B8B8.toInt(), maxW = x(1400) - x(1200)) }
        put(data.clock, x(1640), y(76), 0.075f, white, align = Paint.Align.RIGHT)
        // Phone cards, right.
        put(if (data.phoneCharging) labels.charging else labels.onBattery, x(1640), y(168), 0.04f, if (data.phoneCharging) gold else dim, align = Paint.Align.RIGHT, maxW = x(1640) - x(1440))
        val batt = data.phoneBattery
        val lowBatt = (batt ?: 100) < 20
        put(batt?.let { "$it%" } ?: "—", x(1268), y(292), 0.085f, if (lowBatt) red else green, maxW = x(1435) - x(1268))
        bar(c, x(1445), y(246), x(1622), y(283), (batt ?: 0) / 100f, if (lowBatt) red else green)
        put(labels.phoneBattery, x(1270), y(326), 0.03f, dim, Typeface.DEFAULT, maxW = x(1500) - x(1270))
        data.phoneMv?.let { put("$it mV", x(1622), y(326), 0.034f, white, align = Paint.Align.RIGHT) }
        val temp = data.phoneTempC
        val tc = if (data.phoneHot) red else green
        put(temp?.toString() ?: "—", x(1268), y(452), 0.09f, tc)
        text.textSize = h * 0.09f
        put("°C", x(1268) + text.measureText(temp?.toString() ?: "—") + h * 0.01f, y(452), 0.045f, white, Typeface.DEFAULT)
        bar(c, x(1467), y(408), x(1620), y(447), temp?.let { (it - 20) / 30f } ?: 0f, tc)
        put(if (data.phoneHot) labels.hot else labels.normal, x(1622), y(496), 0.036f, tc, align = Paint.Align.RIGHT)
        put(data.wifiDbm?.let { "$it dBm" } ?: "—", x(1333), y(582), 0.045f, white, align = Paint.Align.CENTER, maxW = x(1455) - x(1258))
        put(data.storageFreePct?.let { "$it%" } ?: "—", x(1558), y(582), 0.045f, white, align = Paint.Align.CENTER, maxW = x(1645) - x(1470))
        val cell = data.cellReady
        val cellText = when (cell) { true -> labels.ready; false -> labels.offline; null -> "—" }
        put(cellText, x(1328), y(698), 0.048f, if (cell == false) red else gold, align = Paint.Align.CENTER, maxW = x(1428) - x(1240))
        put(data.pressureHpa?.let { String.format(Locale.getDefault(), "%.1f hPa", it) } ?: "—", x(1555), y(698), 0.042f, 0xFFB8B8B8.toInt(), align = Paint.Align.CENTER, maxW = x(1642) - x(1440))
        // Now playing, right of the handlebar keys.
        data.media?.let { put(it, x(885), y(762), 0.034f, white, maxW = x(1195) - x(885)) }
        // Bottom row, values right-aligned like the mockup (the TBOX cell is in the picture).
        val vy = y(882)
        put(data.gps ?: "—", x(293), vy, 0.048f, data.gps?.let { data.gpsColor } ?: white, align = Paint.Align.RIGHT, maxW = x(293) - x(100))
        put(batt?.let { "$it%" } ?: "—", x(623), vy, 0.05f, if (lowBatt) red else gold, align = Paint.Align.RIGHT)
        put(cellText, x(950), vy, 0.05f, if (cell == false) red else gold, align = Paint.Align.RIGHT, maxW = x(950) - x(745))
        put(data.linkMbps?.let { String.format(Locale.getDefault(), "%.1f MBPS", it) } ?: "—", x(1288), vy, 0.05f, gold, align = Paint.Align.RIGHT, maxW = x(1288) - x(1090))
    }

    private fun tourNav(c: Canvas, w: Float, h: Float) {
        val k = card(w, h)
        val rad = h * 0.035f
        (art ?: data.photo)?.takeIf { on(DashLayout.Part.ART) }?.let {
            backdrop(c, it, 0f, h * 0.45f, w * 0.235f, h * 0.8f, intArrayOf(0, 255, 255, 0), floatArrayOf(0f, 0.3f, 0.9f, 1f), floatArrayOf(0f, 0.04f, 0.85f, 1f))
        }
        gauge(c, w, h, cx = w * 0.112f, cy = h * 0.395f, rad = h * 0.215f, compact = true)
        rect.set(k.l, k.t, k.r, k.b)
        if (on(DashLayout.Part.LIVE_MAP)) {  // map hidden: no empty frame
            if (!mapInCard) { fill.color = theme.cardTop; c.drawRoundRect(rect, rad, rad, fill) }
            glow(c, h) { c.drawRoundRect(rect, rad, rad, stroke) }
        }
        tourGuide(c, w, h)
        tourHeights(c, h, k.l, h * 0.67f, k.r, h * 0.79f)
        tourRow(c, w, h, h * 0.815f, h * 0.985f)
    }

    /** Right column: next turn on top, then destination · distance left · arrival (trip numbers when not navigating). */
    private fun tourGuide(c: Canvas, w: Float, h: Float) {
        val l = w * 0.79f; val r = w * 0.993f; val pad = h * 0.025f
        val rad = h * 0.035f
        rect.set(l, h * 0.17f, r, h * 0.4f)
        fill.color = theme.cardTop; c.drawRoundRect(rect, rad, rad, fill); rim(c, rect, rad, h)
        val turn = data.navTurnText
        if (turn != null) {
            val s = h * 0.12f
            turnIcon(c, l + pad, h * 0.185f, s, gold)
            text.textAlign = Paint.Align.RIGHT; text.typeface = boldItalic; text.textSize = h * 0.08f; text.color = white
            c.drawText(ellipsize(data.navTurnDist ?: "", r - l - s - 3 * pad), r - pad, h * 0.275f, text)
            text.typeface = bold; text.textSize = h * 0.04f
            for ((i, line) in wrap2(turn, r - l - 2 * pad).withIndex()) c.drawText(line, r - pad, h * (0.34f + i * 0.045f), text)
        } else {
            text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.034f; text.color = white
            for ((i, line) in wrap2(labels.noDestination, r - l - 2 * pad).withIndex()) c.drawText(line, l + pad, h * (0.24f + i * 0.042f), text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.027f; text.color = dim
            for ((i, line) in wrap2(labels.pickDestination, r - l - 2 * pad).withIndex()) c.drawText(line, l + pad, h * (0.32f + i * 0.035f), text)
        }
        rect.set(l, h * 0.42f, r, h * 0.79f)
        fill.color = theme.cardTop; c.drawRoundRect(rect, rad, rad, fill); rim(c, rect, rad, h)
        val nav = data.navDest != null
        val rows = if (nav) listOf(
            Triple("⚑", labels.destination, data.navDest ?: "—"),
            Triple("⇡", labels.remain, data.navRemain ?: "—"),
            Triple("◷", labels.eta, data.navEta ?: "—"),
        ) else listOfNotNull(
            Triple("⇡", labels.trip, data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—").takeIf { on(DashLayout.Part.TRIP) },
            Triple("◷", labels.time, data.rideTime ?: "—").takeIf { on(DashLayout.Part.RIDE_TIME) },
            Triple("▲", labels.max, data.maxKmh?.toString() ?: "—").takeIf { on(DashLayout.Part.MAX_SPEED) },
        )
        for ((i, row) in rows.withIndex()) {
            val t = h * (0.42f + i * 0.123f)
            if (i > 0) { stroke.color = 0xFF2A2420.toInt(); stroke.strokeWidth = 1f; c.drawLine(l + pad, t, r - pad, t, stroke) }
            text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.045f; text.color = white
            c.drawText(row.first, l + pad + h * 0.025f, t + h * 0.08f, text)
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.027f; text.color = dim
            c.drawText(ellipsize(row.second, r - l - h * 0.11f), l + h * 0.085f, t + h * 0.045f, text)
            text.typeface = bold; text.textSize = h * 0.042f; text.color = if (i == 0 && nav) second else white
            c.drawText(ellipsize(row.third, r - l - h * 0.11f), l + h * 0.085f, t + h * 0.095f, text)
        }
    }

    /** Height now, the last 20 minutes as a line, and metres climbed / descended. */
    private fun tourHeights(c: Canvas, h: Float, l: Float, t: Float, r: Float, b: Float) {
        if (!on(DashLayout.Part.ALTITUDE)) return
        val rad = h * 0.035f
        rect.set(l, t, r, b)
        fill.color = theme.cardTop; c.drawRoundRect(rect, rad, rad, fill); rim(c, rect, rad, h)
        val mid = (t + b) / 2
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.06f; text.color = white
        c.drawText("⛰", l + h * 0.06f, mid + h * 0.022f, text)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.027f; text.color = dim
        c.drawText(labels.alt, l + h * 0.12f, mid - h * 0.012f, text)
        text.typeface = bold; text.textSize = h * 0.045f; text.color = white
        c.drawText(data.altitudeM?.let { String.format(Locale.getDefault(), "%,d m", it) } ?: "—", l + h * 0.12f, mid + h * 0.035f, text)
        // Right: climbed (orange) over descended (blue).
        val vx = r - h * 0.025f; val lx = r - (r - l) * 0.24f
        text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.03f
        text.color = gold; c.drawText(labels.climb, lx, mid - h * 0.01f, text)
        text.color = second; c.drawText(labels.descent, lx, mid + h * 0.04f, text)
        text.textAlign = Paint.Align.RIGHT; text.textSize = h * 0.038f; text.color = white
        c.drawText(data.climbM?.let { "+$it m" } ?: "—", vx, mid - h * 0.008f, text)
        c.drawText(data.descentM?.let { "−$it m" } ?: "—", vx, mid + h * 0.042f, text)
        profile(c, h, l + (r - l) * 0.27f, t + h * 0.02f, lx - h * 0.04f, b - h * 0.02f)
    }

    /** Height over the last 20 minutes as a filled line in ([pl], [pt])..([pr], [pb]), scaled to its own min..max (at least 20 m tall so flat roads stay flat). */
    private fun profile(c: Canvas, h: Float, pl: Float, pt: Float, pr: Float, pb: Float) {
        val p = data.altProfile?.takeIf { it.size >= 2 } ?: return
        val lo = p.min(); val span = maxOf(p.max() - lo, 20f)
        path.reset()
        for ((i, v) in p.withIndex()) {
            val x = pl + (pr - pl) * i / (p.size - 1); val y = pb - (pb - pt) * (v - lo) / span
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val line = Path(path)
        path.lineTo(pr, pb); path.lineTo(pl, pb); path.close()
        fill.shader = LinearGradient(0f, pt, 0f, pb, (gold and 0x00FFFFFF) or 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawPath(path, fill); fill.shader = null
        stroke.color = gold; stroke.strokeWidth = h * 0.006f; c.drawPath(line, stroke)
        val ny = pb - (pb - pt) * (p.last() - lo) / span
        fill.color = white; c.drawCircle(pr, ny, h * 0.011f, fill)
    }

    /** Bottom row: ride mode, then phone heat · bike battery · fuel · lean with level bars, then trip and ODO. */
    private fun tourRow(c: Canvas, w: Float, h: Float, t: Float, b: Float) {
        data.fuelStop?.let { banner(c, w * 0.007f, t, w * 0.993f, b, h, it); return }
        val rad = h * 0.035f
        val mid = (t + b) / 2
        if (on(DashLayout.Part.MODE_TABS)) {
            rect.set(w * 0.007f, t, w * 0.2f, b)
            fill.color = theme.cardTop; c.drawRoundRect(rect, rad, rad, fill); rim(c, rect, rad, h)
            val mode = labels.tabs.getOrNull(modeIndex) ?: ""
            text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.028f; text.color = white
            c.drawText(labels.rideMode, w * 0.025f, mid - h * 0.02f, text)
            text.typeface = boldItalic; text.textSize = h * 0.058f; text.color = gold
            c.drawText(ellipsize(mode, w * 0.16f), w * 0.025f, mid + h * 0.045f, text)
        }
        data class Cell(val label: String, val value: String, val level: Float?, val warn: Boolean = false)
        val cells = listOfNotNull(
            Cell(labels.phone, data.phoneTempC?.let { "$it °C" } ?: "—", data.phoneTempC?.let { (it - 20) / 30f }, data.phoneHot).takeIf { on(DashLayout.Part.PHONE_TEMP) },
            Cell(labels.battery, data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—", data.bikeVolts?.let { (it - 11.5f) / 3f }, (data.bikeVolts ?: 99f) < PkeBattery.RED_RUNNING_V).takeIf { on(DashLayout.Part.BIKE_BATTERY) },
            Cell(labels.fuel, data.fuel?.let { "${(it * 100).roundToInt()} %" } ?: "—", data.fuel, (data.fuel ?: 1f) < 0.15f).takeIf { on(DashLayout.Part.FUEL) },
            Cell(labels.lean, data.leanDeg?.let { CustomDashUi.leanText(it) } ?: "—", data.leanDeg?.let { kotlin.math.abs(it) / 60f }).takeIf { on(DashLayout.Part.LEAN) },
        )
        val l = w * 0.21f; val r = w * 0.79f
        if (cells.isNotEmpty()) {  // every cell hidden: no empty card
            rect.set(l, t, r, b)
            fill.color = theme.cardTop; c.drawRoundRect(rect, rad, rad, fill); rim(c, rect, rad, h)
        }
        val cw = (r - l) / cells.size.coerceAtLeast(1)
        for ((i, cell) in cells.withIndex()) {
            val x = l + cw * i
            if (i > 0) { stroke.color = 0xFF2A2420.toInt(); stroke.strokeWidth = 1f; c.drawLine(x, t + h * 0.03f, x, b - h * 0.03f, stroke) }
            text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.026f; text.color = dim
            c.drawText(ellipsize(cell.label, cw - h * 0.05f), x + h * 0.03f, t + h * 0.05f, text)
            text.typeface = bold; text.textSize = h * 0.05f; text.color = if (cell.warn) red else white
            c.drawText(ellipsize(cell.value, cw - h * 0.05f), x + h * 0.03f, t + h * 0.11f, text)
            slantBar(c, x + h * 0.03f, t + h * 0.135f, x + cw - h * 0.03f, t + h * 0.152f, cell.level, if (cell.warn) red else gold)
        }
        // Trip / odo card: only what's shown, and no card when both are hidden.
        val counters = listOfNotNull(
            (labels.trip to data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) }).takeIf { on(DashLayout.Part.TRIP) },
            (labels.odo to data.odoKm?.let { String.format(Locale.getDefault(), "%,.0f km", it) }).takeIf { on(DashLayout.Part.ODO) },
        )
        if (counters.isEmpty()) return
        rect.set(w * 0.8f, t, w * 0.993f, b)
        fill.color = theme.cardTop; c.drawRoundRect(rect, rad, rad, fill); rim(c, rect, rad, h)
        val part = (w * 0.993f - w * 0.8f) / counters.size
        counters.forEachIndexed { i, (label, value) ->
            val x = w * 0.8f + part * i + h * 0.025f
            text.textAlign = Paint.Align.LEFT; text.typeface = bold; text.textSize = h * 0.026f; text.color = dim
            c.drawText(label, x, mid - h * 0.02f, text)
            text.textSize = h * 0.042f; text.color = white
            c.drawText(ellipsize(value ?: "—", part - h * 0.04f), x, mid + h * 0.035f, text)
        }
    }

    /** Level as four slanted segments, like the mockup's orange bars; null = all dark. */
    private fun slantBar(c: Canvas, l: Float, t: Float, r: Float, b: Float, f: Float?, color: Int, n: Int = 4) {
        val skew = (b - t) * 0.6f; val gap = (b - t) * 0.3f
        val sw = (r - l - gap * (n - 1)) / n
        val lit = f?.let { (it.coerceIn(0f, 1f) * n).roundToInt() } ?: 0
        for (i in 0 until n) {
            val x = l + i * (sw + gap)
            path.reset(); path.moveTo(x + skew, t); path.lineTo(x + sw, t); path.lineTo(x + sw - skew, b); path.lineTo(x, b); path.close()
            fill.color = if (i < lit) color else 0xFF2A2622.toInt(); c.drawPath(path, fill)
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
            (data.pressureHpa?.let { String.format(Locale.getDefault(), "%.1f hPa", it) } ?: data.altitudeM?.takeIf { on(DashLayout.Part.ALTITUDE) }?.let { "$it m" } ?: "—") to (if (data.pressureHpa != null) labels.pressure else labels.alt),
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
        val cells = listOfNotNull(
            ("GPS" to (data.gps ?: "—")).takeIf { on(DashLayout.Part.GPS) },
            labels.phone to (data.phoneBattery?.let { "$it%" } ?: "—"),
            (labels.trip to (data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—")).takeIf { on(DashLayout.Part.TRIP) },
            (labels.rangeShort to (data.rangeKm?.let { "≈ $it km" } ?: "—")).takeIf { on(DashLayout.Part.RANGE) },
            (labels.battery to (data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—")).takeIf { on(DashLayout.Part.BIKE_BATTERY) },
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
            backdrop(c, it, 0f, h * 0.165f, w * 0.43f, h * 0.69f, a, st, TO_GAUGE)  // under the ring's edge: no dark gap
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
        /** Race Bar's speed bar runs 0–this (labels every tenth, red from 80 %). */
        const val RACE_MAX_KMH = 200
        /** Over the posted limit by more than this before the sign turns red (GPS vs speedo spread). */
        const val LIMIT_TOLERANCE_KMH = 5
        /** How long the start-of-ride service reminder stays up. */
        const val MAINT_SHOW_MS = 20_000L
        const val RED_KMH = 120
        /** Speed alarm frame: on this long, then off this long. Tune on the bike. */
        const val ALARM_BLINK_MS = 300L
        /** Speed alarm frame colour: pure bright red (the theme's error red is too pink to read in sun). */
        const val ALARM_RED = 0xFFFF1A1A.toInt()
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
        /** AACLUSTER rows under the speedo; the other ticked rows go in the right column. */
        private val CLUSTER_LEFT = listOf(DashLayout.Part.RIDE_TIME, DashLayout.Part.AVG, DashLayout.Part.MAX_SPEED, DashLayout.Part.LEAN)
        /** Backdrop alpha (0–255) behind the left rows: art still visible, numbers still readable. Tune on the bike. */
        const val BACKDROP_DIM = 70
        /** Left-column art: thin feather at the screen edge, long fade into the gauge. */
        private val LEFT_FEATHER = floatArrayOf(0f, 0.06f, 0.55f, 1f)
        /** Left art that runs up to the gauge: full strength most of the way, fading only under the ring's edge. */
        private val TO_GAUGE = floatArrayOf(0f, 0.05f, 0.74f, 1f)

        /**
         * The road out of a turn instruction: "Turn right onto Thornton Rd" → "Thornton Rd",
         * "เลี้ยวขวาเข้าสู่ ถ.วิภาวดีรังสิต" → "ถ.วิภาวดีรังสิต". Google Maps often sends only the road; that stays as is.
         */
        internal fun roadOf(instruction: String): String {
            // A notification whose title isn't a distance arrives as "title · text": look in each part.
            val parts = instruction.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
            for (p in parts) for (key in ROAD_KEYS) {
                val i = p.indexOf(key, ignoreCase = true)
                if (i >= 0) return p.substring(i + key.length).trim().trimEnd('.')
            }
            return parts.lastOrNull().orEmpty()
        }
        private val ROAD_KEYS = listOf(" onto ", " on to ", " toward ", " towards ", "เข้าสู่", "ไปทาง", "ไปยัง")

        /** Litres left after [kmSinceFill] km from a full tank at [lPer100] L/100 km; null if unknown. */
        internal fun fuelLeft(tankL: Float, lPer100: Double?, kmSinceFill: Double?): Double? {
            if (tankL <= 0f || lPer100 == null || lPer100 <= 0.0 || kmSinceFill == null) return null
            return (tankL - kmSinceFill * lPer100 / 100.0).coerceIn(0.0, tankL.toDouble())
        }

        internal fun rangeKm(litres: Double, lPer100: Double): Int = (litres / lPer100 * 100.0).roundToInt()
    }
}
