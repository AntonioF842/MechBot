package com.mechrobotix.mechbot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

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
    private lateinit var motorController: UsbKeyboardLedMotorController
    private lateinit var cameraController: LocalCameraController
    private var activeMovementButtonId = View.NO_ID
    private var cameraPermissionRequestInProgress = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_direct_control)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindViews()
        motorController = UsbKeyboardLedMotorController(this, ::handleComponentStatus)
        cameraController = LocalCameraController(
            context = this,
            lifecycleOwner = this,
            onStatus = ::handleComponentStatus,
            onDetections = ::showDetections
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
        super.onPause()
    }

    override fun onStop() {
        emergencyStop()
        cameraController.stop()
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
        cameraController.close()
        motorController.close()
        super.onDestroy()
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
                    if (!motorController.isReady()) {
                        motorController.prepare()
                        setStatus("Conecta y autoriza el teclado HID antes de mover el robot.")
                        return@setOnTouchListener true
                    }
                    activeMovementButtonId = button.id
                    updateDirectionButtons()
                    if (motorController.send(command)) {
                        txtMovement.text = command.displayName
                        setStatus("Movimiento: ${command.displayName}")
                    } else {
                        clearActiveMovement()
                        setStatus("No se pudo enviar el movimiento al HID.")
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeMovementButtonId == button.id) emergencyStop()
                }
            }
            true
        }
    }

    private fun emergencyStop(message: String? = null) {
        motorController.send(MovementCommand.STOP)
        clearActiveMovement()
        if (message != null) setStatus(message)
    }

    private fun clearActiveMovement() {
        activeMovementButtonId = View.NO_ID
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
        if (!switchDetection.isChecked) {
            detectionOverlay.clear()
            txtDetectionSummary.text = "Detección desactivada"
            return
        }
        if (imageWidth <= 0 || imageHeight <= 0) {
            detectionOverlay.clear()
            txtDetectionSummary.text = "Buscando objetos"
            return
        }
        detectionOverlay.setFrame(detections, imageWidth, imageHeight)
        val detection = detections.firstOrNull()
        if (detection == null) {
            txtDetectionSummary.text = "Sin objetos detectados"
        } else {
            val confidence = if (detection.confidence > 0f) {
                " · ${(detection.confidence * 100f).roundToInt()}%"
            } else {
                ""
            }
            txtDetectionSummary.text = "${detection.label} · ${detection.proximity.text}$confidence"
        }
    }

    private fun resetIndicators() {
        txtUsbState.setChip("USB", ChipState.IDLE)
        txtCameraState.setChip("Cámara", ChipState.IDLE)
        txtDetectionState.setChip("Detección", ChipState.BUSY)
        txtMovement.text = "Detenido"
        updateDirectionButtons()
    }

    private fun handleComponentStatus(message: String) {
        runOnUiThread {
            txtStatus.text = message
            val lower = message.lowercase()
            when {
                "teclado usb listo" in lower -> txtUsbState.setChip("USB listo", ChipState.OK)
                "solicitando permiso usb" in lower -> txtUsbState.setChip("USB permiso", ChipState.BUSY)
                "teclado usb desconectado" in lower || "no hay usb enumerado" in lower -> {
                    txtUsbState.setChip("USB", ChipState.IDLE)
                    clearActiveMovement()
                }
                "permiso usb denegado" in lower ||
                    "no se pudo abrir el teclado usb" in lower ||
                    "no se pudo tomar control" in lower ||
                    "no expone teclado hid" in lower ||
                    "pero no teclado hid" in lower ||
                    "no declara soporte usb host" in lower ||
                    "no se pudo enviar reporte hid" in lower -> {
                    txtUsbState.setChip("USB error", ChipState.ERROR)
                    clearActiveMovement()
                }
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
                "cámara detenida" in lower -> txtDetectionState.setChip(
                    if (switchDetection.isChecked) "Detección" else "Detección off",
                    ChipState.IDLE
                )
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
