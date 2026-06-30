package com.mechrobotix.mechbot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.core.content.ContextCompat

class WifiDirectController(
    private val context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onStatus(message: String)
        fun onPeersChanged(peers: List<WifiP2pDevice>)
        fun onConnectionReady(info: WifiP2pInfo)
        fun onDisconnected()
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)
    private val receiver = WifiDirectBroadcastReceiver(this)
    private val filter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var registered = false

    fun register() {
        if (!registered) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        }
    }

    fun unregister() {
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        callbacks.onStatus("Buscando dispositivos Wi‑Fi Direct...")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = callbacks.onStatus("Búsqueda iniciada. Espera la lista de dispositivos.")
            override fun onFailure(reason: Int) = callbacks.onStatus("No se pudo iniciar búsqueda: $reason")
        })
    }

    @SuppressLint("MissingPermission")
    fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        callbacks.onStatus("Conectando con ${device.deviceName?.ifBlank { "robot" } ?: "robot"}...")
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = callbacks.onStatus("Solicitud enviada. Acepta la conexión en el otro teléfono si aparece el diálogo.")
            override fun onFailure(reason: Int) = callbacks.onStatus("Falló conexión: $reason")
        })
    }

    @SuppressLint("MissingPermission")
    fun requestPeers() {
        manager.requestPeers(channel) { peerList ->
            callbacks.onPeersChanged(peerList.deviceList.toList())
        }
    }

    fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info ->
            callbacks.onConnectionReady(info)
        }
    }

    fun removeGroup() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = callbacks.onStatus("Grupo Wi‑Fi Direct eliminado.")
            override fun onFailure(reason: Int) = callbacks.onStatus("No se pudo eliminar grupo: $reason")
        })
    }

    fun onP2pStateChanged(enabled: Boolean) {
        callbacks.onStatus(if (enabled) "Wi‑Fi Direct disponible." else "Wi‑Fi Direct desactivado.")
    }

    fun onDisconnected() = callbacks.onDisconnected()
}
