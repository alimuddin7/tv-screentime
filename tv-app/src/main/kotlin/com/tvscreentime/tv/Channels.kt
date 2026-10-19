package com.tvscreentime.tv

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Whitelisted YouTube channels ([{id, title}] in prefs) and their latest videos from the public RSS feed. */
object Channels {
    data class Channel(val id: String, val title: String)
    data class Video(val id: String, val title: String, val channel: String, val published: String)

    private fun prefs(c: Context) = c.getSharedPreferences("state", Context.MODE_PRIVATE)

    fun list(c: Context): List<Channel> = JSONArray(prefs(c).getString("channels", "[]")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Channel(it.getString("id"), it.getString("title")) }
    }

    fun json(c: Context) = JSONArray(list(c).map { JSONObject().put("id", it.id).put("title", it.title) })

    private fun save(c: Context, list: List<Channel>) =
        prefs(c).edit().putString("channels", JSONArray(list.map { JSONObject().put("id", it.id).put("title", it.title) }).toString()).apply()

    /** Accepts a channel id, a /channel/UC… URL, an @handle or a youtube.com/@handle URL. Network: call off the main thread. */
    @Synchronized fun add(c: Context, query: String): Channel {
        val id = resolveId(c, query.trim())
        val ch = Channel(id, fetchFeed(id).first)
        save(c, list(c).filter { it.id != id } + ch)
        feedCache = null
        return ch
    }

    @Synchronized fun remove(c: Context, id: String) {
        save(c, list(c).filter { it.id != id })
        feedCache = null
    }

    private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")

    private fun resolveId(c: Context, q: String): String {
        CHANNEL_ID.find(q)?.let { if ("/channel/" in q || q == it.value) return it.value }
        val handle = Regex("@[A-Za-z0-9._-]+").find(q)?.value ?: error(Lang.wrap(c.applicationContext).getString(R.string.channel_bad_input))
        val html = get("https://www.youtube.com/$handle")
        // The page's own RSS link carries its id; the page also mentions other channels.
        return Regex("""feeds/videos\.xml\?channel_id=(UC[A-Za-z0-9_-]{22})""").find(html)?.groupValues?.get(1)
            ?: error(Lang.wrap(c.applicationContext).getString(R.string.channel_not_found, handle))
    }

    // --- Feed ---

    private var feedCache: Pair<Long, List<Video>>? = null

    /** Latest videos of every channel, newest first, Shorts left out. Cached 10 minutes. Network: off the main thread. */
    fun videos(c: Context): List<Video> {
        feedCache?.let { (at, v) -> if (System.currentTimeMillis() - at < 10 * 60_000) return v }
        val pool = Executors.newFixedThreadPool(3)
        val all = list(c).map { ch -> pool.submit<List<Video>> { runCatching { fetchFeed(ch.id).second }.getOrDefault(emptyList()) } }
            .flatMap { it.get() }
            .sortedByDescending { it.published }
        pool.shutdown()
        feedCache = System.currentTimeMillis() to all
        return all
    }

    /** (channel title, videos) from youtube.com/feeds/videos.xml. */
    private fun fetchFeed(channelId: String): Pair<String, List<Video>> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId").reader())
        var channelTitle = ""
        val videos = mutableListOf<Video>()
        var inEntry = false
        var id = ""; var title = ""; var published = ""; var short = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> { inEntry = true; id = ""; title = ""; published = ""; short = false }
                    "yt:videoId" -> id = p.nextText()
                    "title" -> if (inEntry) title = p.nextText() else channelTitle = p.nextText()
                    "published" -> if (inEntry) published = p.nextText()
                    "link" -> if (inEntry && p.getAttributeValue(null, "href")?.contains("/shorts/") == true) short = true
                }
                XmlPullParser.END_TAG -> if (p.name == "entry") {
                    inEntry = false
                    if (!short && id.isNotEmpty()) videos += Video(id, title, channelTitle, published)
                }
            }
        }
        if (channelTitle.isEmpty()) error("Feed channel kosong")
        return channelTitle to videos
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        // Without a browser UA and consent cookie, youtube.com may answer with a consent page instead of the channel.
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/138 Safari/537.36")
        conn.setRequestProperty("Cookie", "CONSENT=YES+1")
        try {
            if (conn.responseCode != 200) error("YouTube HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }
}
