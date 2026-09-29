package com.chaupal.ludo

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject

/**
 * Offline link between the game phone ("host") and the remote phone ("remote")
 * using Google Nearby Connections (Bluetooth + Wi-Fi Direct). No internet needed.
 * The game phone never shows anything about this; all status goes to the page,
 * which only displays it in the hidden pairing sheet or on the remote screen.
 */
class NearbyBridge(private val activity: Activity, private val web: WebView) {

    private val client = Nearby.getConnectionsClient(activity)
    private val serviceId = "com.chaupal.ludo.remote"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val main = Handler(Looper.getMainLooper())

    private var role = ""          // "host" or "remote"
    private var code = ""
    private var permsReady = false
    private var pending: (() -> Unit)? = null
    private val connected = mutableSetOf<String>()
    private var searchTimeout: Runnable? = null

    // ---------- to the page ----------
    private fun emit(event: String, data: String = "") {
        val js = "window.onNearby&&window.onNearby(${JSONObject.quote(event)},${JSONObject.quote(data)})"
        main.post { web.evaluateJavascript(js, null) }
    }

    fun onPermissionsReady() {
        permsReady = true
        main.post { pending?.invoke(); pending = null }
    }

    // ---------- called from the page ----------
    @JavascriptInterface
    fun startHost(c: String) {
        main.post {
            val action = { beginHost(c) }
            if (permsReady) action() else pending = action
        }
    }

    @JavascriptInterface
    fun startRemote(c: String) {
        main.post {
            val action = { beginRemote(c) }
            if (permsReady) action() else pending = action
        }
    }

    @JavascriptInterface
    fun send(json: String) {
        main.post {
            if (connected.isEmpty()) return@post
            val bytes = json.toByteArray(Charsets.UTF_8)
            for (id in connected) client.sendPayload(id, Payload.fromBytes(bytes))
        }
    }

    @JavascriptInterface
    fun stop() { main.post { stopAll() } }

    // ---------- host (game phone) ----------
    private fun beginHost(c: String) {
        stopAll()
        role = "host"; code = c
        advertise()
    }

    private fun advertise() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising("ludo-$code", serviceId, lifecycle, options)
            .addOnSuccessListener { emit("hoststatus", "Waiting for remote") }
            .addOnFailureListener { e ->
                val m = e.message ?: ""
                if (m.contains("8001")) emit("hoststatus", "Waiting for remote")
                else emit("hoststatus", "Turn on Bluetooth and Location, then reopen the app")
            }
    }

    private fun restartAdvertising() {
        client.stopAdvertising()
        main.postDelayed({ if (role == "host") advertise() }, 800)
    }

    // ---------- remote (controller phone) ----------
    private fun beginRemote(c: String) {
        stopAll()
        role = "remote"; code = c
        emit("status", "Searching for game $c…")
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discovery, options)
            .addOnFailureListener { emit("status", "Turn on Bluetooth and Location, then tap Reconnect") }
        searchTimeout = Runnable {
            if (role == "remote" && connected.isEmpty()) {
                client.stopDiscovery()
                emit("status", "Game $c not found nearby. Keep the game app open and tap Reconnect")
                emit("disconnected")
            }
        }.also { main.postDelayed(it, 25000) }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (role != "remote" || info.endpointName != "ludo-$code") return
            client.stopDiscovery()
            emit("status", "Found game, connecting…")
            client.requestConnection("remote-$code", endpointId, lifecycle)
                .addOnFailureListener {
                    emit("status", "Could not connect. Tap Reconnect")
                    emit("disconnected")
                }
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    // ---------- shared ----------
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { emit("message", String(it, Charsets.UTF_8)) }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // The game phone only accepts a remote that used its code.
            if (role == "host" && info.endpointName != "remote-$code") {
                client.rejectConnection(endpointId); return
            }
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connected.add(endpointId)
                searchTimeout?.let { main.removeCallbacks(it) }
                emit("connected")
            } else {
                if (role == "remote") { emit("status", "Connection failed. Tap Reconnect"); emit("disconnected") }
                if (role == "host") restartAdvertising()
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            emit("disconnected")
            if (role == "host") restartAdvertising()
            if (role == "remote") emit("status", "Disconnected. Tap Reconnect")
        }
    }

    fun stopAll() {
        searchTimeout?.let { main.removeCallbacks(it) }
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected.clear()
        role = ""
    }
}
