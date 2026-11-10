// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Dash colour picker: a single colour or a two-stop gradient. Hex, R/G/B sliders, a spectrum,
 * 22 presets, the rider's saved colours (+ / bin) and an eyedropper that picks from the Garage
 * photo (or the ZRide hero art). No alpha: the dash is drawn opaque.
 */
object ColorPicker {
    private const val PREFS = "color_picker"
    private const val MAX_SAVED = 14

    val PRESETS = longArrayOf(
        0xFFFF2D2D, 0xFFFF8A1F, 0xFFFDC052, 0xFFFFEB3B, 0xFF8BC34A, 0xFF0B6B3A, 0xFF2EB872, 0xFF17A398,
        0xFF42A5F5, 0xFF1572C4, 0xFF3A3A9E, 0xFF6A3A9E, 0xFFE91E78, 0xFFFF80AB, 0xFFC9B79C, 0xFFC49A6C,
        0xFF8D5F3A, 0xFF5B3A1A, 0xFF000000, 0xFF4A4A4A, 0xFF9E9E9E, 0xFFFFFFFF,
    ).map { it.toInt() }

    fun saved(ctx: Context): List<Int> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("saved", "").orEmpty()
            .split(',').mapNotNull { it.toLongOrNull()?.toInt() }

    private fun setSaved(ctx: Context, list: List<Int>) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("saved", list.take(MAX_SAVED).joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()

    /** "#RRGGBB" / "RRGGBB" → opaque colour, else null. */
    internal fun parseHex(s: String): Int? {
        val h = s.trim().removePrefix("#")
        if (h.length != 6 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0xFF000000L or h.toLong(16)).toInt()
    }

    internal fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /** A second stop that reads as a gradient of [c]: same hue, turned 40° and a bit darker. */
    internal fun partner(c: Int): Int {
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        hsv[0] = (hsv[0] + 40f) % 360f
        hsv[2] = (hsv[2] * 0.85f).coerceAtLeast(0.25f)
        return Color.HSVToColor(hsv)
    }

    fun show(a: AppCompatActivity, start: Int, end: Int?, onPick: (Int, Int?) -> Unit) {
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val primary = ContextCompat.getColor(a, R.color.text_primary)
        val secondary = ContextCompat.getColor(a, R.color.text_secondary)

        var gradient = end != null
        val stops = intArrayOf(start or 0xFF000000.toInt(), (end ?: partner(start)) or 0xFF000000.toInt())
        var sel = 0
        var updating = false
        lateinit var refresh: (String) -> Unit
        fun set(c: Int, from: String) { stops[sel] = c or 0xFF000000.toInt(); refresh(from) }

        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(8), px(18), px(4)) }

