package com.tvscreentime.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the timer and draws the "time's up" overlay.
 * Commands arrive as intent extras: cmd = block | unblock | timer | add | allow | deny | lang | tools, minutes = Int, pkg = String.
 * Debug from a PC: adb shell am start-foreground-service -n com.tvscreentime.tv/.ScreenTimeService --es cmd timer --ei minutes 1
 */
class ScreenTimeService : Service() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    enum class Mode { TIMER, BLOCKED, UNLOCKED }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("state", MODE_PRIVATE) }
    private var overlay: View? = null
    private var lastTick = 0L
    private var ticking = false
    private val server = LanServer(this)

    val tvId: String by lazy {
        prefs.getString("tvId", null) ?: LanServer.random(8).also { prefs.edit().putString("tvId", it).apply() }
    }
    val tvName: String get() = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: "TV"

    private var mode: Mode
        get() = Mode.valueOf(prefs.getString("mode", Mode.UNLOCKED.name)!!)
        set(v) = prefs.edit().putString("mode", v.name).apply()
    private var remainingMs: Long
        get() = prefs.getLong("remainingMs", 0)
        set(v) = prefs.edit().putLong("remainingMs", v.coerceAtLeast(0)).apply()

    private fun setAllowed(pkg: String?, on: Boolean) {
        pkg ?: return
        val set = allowedApps(this).toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
        prefs.edit().putStringSet("allowed", set).apply()
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("main", "Screentime", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "main")
            .setContentTitle(getString(R.string.service_running))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()
        startForeground(1, n)
        server.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handle(intent?.getStringExtra("cmd"), intent?.getIntExtra("minutes", 0) ?: 0, intent?.getStringExtra("pkg"))
        return START_STICKY
    }

    /** Main thread only. */
    fun handle(cmd: String?, minutes: Int, pkg: String?) {
        when (cmd) {
            "block" -> mode = Mode.BLOCKED
            "unblock" -> mode = Mode.UNLOCKED
            "timer" -> { mode = Mode.TIMER; remainingMs = minutes * 60_000L }
            "add" -> { mode = Mode.TIMER; remainingMs += minutes * 60_000L }
            "allow" -> setAllowed(pkg, true)
            "deny" -> setAllowed(pkg, false)
            "lang" -> { Lang.set(this, pkg); overlay?.let { hideOverlay() } }
            "tools" -> prefs.edit().putBoolean("showTools", pkg == "on").apply()
        }
        Log.i(TAG, "mode=$mode remaining=${remainingMs / 1000}s")
        if (!ticking) { ticking = true; lastTick = SystemClock.elapsedRealtime(); tick() }
        else render()
    }

    fun statusJson(): JSONObject = JSONObject()
        .put("id", tvId).put("name", tvName)
        .put("mode", mode.name).put("remainingMs", remainingMs)
        .put("allowed", JSONArray(allowedApps(this).sorted()))
        .put("tools", showTools(this))

    /** Launchable TV apps the parent can allow. */
    fun appsJson(): JSONArray {
        val allowed = allowedApps(this)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return JSONArray(packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map { JSONObject().put("pkg", it.packageName).put("label", packageManager.getApplicationLabel(it)).put("allowed", it.packageName in allowed) })
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        // Count down only while the screen is on: standby isn't screen time.
        if (mode == Mode.TIMER && remainingMs > 0 && getSystemService(PowerManager::class.java).isInteractive) {
            remainingMs -= now - lastTick
        }
        lastTick = now
        render()
        handler.postDelayed(::tick, 1000)
    }

    private fun render() {
        val locked = mode == Mode.BLOCKED || (mode == Mode.TIMER && remainingMs <= 0)
        if (locked && overlay == null) showOverlay()
        if (!locked && overlay != null) hideOverlay()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) { Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted"); return }
        val v = TextView(this).apply {
            text = Lang.wrap(applicationContext).getString(R.string.times_up) // fresh: the language can change while the service lives
            textSize = 48f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, _ -> true } // swallow every remote key (HOME is unblockable, overlay stays on top anyway)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE,
        )
        getSystemService(WindowManager::class.java).addView(v, lp)
        mute(true)
        v.requestFocus()
        overlay = v
    }

    private fun hideOverlay() {
        getSystemService(WindowManager::class.java).removeView(overlay)
        mute(false)
        overlay = null
    }

    // Tuner audio can bypass the overlay, so silence the music stream while locked.
    private fun mute(on: Boolean) = getSystemService(AudioManager::class.java)
        .adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)

    override fun onDestroy() {
        server.stop()
        handler.removeCallbacksAndMessages(null)
        overlay?.let { hideOverlay() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenTime"

        fun status(context: Context): Pair<Mode, Long> = context.getSharedPreferences("state", MODE_PRIVATE).let {
            Mode.valueOf(it.getString("mode", Mode.UNLOCKED.name)!!) to it.getLong("remainingMs", 0)
        }

        /** Whether the launcher shows the input / network / settings icons (set from the phone). */
        fun showTools(context: Context) = context.getSharedPreferences("state", MODE_PRIVATE).getBoolean("showTools", true)

        fun allowedApps(context: Context): Set<String> =
            context.getSharedPreferences("state", MODE_PRIVATE).getStringSet("allowed", emptySet())!!

        fun send(context: Context, cmd: String? = null, minutes: Int = 0) {
            context.startForegroundService(
                Intent(context, ScreenTimeService::class.java).putExtra("cmd", cmd).putExtra("minutes", minutes)
            )
        }
    }
}
