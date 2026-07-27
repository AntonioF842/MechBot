package com.mechrobotix.mechbot

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

class RemoteControlActivity : AppCompatActivity(), WifiDirectController.Callbacks {
    private enum class Role { NONE, CONTROLLER, ROBOT }
    private enum class ChipState { IDLE, BUSY, OK, ERROR }

    private lateinit var wifi: WifiDirectController
    private lateinit var socketLink: P2pSocketLink
    private var videoReceiver: RemoteFrameReceiver? = null
    private var frameSender: VideoFrameSender? = null
    private var cameraStreamer: RemoteCameraStreamer? = null
    private var motorController: UsbKeyboardLedMotorController? = null

    private lateinit var txtStatus: TextView
    private lateinit var txtWifiState: TextView
    private lateinit var txtUsbState: TextView
    private lateinit var txtVideoState: TextView
    private lateinit var txtDetectionState: TextView
    private lateinit var txtPeerCount: TextView
    private lateinit var txtRobotHint: TextView
    private lateinit var txtDetectionSummary: TextView
    private lateinit var controllerPanel: LinearLayout
    private lateinit var robotPanel: LinearLayout
    private lateinit var spinnerPeers: Spinner
    private lateinit var btnConnectPeer: Button
    private lateinit var btnDisconnectPeer: MaterialButton
    private lateinit var btnControllerMode: MaterialButton
    private lateinit var btnRobotMode: MaterialButton
    private lateinit var btnRobotTests: MaterialButton
    private lateinit var robotTestControls: LinearLayout
    private lateinit var robotEyes: RobotEyesView
    private lateinit var robotEyesFull: RobotEyesView
    private lateinit var fullEyesOverlay: View
    private lateinit var imgRemoteCamera: ImageView
    private lateinit var detectionOverlay: DetectionOverlayView
    private lateinit var switchRemoteDetection: SwitchMaterial

