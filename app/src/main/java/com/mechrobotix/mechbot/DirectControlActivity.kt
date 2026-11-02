package com.mechrobotix.mechbot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class DirectControlActivity : AppCompatActivity() {
    private enum class ChipState { IDLE, BUSY, OK, ERROR }

    private lateinit var previewView: PreviewView
    private lateinit var detectionOverlay: DetectionOverlayView
    private lateinit var txtStatus: TextView
    private lateinit var txtUsbState: TextView
    private lateinit var txtCameraState: TextView
    private lateinit var txtDetectionState: TextView
    private lateinit var txtDetectionSummary: TextView
    private lateinit var txtMovement: TextView
    private lateinit var switchDetection: SwitchMaterial
    private lateinit var btnForward: MaterialButton
    private lateinit var btnBackward: MaterialButton
    private lateinit var btnLeft: MaterialButton
    private lateinit var btnRight: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnVoiceCommand: MaterialButton
    private lateinit var btnBciConnect: MaterialButton

    private lateinit var motorController: UsbKeyboardLedMotorController
    private lateinit var cameraController: LocalCameraController
    private lateinit var voiceCommandHelper: VoiceCommandHelper
    private lateinit var bciManager: BciBluetoothManager
    private lateinit var radarView: RadarView

    private var activeMovementButtonId = View.NO_ID
    private var cameraPermissionRequestInProgress = false
    private var lastVibrationTime = 0L
    private var bciEnabled = false
    private val spatialEngine = SpatialNavigationEngine()
    private var lastSpatialResult: SpatialAnalysisResult? = null

    // Movimiento iniciado por voz: dura poco y el freno autónomo también lo detiene
    private var voiceMovementActive = false
    private var voiceMovementCommand: MovementCommand? = null
    private val voiceMoveDurationMs = 2000L
    private val voiceStopRunnable = Runnable {
        cameraController.setCameraLens(false)
        emergencyStop("Movimiento por voz finalizado")
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionRequestInProgress = false
        if (granted) {
            cameraController.start(previewView)
        } else {
            setStatus("Permiso de cámara denegado.")
            txtCameraState.setChip("Cámara error", ChipState.ERROR)
            txtDetectionState.setChip("Detección", ChipState.IDLE)
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            activateVoiceListening()
        } else {
            setStatus("Permiso de micrófono denegado para comandos de voz.")
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            showBciDevicePicker()
        } else {
            setStatus("Permisos de Bluetooth denegados.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_direct_control)
        applySystemBarInsets()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindViews()

        motorController = UsbKeyboardLedMotorController(this, ::handleComponentStatus)
        cameraController = LocalCameraController(
            context = this,
            lifecycleOwner = this,
            onStatus = ::handleComponentStatus,
            onDetections = ::showDetections
        )

        voiceCommandHelper = VoiceCommandHelper(
            context = this,
            onCommandDetected = { command -> executeVoiceCommand(command) },
            onErrorOrStatus = { statusMessage -> setStatus(statusMessage) }
        )

        bciManager = BciBluetoothManager(
            context = this,
            onBrainCommandDetected = { command, confidence -> executeBrainCommand(command, confidence) },
            onStatusMessage = { status -> setStatus(status) }
        )

        switchDetection.isChecked = true
        bindControls()
        resetIndicators()
        cameraController.setDetectionEnabled(true)
        motorController.handleIntent(intent)
        motorController.prepare()
        ensureCameraPermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        motorController.prepare()
        ensureCameraPermissionAndStart()
    }

    override fun onPause() {
        emergencyStop()
        voiceCommandHelper.stopListening()
        updateVoiceButton()
        bciManager.disconnect()
        super.onPause()
    }

    override fun onStop() {
        emergencyStop()
        cameraController.stop()
        voiceCommandHelper.stopListening()
        updateVoiceButton()
        bciManager.disconnect()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        clearActiveMovement()
        motorController.handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) emergencyStop()
    }

    override fun onDestroy() {
        emergencyStop()
        voiceCommandHelper.destroy()
        bciManager.disconnect()
        cameraController.close()
        motorController.close()
        super.onDestroy()
    }

    // La pantalla se dibuja bajo la barra de estado: se suma su alto al relleno del layout
    private fun applySystemBarInsets() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        detectionOverlay = findViewById(R.id.detectionOverlay)
        txtStatus = findViewById(R.id.txtStatus)
        txtUsbState = findViewById(R.id.txtUsbState)
        txtCameraState = findViewById(R.id.txtCameraState)
        txtDetectionState = findViewById(R.id.txtDetectionState)
        txtDetectionSummary = findViewById(R.id.txtDetectionSummary)
        txtMovement = findViewById(R.id.txtMovement)
        switchDetection = findViewById(R.id.switchDetection)
        btnForward = findViewById(R.id.btnForward)
        btnBackward = findViewById(R.id.btnBackward)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        btnStop = findViewById(R.id.btnStop)
        btnVoiceCommand = findViewById(R.id.btnVoiceCommand)
        btnBciConnect = findViewById(R.id.btnBciConnect)
        radarView = findViewById(R.id.radarView)
    }

    private fun bindControls() {
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { returnToMain() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnToMain()
        })

        switchDetection.setOnCheckedChangeListener { _, enabled ->
            cameraController.setDetectionEnabled(enabled)
            if (enabled) {
                txtDetectionState.setChip("Detección", ChipState.BUSY)
                txtDetectionSummary.text = "Buscando objetos"
            } else {
                detectionOverlay.clear()
                txtDetectionState.setChip("Detección off", ChipState.IDLE)
                txtDetectionSummary.text = "Detección desactivada"
            }
        }

        bindMovementButton(btnForward, MovementCommand.FORWARD)
        bindMovementButton(btnBackward, MovementCommand.BACKWARD)
        bindMovementButton(btnLeft, MovementCommand.LEFT)
        bindMovementButton(btnRight, MovementCommand.RIGHT)

        btnStop.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> emergencyStop("STOP activado.")
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearActiveMovement()
            }
            true
        }
        btnStop.setOnClickListener { emergencyStop("STOP activado.") }

        btnVoiceCommand.setOnClickListener {
            if (voiceCommandHelper.isContinuousMode) {
                voiceCommandHelper.setContinuous(false)
                updateVoiceButton()
            } else {
                ensureAudioPermissionAndListen()
            }
        }
        btnBciConnect.setOnClickListener { ensureBluetoothPermissionAndPickDevice() }
    }

    private fun ensureAudioPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            activateVoiceListening()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun activateVoiceListening() {
        voiceCommandHelper.setContinuous(true)
        updateVoiceButton()
    }

    private fun updateVoiceButton() {
        val on = voiceCommandHelper.isContinuousMode
        btnVoiceCommand.backgroundTintList = ColorStateList.valueOf(
            color(if (on) R.color.accent else R.color.accent_dark)
        )
        btnVoiceCommand.setTextColor(color(if (on) R.color.bg_dark else R.color.text_primary))
    }

    private fun ensureBluetoothPermissionAndPickDevice() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            showBciDevicePicker()
        } else {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showBciDevicePicker() {
        val pairedDevices = bciManager.getPairedDevices()
        if (pairedDevices.isEmpty()) {
            setStatus("No hay dispositivos Bluetooth emparejados. Vincula la ESP32 en Ajustes.")
            return
        }

        val deviceNames = pairedDevices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Seleccionar Casco EEG (ESP32)")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = pairedDevices[which]
                bciEnabled = true
                bciManager.connectToDevice(selectedDevice)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun executeBrainCommand(command: MovementCommand, confidenceInfo: String) {
        if (!bciEnabled) return

        // Filtro de seguridad autónomo para comandos BCI
        if (lastSpatialResult?.action == NavigationAction.FRENAR && command == MovementCommand.FORWARD) {
            emergencyStop("BCI Bloqueado: Obstáculo cercano al frente. Comando '$command' rechazado.")
            return
        }

        cameraController.setCameraLens(command == MovementCommand.BACKWARD)
        txtMovement.text = "BCI: ${command.displayName}$confidenceInfo"

        if (motorController.isReady()) {
            motorController.send(command)
        } else {
            setStatus("BCI (${command.displayName}): Teclado USB no conectado.")
        }
    }

    private fun executeVoiceCommand(command: MovementCommand) {
        // STOP por voz siempre se obedece
        if (command == MovementCommand.STOP) {
            cameraController.setCameraLens(false)
            emergencyStop("STOP por voz")
            return
        }

        if (lastSpatialResult?.action == NavigationAction.FRENAR && command == MovementCommand.FORWARD) {
            emergencyStop("Voz Bloqueada: Obstáculo cercano al frente. Comando '$command' rechazado.")
            return
        }

        cameraController.setCameraLens(command == MovementCommand.BACKWARD)
        txtMovement.text = "Voz: ${command.displayName}"
        voiceMovementActive = true
        voiceMovementCommand = command
        txtMovement.removeCallbacks(voiceStopRunnable)
        txtMovement.postDelayed(voiceStopRunnable, voiceMoveDurationMs)
        if (motorController.isReady()) {
            motorController.send(command)
            setStatus("Comando de voz enviado al HID: ${command.displayName}")
        } else {
            setStatus("Voz reconocida: ${command.displayName}")
        }
    }

    private fun returnToMain() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun bindMovementButton(button: MaterialButton, command: MovementCommand) {
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (activeMovementButtonId != View.NO_ID) return@setOnTouchListener true
                    activeMovementButtonId = button.id
                    updateDirectionButtons()
                    cameraController.setCameraLens(command == MovementCommand.BACKWARD)
                    txtMovement.text = command.displayName
                    if (motorController.isReady()) {
                        motorController.send(command)
                        setStatus("Movimiento: ${command.displayName}")
                    } else {
                        setStatus("Movimiento manual: ${command.displayName}")
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeMovementButtonId == button.id) {
                        cameraController.setCameraLens(false)
                        emergencyStop()
                    }
                }
            }
            true
        }
    }

    private fun emergencyStop(message: String? = null) {
        if (motorController.isReady()) {
            motorController.send(MovementCommand.STOP)
        }
        clearActiveMovement()
        if (message != null) setStatus(message)
    }

    private fun clearActiveMovement() {
        activeMovementButtonId = View.NO_ID
        voiceMovementActive = false
        voiceMovementCommand = null
        txtMovement.removeCallbacks(voiceStopRunnable)
        txtMovement.text = "Detenido"
        updateDirectionButtons()
    }

    private fun updateDirectionButtons() {
        listOf(btnForward, btnBackward, btnLeft, btnRight).forEach { button ->
            val active = button.id == activeMovementButtonId
            button.backgroundTintList = ColorStateList.valueOf(
                color(if (active) R.color.accent else R.color.accent_dark)
            )
            button.setTextColor(color(if (active) R.color.bg_dark else R.color.text_primary))
        }
    }

    private fun ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraController.start(previewView)
        } else if (!cameraPermissionRequestInProgress) {
            cameraPermissionRequestInProgress = true
            txtCameraState.setChip("Cámara permiso", ChipState.BUSY)
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showDetections(detections: List<DetectionResult>, imageWidth: Int, imageHeight: Int) {
        if (!switchDetection.isChecked || imageWidth <= 0 || imageHeight <= 0) {
            detectionOverlay.clear()
            txtDetectionSummary.text = if (!switchDetection.isChecked) "Detección desactivada" else "Buscando objetos"
            radarView.updateSpatialData(SpatialAnalysisResult(NavigationAction.AVANZAR, "Libre", 0f, false))
            lastSpatialResult = null
            return
        }

        detectionOverlay.setFrame(detections, imageWidth, imageHeight)
        val spatialResult = spatialEngine.analyze(detections)
        lastSpatialResult = spatialResult
        radarView.updateSpatialData(spatialResult)

        if (spatialResult.distanceMeters != null) {
            val distStr = String.format("%.2fm", spatialResult.distanceMeters)
            val speedStr = spatialResult.closingSpeedMps?.let { spd ->
                if (spd > 0.1f) String.format(" · Acercándose a %.1fm/s", spd) else ""
            } ?: ""
            txtDetectionSummary.text = "${spatialResult.label ?: "Objeto"} · $distStr$speedStr · Sector: ${spatialResult.primaryZone}"
        } else if (detections.isEmpty()) {
            txtDetectionSummary.text = "Camino libre"
        } else {
            txtDetectionSummary.text = "Objeto detectado fuera de rango confiable"
        }

        if (spatialResult.action == NavigationAction.FRENAR) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVibrationTime >= 800L) {
                lastVibrationTime = now
                triggerObstacleVibration()
            }

            val movingForward = activeMovementButtonId == R.id.btnForward ||
                    (voiceMovementActive && voiceMovementCommand == MovementCommand.FORWARD)
            if (movingForward) {
                emergencyStop("Detenido por objeto cercano. Gire o retroceda.")
            }
        }
    }

    private fun triggerObstacleVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200L)
            }
        }
    }

    private fun resetIndicators() {
        txtUsbState.setChip("USB", ChipState.IDLE)
        txtCameraState.setChip("Cámara", ChipState.IDLE)
        txtDetectionState.setChip("Detección", ChipState.BUSY)
        txtMovement.text = "Detenido"
        updateDirectionButtons()
        updateVoiceButton()
    }

    private fun handleComponentStatus(message: String) {
        runOnUiThread {
            txtStatus.text = message
            val lower = message.lowercase()
            when {
                "teclado usb listo" in lower -> txtUsbState.setChip("USB listo", ChipState.OK)
                "solicitando permiso usb" in lower -> txtUsbState.setChip("USB permiso", ChipState.BUSY)
                "teclado usb desconectado" in lower || "no hay usb enumerado" in lower -> txtUsbState.setChip("USB", ChipState.IDLE)
                "permiso usb denegado" in lower || "no se pudo abrir el teclado usb" in lower || "no expone teclado hid" in lower -> txtUsbState.setChip("USB error", ChipState.ERROR)
            }
            when {
                "iniciando cámara" in lower -> txtCameraState.setChip("Cámara", ChipState.BUSY)
                "cámara trasera activa" in lower || "cámara frontal activa" in lower -> txtCameraState.setChip("Cámara lista", ChipState.OK)
                "cámara detenida" in lower -> txtCameraState.setChip("Cámara", ChipState.IDLE)
                "no se pudo iniciar cámara" in lower || "permiso de cámara denegado" in lower -> txtCameraState.setChip("Cámara error", ChipState.ERROR)
            }
            when {
                "detección habilitada" in lower -> txtDetectionState.setChip("Detección", ChipState.BUSY)
                "detección activa" in lower -> txtDetectionState.setChip("Detección lista", ChipState.OK)
                "detección deshabilitada" in lower -> txtDetectionState.setChip("Detección off", ChipState.IDLE)
                "error de detección" in lower -> txtDetectionState.setChip("Detección error", ChipState.ERROR)
            }
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread { txtStatus.text = message }
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

    private fun color(resourceId: Int): Int = ContextCompat.getColor(this, resourceId)
}