// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Harajuku. Part of ZRide Screen (fork of RideScreen AA / OpenCfMoto).
// Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton

/**
 * First-run walkthrough, one step per page: permissions → Android Auto → at the bike → scan and
 * connect. The last step hands over to [MainActivity]'s own Connect button, so every check it does
 * (dependencies, saved bike, Wi-Fi) still applies. [SetupActivity] stays the full settings page.
 */
class GuideActivity : AppCompatActivity() {

    private class Step(val icon: String, val title: Int, val body: Int, val action: Int? = null, val onAction: (() -> Unit)? = null, val done: (() -> Boolean)? = null)

    private lateinit var body: LinearLayout
    private var index = 0

    private val steps by lazy {
        listOf(
            Step("👋", R.string.guide_welcome_title, R.string.guide_welcome_body),
            Step("🔐", R.string.guide_perms_title, R.string.guide_perms_body, R.string.guide_perms_btn,
                onAction = { askPermissions() }, done = { missingPermissions().isEmpty() }),
            Step("🗺️", R.string.guide_aa_title, R.string.guide_aa_body, R.string.guide_aa_btn,
                onAction = { SetupHelper.openAndroidAuto(this) },
                done = { SetupHelper.isAndroidAutoInstalled(this) }),
            Step("🏍️", R.string.guide_bike_title, R.string.guide_bike_body),
            Step("📷", R.string.guide_connect_title, R.string.guide_connect_body, R.string.guide_connect_btn,
                onAction = { finishAndConnect() }),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        index = savedInstanceState?.getInt(KEY_STEP) ?: 0
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(28))
        }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (index > 0) go(index - 1) else skip() }
        })
        render()
    }

    // Back from the permission dialog / Android Auto / Play Store: re-tick the step.
    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, index)
    }

    private fun go(i: Int) {
        index = i.coerceIn(0, steps.lastIndex)
        render()
    }

    private fun render() {
        body.removeAllViews()
        val step = steps[index]
        val last = index == steps.lastIndex

        // Top row: progress + skip.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(getString(R.string.guide_progress, index + 1, steps.size), 13f, R.color.text_secondary),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (!last) top.addView(text(getString(R.string.guide_skip), 14f, R.color.brand_orange).apply {
            setPadding(dp(12), dp(10), 0, dp(10))
            setOnClickListener { skip() }
        })
        body.addView(top)

        // Progress bar: one segment per step.
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in steps.indices) bar.addView(android.view.View(this).apply {
            setBackgroundColor(color(if (i <= index) R.color.brand_orange else R.color.surface_high))
        }, LinearLayout.LayoutParams(0, dp(4), 1f).apply { marginEnd = if (i < steps.lastIndex) dp(6) else 0 })
        body.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(28) })

        body.addView(text(step.icon, 48f, R.color.text_primary))
        body.addView(text(getString(step.title), 26f, R.color.text_primary, bold = true).apply { setPadding(0, dp(12), 0, dp(12)) })
        body.addView(text(getString(step.body), 16f, R.color.text_secondary).apply { setLineSpacing(0f, 1.25f) })

        val isDone = step.done?.invoke() == true
        if (isDone) body.addView(text("✓ " + getString(R.string.guide_done), 15f, R.color.status_live, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        if (step.action != null && step.onAction != null) {
            body.addView(MaterialButton(this).apply {
                text = getString(step.action)
                if (last) setBackgroundColor(color(R.color.brand_orange))
                setOnClickListener { step.onAction.invoke() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(20) })
        }

        // Back / Next (the last step's own button connects).
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (index > 0) nav.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.guide_back)
            setOnClickListener { go(index - 1) }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(12) })
        if (!last) nav.addView(MaterialButton(this).apply {
            text = getString(R.string.guide_next)
            setOnClickListener { go(index + 1) }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        body.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(32) })

        if (last) body.addView(text(getString(R.string.guide_trouble), 13f, R.color.text_secondary).apply {
            setPadding(0, dp(24), 0, 0); setLineSpacing(0f, 1.2f)
        })
    }

    private fun missingPermissions(): List<String> = (SetupHelper.requiredPermissions() + SetupHelper.nearbyPermissions())
        .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun askPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        else go(index + 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun skip() {
        SetupActivity.markSeen(this)
        finish()
    }

    private fun finishAndConnect() {
        SetupActivity.markSeen(this)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_GUIDE_CONNECT, true))
        finish()
    }

    private fun text(s: String, size: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERMS = 51
        private const val KEY_STEP = "step"

        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, GuideActivity::class.java))
    }
}