    private val mainHandler = Handler(Looper.getMainLooper())
    private var role = Role.NONE
    private var isEyesFullscreen = false
    private var isRobotTestMode = false
    private var lastRobotCommand = MovementCommand.STOP
    private var latestConnectionInfo: WifiP2pInfo? = null
    private var peers: List<WifiP2pDevice> = emptyList()
    private var lastNotice: String? = null
    private var remoteBitmap: Bitmap? = null
    private var remoteDetectionEnabled = false
    private var updatingDetectionSwitches = false
    private var pendingVideoStart = false
    private var videoRunning = false
    private var lastHeartbeatAt = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            setStatus("Permisos listos.")
            wifi.discoverPeers()
            if (pendingVideoStart && role == Role.ROBOT) startRobotVideoIfReady()
        } else {
            pendingVideoStart = false
            setStatus("Faltan permisos: ${denied.joinToString()}")
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (role == Role.CONTROLLER && socketLink.isConnected()) {
                socketLink.sendLine(RemoteMessage.Ping.toWire())
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (role == Role.ROBOT && socketLink.isConnected()) {
                val expired = SystemClock.elapsedRealtime() - lastHeartbeatAt > HEARTBEAT_TIMEOUT_MS
                if (expired && lastRobotCommand != MovementCommand.STOP) {
                    emergencyRobotStop("STOP por pérdida de comunicación.")
                }
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_control)
        bindViews()
        wifi = WifiDirectController(this, this)
        socketLink = P2pSocketLink(::setStatus, ::onSocketMessage, ::onSocketConnectionChanged)
        bindUi()
        resetIndicators()
        updatePeerSelector()
        setDetectionState(false, false)
        savedInstanceState?.getString(KEY_ROLE)?.let {
            runCatching { Role.valueOf(it) }.getOrNull()
        }?.takeIf { it != Role.NONE }?.let(::selectRole)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ROLE, role.name)
        outState.putBoolean(KEY_EYES_FULLSCREEN, isEyesFullscreen)
        outState.putBoolean(KEY_ROBOT_TEST_MODE, isRobotTestMode)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        wifi.register()
    }

    override fun onResume() {
        super.onResume()
        if (role == Role.ROBOT) {
            motorController?.prepare()
            if (socketLink.isConnected()) startWatchdog()
        } else if (role == Role.CONTROLLER && socketLink.isConnected()) {
            startHeartbeat()
        }
    }

    override fun onPause() {
        stopHeartbeatAndWatchdog()
        if (role == Role.CONTROLLER) sendRemoteMessage(RemoteMessage.Movement(MovementCommand.STOP))
        if (role == Role.ROBOT) emergencyRobotStop()
        super.onPause()
    }

    override fun onStop() {
        wifi.unregister()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (role == Role.ROBOT) motorController?.handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && role == Role.ROBOT) emergencyRobotStop()
        if (hasFocus && isEyesFullscreen) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isEyesFullscreen) {
            hideSystemBars()
            robotEyesFull.react(lastRobotCommand)
        }
    }

    override fun onDestroy() {
        stopHeartbeatAndWatchdog()
        emergencyRobotStop()
        socketLink.close()
        releaseControllerResources()
        releaseRobotResources()
        clearRemoteVideoFrame()
        super.onDestroy()
    }

    private fun bindViews() {
        txtStatus = findViewById(R.id.txtStatus)
        txtWifiState = findViewById(R.id.txtWifiState)
        txtUsbState = findViewById(R.id.txtUsbState)
        txtVideoState = findViewById(R.id.txtVideoState)
        txtDetectionState = findViewById(R.id.txtDetectionState)
        txtPeerCount = findViewById(R.id.txtPeerCount)
        txtRobotHint = findViewById(R.id.txtRobotHint)
        txtDetectionSummary = findViewById(R.id.txtDetectionSummary)
        controllerPanel = findViewById(R.id.controllerPanel)
        robotPanel = findViewById(R.id.robotPanel)
        spinnerPeers = findViewById(R.id.spinnerPeers)
        btnConnectPeer = findViewById(R.id.btnConnectPeer)
        btnDisconnectPeer = findViewById(R.id.btnDisconnectPeer)
        btnControllerMode = findViewById(R.id.btnControllerMode)
        btnRobotMode = findViewById(R.id.btnRobotMode)
        btnRobotTests = findViewById(R.id.btnRobotTests)
        robotTestControls = findViewById(R.id.robotTestControls)
        robotEyes = findViewById(R.id.robotEyes)
        robotEyesFull = findViewById(R.id.robotEyesFull)
        fullEyesOverlay = findViewById(R.id.fullEyesOverlay)
        imgRemoteCamera = findViewById(R.id.imgRemoteCamera)
        detectionOverlay = findViewById(R.id.detectionOverlay)
        switchRemoteDetection = findViewById(R.id.switchRemoteDetection)
    }

    private fun bindUi() {
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { returnToMain() }
        btnControllerMode.setOnClickListener { selectRole(Role.CONTROLLER) }
        btnRobotMode.setOnClickListener { selectRole(Role.ROBOT) }
        findViewById<Button>(R.id.btnDiscover).setOnClickListener { ensurePermissionsAndDiscover(false) }
        btnConnectPeer.setOnClickListener { connectSelectedPeer() }
        btnDisconnectPeer.setOnClickListener { disconnectPeer() }
        findViewById<Button>(R.id.btnFullEyes).setOnClickListener { enterEyesFullscreen() }
        findViewById<Button>(R.id.btnExitFullEyes).setOnClickListener { exitEyesFullscreen() }
        btnRobotTests.setOnClickListener { setRobotTestMode(!isRobotTestMode) }
        switchRemoteDetection.setOnCheckedChangeListener { _, enabled ->
            if (!updatingDetectionSwitches && role == Role.CONTROLLER) requestRemoteDetection(enabled)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isEyesFullscreen) {
                    exitEyesFullscreen()
                } else {
                    returnToMain()
                }
            }
        })
        bindMovementButton(R.id.btnForward, MovementCommand.FORWARD)
        bindMovementButton(R.id.btnBackward, MovementCommand.BACKWARD)
        bindMovementButton(R.id.btnLeft, MovementCommand.LEFT)
        bindMovementButton(R.id.btnRight, MovementCommand.RIGHT)
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            sendRemoteMessage(RemoteMessage.Movement(MovementCommand.STOP))
        }
        bindRobotTestMovementButton(R.id.btnTestForward, MovementCommand.FORWARD)
        bindRobotTestMovementButton(R.id.btnTestBackward, MovementCommand.BACKWARD)
        bindRobotTestMovementButton(R.id.btnTestLeft, MovementCommand.LEFT)
        bindRobotTestMovementButton(R.id.btnTestRight, MovementCommand.RIGHT)
        findViewById<Button>(R.id.btnTestStop).setOnClickListener {
            runRobotTestCommand(MovementCommand.STOP)
        }
        findViewById<Button>(R.id.btnStartVideo).setOnClickListener { startControllerVideo() }
        findViewById<Button>(R.id.btnStopVideo).setOnClickListener { stopControllerVideo() }
    }

    private fun returnToMain() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun bindMovementButton(id: Int, command: MovementCommand) {
        findViewById<Button>(id).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> sendRemoteMessage(RemoteMessage.Movement(command))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sendRemoteMessage(RemoteMessage.Movement(MovementCommand.STOP))
            }
            true
        }
    }

    private fun bindRobotTestMovementButton(id: Int, command: MovementCommand) {
        findViewById<Button>(id).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> runRobotTestCommand(command)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> runRobotTestCommand(MovementCommand.STOP)
            }
            true
        }
    }

    private fun selectRole(newRole: Role) {
        if (role == newRole) return
        val previousRole = role
        if (previousRole == Role.ROBOT) emergencyRobotStop()
        stopNetworkServices()
        releaseRoleResources(previousRole)
        role = newRole
        controllerPanel.visibility = if (newRole == Role.CONTROLLER) View.VISIBLE else View.GONE
        robotPanel.visibility = if (newRole == Role.ROBOT) View.VISIBLE else View.GONE
        updateRoleButtons()
        setDetectionState(false, false)
        if (newRole == Role.ROBOT) {
            ensureRobotResources()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            txtRobotHint.text = "Esperando controlador"
            motorController?.prepare()
            motorController?.send(MovementCommand.STOP)
            setStatus("Modo robot activo.")
            ensurePermissionsAndDiscover(true)
        } else {
            exitEyesFullscreen()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setStatus("Modo controlador activo.")
            ensurePermissionsAndDiscover(false)
        }
    }

    private fun ensureControllerResources(): RemoteFrameReceiver {
        return videoReceiver ?: RemoteFrameReceiver(::setStatus, ::showRemoteFrame).also {
            videoReceiver = it
        }
    }

    private fun ensureRobotResources() {
        if (motorController == null) motorController = UsbKeyboardLedMotorController(this, ::setStatus)
    }

    private fun ensureRobotVideoResources(): Pair<VideoFrameSender, RemoteCameraStreamer> {
        val sender = frameSender ?: VideoFrameSender(::setStatus).also { frameSender = it }
        val streamer = cameraStreamer ?: RemoteCameraStreamer(this, this, ::setStatus).also {
            cameraStreamer = it
        }
        return sender to streamer
    }

    private fun releaseRoleResources(releasedRole: Role) {
        when (releasedRole) {
            Role.CONTROLLER -> releaseControllerResources()
            Role.ROBOT -> releaseRobotResources()
            Role.NONE -> Unit
        }
    }

    private fun releaseControllerResources() {
        videoReceiver?.stop()
        videoReceiver = null
        clearRemoteVideoFrame()
    }

    private fun releaseRobotResources() {
        releaseRobotVideoResources()
        motorController?.close()
        motorController = null
    }

    private fun releaseRobotVideoResources() {
        cameraStreamer?.close()
        cameraStreamer = null
        frameSender?.stop()
        frameSender = null
    }

    private fun connectSelectedPeer() {
        val device = peers.getOrNull(spinnerPeers.selectedItemPosition)
        if (device == null) {
            setStatus("Busca un dispositivo primero.")
            return
        }
        wifi.connectTo(device)
    }

    private fun disconnectPeer() {
        if (latestConnectionInfo == null) {
            setStatus("No hay ningún teléfono conectado.")
            return
        }
        sendRemoteMessage(RemoteMessage.Movement(MovementCommand.STOP))
        sendRemoteMessage(RemoteMessage.VideoOff)
        btnDisconnectPeer.isEnabled = false
        txtWifiState.setChip("Wi‑Fi", ChipState.BUSY)
        mainHandler.postDelayed({ wifi.removeGroup() }, DISCONNECT_STOP_GRACE_MS)
    }

    private fun startControllerVideo() {
        val info = latestConnectionInfo
        if (role != Role.CONTROLLER || info == null || !socketLink.isConnected()) {
            setStatus("Conecta primero con el robot.")
            return
        }
        ensureControllerResources().start(info)
        videoRunning = true
        txtVideoState.setChip("Video", ChipState.BUSY)
        sendRemoteMessage(RemoteMessage.VideoOn)
    }

    private fun stopControllerVideo() {
        if (role == Role.CONTROLLER) sendRemoteMessage(RemoteMessage.VideoOff)
        videoRunning = false
        releaseControllerResources()
        txtVideoState.setChip("Video", ChipState.IDLE)
        setStatus("Video detenido.")
    }

    private fun startRobotVideoIfReady() {
        val info = latestConnectionInfo ?: run {
            pendingVideoStart = false
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingVideoStart = true
            ensurePermissionsAndDiscover(true)
            return
        }
        pendingVideoStart = false
        val (sender, streamer) = ensureRobotVideoResources()
        sender.start(info)
        streamer.setDetectionEnabled(remoteDetectionEnabled)
        streamer.start(sender)
        videoRunning = true
        txtRobotHint.text = "Transmitiendo video"
        txtVideoState.setChip("Video", ChipState.BUSY)
    }

    private fun stopRobotVideo() {
        pendingVideoStart = false
        videoRunning = false
        releaseRobotVideoResources()
        txtRobotHint.text = "Video detenido"
        txtVideoState.setChip("Video", ChipState.IDLE)
    }

    private fun requestRemoteDetection(enabled: Boolean) {
        if (!socketLink.isConnected()) {
            setDetectionState(false, false)
            setStatus("Conecta con el robot antes de cambiar la detección.")
            return
        }
        txtDetectionState.setChip("Detección", ChipState.BUSY)
        sendRemoteMessage(if (enabled) RemoteMessage.DetectionOn else RemoteMessage.DetectionOff)
    }

    private fun setDetectionState(enabled: Boolean, notifyController: Boolean) {
        remoteDetectionEnabled = enabled
        if (role == Role.ROBOT) cameraStreamer?.setDetectionEnabled(enabled)
        updatingDetectionSwitches = true
        switchRemoteDetection.isChecked = enabled
        updatingDetectionSwitches = false
        txtDetectionState.setChip(
            if (enabled) "Detección on" else "Detección off",
            if (enabled) ChipState.OK else ChipState.IDLE
        )
        if (!enabled) {
            detectionOverlay.clear()
            txtDetectionSummary.text = "Detección desactivada"
        } else {
            txtDetectionSummary.text = "Buscando objetos"
        }
        if (notifyController && socketLink.isConnected()) {
            sendRemoteMessage(RemoteMessage.DetectionState(enabled))
        }
    }

    private fun onSocketMessage(message: String) {
        val remoteMessage = RemoteMessage.fromWire(message) ?: return
        when (role) {
            Role.ROBOT -> handleRobotMessage(remoteMessage)
            Role.CONTROLLER -> if (remoteMessage is RemoteMessage.DetectionState) {
                setDetectionState(remoteMessage.enabled, false)
            }
            Role.NONE -> Unit
        }
    }

    private fun handleRobotMessage(message: RemoteMessage) {
        lastHeartbeatAt = SystemClock.elapsedRealtime()
        when (message) {
            is RemoteMessage.Movement -> handleRobotMovement(message.command)
            RemoteMessage.VideoOn -> {
                pendingVideoStart = true
                startRobotVideoIfReady()
            }
            RemoteMessage.VideoOff -> stopRobotVideo()
            RemoteMessage.DetectionOn -> setDetectionState(true, true)
            RemoteMessage.DetectionOff -> setDetectionState(false, true)
            is RemoteMessage.DetectionState -> Unit
            RemoteMessage.Ping -> Unit
        }
    }

    private fun handleRobotMovement(command: MovementCommand) {
        lastRobotCommand = command
        robotEyes.react(command)
        robotEyesFull.react(command)
        txtRobotHint.text = command.displayName
        val controller = motorController ?: return
        if (!controller.isReady()) controller.prepare()
        if (!controller.send(command) && command != MovementCommand.STOP) {
            setStatus("No se pudo enviar el movimiento al HID.")
        } else {
            setStatus("Comando: ${command.displayName}")
        }
    }

    private fun emergencyRobotStop(message: String? = null) {
        val controller = motorController ?: return
        lastRobotCommand = MovementCommand.STOP
        controller.send(MovementCommand.STOP)
        if (::robotEyes.isInitialized) robotEyes.react(MovementCommand.STOP)
        if (::robotEyesFull.isInitialized) robotEyesFull.react(MovementCommand.STOP)
        if (::txtRobotHint.isInitialized && role == Role.ROBOT) txtRobotHint.text = "Detenido"
        if (message != null) setStatus(message)
    }

    private fun setRobotTestMode(enabled: Boolean) {
        isRobotTestMode = enabled && role == Role.ROBOT
        robotTestControls.visibility = if (isRobotTestMode) View.VISIBLE else View.GONE
        btnRobotTests.text = if (isRobotTestMode) "Ocultar controles de prueba" else "Pruebas sin conexión"
        if (!isRobotTestMode) emergencyRobotStop()
    }

    private fun runRobotTestCommand(command: MovementCommand) {
        if (role == Role.ROBOT && isRobotTestMode) handleRobotMovement(command)
    }

    private fun enterEyesFullscreen() {
        if (role != Role.ROBOT) return
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
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun ensurePermissionsAndDiscover(includeCamera: Boolean) {
        val required = buildList {
            if (includeCamera) add(Manifest.permission.CAMERA)
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
            if (pendingVideoStart && role == Role.ROBOT) startRobotVideoIfReady()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun sendRemoteMessage(message: RemoteMessage): Boolean {
        if (role == Role.NONE || !socketLink.sendLine(message.toWire())) {
            if (message !is RemoteMessage.Ping) setStatus("Aún no hay socket de comandos.")
            return false
        }
        return true
    }

    private fun onSocketConnectionChanged(connected: Boolean) {
        if (connected) {
            txtWifiState.setChip("Wi‑Fi listo", ChipState.OK)
            if (role == Role.CONTROLLER) {
                setDetectionState(false, false)
                sendRemoteMessage(RemoteMessage.DetectionOff)
                startHeartbeat()
            } else if (role == Role.ROBOT) {
                lastHeartbeatAt = SystemClock.elapsedRealtime()
                setDetectionState(false, true)
                startWatchdog()
            }
        } else {
            stopHeartbeatAndWatchdog()
            if (role == Role.ROBOT) emergencyRobotStop("Socket de comandos desconectado.")
            txtWifiState.setChip("Wi‑Fi", ChipState.IDLE)
        }
    }

    private fun startHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.post(heartbeatRunnable)
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.post(watchdogRunnable)
    }

    private fun stopHeartbeatAndWatchdog() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    private fun showRemoteFrame(frame: RemoteVideoFrame) {
        val previous = remoteBitmap
        remoteBitmap = frame.bitmap
        imgRemoteCamera.setImageBitmap(frame.bitmap)
        if (frame.detectionEnabled) {
            detectionOverlay.setFrame(frame.detections, frame.bitmap.width, frame.bitmap.height)
            val detection = frame.detections.firstOrNull()
            txtDetectionSummary.text = if (detection == null) {
                "Sin objetos detectados"
            } else {
                val confidence = if (detection.confidence > 0f) {
                    " · ${(detection.confidence * 100f).roundToInt()}%"
                } else {
                    ""
                }
                "${detection.label} · ${detection.proximity.text}$confidence"
            }
        } else {
            detectionOverlay.clear()
            txtDetectionSummary.text = "Detección desactivada"
        }
        if (previous !== frame.bitmap && previous?.isRecycled == false) previous.recycle()
    }

    override fun onStatus(message: String) = setStatus(message)

    override fun onPeersChanged(peers: List<WifiP2pDevice>) {
        this.peers = peers
        updatePeerSelector()
        val count = peers.size
        txtPeerCount.text = if (count == 1) "1 dispositivo encontrado" else "$count dispositivos encontrados"
        if (role == Role.CONTROLLER) setStatus(if (count == 0) "Sin dispositivos encontrados." else "Selecciona un dispositivo.")
    }

    override fun onConnectionReady(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        latestConnectionInfo = info
        btnDisconnectPeer.isEnabled = true
        val owner = if (info.isGroupOwner) "GO local" else "GO ${info.groupOwnerAddress.hostAddress}"
        setStatus("Wi‑Fi Direct conectado · $owner")
        txtWifiState.setChip("Wi‑Fi", ChipState.BUSY)
        socketLink.start(info)
        showNotice("Conexión lista")
    }

    override fun onDisconnected() {
        latestConnectionInfo = null
        stopNetworkServices()
        btnDisconnectPeer.isEnabled = false
        emergencyRobotStop()
        txtWifiState.setChip("Wi‑Fi", ChipState.IDLE)
        txtVideoState.setChip("Video", ChipState.IDLE)
        setDetectionState(false, false)
        setStatus("Wi‑Fi Direct desconectado.")
        showNotice("Desconectado")
    }

    private fun stopNetworkServices() {
        stopHeartbeatAndWatchdog()
        socketLink.stop()
        releaseControllerResources()
        releaseRobotVideoResources()
        videoRunning = false
    }

    private fun clearRemoteVideoFrame() {
        imgRemoteCamera.setImageDrawable(null)
        detectionOverlay.clear()
        remoteBitmap?.takeIf { !it.isRecycled }?.recycle()
        remoteBitmap = null
        txtDetectionSummary.text = if (remoteDetectionEnabled) "Buscando objetos" else "Detección desactivada"
    }

    private fun updatePeerSelector() {
        val names = if (peers.isEmpty()) {
            listOf("Sin dispositivos")
        } else {
            peers.map { device -> device.deviceName?.ifBlank { "Dispositivo sin nombre" } ?: "Dispositivo sin nombre" }
        }
        spinnerPeers.adapter = ArrayAdapter(this, R.layout.item_spinner_peer, names).apply {
            setDropDownViewResource(R.layout.item_spinner_peer_dropdown)
        }
        btnConnectPeer.isEnabled = peers.isNotEmpty()
        btnDisconnectPeer.isEnabled = latestConnectionInfo != null
        txtPeerCount.text = if (peers.isEmpty()) "Sin dispositivos" else "${peers.size} disponibles"
    }

    private fun resetIndicators() {
        txtWifiState.setChip("Wi‑Fi", ChipState.IDLE)
        txtUsbState.setChip("USB", ChipState.IDLE)
        txtVideoState.setChip("Video", ChipState.IDLE)
        txtDetectionState.setChip("Detección off", ChipState.IDLE)
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
            "teclado usb listo" in lower -> txtUsbState.setChip("USB listo", ChipState.OK)
            "solicitando permiso usb" in lower -> txtUsbState.setChip("USB permiso", ChipState.BUSY)
            "teclado usb desconectado" in lower || "no hay usb enumerado" in lower -> txtUsbState.setChip("USB", ChipState.IDLE)
            "permiso usb denegado" in lower || "reporte hid" in lower || "no expone teclado hid" in lower -> txtUsbState.setChip("USB error", ChipState.ERROR)
        }
        when {
            "wi‑fi direct conectado" in lower || "socket de comandos conectado" in lower -> txtWifiState.setChip("Wi‑Fi listo", ChipState.OK)
            "conectando" in lower || "buscando" in lower || "búsqueda" in lower || "esperando socket" in lower -> txtWifiState.setChip("Wi‑Fi", ChipState.BUSY)
            "wi‑fi direct desconectado" in lower || "wi‑fi direct disponible" in lower -> txtWifiState.setChip("Wi‑Fi", ChipState.IDLE)
            "wi‑fi direct desactivado" in lower || "falló conexión" in lower || "error en socket" in lower -> txtWifiState.setChip("Wi‑Fi error", ChipState.ERROR)
        }
        when {
            "video: recibiendo" in lower || "video: enlace listo" in lower || "cámara frontal remota activa" in lower || "cámara trasera remota activa" in lower -> txtVideoState.setChip("Video listo", ChipState.OK)
            "video: esperando" in lower || "video: conectando" in lower -> txtVideoState.setChip("Video", ChipState.BUSY)
            "video detenido" in lower -> txtVideoState.setChip("Video", ChipState.IDLE)
            "video: error" in lower || "no se pudo iniciar cámara remota" in lower -> txtVideoState.setChip("Video error", ChipState.ERROR)
        }
        when {
            "detección remota habilitada" in lower -> txtDetectionState.setChip("Detección on", ChipState.OK)
            "detección remota deshabilitada" in lower -> txtDetectionState.setChip("Detección off", ChipState.IDLE)
            "detección remota:" in lower -> txtDetectionState.setChip("Detección error", ChipState.ERROR)
        }
    }

    private fun showNotice(message: String) {
        if (lastNotice == message) return
        lastNotice = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun color(resourceId: Int): Int = ContextCompat.getColor(this, resourceId)

    private companion object {
        private const val KEY_ROLE = "role"
        private const val KEY_EYES_FULLSCREEN = "eyesFullscreen"
        private const val KEY_ROBOT_TEST_MODE = "robotTestMode"
        private const val DISCONNECT_STOP_GRACE_MS = 180L
        private const val HEARTBEAT_INTERVAL_MS = 650L
        private const val HEARTBEAT_TIMEOUT_MS = 1800L
        private const val WATCHDOG_INTERVAL_MS = 300L
    }
}
