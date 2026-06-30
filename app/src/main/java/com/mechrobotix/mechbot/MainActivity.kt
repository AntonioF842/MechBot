package com.mechrobotix.mechbot

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), WifiDirectController.Callbacks {

    private enum class Role { NONE, CONTROLLER, ROBOT }
    private enum class ChipState { IDLE, BUSY, OK, ERROR }

    private lateinit var wifi: WifiDirectController
    private lateinit var socketLink: P2pSocketLink
    private lateinit var videoReceiver: RemoteFrameReceiver
    private lateinit var frameSender: FrameSender
    private lateinit var cameraStreamer: CameraFrameStreamer
    private lateinit var motorController: UsbKeyboardLedMotorController

    private lateinit var txtStatus: TextView
    private lateinit var txtWifiState: TextView
    private lateinit var txtUsbState: TextView
    private lateinit var txtVideoState: TextView
    private lateinit var txtPeerCount: TextView
    private lateinit var txtRobotHint: TextView
    private lateinit var controllerPanel: LinearLayout
    private lateinit var robotPanel: LinearLayout
    private lateinit var spinnerPeers: Spinner
    private lateinit var btnConnectPeer: Button
    private lateinit var btnControllerMode: MaterialButton
    private lateinit var btnRobotMode: MaterialButton
    private lateinit var robotEyes: RobotEyesView
    private lateinit var robotEyesFull: RobotEyesView
    private lateinit var fullEyesOverlay: View
    private lateinit var imgRemoteCamera: ImageView

    private var role = Role.NONE
    private var isEyesFullscreen = false
    private var lastRobotCommand: MechbotCommand = MechbotCommand.STOP
    private var latestConnectionInfo: WifiP2pInfo? = null
    private var peers: List<WifiP2pDevice> = emptyList()
    private var lastNotice: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            setStatus("Permisos listos.")
            wifi.discoverPeers()
        } else {
            setStatus("Faltan permisos: ${denied.joinToString()}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtWifiState = findViewById(R.id.txtWifiState)
        txtUsbState = findViewById(R.id.txtUsbState)
        txtVideoState = findViewById(R.id.txtVideoState)
        txtPeerCount = findViewById(R.id.txtPeerCount)
        txtRobotHint = findViewById(R.id.txtRobotHint)
        controllerPanel = findViewById(R.id.controllerPanel)
        robotPanel = findViewById(R.id.robotPanel)
        spinnerPeers = findViewById(R.id.spinnerPeers)
        btnConnectPeer = findViewById(R.id.btnConnectPeer)
        btnControllerMode = findViewById(R.id.btnControllerMode)
        btnRobotMode = findViewById(R.id.btnRobotMode)
        robotEyes = findViewById(R.id.robotEyes)
        robotEyesFull = findViewById(R.id.robotEyesFull)
        fullEyesOverlay = findViewById(R.id.fullEyesOverlay)
        imgRemoteCamera = findViewById(R.id.imgRemoteCamera)

        wifi = WifiDirectController(this, this)
        socketLink = P2pSocketLink(::setStatus, ::onSocketMessage)
        videoReceiver = RemoteFrameReceiver(::setStatus) { bitmap ->
            runOnUiThread { imgRemoteCamera.setImageBitmap(bitmap) }
        }
        frameSender = FrameSender(::setStatus)
        cameraStreamer = CameraFrameStreamer(this, this, ::setStatus)
        motorController = UsbKeyboardLedMotorController(this, ::setStatus)

        bindUi()
        resetIndicators()
        updatePeerSelector()

        val savedRole = savedInstanceState
            ?.getString(KEY_ROLE)
            ?.let { runCatching { Role.valueOf(it) }.getOrNull() }
            ?: Role.NONE
        if (savedRole != Role.NONE) {
            selectRole(savedRole)
            if (savedInstanceState?.getBoolean(KEY_EYES_FULLSCREEN) == true && savedRole == Role.ROBOT) {
                enterEyesFullscreen()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ROLE, role.name)
        outState.putBoolean(KEY_EYES_FULLSCREEN, isEyesFullscreen)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        wifi.register()
    }

    override fun onResume() {
        super.onResume()
        if (role == Role.ROBOT) motorController.prepare()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (role == Role.ROBOT) motorController.handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        wifi.unregister()
    }

    override fun onDestroy() {
        socketLink.stop()
        videoReceiver.stop()
        frameSender.stop()
        cameraStreamer.stop()
        motorController.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isEyesFullscreen) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isEyesFullscreen) {
            hideSystemBars()
            robotEyesFull.react(lastRobotCommand)
        }
    }

    private fun bindUi() {
        btnControllerMode.setOnClickListener { selectRole(Role.CONTROLLER) }
        btnRobotMode.setOnClickListener { selectRole(Role.ROBOT) }
        findViewById<Button>(R.id.btnDiscover).setOnClickListener { ensurePermissionsAndDiscover() }
        btnConnectPeer.setOnClickListener { connectSelectedPeer() }

        findViewById<Button>(R.id.btnFullEyes).setOnClickListener { enterEyesFullscreen() }
        findViewById<Button>(R.id.btnExitFullEyes).setOnClickListener { exitEyesFullscreen() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isEyesFullscreen) {
                    exitEyesFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        bindMovementButton(R.id.btnForward, MechbotCommand.FORWARD)
        bindMovementButton(R.id.btnBackward, MechbotCommand.BACKWARD)
        bindMovementButton(R.id.btnLeft, MechbotCommand.LEFT)
        bindMovementButton(R.id.btnRight, MechbotCommand.RIGHT)
        findViewById<Button>(R.id.btnStop).setOnClickListener { sendCommand(MechbotCommand.STOP) }

        findViewById<Button>(R.id.btnStartVideo).setOnClickListener {
            val info = latestConnectionInfo
            if (info == null) {
                setStatus("Conecta primero con el robot.")
                return@setOnClickListener
            }
            txtVideoState.setChip("Video", ChipState.BUSY)
            videoReceiver.start(info)
            sendCommand(MechbotCommand.CAMERA_ON)
        }

        findViewById<Button>(R.id.btnStopVideo).setOnClickListener {
            sendCommand(MechbotCommand.CAMERA_OFF)
            videoReceiver.stop()
            imgRemoteCamera.setImageDrawable(null)
            txtVideoState.setChip("Video off", ChipState.IDLE)
            setStatus("Video detenido.")
        }
    }

    private fun bindMovementButton(id: Int, command: MechbotCommand) {
        findViewById<Button>(id).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> sendCommand(command)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sendCommand(MechbotCommand.STOP)
            }
            true
        }
    }

    private fun selectRole(newRole: Role) {
        role = newRole
        controllerPanel.visibility = if (newRole == Role.CONTROLLER) View.VISIBLE else View.GONE
        robotPanel.visibility = if (newRole == Role.ROBOT) View.VISIBLE else View.GONE
        updateRoleButtons()

        if (newRole == Role.ROBOT) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            txtRobotHint.text = "Esperando controlador"
            setStatus("Modo robot activo.")
            motorController.prepare()
            motorController.send(MechbotCommand.STOP)
        } else {
            exitEyesFullscreen()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setStatus("Modo controlador activo.")
        }

        ensurePermissionsAndDiscover()
    }

    private fun connectSelectedPeer() {
        val index = spinnerPeers.selectedItemPosition
        val device = peers.getOrNull(index)
        if (device == null) {
            setStatus("Busca un robot primero.")
            return
        }
        wifi.connectTo(device)
    }

    private fun enterEyesFullscreen() {
        if (role != Role.ROBOT) {
            setStatus("Activa modo robot primero.")
            return
        }

        isEyesFullscreen = true
        fullEyesOverlay.visibility = View.VISIBLE
        robotEyesFull.react(lastRobotCommand)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
    }

    private fun exitEyesFullscreen() {
        isEyesFullscreen = false
        fullEyesOverlay.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun ensurePermissionsAndDiscover() {
        val required = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            wifi.discoverPeers()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun sendCommand(command: MechbotCommand) {
        if (role != Role.CONTROLLER) return
        socketLink.sendLine(command.wire)
    }

    private fun onSocketMessage(message: String) {
        val command = MechbotCommand.fromWire(message) ?: return
        if (role == Role.ROBOT) handleRobotCommand(command)
    }

    private fun handleRobotCommand(command: MechbotCommand) {
        when (command) {
            MechbotCommand.CAMERA_ON -> {
                val info = latestConnectionInfo
                if (info == null) {
                    setStatus("No hay conexión para video.")
                    return
                }
                txtRobotHint.text = "Transmitiendo video"
                txtVideoState.setChip("Video", ChipState.BUSY)
                frameSender.start(info)
                cameraStreamer.start(frameSender)
            }
            MechbotCommand.CAMERA_OFF -> {
                cameraStreamer.stop()
                frameSender.stop()
                txtRobotHint.text = "Video detenido"
                txtVideoState.setChip("Video off", ChipState.IDLE)
            }
            else -> {
                lastRobotCommand = command
                robotEyes.react(command)
                robotEyesFull.react(command)
                txtRobotHint.text = command.robotHint()
                setStatus("Comando: ${command.robotHint()}")
                motorController.send(command)
            }
        }
    }

    override fun onStatus(message: String) = setStatus(message)

    override fun onPeersChanged(peers: List<WifiP2pDevice>) {
        this.peers = peers
        updatePeerSelector()
        val count = peers.size
        txtPeerCount.text = if (count == 1) "1 dispositivo encontrado" else "$count dispositivos encontrados"
        setStatus(if (count == 0) "Sin robots encontrados." else "Selecciona un robot.")
    }

    override fun onConnectionReady(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        latestConnectionInfo = info
        val owner = if (info.isGroupOwner) "GO local" else "GO ${info.groupOwnerAddress.hostAddress}"
        txtWifiState.setChip("Wi‑Fi listo", ChipState.OK)
        setStatus("Wi‑Fi Direct conectado · $owner")
        socketLink.start(info)
        showNotice("Conexión lista")
    }

    override fun onDisconnected() {
        latestConnectionInfo = null
        socketLink.stop()
        videoReceiver.stop()
        frameSender.stop()
        cameraStreamer.stop()
        txtWifiState.setChip("Wi‑Fi", ChipState.IDLE)
        txtVideoState.setChip("Video", ChipState.IDLE)
        setStatus("Wi‑Fi Direct desconectado.")
        showNotice("Desconectado")
    }

    private fun updatePeerSelector() {
        val names = if (peers.isEmpty()) {
            listOf("Sin dispositivos")
        } else {
            peers.map { device -> device.deviceName?.ifBlank { "Dispositivo sin nombre" } ?: "Dispositivo sin nombre" }
        }
        val adapter = ArrayAdapter(this, R.layout.item_spinner_peer, names).apply {
            setDropDownViewResource(R.layout.item_spinner_peer_dropdown)
        }
        spinnerPeers.adapter = adapter
        btnConnectPeer.isEnabled = peers.isNotEmpty()
        txtPeerCount.text = if (peers.isEmpty()) "Sin dispositivos" else "${peers.size} disponibles"
    }

    private fun resetIndicators() {
        txtWifiState.setChip("Wi‑Fi", ChipState.IDLE)
        txtUsbState.setChip("USB", ChipState.IDLE)
        txtVideoState.setChip("Video", ChipState.IDLE)
    }

    private fun updateRoleButtons() {
        btnControllerMode.backgroundTintList = ColorStateList.valueOf(color(if (role == Role.CONTROLLER) R.color.accent else R.color.accent_dark))
        btnRobotMode.backgroundTintList = ColorStateList.valueOf(color(if (role == Role.ROBOT) R.color.accent else R.color.accent_dark))
        btnControllerMode.setTextColor(color(if (role == Role.CONTROLLER) R.color.bg_dark else R.color.text_primary))
        btnRobotMode.setTextColor(color(if (role == Role.ROBOT) R.color.bg_dark else R.color.text_primary))
    }

    private fun TextView.setChip(text: String, state: ChipState) {
        this.text = text
        setTextColor(color(if (state == ChipState.IDLE) R.color.text_secondary else R.color.bg_dark))
        setBackgroundResource(
            when (state) {
                ChipState.IDLE -> R.drawable.bg_chip_idle
                ChipState.BUSY -> R.drawable.bg_chip_busy
                ChipState.OK -> R.drawable.bg_chip_ok
                ChipState.ERROR -> R.drawable.bg_chip_error
            }
        )
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            txtStatus.text = message
            updateIndicatorsFromStatus(message)
        }
    }

    private fun updateIndicatorsFromStatus(message: String) {
        val lower = message.lowercase()
        when {
            "teclado usb listo" in lower -> {
                txtUsbState.setChip("USB listo", ChipState.OK)
                showNotice("USB listo")
            }
            "solicitando permiso usb" in lower -> txtUsbState.setChip("USB permiso", ChipState.BUSY)
            "teclado usb desconectado" in lower -> {
                txtUsbState.setChip("USB", ChipState.IDLE)
                showNotice("USB desconectado")
            }
            "permiso usb denegado" in lower ||
                ("no se pudo" in lower && "usb" in lower) ||
                "no expone" in lower ||
                "reporte hid" in lower -> txtUsbState.setChip("USB error", ChipState.ERROR)
            "no hay usb enumerado" in lower || "usb detectado" in lower -> txtUsbState.setChip("USB", ChipState.IDLE)
        }

        when {
            "wi‑fi direct conectado" in lower || "socket de comandos conectado" in lower -> txtWifiState.setChip("Wi‑Fi listo", ChipState.OK)
            "conectando" in lower || "buscando" in lower || "búsqueda" in lower || "solicitud enviada" in lower || "esperando socket" in lower -> txtWifiState.setChip("Wi‑Fi", ChipState.BUSY)
            "wi‑fi direct desconectado" in lower || "wi‑fi direct disponible" in lower -> txtWifiState.setChip("Wi‑Fi", ChipState.IDLE)
            "wi‑fi direct desactivado" in lower || "falló conexión" in lower || "no se pudo iniciar búsqueda" in lower || "error en socket" in lower -> txtWifiState.setChip("Wi‑Fi error", ChipState.ERROR)
        }

        when {
            "video: recibiendo" in lower || "video: enlace listo" in lower || "cámara frontal activa" in lower -> txtVideoState.setChip("Video listo", ChipState.OK)
            "video: esperando" in lower || "video: conectando" in lower -> txtVideoState.setChip("Video", ChipState.BUSY)
            "video detenido" in lower || "cámara esclava detenida" in lower -> txtVideoState.setChip("Video", ChipState.IDLE)
            "video: error" in lower || "no se pudo iniciar cámara" in lower -> txtVideoState.setChip("Video error", ChipState.ERROR)
        }
    }

    private fun showNotice(message: String) {
        if (lastNotice == message) return
        lastNotice = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun MechbotCommand.robotHint(): String = when (this) {
        MechbotCommand.FORWARD -> "Adelante"
        MechbotCommand.BACKWARD -> "Atrás"
        MechbotCommand.LEFT -> "Izquierda"
        MechbotCommand.RIGHT -> "Derecha"
        MechbotCommand.STOP -> "Detenido"
        MechbotCommand.CAMERA_ON -> "Video activo"
        MechbotCommand.CAMERA_OFF -> "Video detenido"
    }

    private companion object {
        private const val KEY_ROLE = "role"
        private const val KEY_EYES_FULLSCREEN = "eyesFullscreen"
    }
}
