package com.tvscreentime.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    // Top bar and tile row are far apart, so D-pad focus search needs explicit up/down links.
    private val topIcons = mutableListOf<View>()
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
            textSize = 34f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val top = LinearLayout(this).apply {
            setPadding(dp(64), dp(40), dp(64), 0)
            addView(LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(clock); addView(remaining)
            })
            addView(View(this@LauncherActivity), LinearLayout.LayoutParams(0, 0, 1f))
            // Input source, network and pairing: reachable with D-pad UP, deliberately quiet.
            addView(topIcon(R.drawable.ic_input, R.string.input_source) { pickInput() })
            addView(topIcon(R.drawable.ic_wifi, R.string.network) { openSettings(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS) })
            addView(topIcon(R.drawable.ic_settings, R.string.tv_settings) { openSettings(Settings.ACTION_SETTINGS) })
            addView(topIcon(R.drawable.ic_phone, R.string.pair_title) { startActivity(Intent(this@LauncherActivity, PairActivity::class.java)) })
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
        row.getChildAt(0)?.let { first -> topIcons.forEach { it.nextFocusDownId = first.id } }
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
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
            }
            clipToOutline = true
            isFocusable = true
            id = View.generateViewId()
            nextFocusUpId = topIcons.first().id
            // Portrait card: filled with the banner's edge colour so the banner blends in, the (landscape) banner or icon centered on top.
            setBackgroundColor(edgeColor(art))
            addView(ImageView(this@LauncherActivity).apply {
                setImageDrawable(art)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (banner) setPadding(dp(10), 0, dp(10), 0) else setPadding(dp(40), 0, dp(40), 0)
            }, FrameLayout.LayoutParams(-1, -1))
            onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
                val s = if (focused) 1.12f else 1f
                v.animate().scaleX(s).scaleY(s).translationZ(if (focused) dp(20).toFloat() else 0f).setDuration(200).start()
                if (focused) showShelf(art, name)
            }
            setOnClickListener { onClick() }
        }
        row.addView(tile, LinearLayout.LayoutParams(dp(150), dp(225)).apply { marginEnd = dp(32) })
    }

    /** Colour at the left edge of the art: most TV banners sit on a flat background. */
    private fun edgeColor(art: Drawable): Int {
        val b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        val bounds = art.copyBounds()
        art.setBounds(0, 0, 32, 18); art.draw(Canvas(b)); art.bounds = bounds
        val c = b.getPixel(1, 9)
        return if (Color.alpha(c) < 128) Color.rgb(38, 38, 42) else c or 0xFF000000.toInt()
    }

    private fun tiny(art: Drawable): Bitmap {
        val small = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
        val bounds = art.copyBounds()
        art.setBounds(0, 0, 6, 4); art.draw(Canvas(small)); art.bounds = bounds
        return small
    }

    /** Fake blur that works on API 28: render the art at 6x4 and let FIT_XY upscale it smoothly. */
    private fun showShelf(art: Drawable, name: String) {
        shelf.alpha = 0f
        shelf.setImageBitmap(tiny(art))
        shelf.animate().alpha(1f).setDuration(300).start()
        title.text = name
    }

    private fun topIcon(icon: Int, label: Int, onClick: () -> Unit) = ImageView(this).apply {
        id = View.generateViewId()
        topIcons += this
        setImageResource(icon)
        contentDescription = getString(label)
        alpha = 0.4f; isFocusable = true
        setPadding(dp(14), dp(4), dp(14), dp(4))
        layoutParams = LinearLayout.LayoutParams(dp(58), dp(38))
        setOnFocusChangeListener { v, f -> v.alpha = if (f) 1f else 0.4f }
        setOnClickListener { onClick() }
    }

    /** HDMI/AV/tuner picker from the system's TV inputs. Passthrough inputs open in the Live TV app. */
    private fun pickInput() {
        // Only the TV's own hardware inputs: those from the package that provides HDMI. App "inputs" (Play Movies, …) are left out.
        val all = getSystemService(TvInputManager::class.java)?.tvInputList.orEmpty().filter { !it.isHidden(this) }
        val hwPackages = all.filter { it.isPassthroughInput }.map { it.serviceInfo.packageName }.toSet()
        val inputs = all.filter { it.serviceInfo.packageName in hwPackages }
            .sortedBy { if (it.isPassthroughInput) 1 else 0 }
        if (inputs.isEmpty()) return openTuner()
        val labels = inputs.map {
            if (!it.isPassthroughInput) getString(R.string.tv_digital)
            else it.loadCustomLabel(this)?.toString() ?: it.loadLabel(this).toString()
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.input_source)
            .setItems(labels) { _, i ->
                val input = inputs[i]
                if (input.isPassthroughInput) runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { openTuner() }
                else openTuner()
            }
            .show()
    }

    private fun openTuner() {
        TUNER_PACKAGES.firstNotNullOfOrNull { packageManager.getLeanbackLaunchIntentForPackage(it) }
            ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun openSettings(vararg actions: String) {
        (actions.toList() + Settings.ACTION_SETTINGS)
            .firstOrNull { Intent(it).resolveActivity(packageManager) != null }
            ?.let { startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
