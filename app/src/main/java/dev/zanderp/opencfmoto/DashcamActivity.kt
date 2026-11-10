// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

/** 📹 The dash's dashcam: browse front / rear clips and photos, play them, save them to the phone (see [Dashcam]). */
@OptIn(UnstableApi::class)
class DashcamActivity : AppCompatActivity() {

    private enum class Tab { FRONT, REAR, PHOTOS }

    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var tab = Tab.FRONT
    private var group: Dashcam.Group? = null
    private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
        }
        playerView = PlayerView(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.widthPixels * 9 / 16)
            setFullscreenButtonClickListener { full -> fullscreen(full) }
        }
        scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }
        scroll.addView(body)
        root.addView(playerView)
        root.addView(scroll)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        Dashcam.onProgress = { if (tab == Tab.PHOTOS || group != null) render(reload = false) }
    }

    override fun onPause() {
        super.onPause()
        Dashcam.onProgress = null
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    @Deprecated("Back closes the full-screen player, then a clip list, then the screen")
    override fun onBackPressed() {
        when {
            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> fullscreen(false)
            group != null -> { group = null; render() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- screen

    private var cachedGroups: Pair<Tab, List<Dashcam.Group>>? = null
    private var cachedClips: Pair<Dashcam.Group, List<Dashcam.Clip>>? = null
    private var cachedPhotos: List<String>? = null

    private fun render(reload: Boolean = true) {
        body.removeAllViews()
        body.addView(text(getString(R.string.dvr_title), 22f, R.color.text_primary, bold = true))
        body.addView(text(getString(R.string.dvr_sub, Dashcam.HOST), 13f, R.color.text_secondary))

        // Front | Rear | Photos, then refresh and the live-view probe.
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp().apply { topMargin = dp(12) }
            listOf(Tab.FRONT to R.string.dvr_front, Tab.REAR to R.string.dvr_rear, Tab.PHOTOS to R.string.dvr_photos).forEach { (t, label) ->
                addView(button(getString(label), selected = t == tab) {
                    tab = t; group = null; render()
                }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } })
            }
        })
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(getString(R.string.dvr_refresh)) {
                cachedGroups = null; cachedClips = null; cachedPhotos = null; render()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } })
            addView(button(getString(R.string.dvr_live_probe)) { probeLive() }
                .apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } })
        })

        val g = group
        when {
            tab == Tab.PHOTOS -> load(reload && cachedPhotos == null, { cachedPhotos = Dashcam.pictures(this) }) { photos() }
            g != null -> load(reload && cachedClips?.first != g, { cachedClips = g to Dashcam.clips(this, cam(), g) }) { clips(g) }
            else -> load(reload && cachedGroups?.first != tab, { cachedGroups = tab to Dashcam.groups(this, cam()) }) { groups() }
        }
    }

    private fun cam() = if (tab == Tab.REAR) Dashcam.Cam.REAR else Dashcam.Cam.FRONT

    /** Fetch off the main thread (when [needed]), then draw; a newer render cancels an older load. */
    private fun load(needed: Boolean, fetch: () -> Unit, draw: () -> Unit) {
        if (!needed) return draw()
        val token = ++loadToken
        val status = text(getString(R.string.dvr_loading), 14f, R.color.text_secondary).apply { setPadding(0, dp(16), 0, 0) }
        body.addView(status)
        Thread {
            val err = try { fetch(); null } catch (e: Exception) { e }
            runOnUiThread {
                if (token != loadToken || isFinishing) return@runOnUiThread
                if (err == null) render(reload = false)
                else status.text = getString(R.string.dvr_not_connected, Dashcam.HOST)
            }
        }.start()
    }

    private fun groups() {
        val list = cachedGroups?.second.orEmpty()
        if (list.isEmpty()) return empty()
        list.groupBy { it.date }.forEach { (date, blocks) ->
            body.addView(label(date).apply { setPadding(0, dp(16), 0, dp(4)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            blocks.forEach { b ->
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, dp(8), 0)
                    addView(thumbView(b.thumb, dp(140)))
                    addView(text(b.time, 13f, R.color.text_primary, bold = true))
                    setOnClickListener { group = b; render() }
                })
            }
            body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
    }

    private fun clips(g: Dashcam.Group) {
        val list = cachedClips?.takeIf { it.first == g }?.second.orEmpty()
        body.addView(button("← ${g.date} · ${g.time}") { group = null; render() }.apply { layoutParams = lp().apply { topMargin = dp(10) } })
        if (list.isEmpty()) return empty()
        val pending = list.filterNot { Dashcam.isSaved(this, it.name) }
        if (pending.isNotEmpty()) {
            body.addView(button(getString(R.string.dvr_download_all, pending.size), selected = true) {
                pending.forEach { Dashcam.download(this, it.url, it.name, video = true) }
            }.apply { layoutParams = lp().apply { topMargin = dp(6) } })
        }
        list.forEach { c ->
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_stat_tile)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = lp().apply { topMargin = dp(8) }
                addView(thumbView(c.thumb, dp(112)))
                addView(LinearLayout(this@DashcamActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
                    addView(text(c.title.ifBlank { c.name }, 14f, R.color.text_primary, bold = true))
                    addView(text(c.name, 11f, R.color.text_secondary))
                    addView(text(state(c.url, c.name), 12f, R.color.brand_orange))
                })
                addView(button("⬇") { Dashcam.download(this@DashcamActivity, c.url, c.name, video = true) })
                setOnClickListener { play(c.url) }
            })
        }
    }

    private fun photos() {
        val list = cachedPhotos.orEmpty()
        if (list.isEmpty()) return empty()
        val pending = list.filterNot { Dashcam.isSaved(this, name(it)) }
        if (pending.isNotEmpty()) {
            body.addView(button(getString(R.string.dvr_download_all, pending.size), selected = true) {
                pending.forEach { Dashcam.download(this, it, name(it), video = false) }
            }.apply { layoutParams = lp().apply { topMargin = dp(10) } })
        }
        val cell = (resources.displayMetrics.widthPixels - dp(56)) / 3
        list.chunked(3).forEach { rowItems ->
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp().apply { topMargin = dp(8) }
                rowItems.forEach { url ->
                    addView(LinearLayout(this@DashcamActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 0, dp(8), 0)
                        addView(thumbView(url, cell))
                        addView(text(state(url, name(url)), 11f, R.color.brand_orange))
                        setOnClickListener { showPhoto(url) }
                    })
                }
            })
        }
    }

    private fun name(url: String) = url.substringAfterLast('/').substringBefore('?')

    private fun state(url: String, name: String): String = when (val p = Dashcam.progress[url]) {
        null -> if (Dashcam.isSaved(this, name)) getString(R.string.dvr_saved) else ""
        -1 -> getString(R.string.dvr_failed)
        100 -> getString(R.string.dvr_saved)
        else -> getString(R.string.dvr_downloading, p)
    }

    private fun empty() {
        body.addView(text(getString(R.string.dvr_empty), 14f, R.color.text_secondary).apply { setPadding(0, dp(16), 0, 0) })
    }

    // ---------------------------------------------------------------- player, photo, probe

    private fun play(url: String) {
        val p = player ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Dashcam.client(this, readSec = 30))))
            .build().also { player = it; playerView.player = it }
        playerView.visibility = View.VISIBLE
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
    }

    private fun fullscreen(full: Boolean) {
        requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scroll.visibility = if (full) View.GONE else View.VISIBLE
        playerView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (full) ViewGroup.LayoutParams.MATCH_PARENT else minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 9 / 16,
        )
    }

    private fun showPhoto(url: String) {
        val img = ImageView(this).apply { adjustViewBounds = true }
        Dashcam.thumb(this, url, resources.displayMetrics.widthPixels) { it?.let(img::setImageBitmap) }
        AlertDialog.Builder(this)
            .setView(img)
            .setPositiveButton(R.string.dvr_download) { _, _ -> Dashcam.download(this, url, name(url), video = false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun probeLive() {
        val dlg = AlertDialog.Builder(this).setTitle(R.string.dvr_live_probe).setMessage(R.string.dvr_loading)
            .setPositiveButton(android.R.string.ok, null).show()
        Thread {
            val r = try { Dashcam.probeLive(this) } catch (e: Exception) { e.toString() }
            runOnUiThread { if (dlg.isShowing) dlg.setMessage(getString(R.string.dvr_live_result, r)) }
        }.start()
    }

    // ---------------------------------------------------------------- views

    private fun thumbView(url: String?, w: Int) = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(w, w * 9 / 16)
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0xFF1A1A1A.toInt())
        Dashcam.thumb(this@DashcamActivity, url, w) { it?.let(::setImageBitmap) }
    }

    private fun button(s: String, selected: Boolean = false, onClick: () -> Unit) = MaterialButton(
        this, null, if (selected) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = s
        isAllCaps = false
        minWidth = 0
        setOnClickListener { onClick() }
    }

    private fun label(s: String) = text(s, 12f, R.color.text_secondary).apply { letterSpacing = 0.08f }

    private fun text(s: String, size: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = lp().apply { topMargin = dp(2) }
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    companion object {
        fun start(a: android.app.Activity) = Premium.requireOrPrompt(a) { a.startActivity(Intent(a, DashcamActivity::class.java)) }
    }
}
