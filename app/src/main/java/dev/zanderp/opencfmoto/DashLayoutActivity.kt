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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zanderp.opencfmoto.DashLayout.Widget

/**
 * Edits the rider's dash screen ([DashLayout]) in steps — template, its colours, what it shows,
 * advanced options — under a live preview at the dash's shape that stays on screen.
 * "Show on dash" returns RESULT_OK; [MainActivity] then connects in that mode.
 */
class DashLayoutActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var scroll: ScrollView
    private var layout = DashLayout.DEFAULT
    private var preview: CustomDashUi? = null
    /** Template kind being browsed in step ①; null = the selected template's own. */
    private var category: DashLayout.Category? = null
    /** Picker thumbnails; each is redrawn only when its template's own layout changes. */
    private val thumbs = HashMap<DashLayout.Style, Pair<DashLayout.Layout, android.graphics.Bitmap>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Title + preview pinned on top, the steps scroll under it, "Show on dash" pinned below.
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.bg)) }
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(text(getString(R.string.dl_title), 22f, R.color.text_primary, bold = true))
            previewFrame = FrameLayout(this@DashLayoutActivity)
            addView(previewFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { topMargin = dp(10) })
        })
        scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(20))
        }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        page.addView(MaterialButton(this).apply {
            text = getString(R.string.dl_send)
            setOnClickListener { setResult(RESULT_OK); finish() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(20), dp(6), dp(20), dp(10)) })
        setContentView(page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        layout = DashLayout.load(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's notification-access screen: show the new state.
        if (::body.isInitialized && body.childCount > 0) render()
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
        val y = scroll.scrollY
        body.removeAllViews()

        // Preview: the real dash view at the panel's pixel size, scaled down to the screen width.
        // The rider's own panel when the bike is known (368 1024×425, 125X 800×416), else the 368.
        val (pw, ph) = KnownDashes.panelFor(null, this) ?: (PREVIEW_W to PREVIEW_H)
        preview?.release()
        previewFrame.removeAllViews()
        val ui = CustomDashUi(this, layout, ph, projected = false).also { preview = it; it.bind() }
        previewFrame.addView(ui.root, FrameLayout.LayoutParams(pw, ph))
        previewFrame.post {
            val s = previewFrame.width / pw.toFloat()
            previewFrame.layoutParams = previewFrame.layoutParams.apply { height = (ph * s).toInt() }
            ui.root.pivotX = 0f
            ui.root.pivotY = 0f
            ui.root.scaleX = s
            ui.root.scaleY = s
        }
        body.addView(text(getString(R.string.dl_hint), 13f, R.color.text_secondary).apply { setPadding(0, dp(4), 0, 0) })

        templateStep(pw, ph)
        colourStep()
        showStep()
        advancedStep()
        body.addView(text(getString(R.string.dl_buttons_hint), 13f, R.color.text_secondary).apply { setPadding(0, dp(16), 0, 0) })
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** ① Templates as a row of pictures (each in its own colours), the selected one outlined. */
    private fun templateStep(pw: Int, ph: Int) {
        body.addView(section(getString(R.string.dl_step_template)))
        // Kind first (chips), then the dashes of that kind (pictures). Browsing a kind changes nothing until a dash is tapped.
        val inUse = layout.style.category
        val cat = category ?: inUse
        body.addView(text(getString(R.string.dl_pick_category), 12f, R.color.text_secondary))
        val kinds = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        for (c in DashLayout.Category.entries) {
            val n = DashLayout.Style.entries.count { it.category == c }
            kinds.addView(Chip(this).apply {
                text = "${getString(c.label)} ($n)"
                isCheckable = true
                isCheckedIconVisible = true
                isChecked = c == cat
                // A dot marks the kind holding the template in use.
                if (c == inUse) text = "● $text"
                setOnClickListener { if (this@DashLayoutActivity.category != c) { this@DashLayoutActivity.category = c; render() } }
            })
        }
        body.addView(kinds)
        body.addView(text(getString(R.string.dl_pick_template), 12f, R.color.text_secondary).apply { setPadding(0, dp(6), 0, dp(4)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        var selected: View? = null
        for (st in DashLayout.Style.entries.filter { it.category == cat }) {
            val on = layout.style == st
            val l = if (on) layout else DashLayout.load(this, st)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(color(R.color.surface_high))
                    if (on) setStroke(dp(3), color(R.color.brand_orange))
                }
                contentDescription = getString(st.label)
                setOnClickListener { if (!on) update(DashLayout.load(this@DashLayoutActivity, st)) }
            }
            val bmp = thumbs[st]?.takeIf { it.first == l }?.second ?: thumb(l, pw, ph).also { thumbs[st] = l to it }
            card.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                alpha = if (on) 1f else 0.75f
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            card.addView(text(getString(st.label), 12f, if (on) R.color.brand_orange else R.color.text_primary, bold = on).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            row.addView(card, LinearLayout.LayoutParams(dp(168), WRAP_CONTENT).apply { marginEnd = dp(8) })
            if (on) selected = card
        }
        body.addView(hs)
        selected?.let { v -> hs.post { hs.scrollTo((v.left - dp(24)).coerceAtLeast(0), 0) } }
    }

    /** ② Colours of the selected template only. */
    private fun colourStep() {
        body.addView(section(getString(R.string.dl_step_colour, getString(layout.style.label))))
        body.addView(text(getString(R.string.dl_colour_hint), 12f, R.color.text_secondary).apply { setPadding(0, 0, 0, dp(8)) })
        // Theme colours (themed templates) · "custom" → full picker with gradients · presets.
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val themeColours = layout.style.themed && !layout.customColor
        fun swatch(bg: android.graphics.drawable.Drawable, label: String?, desc: String, onClick: () -> Unit) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            box.addView(View(this).apply { background = bg; contentDescription = desc; setOnClickListener { onClick() } },
                LinearLayout.LayoutParams(dp(40), dp(40)))
            if (label != null) box.addView(text(label, 10f, R.color.text_secondary))
            swatches.addView(box, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(10) })
        }
        fun ring(d: GradientDrawable, on: Boolean) = d.apply { if (on) setStroke(dp(3), Color.WHITE) }
        if (layout.style.themed) {
            swatch(ring(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2A2A30.toInt(), 0xFF8F8A80.toInt())).apply { shape = GradientDrawable.OVAL }, themeColours),
                getString(R.string.cp_theme), getString(R.string.cp_theme)) { update(layout.copy(customColor = false, accent2 = null)) }
        }
        val customOn = !themeColours && (layout.accent2 != null || layout.accent !in DashLayout.ACCENTS)
        val customBg = if (customOn) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(layout.accent, layout.accent2 ?: layout.accent))
            else GradientDrawable().apply { gradientType = GradientDrawable.SWEEP_GRADIENT; colors = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED) }
        swatch(ring(customBg.apply { shape = GradientDrawable.OVAL }, customOn), getString(R.string.cp_custom), getString(R.string.cp_title)) {
            ColorPicker.show(this, layout.accent, layout.accent2) { c, c2 -> update(layout.copy(accent = c, accent2 = c2, customColor = true)) }
        }
        for (c in DashLayout.ACCENTS) {
            swatch(ring(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }, !themeColours && c == layout.accent && layout.accent2 == null),
                null, ColorPicker.hex(c)) { update(layout.copy(accent = c, accent2 = null, customColor = true)) }
        }
        body.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(swatches) })
    }

    /** ③ What the dash shows: widgets (grid) or parts to hide (PRO templates). */
    private fun showStep() {
        body.addView(section(getString(R.string.dl_step_show)))
        if (layout.style.isPro) {
            body.addView(text(getString(R.string.dl_show_hint), 12f, R.color.text_secondary).apply { setPadding(0, 0, 0, dp(6)) })
            val group = ChipGroup(this)
            for (p in DashLayout.Part.entries) {
                group.addView(Chip(this).apply {
                    text = getString(p.label)
                    isCheckable = true
                    isCheckedIconVisible = true  // ✓ = shown, plain = hidden
                    isChecked = p !in this@DashLayoutActivity.layout.hidden
                    setOnCheckedChangeListener { _, shown -> update(this@DashLayoutActivity.layout.copy(hidden = if (shown) this@DashLayoutActivity.layout.hidden - p else this@DashLayoutActivity.layout.hidden + p)) }
                })
            }
            body.addView(group)
            body.addView(text(getString(R.string.dl_pro_hint), 12f, R.color.text_secondary).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        // Grid widgets: shown ones in dash order (first = large), then the hidden ones.
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
        val gps = DashLayout.Part.GPS
        body.addView(switch(getString(gps.label), null, gps !in layout.hidden) {
            update(layout.copy(hidden = if (it) layout.hidden - gps else layout.hidden + gps))
        })
    }

    /** ④ Speed alert, night dimming, start-up sweep, tank size. */
    private fun advancedStep() {
        body.addView(section(getString(R.string.dl_step_more)))
        body.addView(text(getString(R.string.dl_alert), 15f, R.color.text_primary))
        body.addView(text(getString(R.string.dl_alert_hint), 12f, R.color.text_secondary))
        val alerts = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        for (a in DashLayout.ALERTS) {
            alerts.addView(Chip(this).apply {
                text = if (a == 0) getString(R.string.dl_alert_off) else "$a km/h"
                isCheckable = true
                isChecked = this@DashLayoutActivity.layout.speedAlert == a
                setOnClickListener { if (this@DashLayoutActivity.layout.speedAlert != a) update(this@DashLayoutActivity.layout.copy(speedAlert = a)) }
            })
        }
        body.addView(alerts)
        body.addView(switch(getString(R.string.dl_night), getString(R.string.dl_night_hint), layout.nightDim) { update(layout.copy(nightDim = it)) })
        body.addView(switch(getString(R.string.dl_nav_on_dash), getString(R.string.dl_nav_on_dash_hint), layout.navOnDash) { update(layout.copy(navOnDash = it)) })
        // Song titles and Google Maps / Waze turns need notification access; the handlebar controls don't.
        if (!NowPlaying.canReadTitles(this)) {
            body.addView(text(getString(R.string.dl_media_access_hint), 12f, R.color.text_secondary).apply { setPadding(0, dp(12), 0, 0) })
            body.addView(MaterialButton(this).apply {
                text = getString(R.string.dl_media_access)
                setOnClickListener {
                    runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        if (layout.style.isPro) {
            for (fx in DashLayout.Fx.entries) {
                body.addView(switch(getString(fx.label), getString(fx.hint), fx in layout.effects) {
                    update(layout.copy(effects = if (it) layout.effects + fx else layout.effects - fx))
                })
            }
        }
        // Tank size feeds the fuel estimate of every layout (PRO gauge, grid "Fuel" widget).
        run {
            body.addView(text(getString(R.string.dl_tank), 15f, R.color.text_primary).apply { setPadding(0, dp(12), 0, 0) })
            body.addView(text(getString(R.string.dl_fuel_hint), 12f, R.color.text_secondary))
            BikePicker.selected(this)?.takeIf { it.tankL > 0f }?.let { m ->
                body.addView(text(getString(R.string.dl_tank_default, m.label, m.tankL.toString().removeSuffix(".0")), 12f, R.color.brand_orange))
            }
            body.addView(android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                val tank = this@DashLayoutActivity.layout.tankLiters
                setText(if (tank > 0f) tank.toString().removeSuffix(".0") else "")
                setTextColor(color(R.color.text_primary))
                hint = "0"
                // Saved as typed, without re-rendering (that would drop the keyboard); preview updates next time.
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: android.text.Editable?) {
                        val a = this@DashLayoutActivity
                        a.layout = a.layout.copy(tankLiters = e?.toString()?.replace(',', '.')?.toFloatOrNull()?.coerceIn(0f, 60f) ?: 0f)
                        DashLayout.save(a, a.layout)
                    }
                })
            })
        }
    }

    /**
     * One still frame of [l] at the panel's pixel size, scaled to [THUMB_W] wide. Drawn once into a
     * bitmap so the 9 picker cards don't each keep a live dash (and its full-size artwork) around.
     */
    // ponytail: renders on the main thread (~9 small frames on first open / colour change); move to a background thread if it stutters
    private fun thumb(l: DashLayout.Layout, pw: Int, ph: Int): android.graphics.Bitmap {
        val ui = CustomDashUi(this, l, ph, projected = false)
        val v = ui.root
        v.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, pw, ph)
        val s = THUMB_W / pw.toFloat()
        return android.graphics.Bitmap.createBitmap(THUMB_W, (ph * s).toInt(), android.graphics.Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).apply { scale(s, s); v.draw(this) }
            ui.release()
        }
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

    private fun switch(title: String, hint: String?, on: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(LinearLayout(this@DashLayoutActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, R.color.text_primary))
            if (hint != null) addView(text(hint, 12f, R.color.text_secondary))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(MaterialSwitch(this@DashLayoutActivity).apply {
            isChecked = on
            contentDescription = title
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
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
        /** Picker thumbnail bitmap width (px); half the 368 panel keeps text crisp on a 2-column grid. */
        private const val THUMB_W = 512
    }
}
