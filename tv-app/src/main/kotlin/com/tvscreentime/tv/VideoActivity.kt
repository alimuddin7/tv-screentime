package com.tvscreentime.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.URL
import java.util.concurrent.Executors

/** Grid of the latest videos from whitelisted channels. Playing one opens PlayerActivity, which returns here. */
class VideoActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    private val io = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<String, Bitmap>(60) // ~60 x 115 KB (320x180 RGB_565)
    private var videos = emptyList<Channels.Video>()
    private lateinit var grid: RecyclerView
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        message = TextView(this).apply {
            text = getString(R.string.loading_videos); textSize = 22f; setTextColor(Color.WHITE); alpha = 0.7f; gravity = Gravity.CENTER
        }
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@VideoActivity, 4)
            setPadding(dp(56), dp(24), dp(56), dp(48))
            clipToPadding = false
            adapter = Adapter()
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(LinearLayout(this@VideoActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@VideoActivity).apply {
                    text = getString(R.string.video); textSize = 40f; setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    setPadding(dp(64), dp(36), 0, 0)
                })
                addView(grid, LinearLayout.LayoutParams(-1, -1))
            })
            addView(message, FrameLayout.LayoutParams(-1, -1))
        })
        io.execute {
            val v = runCatching { Channels.videos(this) }
            runOnUiThread {
                videos = v.getOrDefault(emptyList())
                message.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
                message.text = when {
                    v.isFailure -> getString(R.string.videos_failed)
                    Channels.list(this).isEmpty() -> getString(R.string.no_channels)
                    else -> getString(R.string.no_videos)
                }
                grid.adapter!!.notifyDataSetChanged()
                grid.post { grid.getChildAt(0)?.requestFocus() }
            }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private inner class Holder(val root: LinearLayout, val image: ImageView, val title: TextView) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = videos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ImageView(this@VideoActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(38, 38, 42))
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
                }
                clipToOutline = true
            }
            val title = TextView(this@VideoActivity).apply {
                textSize = 15f; setTextColor(Color.WHITE); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(10), 0, 0); alpha = 0.6f
            }
            val root = LinearLayout(this@VideoActivity).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(image, LinearLayout.LayoutParams(-1, dp(118)))
                addView(title)
                onFocusChangeListener = View.OnFocusChangeListener { _, f ->
                    image.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).translationZ(if (f) dp(12).toFloat() else 0f).setDuration(150).start()
                    title.alpha = if (f) 1f else 0.6f
                }
            }
            return Holder(root, image, title).also { h ->
                root.setOnClickListener {
                    val v = videos.getOrNull(h.bindingAdapterPosition) ?: return@setOnClickListener
                    startActivity(Intent(this@VideoActivity, PlayerActivity::class.java).putExtra("id", v.id))
                }
            }
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val v = videos[position]
            h.title.text = v.title
            h.image.tag = v.id
            val cached = thumbs.get(v.id)
            h.image.setImageBitmap(cached)
            if (cached == null) io.execute {
                val bmp = runCatching {
                    URL("https://i.ytimg.com/vi/${v.id}/mqdefault.jpg").openStream().use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                    }
                }.getOrNull() ?: return@execute
                thumbs.put(v.id, bmp)
                runOnUiThread { if (h.image.tag == v.id) h.image.setImageBitmap(bmp) }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
