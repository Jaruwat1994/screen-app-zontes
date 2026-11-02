// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import dev.zanderp.opencfmoto.DashLayout.Widget

/**
 * Edits the rider's dash screen ([DashLayout]) with a live preview at the dash's shape.
 * "Show on dash" returns RESULT_OK; [MainActivity] then connects in that mode.
 */
class DashLayoutActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private var layout = DashLayout.DEFAULT
    private var preview: CustomDashUi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        layout = DashLayout.load(this)
        render()
    }

    override fun onDestroy() {
        preview?.release()
        super.onDestroy()
    }

    private fun update(l: DashLayout.Layout) {
        layout = l
        DashLayout.save(this, l)
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(text(getString(R.string.dl_title), 22f, R.color.text_primary, bold = true))
        body.addView(text(getString(R.string.dl_hint), 13f, R.color.text_secondary))

        // Preview: the real dash view at the panel's pixel size, scaled down to the screen width.
        val (pw, ph) = PREVIEW_W to PREVIEW_H
        preview?.release()
        val ui = CustomDashUi(this, layout, ph, projected = false).also { preview = it; it.bind() }
        val frame = FrameLayout(this)
        frame.addView(ui.root, FrameLayout.LayoutParams(pw, ph))
        body.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { topMargin = dp(12) })
        frame.post {
            val s = frame.width / pw.toFloat()
            frame.layoutParams = frame.layoutParams.apply { height = (ph * s).toInt() }
            ui.root.pivotX = 0f
            ui.root.pivotY = 0f
            ui.root.scaleX = s
            ui.root.scaleY = s
        }

        // Widgets: shown ones in dash order (first = large), then the hidden ones.
        body.addView(section(getString(R.string.dl_widgets)))
        val shown = layout.widgets
        for ((i, w) in (shown + Widget.entries.filter { it !in shown }).withIndex()) {
            val on = w in shown
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(CheckBox(this).apply {
                text = getString(w.label) + if (i == 0 && on) " · " + getString(R.string.dl_large) else ""
                setTextColor(color(R.color.text_primary))
                isChecked = on
                setOnCheckedChangeListener { _, checked -> toggle(w, checked) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (on) {
                row.addView(arrow("▲", enabled = i > 0) { move(i, i - 1) })
                row.addView(arrow("▼", enabled = i < shown.size - 1) { move(i, i + 1) })
            }
            body.addView(row)
        }

        body.addView(section(getString(R.string.dl_accent)))
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in DashLayout.ACCENTS) {
            swatches.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    if (c == layout.accent) setStroke(dp(3), Color.WHITE)
                }
                contentDescription = String.format("#%06X", c and 0xFFFFFF)
                setOnClickListener { update(layout.copy(accent = c)) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        }
        body.addView(swatches)

        body.addView(MaterialButton(this).apply {
            text = getString(R.string.dl_send)
            setOnClickListener { setResult(RESULT_OK); finish() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
    }

    private fun toggle(w: Widget, on: Boolean) {
        val list = layout.widgets
        when {
            on && list.size >= DashLayout.MAX_WIDGETS -> {
                Toast.makeText(this, getString(R.string.dl_max, DashLayout.MAX_WIDGETS), Toast.LENGTH_SHORT).show()
                render()
            }
            on -> update(layout.copy(widgets = list + w))
            list.size <= 1 -> render()  // keep at least one widget
            else -> update(layout.copy(widgets = list - w))
        }
    }

    private fun move(from: Int, to: Int) {
        val list = layout.widgets.toMutableList()
        list.add(to, list.removeAt(from))
        update(layout.copy(widgets = list))
    }

    // ---------------------------------------------------------------- small views

    private fun arrow(s: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 18f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(color(if (enabled) R.color.brand_orange else R.color.surface_high))
        isEnabled = enabled
        setOnClickListener { onClick() }
    }

    private fun section(s: String) = text(s, 15f, R.color.brand_orange, bold = true).apply {
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun text(s: String, size: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Zontes 368 panel (KnownDashes "21334"); other dashes differ a little in height. */
        const val PREVIEW_W = 1024
        const val PREVIEW_H = 425
    }
}
