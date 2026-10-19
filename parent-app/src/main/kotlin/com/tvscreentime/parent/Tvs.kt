package com.tvscreentime.parent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A paired TV. `host` may change (DHCP); NSD discovery fixes it by matching `id`. */
data class Tv(val id: String, val name: String, val host: String, val port: Int, val token: String) {
    val base get() = "http://$host:$port"
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port).put("token", token)

    companion object {
        fun fromJson(o: JSONObject) = Tv(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("token"))
    }
}

data class Status(val mode: String, val remainingMs: Long, val allowed: Set<String>) {
    companion object {
        fun fromJson(o: JSONObject) = Status(
            o.getString("mode"), o.getLong("remainingMs"),
            o.getJSONArray("allowed").let { a -> (0 until a.length()).map(a::getString).toSet() },
        )
    }
}

/** App state: paired TVs (persisted) and their live status (null = offline). Compose observes both. */
object Tvs {
    val list = mutableStateListOf<Tv>()
    val status = mutableStateMapOf<String, Status?>()

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("tvs", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val sockets = mutableMapOf<String, WebSocket>()
    private val json = "application/json".toMediaType()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val a = JSONArray(prefs.getString("tvs", "[]"))
        list.addAll((0 until a.length()).map { Tv.fromJson(a.getJSONObject(it)) })
    }

    private fun save() = prefs.edit().putString("tvs", JSONArray(list.map { it.toJson() }).toString()).apply()

    private fun put(tv: Tv) {
        val i = list.indexOfFirst { it.id == tv.id }
        if (i >= 0) list[i] = tv else list.add(tv)
        save()
    }

    fun rename(tv: Tv, name: String) = put(tv.copy(name = name.trim().ifEmpty { tv.name }))

    fun remove(tv: Tv) {
        sockets.remove(tv.id)?.cancel()
        list.removeAll { it.id == tv.id }
        status.remove(tv.id)
        save()
    }

    // --- HTTP (blocking: call from a background thread) ---

    private fun call(tv: Tv, method: String, path: String, body: JSONObject? = null): String {
        val req = Request.Builder().url(tv.base + path)
            .header("Authorization", "Bearer ${tv.token}")
            .method(method, body?.toString()?.toRequestBody(json))
            .build()
        http.newCall(req).execute().use { r: Response ->
            if (!r.isSuccessful) error(r.body.string().takeIf { r.code == 400 && it.isNotBlank() } ?: "HTTP ${r.code}")
            return r.body.string()
        }
    }

    /** QR payload from the TV: {v, h, p, c, id, n}. */
    fun pair(qr: String, phoneName: String): Tv {
        val q = JSONObject(qr)
        val base = Tv(q.getString("id"), q.getString("n"), q.getString("h"), q.getInt("p"), token = "")
        val req = Request.Builder().url(base.base + "/pair")
            .post(JSONObject().put("code", q.getString("c")).put("name", phoneName).toString().toRequestBody(json))
            .build()
        val res = http.newCall(req).execute().use {
            if (!it.isSuccessful) error(if (it.code == 403) Lang.wrap(app).getString(R.string.qr_expired) else "HTTP ${it.code}")
            JSONObject(it.body.string())
        }
        val tv = base.copy(token = res.getString("token"), name = list.firstOrNull { it.id == base.id }?.name ?: base.name)
        put(tv)
        status[tv.id] = Status.fromJson(res.getJSONObject("status"))
        command(tv, "lang", pkg = Lang.get(app))
        connect(tv)
        return tv
    }

    /** cmd = block | unblock | timer | add | allow | deny. Fire-and-forget; the WebSocket brings the new status. */
    fun command(tv: Tv, cmd: String, minutes: Int = 0, pkg: String? = null, onError: (String) -> Unit = {}) = thread {
        runCatching { call(tv, "POST", "/command", JSONObject().put("cmd", cmd).put("minutes", minutes).put("pkg", pkg)) }
            .onSuccess { status[tv.id] = Status.fromJson(JSONObject(it)) }
            .onFailure { onError("${tv.name}: ${it.message}") }
    }

    data class App(val pkg: String, val label: String, val allowed: Boolean)
    data class Device(val id: String, val name: String, val self: Boolean)

    fun apps(tv: Tv): List<App> = JSONArray(call(tv, "GET", "/apps")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { App(it.getString("pkg"), it.getString("label"), it.getBoolean("allowed")) }
            .sortedBy { it.label.lowercase() }
    }

    fun devices(tv: Tv): List<Device> = JSONArray(call(tv, "GET", "/devices")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Device(it.getString("id"), it.getString("name"), it.getBoolean("self")) }
    }

    fun revoke(tv: Tv, deviceId: String) { call(tv, "DELETE", "/devices/$deviceId") }

    data class Channel(val id: String, val title: String)

    fun channels(tv: Tv): List<Channel> = JSONArray(call(tv, "GET", "/channels")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Channel(it.getString("id"), it.getString("title")) }
    }

    /** q = channel URL, @handle or channel id; the TV resolves it. */
    fun addChannel(tv: Tv, q: String): Channel =
        JSONObject(call(tv, "POST", "/channels", JSONObject().put("q", q))).let { Channel(it.getString("id"), it.getString("title")) }

    fun removeChannel(tv: Tv, id: String) { call(tv, "DELETE", "/channels/$id") }

    // --- Live status ---

    fun connectAll() = list.forEach(::connect)

    fun disconnectAll() {
        sockets.values.forEach { it.cancel() }
        sockets.clear()
    }

    private fun connect(tv: Tv) {
        sockets.remove(tv.id)?.cancel()
        sockets[tv.id] = http.newWebSocket(Request.Builder().url("ws://${tv.host}:${tv.port}/ws?token=${tv.token}").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { status[tv.id] = Status.fromJson(JSONObject(text)) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { offline(tv, webSocket) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { offline(tv, webSocket) }
            })
    }

    private fun offline(tv: Tv, ws: WebSocket) {
        if (sockets[tv.id] !== ws) return // replaced or app went to background
        status[tv.id] = null
        // ponytail: fixed 5 s retry; the TV may have rebooted or got a new IP (discovery updates it).
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (sockets[tv.id] === ws) list.firstOrNull { it.id == tv.id }?.let(::connect)
        }, 5000)
    }

    // --- NSD: follow TVs whose IP changed ---

    private var discovery: NsdManager.DiscoveryListener? = null

    fun startDiscovery() {
        val nsd = app.getSystemService(NsdManager::class.java)
        discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val id = info.attributes["id"]?.let { String(it) } ?: return
                        val host = info.host?.hostAddress ?: return
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            val tv = list.firstOrNull { it.id == id } ?: return@post
                            if (tv.host != host || tv.port != info.port) {
                                put(tv.copy(host = host, port = info.port))
                                connect(list.first { it.id == id })
                            }
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        nsd.discoverServices("_tvscreentime._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stopDiscovery() {
        discovery?.let { runCatching { app.getSystemService(NsdManager::class.java).stopServiceDiscovery(it) } }
        discovery = null
    }
}
