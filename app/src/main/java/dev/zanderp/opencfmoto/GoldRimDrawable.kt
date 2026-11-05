// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * Gold card rim that brightens towards the bottom right. With [running], two comets of light chase
 * each other round the rim (call [start] / [stop] with the screen's lifecycle). Honours the system
 * "remove animations" setting through ValueAnimator.
 */
class GoldRimDrawable(ctx: Context, radiusDp: Float, private val running: Boolean = false) : Drawable() {
    private val dp = ctx.resources.displayMetrics.density
    private val radius = radiusDp * dp
    private val gold = ContextCompat.getColor(ctx, R.color.brand_gold)
    private val hot = 0xFFFFF3B0.toInt()
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * dp }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val rect = RectF()
    private val path = Path()
    private val seg = Path()
    private var measure: PathMeasure? = null
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidateSelf() }
    }

    fun start() { if (running && !anim.isStarted) anim.start() }
    fun stop() { anim.cancel() }

    override fun onBoundsChange(b: Rect) {
        val inset = 0.8f * dp
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        rim.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(withAlpha(gold, 0x66), withAlpha(gold, 0x26), withAlpha(gold, 0xFF)), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        measure = PathMeasure(path, false)
    }

    override fun draw(c: Canvas) {
        c.drawRoundRect(rect, radius, radius, rim)
        if (!running) return
        val m = measure ?: return
        val len = m.length
        val tail = len * 0.2f
        val n = 18
        for (k in 0..1) {
            val head = ((phase + k * 0.5f) % 1f) * len
            for (i in 0 until n) {
                val f = (i + 1f) / n
                seg.reset()
                piece(m, head - tail + tail * i / n, head - tail + tail * (i + 1) / n, len)
                light.color = withAlpha(gold, (0x55 * f).toInt()); light.strokeWidth = 7f * dp
                c.drawPath(seg, light)
                light.color = withAlpha(hot, (0xFF * f).toInt()); light.strokeWidth = 2f * dp
                c.drawPath(seg, light)
            }
        }
    }

    /** Path piece from [a] to [b] along the rim, wrapping round the start point. */
    private fun piece(m: PathMeasure, a: Float, b: Float, len: Float) {
        var s = a
        var e = b
        if (s < 0f) { s += len; e += len }
        if (e <= len) m.getSegment(s, e, seg, true)
        else { m.getSegment(s, len, seg, true); m.getSegment(0f, e - len, seg, true) }
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
