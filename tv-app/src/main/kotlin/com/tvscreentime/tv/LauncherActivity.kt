package com.tvscreentime.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/** HOME launcher, tvOS-style: blurred "top shelf" of the focused app, one row of rounded tiles. */
class LauncherActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    private lateinit var row: LinearLayout
    private var lang: String? = null
    private lateinit var shelf: ImageView
    private lateinit var title: TextView
    private lateinit var remaining: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateRemaining(); handler.postDelayed(this, 30_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenTimeService.send(this)
        lang = Lang.get(this)

        shelf = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0xCC000000.toInt(), Color.BLACK),
            )
        }
        title = text(52f, "sans-serif-light")
        remaining = text(20f, "sans-serif").apply { alpha = 0.7f }
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val top = LinearLayout(this).apply {
            setPadding(dp(64), dp(40), dp(64), 0)
            addView(View(this@LauncherActivity), LinearLayout.LayoutParams(0, 0, 1f))
            // Pairing/settings entry: reachable with D-pad UP, deliberately quiet.
            addView(text(26f, "sans-serif-light").apply {
                text = "⚙"; alpha = 0.4f; isFocusable = true
                setPadding(dp(16), 0, dp(24), 0)
                setOnFocusChangeListener { v, f -> v.alpha = if (f) 1f else 0.4f }
                setOnClickListener { startActivity(Intent(this@LauncherActivity, PairActivity::class.java)) }
            })
            addView(LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(clock); addView(remaining)
            })
        }

        row = LinearLayout(this).apply {
            clipChildren = false; clipToPadding = false
            setPadding(dp(64), dp(24), dp(64), dp(56))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(64) })
            addView(HorizontalScrollView(this@LauncherActivity).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false; clipToPadding = false
                addView(row)
            })
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(shelf, FrameLayout.LayoutParams(-1, -1))
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
    }

    override fun onResume() {
        super.onResume()
        if (Lang.get(this) != lang) return recreate() // language changed from the phone
        row.removeAllViews()
        if (Channels.list(this).isNotEmpty())
            addTile(getDrawable(R.drawable.video_banner)!!, getString(R.string.video), banner = true) { startActivity(Intent(this, VideoActivity::class.java)) }
        // ponytail: fixed tuner package list (Mi TV/AOSP); Coocaa's tuner can be added via the allow list.
        TUNER_PACKAGES.firstOrNull { packageManager.getLeanbackLaunchIntentForPackage(it) != null }
            ?.let { addAppTile(it, getString(R.string.tv_digital)) }
        ScreenTimeService.allowedApps(this).sorted().forEach { addAppTile(it, null) }
        row.getChildAt(0)?.requestFocus()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun updateRemaining() {
        val (mode, ms) = ScreenTimeService.status(this)
        remaining.visibility = if (mode == ScreenTimeService.Mode.TIMER) View.VISIBLE else View.GONE
        remaining.text = getString(R.string.minutes_left, (ms + 59_999) / 60_000)
    }

    private fun addAppTile(pkg: String, label: String?) {
        val pm = packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        val info = pm.getApplicationInfo(pkg, 0)
        val banner: Drawable? = pm.getApplicationBanner(info) ?: pm.getActivityBanner(launch)
        addTile(banner ?: pm.getApplicationIcon(info), label ?: pm.getApplicationLabel(info).toString(), banner != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun addTile(art: Drawable, name: String, banner: Boolean, onClick: () -> Unit) {
        val radius = dp(12).toFloat()
        val tile = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = radius; setColor(Color.rgb(38, 38, 42)) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
            }
            clipToOutline = true
            isFocusable = true
            addView(ImageView(this@LauncherActivity).apply {
                setImageDrawable(art)
                // Apps without a TV banner get their icon centered on the card.
                scaleType = if (banner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
                if (!banner) setPadding(dp(48), dp(36), dp(48), dp(36))
            }, FrameLayout.LayoutParams(-1, -1))
            onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
                val s = if (focused) 1.12f else 1f
                v.animate().scaleX(s).scaleY(s).translationZ(if (focused) dp(20).toFloat() else 0f).setDuration(200).start()
                if (focused) showShelf(art, name)
            }
            setOnClickListener { onClick() }
        }
        row.addView(tile, LinearLayout.LayoutParams(dp(224), dp(126)).apply { marginEnd = dp(40) })
    }

    /** Fake blur that works on API 28: render the art at 6x4 and let FIT_XY upscale it smoothly. */
    private fun showShelf(art: Drawable, name: String) {
        val small = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
        val bounds = art.copyBounds()
        art.setBounds(0, 0, 6, 4); art.draw(Canvas(small)); art.bounds = bounds
        shelf.alpha = 0f
        shelf.setImageBitmap(small)
        shelf.animate().alpha(1f).setDuration(300).start()
        title.text = name
    }

    // A launcher's BACK should never leave it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private fun text(size: Float, family: String) = TextView(this).apply {
        textSize = size; setTextColor(Color.WHITE); typeface = Typeface.create(family, Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val TUNER_PACKAGES = listOf("com.droidlogic.droidlivetv", "com.android.tv")
    }
}
