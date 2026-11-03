// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
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
class ProDashView(ctx: Context, private val accent: Int, private val style: DashLayout.Style = DashLayout.Style.PRO) : View(ctx) {

    /** Look of one template: [main] replaces the gold, [second] is the low end of the speed arc and the volts gauge. */
    private class Theme(val main: Int, val second: Int, val bg: Int, val cardTop: Int, val cardBottom: Int, val tabs: List<String>?, val framed: Boolean = true, val art: Int = 0, val scene: Int = 0, val fuelRow: Boolean = false)

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
    )

    var data = Data()
        set(v) { field = v; invalidate() }

    private val theme = when (style) {
        DashLayout.Style.CYBER -> Theme(0xFFFF2BD6.toInt(), 0xFF22E5FF.toInt(), 0xFF1C0B2A.toInt(), 0xFF1D0F33.toInt(), 0xFF0A0614.toInt(), listOf("CYBERPUNK", "ROAD", "ECO"), art = R.drawable.dash_art_cyber, scene = R.drawable.dash_scene_cyber)
        DashLayout.Style.DRAGON -> Theme(0xFFE8B04A.toInt(), 0xFFB5652B.toInt(), 0xFF241807.toInt(), 0xFF261B0C.toInt(), 0xFF120C05.toInt(), listOf("DRAGON", "GUARDIAN", "EXPLORER"), art = R.drawable.dash_art_dragon, scene = R.drawable.dash_scene_dragon)
        DashLayout.Style.RACING -> Theme(0xFFFF2A2A.toInt(), 0xFFC9CDD3.toInt(), 0xFF200A0A.toInt(), 0xFF1F1213.toInt(), 0xFF0E0909.toInt(), listOf("RACE", "SPORT", "TOURING"), art = R.drawable.dash_art_racing, scene = R.drawable.dash_scene_racing)
        DashLayout.Style.NEON -> Theme(0xFFB066FF.toInt(), 0xFF5B7CFF.toInt(), 0xFF170C2C.toInt(), 0xFF1A1236.toInt(), 0xFF0B0818.toInt(), listOf("RONIN", "SAMURAI", "NINJA"), art = R.drawable.dash_art_neon, scene = R.drawable.dash_scene_neon)
        DashLayout.Style.NEONCITY -> Theme(0xFFFF2E88.toInt(), 0xFF22E5FF.toInt(), 0xFF150A24.toInt(), 0xFF101A30.toInt(), 0xFF0A0E1C.toInt(), listOf("CYBERPUNK", "RACING", "TOURING", "ECO"), art = R.drawable.dash_art_neoncity, scene = R.drawable.dash_scene_neoncity, fuelRow = true)
        DashLayout.Style.ADVENTURE -> Theme(0xFFFFA41B.toInt(), 0xFFFFD36B.toInt(), 0xFF1C150A.toInt(), 0xFF13202A.toInt(), 0xFF0A1016.toInt(), listOf("ADVENTURE", "SPORT", "TOURING", "ECO"), art = R.drawable.dash_art_adventure, scene = R.drawable.dash_scene_adventure, fuelRow = true)
        DashLayout.Style.TOURING -> Theme(0xFFFFB81C.toInt(), 0xFFFF7A1A.toInt(), 0xFF0F1A2A.toInt(), 0xFF13233A.toInt(), 0xFF0A121E.toInt(), listOf("SPORT", "TOURING", "CITY", "ECO"), framed = false, art = R.drawable.dash_art_touring, scene = R.drawable.dash_scene_touring)
        else -> Theme(accent, 0xFF4FC3F7.toInt(), 0xFF1A1A1F.toInt(), 0xFF13202E.toInt(), 0xFF0B1118.toInt(), null, framed = false)
    }
    private val gold = theme.main
    /** Template artwork (cut from the ZRide mockup); the plain Pro look shows the Garage photo instead. */
    private val art = theme.art.takeIf { it != 0 }?.let { android.graphics.BitmapFactory.decodeResource(resources, it) }
    private val scene = theme.scene.takeIf { it != 0 }?.let { android.graphics.BitmapFactory.decodeResource(resources, it) }
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
        c.drawColor(0xFF050506.toInt())
        c.save()
        c.translate((vw - w * s) / 2f, (vh - h * s) / 2f)
        c.scale(s, s)
        background(c, w, h)
        topBar(c, w, h)
        if (style == DashLayout.Style.TOURING) touring(c, w, h)
        else { leftPanel(c, w, h); gauge(c, w, h); rightPanel(c, w, h); bottomStrip(c, w, h) }
        c.restore()
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
        text.typeface = bold; text.color = white; text.textAlign = Paint.Align.LEFT
        c.drawText(first, w * 0.03f, base, text)
        val x2 = w * 0.03f + text.measureText(first)
        text.typeface = boldItalic; text.color = gold; text.textSize = h * 0.085f
        c.drawText(last, x2, base, text)

        if (style != DashLayout.Style.TOURING) tabs(c, h, w / 2, h * 0.045f, w * 0.46f)

        // Right: Bluetooth state and the clock.
        text.textAlign = Paint.Align.RIGHT
        text.typeface = bold; text.textSize = h * 0.085f; text.color = white
        c.drawText(data.clock, w * 0.975f, base, text)
        val clockW = text.measureText(data.clock)
        text.textSize = h * 0.06f
        text.color = if (data.bluetoothOn) 0xFF4FC3F7.toInt() else dim
        c.drawText("ᛒ", w * 0.975f - clockW - h * 0.06f, base, text)
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
        // Photo card.
        rect.set(l, h * 0.19f, r, h * if (theme.fuelRow) 0.41f else 0.47f)
        val rad = h * 0.03f
        fill.color = panel
        c.drawRoundRect(rect, rad, rad, fill)
        (art ?: data.photo)?.let { cover(c, it, rad) } ?: placeholder?.let { d ->
            // No Garage photo yet: a faint bike outline instead of an empty card.
            val side = (rect.height() * 0.6f).toInt()
            val x = rect.centerX().toInt() - side / 2
            val y = rect.centerY().toInt() - side / 2
            d.setBounds(x, y, x + side, y + side)
            d.draw(c)
        }
        rim(c, rect, rad, h)

        val tyre = if (data.tyreFrontBar != null || data.tyreRearBar != null)
            "${bar(data.tyreFrontBar)} / ${bar(data.tyreRearBar)} bar" else "— bar"
        val rows = listOf(
            Triple("(!)", tyre, labels.tyre),
            Triple("°C", data.phoneTempC?.let { "$it °C" } ?: "—", labels.phone),
            Triple("V", data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—", labels.battery),
        ) + if (theme.fuelRow) listOf(Triple("", "", labels.fuel)) else emptyList()
        val top = h * if (theme.fuelRow) 0.43f else 0.52f
        val rowH = h * if (theme.fuelRow) 0.112f else 0.125f
        for ((i, row) in rows.withIndex()) {
            val y = top + i * rowH
            if (i == 3) {
                // Fuel row: pump, then E ▮▮▮▮▮ F.
                pump?.let { d -> val s = (h * 0.05f).toInt(); val x = (l + w * 0.025f - s / 2).toInt(); val yy = (y + rowH * 0.22f).toInt(); d.setBounds(x, yy, x + s, yy + s); d.draw(c) }
                fuelBar(c, l + w * 0.06f, y + rowH * 0.18f, r - w * 0.01f, y + rowH * 0.56f, h)
                text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = h * 0.034f; text.color = dim
                c.drawText(row.third, l + w * 0.06f, y + rowH * 0.88f, text)
                continue
            }
            text.textAlign = Paint.Align.CENTER; text.typeface = bold
            text.textSize = h * 0.05f; text.color = gold
            c.drawText(row.first, l + w * 0.025f, y + rowH * 0.6f, text)
            text.textAlign = Paint.Align.LEFT
            text.textSize = h * 0.06f
            text.color = if (i == 1 && data.phoneHot) red else white
            c.drawText(row.second, l + w * 0.06f, y + rowH * 0.55f, text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.034f; text.color = dim
            c.drawText(row.third, l + w * 0.06f, y + rowH * 0.88f, text)
            if (i < rows.size - 1) {
                stroke.color = 0xFF2A2A30.toInt(); stroke.strokeWidth = 1f
                c.drawLine(l + w * 0.01f, y + rowH, r, y + rowH, stroke)
            }
        }
    }

    // ---------------------------------------------------------------- centre gauge

    private fun gauge(c: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h * 0.53f
        val rad = h * 0.33f
        val start = 135f
        val sweep = 270f
        val speed = (data.speedKmh ?: 0).coerceIn(0, MAX_KMH)

        // Dark face + metallic rim.
        fill.shader = RadialGradient(cx, cy, rad, 0xFF16161B.toInt(), 0xFF070708.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, fill)
        fill.shader = null
        stroke.shader = SweepGradient(cx, cy, intArrayOf(0xFF3A3A40.toInt(), 0xFFE8E8EE.toInt(), 0xFF3A3A40.toInt(), 0xFF9A9AA2.toInt(), 0xFF3A3A40.toInt()), null)
        stroke.strokeWidth = h * 0.012f
        c.drawCircle(cx, cy, rad, stroke)
        stroke.shader = null
        if (theme.framed) glow(c, h) { c.drawCircle(cx, cy, rad * 1.03f, stroke) }
        if (style == DashLayout.Style.DRAGON) {
            // Scale ring: short gold dashes just outside the rim.
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(h * 0.02f, h * 0.012f), 0f)
            stroke.color = 0xAAE8B04A.toInt(); stroke.strokeWidth = h * 0.018f; stroke.strokeCap = Paint.Cap.BUTT
            c.drawCircle(cx, cy, rad * 1.08f, stroke)
            stroke.pathEffect = null; stroke.strokeCap = Paint.Cap.ROUND
        }

        // Red zone and live speed arc.
        val ring = rad * 0.88f
        rect.set(cx - ring, cy - ring, cx + ring, cy + ring)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = h * 0.045f
        stroke.color = 0x55FF3B30
        c.drawArc(rect, start + sweep * RED_KMH / MAX_KMH, sweep * (MAX_KMH - RED_KMH) / MAX_KMH, false, stroke)
        if (speed > 0) {
            stroke.color = if (speed >= RED_KMH) red else gold
            if (theme.framed && speed < RED_KMH) stroke.shader = SweepGradient(cx, cy, intArrayOf(second, gold, gold), floatArrayOf(0f, 0.4f, 1f))
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
            stroke.color = if (v >= RED_KMH) red else white
            stroke.strokeWidth = h * if (major) 0.008f else 0.004f
            c.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), stroke)
            if (major) {
                val lr = rad * 0.66f
                text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic
                text.textSize = h * 0.05f; text.color = if (v >= RED_KMH) red else white
                c.drawText("$v", cx + lr * cos(a).toFloat(), cy + lr * sin(a).toFloat() + text.textSize * 0.35f, text)
            }
        }

        // Speed.
        text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic
        text.textSize = h * 0.24f; text.color = white
        c.drawText(data.speedKmh?.toString() ?: "—", cx, cy + h * 0.06f, text)
        text.typeface = bold; text.textSize = h * 0.045f; text.color = dim
        c.drawText("km/h", cx, cy + h * 0.13f, text)

        // "Gear" box at the bottom: heading (the bike reports no gear).
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
        c.drawText(labels.heading, cx, bb - (bb - bt) * 0.08f, text)

        // Side gauges: fuel (left, F at top) and bike battery (right, 11.5–14.5 V).
        val sideR = rad * 1.17f
        // Canvas angles run clockwise from 3 o'clock, so 152° is lower-left and 28° lower-right.
        segments(c, cx, cy, sideR, 152f, 56f, data.fuel, leftSide = true) { f -> if (f < 0.15f) red else gold }
        sideLabel(c, cx, cy, sideR, 212f, "F"); sideLabel(c, cx, cy, sideR, 148f, "E")
        val volts = data.bikeVolts?.let { ((it - 11.5f) / 3f).coerceIn(0f, 1f) }
        segments(c, cx, cy, sideR, 28f, 56f, volts, leftSide = false) { f -> if (f < 0.3f) red else second }
        sideLabel(c, cx, cy, sideR, -32f, "H"); sideLabel(c, cx, cy, sideR, 32f, "L")
        pump?.let { d ->
            val side = (h * 0.055f).toInt()
            val x = (cx - sideR - h * 0.1f).toInt()
            val y = (cy - side / 2f).toInt()
            d.setBounds(x, y, x + side, y + side); d.draw(c)
        }
        text.textSize = h * 0.04f; text.typeface = bold
        text.color = second
        c.drawText("V", cx + sideR + h * 0.075f, cy + h * 0.015f, text)
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

    private fun rightPanel(c: Canvas, w: Float, h: Float, l: Float = w * 0.745f, r: Float = w * 0.975f, top: Float = h * 0.19f, bottom: Float = h * 0.84f, stats: Boolean = true) {
        rect.set(l, top, r, bottom)
        val rad = h * 0.03f
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, theme.cardTop, theme.cardBottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
        scene?.let {
            // Scenery behind the ride card, faded out top and bottom so the numbers stay readable.
            cover(c, it, rad)
            val t = theme.cardTop and 0x00FFFFFF
            val b = theme.cardBottom and 0x00FFFFFF
            fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                intArrayOf(t or 0xE6000000.toInt(), t or 0xCC000000.toInt(), t or 0x40000000, b or 0xF2000000.toInt()),
                floatArrayOf(0f, 0.36f, 0.58f, 0.72f), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, rad, rad, fill)
            fill.shader = null
        }
        rim(c, rect, rad, h)

        val pad = w * 0.014f
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold; text.textSize = h * 0.038f; text.color = gold
        c.drawText(labels.ride, l + pad, rect.top + h * 0.07f, text)
        if (style == DashLayout.Style.ADVENTURE || style == DashLayout.Style.TOURING) {
            // Altitude chip, top right of the card (GPS height).
            text.textAlign = Paint.Align.RIGHT; text.color = white
            c.drawText("▲ " + (data.altitudeM?.let { String.format(Locale.getDefault(), "%,d m", it) } ?: "—"), r - pad, rect.top + h * 0.07f, text)
            text.textAlign = Paint.Align.LEFT; text.color = gold
        }

        // Big line: estimated range, else the most urgent maintenance.
        val big = data.rangeKm?.let { "≈ $it km" }
        text.textSize = h * 0.095f; text.color = white; text.typeface = boldItalic
        c.drawText(big ?: (data.maintenance?.substringBefore('\n') ?: "—"), l + pad, rect.top + h * 0.19f, text)
        text.typeface = Typeface.DEFAULT; text.textSize = h * 0.035f; text.color = dim
        val sub = if (big != null) labels.range else data.maintenance?.substringAfter('\n', "")?.let { "${labels.maint} · $it" } ?: labels.maint
        c.drawText(ellipsize(sub, rect.width() - 2 * pad), l + pad, rect.top + h * 0.245f, text)

        val maint = data.maintenance
        if (big != null && maint != null) {
            text.textSize = h * 0.035f; text.color = white
            c.drawText(ellipsize("${labels.maint}: ${maint.replace('\n', ' ')}", rect.width() - 2 * pad), l + pad, rect.top + h * 0.33f, text)
        }

        if (!stats) return
        // Bottom row: avg · ride time · max.
        val cols = listOf(
            (data.avgKmh?.toString() ?: "—") to labels.avg,
            (data.rideTime ?: "—") to labels.time,
            (data.maxKmh?.toString() ?: "—") to labels.max,
        )
        val rowTop = rect.bottom - h * 0.2f
        stroke.color = 0xFF24303C.toInt(); stroke.strokeWidth = 1f
        c.drawLine(l + pad, rowTop, r - pad, rowTop, stroke)
        val cw = rect.width() / 3
        for ((i, col) in cols.withIndex()) {
            val x = l + cw * i + cw / 2
            text.textAlign = Paint.Align.CENTER
            text.typeface = bold; text.textSize = h * 0.065f; text.color = white
            c.drawText(col.first, x, rowTop + h * 0.095f, text)
            text.typeface = Typeface.DEFAULT; text.textSize = h * 0.032f; text.color = dim
            c.drawText(col.second, x, rowTop + h * 0.15f, text)
        }
    }

    // ---------------------------------------------------------------- bottom: ODO · TRIP

    private fun bottomStrip(c: Canvas, w: Float, h: Float) {
        val y = h * 0.955f
        val odo = data.odoKm?.let { "≈ " + String.format(Locale.getDefault(), "%,.0f km", it) } ?: "—"
        val trip = data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—"
        val left = "${labels.odo}  $odo"
        val right = "${labels.trip}  $trip"
        text.typeface = bold; text.textSize = h * 0.05f; text.color = white
        text.textAlign = Paint.Align.RIGHT
        c.drawText(left, w / 2 - h * 0.04f, y, text)
        text.textAlign = Paint.Align.LEFT
        c.drawText(right, w / 2 + h * 0.04f, y, text)
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
        rect.set(w * 0.02f, h * 0.18f, w * 0.25f, h * 0.82f)
        val rad = h * 0.03f
        fill.color = panel; c.drawRoundRect(rect, rad, rad, fill)
        (art ?: data.photo)?.let { cover(c, it, rad) }
        rim(c, rect, rad, h)

        text.textAlign = Paint.Align.LEFT; text.typeface = boldItalic; text.textSize = h * 0.038f; text.color = white
        c.drawText(labels.rideMode, w * 0.28f, h * 0.215f, text)
        tabs(c, h, w * 0.28f, h * 0.235f, w * 0.33f, fromLeft = true)

        // Curved speed bar: from the bottom left, sweeping up to the right.
        path.reset()
        path.moveTo(w * 0.38f, h * 0.68f); path.quadTo(w * 0.69f, h * 0.68f, w * 0.71f, h * 0.35f)
        val pm = android.graphics.PathMeasure(path, false)
        val len = pm.length
        val seg = Path()
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeWidth = h * 0.05f
        stroke.color = 0xFF18202C.toInt(); c.drawPath(path, stroke)
        pm.getSegment(len * RED_KMH / MAX_KMH, len, seg, true)
        stroke.color = 0x55FF3B30; c.drawPath(seg, stroke)
        val speed = (data.speedKmh ?: 0).coerceIn(0, MAX_KMH)
        if (speed > 0) {
            seg.reset(); pm.getSegment(0f, len * speed / MAX_KMH, seg, true)
            stroke.color = if (speed >= RED_KMH) red else Color.WHITE
            if (speed < RED_KMH) stroke.shader = LinearGradient(w * 0.38f, 0f, w * 0.70f, 0f, gold, second, Shader.TileMode.CLAMP)
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
            stroke.color = if (v >= RED_KMH) red else white; stroke.strokeWidth = h * 0.006f
            c.drawLine(pos[0] + nx * h * 0.028f, pos[1] + ny * h * 0.028f, pos[0] + nx * h * 0.05f, pos[1] + ny * h * 0.05f, stroke)
            text.textAlign = Paint.Align.CENTER; text.typeface = boldItalic; text.textSize = h * 0.04f
            text.color = if (v >= RED_KMH) red else white
            c.drawText("$v", pos[0] + nx * h * 0.085f, pos[1] + ny * h * 0.085f + text.textSize * 0.35f, text)
        }

        // Digital speed.
        text.textAlign = Paint.Align.LEFT; text.typeface = boldItalic; text.textSize = h * 0.25f; text.color = white
        val sp = data.speedKmh?.toString() ?: "—"
        c.drawText(sp, w * 0.28f, h * 0.55f, text)
        val sw = text.measureText(sp)
        text.typeface = bold; text.textSize = h * 0.05f; text.color = dim
        c.drawText("km/h", w * 0.28f + sw + h * 0.02f, h * 0.55f, text)

        // Heading where the mockup has the gear.
        path.reset()
        val bl = w * 0.655f; val br = w * 0.735f; val bt = h * 0.55f; val bb = h * 0.69f
        path.moveTo(bl + h * 0.03f, bt); path.lineTo(br, bt); path.lineTo(br - h * 0.03f, bb); path.lineTo(bl, bb); path.close()
        fill.color = 0xE60D1420.toInt(); c.drawPath(path, fill)
        stroke.color = (gold and 0x00FFFFFF) or 0x88000000.toInt(); stroke.strokeWidth = h * 0.004f; c.drawPath(path, stroke)
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = h * 0.028f; text.color = white
        c.drawText(labels.heading, (bl + br) / 2, bt + h * 0.035f, text)
        text.typeface = boldItalic; text.textSize = h * 0.07f; text.color = gold
        c.drawText(data.heading ?: "—", (bl + br) / 2, bb - h * 0.02f, text)

        // Chips: tyre · phone · battery · fuel.
        val ct = h * 0.725f; val cb = h * 0.815f
        val cl = w * 0.265f; val cr = w * 0.98f
        val cw = (cr - cl) / 4
        val gap = h * 0.015f
        val tyre = if (data.tyreFrontBar != null || data.tyreRearBar != null) "${bar(data.tyreFrontBar)} / ${bar(data.tyreRearBar)}" else "— bar"
        chip(c, cl, ct, cl + cw - gap, cb, h, "(!)", tyre)
        chip(c, cl + cw, ct, cl + 2 * cw - gap, cb, h, "°C", data.phoneTempC?.let { "$it °C" } ?: "—")
        chip(c, cl + 2 * cw, ct, cl + 3 * cw - gap, cb, h, "V", data.bikeVolts?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: "—")
        chip(c, cl + 3 * cw, ct, cr, cb, h, "", "")
        pump?.let { d -> val s = (h * 0.045f).toInt(); val x = (cl + 3 * cw + h * 0.025f).toInt(); val y = ((ct + cb) / 2 - s / 2).toInt(); d.setBounds(x, y, x + s, y + s); d.draw(c) }
        fuelBar(c, cl + 3 * cw + h * 0.09f, ct + h * 0.025f, cr - h * 0.02f, cb - h * 0.025f, h)

        // Bottom tiles: trip · odo · altitude · avg · ride time.
        val tt = h * 0.84f; val tb = h * 0.975f
        val tl = w * 0.02f
        val tileW = (w * 0.98f - tl) / 5
        val tiles = listOf(
            Triple("↗", data.tripKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "—", labels.trip),
            Triple("◔", data.odoKm?.let { "≈ " + String.format(Locale.getDefault(), "%,.0f km", it) } ?: "—", labels.odo),
            Triple("▲", data.altitudeM?.let { String.format(Locale.getDefault(), "%,d m", it) } ?: "—", labels.alt),
            Triple("⌀", data.avgKmh?.let { "$it km/h" } ?: "—", labels.avg.substringBefore(" km/h")),
            Triple("◷", data.rideTime ?: "—", labels.time),
        )
        for ((i, t) in tiles.withIndex()) chip(c, tl + i * tileW, tt, tl + (i + 1) * tileW - gap, tb, h, t.first, t.second, t.third)

        rightPanel(c, w, h, l = w * 0.755f, r = w * 0.98f, top = h * 0.18f, bottom = h * 0.70f, stats = false)
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
                c.drawText("侍", w * 0.718f, h * 0.31f, text)
            }
            DashLayout.Style.DRAGON -> {
                // Warm cloud bands along the bottom corners.
                for ((x, dir) in listOf(0f to 1f, w to -1f)) {
                    fill.shader = RadialGradient(x, h, h * 0.45f, 0x55B5652B, 0x00000000, Shader.TileMode.CLAMP)
                    c.drawCircle(x + dir * h * 0.05f, h, h * 0.45f, fill)
                }
                fill.shader = null
            }
            else -> Unit
        }
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

        /** Litres left after [kmSinceFill] km from a full tank at [lPer100] L/100 km; null if unknown. */
        internal fun fuelLeft(tankL: Float, lPer100: Double?, kmSinceFill: Double?): Double? {
            if (tankL <= 0f || lPer100 == null || lPer100 <= 0.0 || kmSinceFill == null) return null
            return (tankL - kmSinceFill * lPer100 / 100.0).coerceIn(0.0, tankL.toDouble())
        }

        internal fun rangeKm(litres: Double, lPer100: Double): Int = (litres / lPer100 * 100.0).roundToInt()
    }
}
