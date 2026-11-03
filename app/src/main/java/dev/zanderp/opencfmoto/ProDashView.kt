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
class ProDashView(ctx: Context, private val accent: Int) : View(ctx) {

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
    )

    var data = Data()
        set(v) { field = v; invalidate() }

    private val gold = accent
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
    private val pump = ContextCompat.getDrawable(ctx, R.drawable.ic_tile_fuel)?.mutate()?.apply { setTint(accent) }
    private val placeholder = ContextCompat.getDrawable(ctx, R.drawable.ic_ride)?.mutate()?.apply {
        setTint(0x40FFFFFF)
    }
    private val rect = RectF()

    private val labels = object {
        val tabs = listOf(ctx.getString(R.string.pro_tab_sport), ctx.getString(R.string.pro_tab_road), ctx.getString(R.string.pro_tab_eco))
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
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        background(c, w, h)
        topBar(c, w, h)
        leftPanel(c, w, h)
        gauge(c, w, h)
        rightPanel(c, w, h)
        bottomStrip(c, w, h)
    }

    // ---------------------------------------------------------------- frame

    private fun background(c: Canvas, w: Float, h: Float) {
        fill.shader = RadialGradient(w / 2, h * 0.55f, w * 0.6f, 0xFF1A1A1F.toInt(), 0xFF050506.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
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

        // Mode tabs: the selected one follows the accent (SPORT gold · ROAD blue · ECO green).
        val sel = when (accent) { DashLayout.ACCENTS[2] -> 1; DashLayout.ACCENTS[1] -> 2; else -> 0 }
        val tw = w * 0.085f
        val th = h * 0.075f
        val tx = w / 2 - tw * 1.5f
        val ty = h * 0.045f
        for (i in 0..2) {
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
            text.typeface = boldItalic; text.textSize = h * 0.045f; text.textAlign = Paint.Align.CENTER
            text.color = if (i == sel) Color.BLACK else dim
            c.drawText(labels.tabs[i], l + tw / 2, ty + th * 0.72f, text)
        }

        // Right: Bluetooth state and the clock.
        text.textAlign = Paint.Align.RIGHT
        text.typeface = bold; text.textSize = h * 0.085f; text.color = white
        c.drawText(data.clock, w * 0.975f, base, text)
        val clockW = text.measureText(data.clock)
        text.textSize = h * 0.06f
        text.color = if (data.bluetoothOn) 0xFF4FC3F7.toInt() else dim
        c.drawText("ᛒ", w * 0.975f - clockW - h * 0.06f, base, text)
    }

    // ---------------------------------------------------------------- left: bike + rows

    private fun leftPanel(c: Canvas, w: Float, h: Float) {
        val l = w * 0.025f
        val r = w * 0.255f
        // Photo card.
        rect.set(l, h * 0.19f, r, h * 0.47f)
        val rad = h * 0.03f
        fill.color = panel
        c.drawRoundRect(rect, rad, rad, fill)
        data.photo?.let { bmp ->
            val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(rect.width() / bmp.width, rect.height() / bmp.height)
            sh.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(rect.centerX() - bmp.width * scale / 2, rect.centerY() - bmp.height * scale / 2)
            })
            fill.shader = sh
            c.drawRoundRect(rect, rad, rad, fill)
            fill.shader = null
        } ?: placeholder?.let { d ->
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
        )
        val top = h * 0.52f
        val rowH = h * 0.125f
        for ((i, row) in rows.withIndex()) {
            val y = top + i * rowH
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

        // Red zone and live speed arc.
        val ring = rad * 0.88f
        rect.set(cx - ring, cy - ring, cx + ring, cy + ring)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = h * 0.045f
        stroke.color = 0x55FF3B30
        c.drawArc(rect, start + sweep * RED_KMH / MAX_KMH, sweep * (MAX_KMH - RED_KMH) / MAX_KMH, false, stroke)
        if (speed > 0) {
            stroke.color = if (speed >= RED_KMH) red else gold
            c.drawArc(rect, start, sweep * speed / MAX_KMH, false, stroke)
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
        segments(c, cx, cy, sideR, 28f, 56f, volts, leftSide = false) { f -> if (f < 0.3f) red else 0xFF4FC3F7.toInt() }
        sideLabel(c, cx, cy, sideR, -32f, "H"); sideLabel(c, cx, cy, sideR, 32f, "L")
        pump?.let { d ->
            val side = (h * 0.055f).toInt()
            val x = (cx - sideR - h * 0.1f).toInt()
            val y = (cy - side / 2f).toInt()
            d.setBounds(x, y, x + side, y + side); d.draw(c)
        }
        text.textSize = h * 0.04f; text.typeface = bold
        text.color = 0xFF4FC3F7.toInt()
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
        stroke.strokeWidth = height * 0.035f
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
        val lr = r + height * 0.05f
        text.textAlign = Paint.Align.CENTER; text.typeface = bold; text.textSize = height * 0.04f; text.color = white
        c.drawText(s, cx + lr * cos(a).toFloat(), cy + lr * sin(a).toFloat() + text.textSize * 0.35f, text)
    }

    // ---------------------------------------------------------------- right: ride card

    private fun rightPanel(c: Canvas, w: Float, h: Float) {
        val l = w * 0.745f
        val r = w * 0.975f
        rect.set(l, h * 0.19f, r, h * 0.84f)
        val rad = h * 0.03f
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0xFF13202E.toInt(), 0xFF0B1118.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
        rim(c, rect, rad, h)

        val pad = w * 0.014f
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold; text.textSize = h * 0.038f; text.color = gold
        c.drawText(labels.ride, l + pad, rect.top + h * 0.07f, text)

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
