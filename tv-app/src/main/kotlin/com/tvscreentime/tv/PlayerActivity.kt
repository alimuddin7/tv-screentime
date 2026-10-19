package com.tvscreentime.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Plays one video with the official YouTube IFrame Player. When it ends we finish(),
 * so the child lands back on our own list instead of YouTube's recommendations.
 * Remote: OK = play/pause, left/right = seek 10 s, BACK = back to the list.
 */
class PlayerActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return finish()
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Nothing inside the player may navigate away (logo, title, end screen links).
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame
            }
            addJavascriptInterface(Bridge(), "Android")
            isFocusable = false
        }
        setContentView(web)
        // An https base URL gives the embed a real origin/referrer, which YouTube requires.
        web.loadDataWithBaseURL("https://tvscreentime.app/", html(id), "text/html", "utf-8", null)
    }

    private inner class Bridge {
        @JavascriptInterface fun ended() = runOnUiThread { finish() }
        @JavascriptInterface fun error(code: Int) = runOnUiThread {
            Toast.makeText(this@PlayerActivity, getString(R.string.cant_play, code), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val js = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle()"
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> "seek(-10)"
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "seek(10)"
            else -> return super.dispatchKeyEvent(e)
        }
        web.evaluateJavascript(js, null)
        return true
    }

    override fun onPause() {
        web.evaluateJavascript("player && player.pauseVideo()", null)
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun html(id: String) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head>
        <body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          var player;
          function onYouTubeIframeAPIReady() {
            player = new YT.Player('p', {
              width: '100%', height: '100%', videoId: '$id',
              playerVars: { autoplay: 1, controls: 0, rel: 0, fs: 0, disablekb: 1, iv_load_policy: 3, playsinline: 1, origin: 'https://tvscreentime.app' },
              events: {
                onReady: function (e) { e.target.playVideo(); },
                onStateChange: function (e) { if (e.data === YT.PlayerState.ENDED) Android.ended(); },
                onError: function (e) { Android.error(e.data); }
              }
            });
          }
          function toggle() { if (!player) return; player.getPlayerState() === 1 ? player.pauseVideo() : player.playVideo(); }
          function seek(s) { if (player) player.seekTo(player.getCurrentTime() + s, true); }
        </script></body></html>
    """.trimIndent()
}