        // Solid | Gradient
        val tabs = LinearLayout(a)
        val tabSolid = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setText(R.string.cp_solid) }
        val tabGrad = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setText(R.string.cp_gradient) }
        tabs.addView(tabSolid, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = px(6) })
        tabs.addView(tabGrad, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(tabs)

        // Preview: one or two stop circles, the gradient bar, eyedropper, hex.
        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(10), 0, px(6)) }
        val dotA = View(a)
        val dotB = View(a)
        row.addView(dotA, LinearLayout.LayoutParams(px(52), px(52)))
        row.addView(dotB, LinearLayout.LayoutParams(px(52), px(52)).apply { marginStart = px(8) })
        val eye = ImageButton(a).apply {
            setImageResource(R.drawable.ic_eyedropper)
            imageTintList = ColorStateList.valueOf(primary)
            background = null
            contentDescription = a.getString(R.string.cp_eyedropper)
            setPadding(px(10), px(10), px(10), px(10))
        }
        row.addView(eye, LinearLayout.LayoutParams(px(48), px(48)).apply { marginStart = px(8) })
        row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        val hexField = EditText(a).apply {
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            typeface = Typeface.MONOSPACE; setTextColor(primary); textSize = 16f
            gravity = Gravity.CENTER; minEms = 5
        }
        row.addView(hexField, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(row)
        val bar = View(a)
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, px(12)).apply { bottomMargin = px(6) })

        // R / G / B sliders, each track showing what moving it would give.
        val seeks = Array(3) { SeekBar(a) }
        // One track drawable per slider, recoloured in place (swapping drawables loses the 8 dp height).
        val tracks = Array(3) { GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.BLACK, Color.WHITE)).apply { cornerRadius = px(4).toFloat() } }
        val values = Array(3) { TextView(a) }
        for ((i, name) in listOf("R", "G", "B").withIndex()) {
            val r = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(TextView(a).apply { text = name; setTextColor(secondary); textSize = 14f }, LinearLayout.LayoutParams(px(20), WRAP_CONTENT))
            seeks[i].apply {
                max = 255
                progressDrawable = tracks[i]
                minHeight = px(8); maxHeight = px(8)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                        if (!user || updating) return
                        val c = stops[sel]
                        val rgb = intArrayOf(Color.red(c), Color.green(c), Color.blue(c)).also { it[i] = p }
                        set(Color.rgb(rgb[0], rgb[1], rgb[2]), "slider")
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            r.addView(seeks[i], LinearLayout.LayoutParams(0, px(36), 1f))
            values[i].apply { setTextColor(primary); textSize = 14f; gravity = Gravity.END; typeface = Typeface.MONOSPACE }
            r.addView(values[i], LinearLayout.LayoutParams(px(40), WRAP_CONTENT))
            root.addView(r)
        }

        val spectrum = SpectrumView(a) { set(it, "spectrum") }
        root.addView(spectrum, LinearLayout.LayoutParams(MATCH_PARENT, px(150)).apply { topMargin = px(8); bottomMargin = px(10) })

        // 22 presets in two rows.
        for (chunk in PRESETS.chunked(11)) {
            val pr = LinearLayout(a)
            for (c in chunk) pr.addView(dot(a, c, px(2)).apply { setOnClickListener { set(c, "preset") } },
                LinearLayout.LayoutParams(0, px(26), 1f).apply { setMargins(px(2), px(3), px(2), px(3)) })
            root.addView(pr)
        }

        // Saved colours + add / delete.
        root.addView(TextView(a).apply { setText(R.string.cp_saved); setTextColor(secondary); textSize = 13f; setPadding(0, px(10), 0, px(4)) })
        val savedRow = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val savedList = LinearLayout(a)
        var savedSel: Int? = null
        lateinit var renderSaved: () -> Unit
        renderSaved = {
            savedList.removeAllViews()
            for (c in saved(a)) savedList.addView(dot(a, c, px(if (c == savedSel) 3 else 1)).apply {
                setOnClickListener { savedSel = c; set(c, "saved"); renderSaved() }
            }, LinearLayout.LayoutParams(px(30), px(30)).apply { marginEnd = px(6) })
        }
        savedRow.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(savedList) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        savedRow.addView(TextView(a).apply {
            text = "+"; textSize = 28f; setTextColor(primary); setPadding(px(12), 0, px(12), 0)
            contentDescription = a.getString(R.string.cp_add)
            setOnClickListener {
                val c = stops[sel]
                setSaved(a, listOf(c) + saved(a).filter { it != c }); savedSel = c; renderSaved()
            }
        })
        savedRow.addView(ImageButton(a).apply {
            setImageResource(R.drawable.ic_delete); imageTintList = ColorStateList.valueOf(primary); background = null
            contentDescription = a.getString(R.string.cp_delete)
            setOnClickListener {
                val list = saved(a)
                val drop = savedSel ?: list.firstOrNull() ?: return@setOnClickListener
                setSaved(a, list.filter { it != drop }); savedSel = null; renderSaved()
            }
        }, LinearLayout.LayoutParams(px(44), px(44)))
        root.addView(savedRow)
        renderSaved()

        refresh = { from ->
            updating = true
            val c = stops[sel]
            dotA.background = circle(stops[0], if (sel == 0 && gradient) Color.WHITE else 0x66FFFFFF, px(if (sel == 0 && gradient) 3 else 1))
            dotB.background = circle(stops[1], if (sel == 1) Color.WHITE else 0x66FFFFFF, px(if (sel == 1) 3 else 1))
            dotB.visibility = if (gradient) View.VISIBLE else View.GONE
            bar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                if (gradient) intArrayOf(stops[0], stops[1]) else intArrayOf(stops[0], stops[0])).apply { cornerRadius = px(6).toFloat() }
            if (from != "hex") hexField.setText(hex(c))
            val rgb = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
            for (i in 0..2) {
                seeks[i].progress = rgb[i]
                values[i].text = rgb[i].toString()
                val lo = rgb.copyOf().also { it[i] = 0 }
                val hi = rgb.copyOf().also { it[i] = 255 }
                tracks[i].colors = intArrayOf(Color.rgb(lo[0], lo[1], lo[2]), Color.rgb(hi[0], hi[1], hi[2]))
            }
            if (from != "spectrum") spectrum.mark(c)
            tabSolid.alpha = if (gradient) 0.5f else 1f
            tabGrad.alpha = if (gradient) 1f else 0.5f
            updating = false
        }
        hexField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { if (!updating) parseHex(e.toString())?.let { set(it, "hex") } }
        })
        tabSolid.setOnClickListener { gradient = false; sel = 0; refresh("tab") }
        tabGrad.setOnClickListener { gradient = true; refresh("tab") }
        dotA.setOnClickListener { sel = 0; refresh("stop") }
        dotB.setOnClickListener { sel = 1; refresh("stop") }
        eye.setOnClickListener { eyedrop(a) { set(it, "eye") } }
        refresh("open")

        AlertDialog.Builder(a)
            .setTitle(R.string.cp_title)
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton(R.string.cp_apply) { _, _ -> onPick(stops[0], if (gradient) stops[1] else null) }
            .setNegativeButton(R.string.dash_cancel, null)
            .show()
    }

    /** Tap a point of the Garage photo (or the ZRide hero art) to take its colour. */
    private fun eyedrop(a: AppCompatActivity, onColor: (Int) -> Unit) {
        val bmp: Bitmap = BikeMemory.selected(a)?.photoPath?.let { path ->
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 1080) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        } ?: BitmapFactory.decodeResource(a.resources, stockHero(BikeMemory.selected(a)?.name) ?: R.drawable.home_hero_368g)
        val img = ImageView(a).apply { setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_XY }
        val dialog = AlertDialog.Builder(a).setTitle(R.string.cp_eyedropper_hint).setView(img)
            .setNegativeButton(R.string.dash_cancel, null).create()
        img.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP && v.width > 0 && v.height > 0) {
                val x = (e.x / v.width * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                val y = (e.y / v.height * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                onColor(bmp.getPixel(x, y) or 0xFF000000.toInt())
                dialog.dismiss()
            }
            true
        }
        dialog.show()
    }

    private fun circle(c: Int, rim: Int, w: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(c); setStroke(w, rim)
    }

    private fun dot(ctx: Context, c: Int, rimPx: Int) = View(ctx).apply {
        background = circle(c, 0x66FFFFFF, rimPx)
        contentDescription = hex(c)
    }

    /**
     * Hue left → right; white at the top, the pure hue in the middle, black at the bottom
     * (same layout as the brand mockup's picker).
     */
    private class SpectrumView(ctx: Context, private val onPick: (Int) -> Unit) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f)) }.also { it[6] = Color.RED }
        private var marked: Int? = null
        private var tx = -1f
        private var ty = -1f

        fun mark(c: Int) { marked = c; tx = -1f; invalidate() }

        private fun colorAt(x: Float, y: Float): Int {
            val hue = (x / width * 360f).coerceIn(0f, 359.9f)
            val half = height / 2f
            return if (y < half) Color.HSVToColor(floatArrayOf(hue, (y / half).coerceIn(0f, 1f), 1f))
            else Color.HSVToColor(floatArrayOf(hue, 1f, (1f - (y - half) / half).coerceIn(0f, 1f)))
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.shader = LinearGradient(0f, 0f, w, 0f, hues, null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.WHITE, 0x00FFFFFF, 0x00000000, Color.BLACK),
                floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            var x = tx
            var y = ty
            if (x < 0f) {
                val m = marked ?: return
                val hsv = FloatArray(3).also { Color.colorToHSV(m, it) }
                x = hsv[0] / 360f * w
                y = if (hsv[2] >= 0.999f) hsv[1] * h / 2f else h / 2f + (1f - hsv[2]) * h / 2f
            }
            val r = h * 0.06f
            ring.strokeWidth = r * 0.45f; ring.color = Color.WHITE
            c.drawCircle(x, y, r, ring)
            ring.strokeWidth = r * 0.15f; ring.color = Color.BLACK
            c.drawCircle(x, y, r * 1.25f, ring)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            tx = e.x.coerceIn(0f, width.toFloat())
            ty = e.y.coerceIn(0f, height.toFloat())
            onPick(colorAt(tx, ty))
            invalidate()
            return true
        }
    }
}
