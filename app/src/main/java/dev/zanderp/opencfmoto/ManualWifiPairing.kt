// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Dialog to pair a bike by typing the SoftAP SSID + password (TRK / no-QR dashes).
 */
object ManualWifiPairing {

    fun show(activity: AppCompatActivity, onPaired: (raw: String, qr: QrData) -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val pad = dp(20)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        fun field(hint: String, password: Boolean = false): EditText {
            val til = TextInputLayout(activity).apply {
                this.hint = hint
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            }
            val et = TextInputEditText(til.context).apply {
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                isSingleLine = true
            }
            til.addView(et)
            col.addView(til)
            return et
        }

        val ssid = field(activity.getString(R.string.hc_act_wifi_ssid_hint))
        val pwd = field(activity.getString(R.string.hc_act_wifi_password_hint), password = true)
        val name = field(activity.getString(R.string.hc_act_bike_name_optional))

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.hc_act_manual_wifi_title)
            .setMessage(R.string.hc_act_manual_wifi_msg)
            .setView(col)
            .setPositiveButton(R.string.hc_act_add_bike, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val built = QrData.buildManual(
                    ssid = ssid.text?.toString().orEmpty(),
                    pwd = pwd.text?.toString().orEmpty(),
                    displayName = name.text?.toString(),
                )
                if (built == null) {
                    Toast.makeText(activity, R.string.hc_act_ssid_pwd_required, Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onPaired(built.first, built.second)
            }
        }
        dialog.show()
    }
}
