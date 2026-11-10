// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of ZRide Screen (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View

/**
 * Android Auto + side panel ([DashLayout.Style.AASIDE]): Android Auto (Google Maps / Waze with their
 * own route line) on the right of the dash, the rider's gauge panel on the left.
 *
 * Android Auto is told the right-hand area's shape ([VideoPrefs.aaMarginsFor]) so it lays itself out
 * for it; [AaCompositor] draws it there and the panel ([AaSidePanel]) in the strip left of it.
 */
object AaSide {
    /** My dash → "show on the dash" with an Android Auto template: the AA connect about to start carries it. */
    @Volatile var requested = false
    /** This Android Auto session shares the dash with the rider's template. */
    @Volatile var active = false
    /** AASIDE: the left [frac] of the dash is the panel (0 otherwise). */
    @Volatile var frac = 0f
    /** Big-map template: Android Auto goes in this card (fractions of the dash); null = side panel. */
    @Volatile var card: RectF? = null

    /** Commit [requested] for the AA session that is starting now. */
    fun begin(ctx: Context) {
        active = requested && Premium.unlocked(ctx)
        requested = false
        frac = 0f; card = null
        if (!active) return
        val l = DashLayout.load(ctx)
        if (l.style == DashLayout.Style.AASIDE) { frac = l.sideFrac; return }
        val (w, h) = VideoPrefs.detectedPanelSize(ctx) ?: (ProDashView.DESIGN_W.toInt() to ProDashView.DESIGN_H.toInt())
        card = cardFrac(ctx, l, w, h)
        if (card == null) active = false
    }

    /** [l]'s map card on a [w]×[h] dash, as fractions; the bike's real canvas may differ a little from the guess at connect. */
    fun cardFrac(ctx: Context, l: DashLayout.Layout, w: Int, h: Int): RectF? {
        val v = ProDashView(ctx, l.accent, l.style, l.accent2, l.customColor, l.hidden)
        v.sideFrac = l.sideFrac
        v.layout(0, 0, w, h)
        val r = v.mapRect() ?: return null
        return RectF(r.left / w, r.top / h, r.right / w, r.bottom / h)
    }
}

/**
 * The AASIDE panel, [w]×[h] px: a [CustomDashUi] laid out off-screen and drawn ~10×/s into one of two
 * bitmaps (the compositor uploads one while the other is drawn). A frame equal to the last one isn't
 * sent, so a parked bike doesn't keep the encoder busy.
 */
class AaSidePanel(ctx: Context, private val w: Int, private val h: Int, private val sink: (Bitmap) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val ui = CustomDashUi(
        ContextThemeWrapper(ctx, R.style.Theme_OpenCfMoto),
        if (AaSide.card == null) DashLayout.load(ctx, DashLayout.Style.AASIDE) else DashLayout.load(ctx), h,
        projected = true, sidePanel = true,
    )
    private val bufs = Array(2) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    private var next = 0
    private var sent: Bitmap? = null
    private var running = true

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val b = bufs[next]
            b.eraseColor(Color.BLACK)
            ui.root.draw(Canvas(b))
            if (sent?.sameAs(b) != true) {
                sent = b
                next = 1 - next
                sink(b)
            }
            main.postDelayed(this, FRAME_MS)
        }
    }

    init {
        ui.root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        ui.root.layout(0, 0, w, h)
        ui.bind()
        main.post(tick)
        LogBus.log("[AASIDE] panel ${w}x$h " + if (AaSide.card == null) "beside Android Auto" else "around Android Auto (map card)")
    }

    fun release() {
        running = false
        main.removeCallbacks(tick)
        ui.release()
    }

    private companion object {
        const val FRAME_MS = 100L
    }
}
