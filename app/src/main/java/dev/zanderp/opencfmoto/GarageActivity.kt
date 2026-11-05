// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * The garage: manage every remembered motorcycle. Pick the active bike (one-tap Connect uses it),
 * rename it, give it a photo, or remove it. Each bike carries its own projection + button settings
 * (see [BikeScope]); switching the active bike here switches those too.
 */
class GarageActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var empty: TextView

    /** The bike a pending photo pick is for (photo picker is async). */
    private var photoTargetRaw: String? = null

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) return@registerForActivityResult
        val qr = QrData.parse(raw)
        if (qr == null) {
            LogBus.log("[Garage] QR parse FAILED — raw: $raw")
            Toast.makeText(
                this,
                QrData.parseFailureHint(raw, this) ?: getString(R.string.main_invalid_qr),
                Toast.LENGTH_LONG,
            ).show()
            return@registerForActivityResult
        }
        BikeMemory.save(this, raw, qr)
        Toast.makeText(this, getString(R.string.hc_act_bike_added, BikeMemory.lastBikeName(this).orEmpty()), Toast.LENGTH_SHORT).show()
        refresh()
    }

    private val photoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val raw = photoTargetRaw ?: return@registerForActivityResult
        photoTargetRaw = null
        if (uri == null) return@registerForActivityResult
        val path = importPhoto(uri, raw)
        if (path != null) {
            BikeMemory.setPhoto(this, raw, path)
            refresh()
        } else {
            Toast.makeText(this, R.string.hc_act_image_read_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_garage)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.garage_root)) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        container = findViewById(R.id.garage_container)
        empty = findViewById(R.id.garage_empty)
        findViewById<View>(R.id.garage_scan).setOnClickListener {
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
        findViewById<View>(R.id.garage_manual).setOnClickListener {
            ManualWifiPairing.show(this) { raw, qr ->
                BikeMemory.save(this, raw, qr)
                Toast.makeText(this, getString(R.string.hc_act_bike_added, BikeMemory.lastBikeName(this).orEmpty()), Toast.LENGTH_SHORT)
                    .show()
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        val bikes = BikeMemory.devices(this)
        val selected = BikeMemory.selected(this)
        empty.visibility = if (bikes.isEmpty()) View.VISIBLE else View.GONE
        for (bike in bikes) container.addView(buildCard(bike, bike.raw == selected?.raw))
    }

    private fun buildCard(bike: SavedBike, isActive: Boolean): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            radius = dp(18).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@GarageActivity, R.color.surface))
            setContentPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            if (isActive) {
                strokeWidth = dp(2)
                strokeColor = ContextCompat.getColor(this@GarageActivity, R.color.brand_orange)
            }
            setOnClickListener { showActions(bike, isActive) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bmp = bike.photoPath?.let { loadPhoto(it) }
            if (bmp != null) {
                setImageBitmap(bmp)
            } else {
                setImageResource(R.drawable.ic_ride)
                setColorFilter(ContextCompat.getColor(this@GarageActivity, R.color.brand_orange))
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
        })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
        }
        col.addView(TextView(this).apply {
            text = bike.name
            setTextColor(ContextCompat.getColor(this@GarageActivity, R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = if (isActive) getString(R.string.hc_act_active_bike_edit) else (bike.qr?.ssid ?: getString(R.string.hc_act_tap_select_edit))
            setTextColor(
                ContextCompat.getColor(
                    this@GarageActivity,
                    if (isActive) R.color.brand_orange else R.color.text_secondary
                )
            )
            textSize = 13f
        })
        row.addView(col)

        card.addView(row)
        return card
    }

    private fun showActions(bike: SavedBike, isActive: Boolean) {
        val hasPhoto = bike.photoPath != null
        val useBike = getString(R.string.hc_act_use_this_bike)
        val rename = getString(R.string.hc_act_rename)
        val addPhoto = getString(R.string.hc_act_add_photo)
        val changePhoto = getString(R.string.hc_act_change_photo)
        val removePhoto = getString(R.string.hc_act_remove_photo)
        val removeBike = getString(R.string.hc_act_remove_bike)
        val items = buildList {
            if (!isActive) add(useBike)
            add(rename)
            add(getString(R.string.bike_picker_menu))
            add(if (hasPhoto) changePhoto else addPhoto)
            if (hasPhoto) add(removePhoto)
            add(removeBike)
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(bike.name)
            .setItems(items) { _, which ->
                when (items[which]) {
                    useBike -> {
                        BikeMemory.select(this, bike.raw)
                        Toast.makeText(this, getString(R.string.hc_act_bike_selected, bike.name), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    rename -> promptRename(bike)
                    getString(R.string.bike_picker_menu) -> BikePicker.show(this, bike.raw) { refresh() }
                    addPhoto, changePhoto -> {
                        photoTargetRaw = bike.raw
                        photoLauncher.launch("image/*")
                    }
                    removePhoto -> {
                        deletePhotoFile(bike.photoPath)
                        BikeMemory.setPhoto(this, bike.raw, null)
                        refresh()
                    }
                    removeBike -> confirmRemove(bike)
                }
            }
            .show()
    }

    private fun promptRename(bike: SavedBike) {
        val input = EditText(this).apply {
            setText(bike.name)
            setSelection(text.length)
            hint = getString(R.string.hc_act_bike_name)
        }
        val pad = dp(20)
        val wrap = LinearLayout(this).apply {
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hc_act_rename_bike)
            .setView(wrap)
            .setPositiveButton(R.string.res_save) { _, _ ->
                BikeMemory.rename(this, bike.raw, input.text.toString())
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(bike: SavedBike) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hc_act_remove_bike_title, bike.name))
            .setMessage(R.string.hc_act_remove_bike_msg)
            .setPositiveButton(R.string.dest_clear) { _, _ ->
                deletePhotoFile(bike.photoPath)
                BikeMemory.remove(this, bike.raw)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- photo storage: downscale into app-private files so we don't hold a content Uri permission ----

    private fun photosDir(): File = File(filesDir, "bike_photos").apply { mkdirs() }

    private fun importPhoto(uri: Uri, raw: String): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, 720)
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        // Pablo M (22 Oct): phone photos came out sideways. Cameras store "rotate 90°" in EXIF instead of
        // turning the pixels, and re-saving as JPEG drops that tag — so apply it here, once.
        val degrees = try {
            contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90, android.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270, android.media.ExifInterface.ORIENTATION_TRANSVERSE -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        val bmp = if (degrees == 0f) decoded else {
            val m = android.graphics.Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also { if (it !== decoded) decoded.recycle() }
        }
        val out = File(photosDir(), BikeScope.idFor(raw) + ".jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        out.absolutePath
    } catch (_: Exception) {
        null
    }

    private fun loadPhoto(path: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()

    private fun deletePhotoFile(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    private fun sampleSizeFor(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var mx = maxOf(w, h)
        while (mx > target * 2) { sample *= 2; mx /= 2 }
        return sample
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, GarageActivity::class.java))
        }
    }
}
