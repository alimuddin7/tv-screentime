package com.tvscreentime.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

/** Shows the pairing QR and the list of paired phones. Pairing is open only while this screen is visible. */
class PairActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    private lateinit var qr: ImageView
    private lateinit var hint: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        qr = ImageView(this)
        hint = text(18f).apply { alpha = 0.7f; gravity = Gravity.CENTER }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(64), dp(48), dp(64), dp(48))
            addView(LinearLayout(this@PairActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text(32f, "sans-serif-light").apply { text = getString(R.string.pair_title) })
                addView(qr, LinearLayout.LayoutParams(dp(300), dp(300)).apply { topMargin = dp(24); bottomMargin = dp(16) })
                addView(hint)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(LinearLayout(this@PairActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(24f, "sans-serif-light").apply { text = getString(R.string.paired_devices) })
                addView(ScrollView(this@PairActivity).apply { addView(list) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(48) })
        })
    }

    override fun onResume() {
        super.onResume()
        LanServer.onPaired = { runOnUiThread { showQr(); showDevices() } }
        showQr()
        showDevices()
    }

    override fun onPause() {
        LanServer.pairCode = null
        LanServer.onPaired = null
        super.onPause()
    }

    private fun showQr() {
        val ip = LanServer.localIp()
        if (ip == null) { hint.text = getString(R.string.no_wifi); return }
        val code = LanServer.pairCode ?: LanServer.newCode().also { LanServer.pairCode = it }
        val id = getSharedPreferences("state", MODE_PRIVATE).getString("tvId", "")!! // written by the service on start
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: "TV"
        val payload = JSONObject().put("v", 1).put("h", ip).put("p", LanServer.PORT).put("c", code).put("id", id).put("n", name)
        qr.setImageBitmap(qrBitmap(payload.toString(), 600))
        hint.text = getString(R.string.pair_hint, ip)
    }

    private fun showDevices() {
        list.removeAllViews()
        val all = Devices.all(this)
        if (all.length() == 0) list.addView(text(18f).apply { text = getString(R.string.none); alpha = 0.5f })
        all.keys().forEach { id ->
            val name = all.getJSONObject(id).getString("name")
            list.addView(text(20f).apply {
                text = name
                isFocusable = true
                setPadding(dp(20), dp(14), dp(20), dp(14))
                setOnFocusChangeListener { v, f ->
                    v.background = if (f) GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) } else null
                }
                setOnClickListener {
                    AlertDialog.Builder(this@PairActivity)
                        .setMessage(getString(R.string.revoke_q, name))
                        .setPositiveButton(R.string.revoke) { _, _ -> Devices.remove(this@PairActivity, id); showDevices() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
        }
    }

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }

    private fun text(size: Float, family: String = "sans-serif") = TextView(this).apply {
        textSize = size; setTextColor(Color.WHITE); typeface = Typeface.create(family, Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
