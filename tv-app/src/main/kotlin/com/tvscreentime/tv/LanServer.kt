package com.tvscreentime.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * LAN API for parent phones (HTTP + WebSocket, JSON bodies, `Authorization: Bearer <token>`).
 *   POST /pair {code, name} -> {token, status}   only while PairActivity shows the QR
 *   GET  /status            -> {id, name, mode, remainingMs, allowed[]}
 *   POST /command {cmd, minutes?, pkg?} -> status     cmd = block|unblock|timer|add|allow|deny
 *   GET  /apps              -> [{pkg, label, allowed}]
 *   GET  /devices           -> [{id, name, self}]
 *   DELETE /devices/{id}
 *   GET  /channels          -> [{id, title}]
 *   POST /channels {q}      -> {id, title}   q = channel URL, @handle or UC… id
 *   DELETE /channels/{id}
 *   WS   /ws?token=...      -> status JSON whenever it changes
 * ponytail: plain HTTP on the home LAN; tokens are the only protection. Add TLS if this ever leaves the LAN.
 */
class LanServer(private val svc: ScreenTimeService) {
    private var server: EmbeddedServer<*, *>? = null
    private val nsd by lazy { svc.getSystemService(NsdManager::class.java) }
    private val nsdListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
    }

    fun start() {
        server = embeddedServer(CIO, port = PORT) {
            install(WebSockets)
            routing {
                post("/pair") {
                    val body = JSONObject(call.receiveText())
                    val code = pairCode
                    if (code == null || !MessageDigest.isEqual(code.toByteArray(), body.optString("code").toByteArray())) {
                        return@post call.respond(HttpStatusCode.Forbidden)
                    }
                    pairCode = newCode() // one phone per QR
                    val token = Devices.add(svc, body.optString("name", "HP").take(40))
                    onPaired?.invoke()
                    call.json(JSONObject().put("token", token).put("status", svc.statusJson()))
                }
                get("/status") { device() ?: return@get; call.json(svc.statusJson()) }
                post("/command") {
                    device() ?: return@post
                    val b = JSONObject(call.receiveText())
                    withContext(Dispatchers.Main) {
                        svc.handle(b.optString("cmd"), b.optInt("minutes"), b.optString("pkg").ifEmpty { null })
                    }
                    call.json(svc.statusJson())
                }
                get("/apps") { device() ?: return@get; call.json(svc.appsJson()) }
                get("/devices") {
                    val me = device() ?: return@get
                    val all = Devices.all(svc)
                    call.json(JSONArray(all.keys().asSequence().map {
                        JSONObject().put("id", it).put("name", all.getJSONObject(it).getString("name")).put("self", it == me)
                    }.toList()))
                }
                delete("/devices/{id}") {
                    device() ?: return@delete
                    Devices.remove(svc, call.parameters["id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/channels") { device() ?: return@get; call.json(Channels.json(svc)) }
                post("/channels") {
                    device() ?: return@post
                    val q = JSONObject(call.receiveText()).optString("q")
                    runCatching { withContext(Dispatchers.IO) { Channels.add(svc, q) } }
                        .onSuccess { call.json(JSONObject().put("id", it.id).put("title", it.title)) }
                        .onFailure { call.respondText(it.message ?: "error", status = HttpStatusCode.BadRequest) }
                }
                delete("/channels/{id}") {
                    device() ?: return@delete
                    Channels.remove(svc, call.parameters["id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }
                webSocket("/ws") {
                    val token = call.request.queryParameters["token"]
                    var last = ""
                    while (isActive) {
                        if (Devices.find(svc, token) == null) return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unpaired"))
                        val s = svc.statusJson().toString()
                        if (s != last) { send(Frame.Text(s)); last = s }
                        delay(1000)
                    }
                }
            }
        }.start(wait = false)

        nsd.registerService(NsdServiceInfo().apply {
            serviceName = "Screentime ${svc.tvName}"
            serviceType = SERVICE_TYPE
            port = PORT
            setAttribute("id", svc.tvId)
        }, NsdManager.PROTOCOL_DNS_SD, nsdListener)
    }

    fun stop() {
        runCatching { nsd.unregisterService(nsdListener) }
        server?.stop(500, 1000)
    }

    /** Device id for the bearer token, or null after answering 401. */
    private suspend fun RoutingContext.device(): String? {
        val id = Devices.find(svc, call.request.headers["Authorization"]?.removePrefix("Bearer "))
        if (id == null) call.respond(HttpStatusCode.Unauthorized)
        return id
    }

    private suspend fun RoutingCall.json(body: Any) = respondText(body.toString(), ContentType.Application.Json)

    companion object {
        const val PORT = 8787
        const val SERVICE_TYPE = "_tvscreentime._tcp"

        /** Set while the QR is on screen; null means pairing is closed. */
        @Volatile var pairCode: String? = null
        @Volatile var onPaired: (() -> Unit)? = null

        fun newCode() = random(12)

        fun random(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

        fun localIp(): String? = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }
}

/** Paired phones, stored as {id: {name, token}} JSON in prefs. */
object Devices {
    private fun prefs(c: Context) = c.getSharedPreferences("state", Context.MODE_PRIVATE)

    fun all(c: Context) = JSONObject(prefs(c).getString("devices", "{}")!!)

    fun find(c: Context, token: String?): String? {
        if (token.isNullOrEmpty()) return null
        val all = all(c)
        return all.keys().asSequence().firstOrNull {
            MessageDigest.isEqual(all.getJSONObject(it).getString("token").toByteArray(), token.toByteArray())
        }
    }

    @Synchronized fun add(c: Context, name: String): String {
        val token = LanServer.random(32)
        val all = all(c).put(LanServer.random(6), JSONObject().put("name", name).put("token", token))
        prefs(c).edit().putString("devices", all.toString()).apply()
        return token
    }

    @Synchronized fun remove(c: Context, id: String) {
        prefs(c).edit().putString("devices", all(c).apply { remove(id) }.toString()).apply()
    }
}
