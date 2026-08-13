package com.mechrobotix.mechbot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.Parcelable
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

class UsbKeyboardLedMotorController(
    context: Context,
    private val onStatus: (String) -> Unit
) : MotorController {

    private data class Target(
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val outEndpoint: UsbEndpoint?,
        val priority: Int
    ) {
        val isBootKeyboard: Boolean
            get() = usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                usbInterface.interfaceSubclass == USB_INTERFACE_SUBCLASS_BOOT &&
                usbInterface.interfaceProtocol == USB_INTERFACE_PROTOCOL_KEYBOARD
    }

    private enum class ClaimMode {
        NONE,
        NORMAL,
        FORCED,
        CONTROL_ONLY
    }

    private enum class ControlReportFormat {
        ID_0,
        ID_1,
        ID_1_PREFIXED
    }

    private enum class EndpointReportFormat {
        RAW,
        ID_1_PREFIXED
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    private val permissionIntent: PendingIntent
    private var connection: UsbDeviceConnection? = null
    private var keyboardInterface: UsbInterface? = null
    private var interruptOutEndpoint: UsbEndpoint? = null
    private var connectedDeviceId: Int? = null
    private var claimMode = ClaimMode.NONE
    private var preferredControlReport: ControlReportFormat? = null
    private var preferredEndpointReport: EndpointReportFormat? = null
    private var lastMask = 0
    private var permissionRequestDeviceId: Int? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.parcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = device != null && (
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                            usbManager.hasPermission(device)
                        )
                    synchronized(lock) {
                        permissionRequestDeviceId = null
                        if (device != null && granted) {
                            lastMask = 0
                            closeLocked()
                            if (connectLocked(device)) sendReportLocked(0)
                        } else {
                            reportStatus("Permiso USB denegado.", Log.WARN)
                        }
                        Unit
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleIntent(intent)

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.parcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    synchronized(lock) {
                        if (device != null && device.deviceId == permissionRequestDeviceId) {
                            permissionRequestDeviceId = null
                        }
                        if (device != null && device.deviceId == connectedDeviceId) {
                            lastMask = 0
                            closeLocked()
                            reportStatus("Teclado USB desconectado.")
                        }
                        Unit
                    }
                }
            }
        }
    }

    init {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        permissionIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        registerReceiver()
    }

    override fun prepare() {
        synchronized(lock) {
            lastMask = 0
            if (connection == null) {
                if (connectOrRequestLocked()) sendReportLocked(0)
            } else {
                sendReportLocked(0)
            }
            Unit
        }
    }

    fun handleIntent(intent: Intent?) {
        val device = intent?.parcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
        synchronized(lock) {
            lastMask = 0
            if (device != null) {
                if (connection != null && connectedDeviceId == device.deviceId) {
                    sendReportLocked(0)
                } else {
                    closeLocked()
                    if (connectLocked(device)) sendReportLocked(0)
                }
            } else if (connection == null) {
                if (connectOrRequestLocked()) sendReportLocked(0)
            }
            Unit
        }
    }

    override fun isReady(): Boolean = synchronized(lock) {
        connection != null && keyboardInterface != null && claimMode != ClaimMode.NONE
    }

    override fun send(command: MovementCommand): Boolean {
        val mask = command.toLedMask()
        synchronized(lock) {
            lastMask = mask
            if (connection == null) {
                if (command == MovementCommand.STOP) return false
                if (!connectOrRequestLocked()) return false
            }
            if (sendReportLocked(mask)) return true

            val deviceId = connectedDeviceId
            closeLocked()
            val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
            return device != null && connectLocked(device) && sendReportLocked(mask)
        }
    }

    override fun close() {
        synchronized(lock) {
            lastMask = 0
            sendReportLocked(0, notifyFailure = false)
            closeLocked()
        }
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    private fun connectOrRequestLocked(): Boolean {
        val target = findKeyboardTarget()
        if (target == null) {
            val devices = usbManager.deviceList.values.toList()
            val supportsUsbHost = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
            val message = when {
                devices.isNotEmpty() -> "USB detectado, pero no teclado HID compatible."
                !supportsUsbHost -> "El dispositivo no declara soporte USB Host/OTG."
                else -> "No hay USB enumerado."
            }
            reportStatus(message, Log.WARN)
            return false
        }
        if (!usbManager.hasPermission(target.device)) {
            requestPermissionLocked(target.device)
            return false
        }
        return connectLocked(target.device)
    }

    private fun requestPermissionLocked(device: UsbDevice) {
        if (permissionRequestDeviceId != device.deviceId) {
            permissionRequestDeviceId = device.deviceId
            usbManager.requestPermission(device, permissionIntent)
        }
        reportStatus("Solicitando permiso USB.")
    }

    private fun connectLocked(device: UsbDevice): Boolean {
        val target = findKeyboardTarget(device) ?: run {
            reportStatus("El USB conectado no expone teclado HID compatible.", Log.WARN)
            return false
        }
        if (!usbManager.hasPermission(device)) {
            requestPermissionLocked(device)
            return false
        }

        val opened = usbManager.openDevice(device) ?: run {
            reportStatus("No se pudo abrir el teclado USB.", Log.ERROR)
            return false
        }

        val normalClaim = runCatching { opened.claimInterface(target.usbInterface, false) }.getOrDefault(false)
        val forcedClaim = if (!normalClaim) {
            runCatching { opened.claimInterface(target.usbInterface, true) }.getOrDefault(false)
        } else {
            false
        }

        connection = opened
        keyboardInterface = target.usbInterface
        interruptOutEndpoint = target.outEndpoint
        connectedDeviceId = device.deviceId
        claimMode = when {
            normalClaim -> ClaimMode.NORMAL
            forcedClaim -> ClaimMode.FORCED
            else -> ClaimMode.NONE
        }

        logDeviceDetails(target)

        if (claimMode != ClaimMode.NONE) {
            configureKeyboardProtocolLocked(target)
            permissionRequestDeviceId = null
            reportReadyStatus(target)
            return true
        }

        // Algunos kernels permiten SET_REPORT aunque hid-input conserve la interfaz.
        if (sendControlReportsLocked(0)) {
            claimMode = ClaimMode.CONTROL_ONLY
            interruptOutEndpoint = null
            permissionRequestDeviceId = null
            reportStatus("Teclado USB listo (modo compatible).")
            Log.i(TAG, "HID control-only activo: la interfaz no pudo reclamarse, SET_REPORT sí respondió")
            return true
        }

        Log.w(TAG, "No fue posible reclamar interfaz ni usar SET_REPORT sin exclusividad")
        closeLocked()
        reportStatus("No se pudo tomar control del teclado USB.", Log.ERROR)
        return false
    }

    private fun reportReadyStatus(target: Target) {
        val mode = when (claimMode) {
            ClaimMode.NORMAL -> "directo"
            ClaimMode.FORCED -> "exclusivo"
            ClaimMode.CONTROL_ONLY -> "compatible"
            ClaimMode.NONE -> "sin control"
        }
        val keyboardType = if (target.isBootKeyboard) "Boot" else "HID"
        reportStatus("Teclado USB listo ($keyboardType, modo $mode).")
    }

    private fun closeLocked() {
        val currentConnection = connection
        val currentInterface = keyboardInterface
        if (currentConnection != null && currentInterface != null &&
            claimMode != ClaimMode.NONE && claimMode != ClaimMode.CONTROL_ONLY
        ) {
            runCatching { currentConnection.releaseInterface(currentInterface) }
        }
        currentConnection?.close()
        connection = null
        keyboardInterface = null
        interruptOutEndpoint = null
        connectedDeviceId = null
        claimMode = ClaimMode.NONE
        preferredControlReport = null
        preferredEndpointReport = null
    }

    private fun sendReportLocked(mask: Int, notifyFailure: Boolean = true): Boolean {
        if (sendControlReportsLocked(mask)) return true

        if (claimMode != ClaimMode.CONTROL_ONLY) {
            val endpoint = interruptOutEndpoint
            if (endpoint != null) {
                val value = (mask and LED_MASK).toByte()
                val formats = orderedEndpointFormats()
                for (format in formats) {
                    val report = endpointReport(format, value)
                    if (sendInterruptReportLocked(endpoint, report)) {
                        preferredEndpointReport = format
                        Log.i(TAG, "HID LED por interrupt OUT format=$format data=${report.toHexString()}")
                        return true
                    }
                }
            }
        }

        if (notifyFailure) {
            reportStatus("No se pudo enviar reporte HID.", Log.ERROR)
        }
        return false
    }

    private fun sendControlReportsLocked(mask: Int): Boolean {
        val currentConnection = connection ?: return false
        val currentInterface = keyboardInterface ?: return false
        val value = (mask and LED_MASK).toByte()
        for (format in orderedControlFormats()) {
            val report = controlReport(format, value)
            val sent = runCatching {
                currentConnection.controlTransfer(
                    HID_CLASS_OUT,
                    HID_SET_REPORT,
                    (HID_OUTPUT_REPORT shl 8) or report.reportId,
                    currentInterface.id,
                    report.data,
                    report.data.size,
                    USB_TIMEOUT_MS
                )
            }.getOrElse {
                Log.w(TAG, "SET_REPORT lanzó ${it.javaClass.simpleName}", it)
                -1
            }
            Log.d(
                TAG,
                "SET_REPORT interface=${currentInterface.id} format=$format reportId=${report.reportId} " +
                    "data=${report.data.toHexString()} result=$sent"
            )
            if (sent == report.data.size) {
                preferredControlReport = format
                Log.i(TAG, "HID LED por SET_REPORT format=$format bytes=$sent")
                return true
            }
        }
        return false
    }


    private fun orderedControlFormats(): List<ControlReportFormat> {
        val preferred = preferredControlReport
        val defaults = listOf(
            ControlReportFormat.ID_0,
            ControlReportFormat.ID_1,
            ControlReportFormat.ID_1_PREFIXED
        )
        return if (preferred == null) defaults else listOf(preferred) + defaults.filterNot { it == preferred }
    }

    private fun controlReport(format: ControlReportFormat, value: Byte): ControlReport = when (format) {
        ControlReportFormat.ID_0 -> ControlReport(0, byteArrayOf(value))
        ControlReportFormat.ID_1 -> ControlReport(REPORT_ID_FALLBACK, byteArrayOf(value))
        ControlReportFormat.ID_1_PREFIXED -> ControlReport(
            REPORT_ID_FALLBACK,
            byteArrayOf(REPORT_ID_FALLBACK.toByte(), value)
        )
    }

    private fun orderedEndpointFormats(): List<EndpointReportFormat> {
        val preferred = preferredEndpointReport
        val defaults = listOf(EndpointReportFormat.RAW, EndpointReportFormat.ID_1_PREFIXED)
        return if (preferred == null) defaults else listOf(preferred) + defaults.filterNot { it == preferred }
    }

    private fun endpointReport(format: EndpointReportFormat, value: Byte): ByteArray = when (format) {
        EndpointReportFormat.RAW -> byteArrayOf(value)
        EndpointReportFormat.ID_1_PREFIXED -> byteArrayOf(REPORT_ID_FALLBACK.toByte(), value)
    }

    private fun sendInterruptReportLocked(endpoint: UsbEndpoint, report: ByteArray): Boolean {
        val currentConnection = connection ?: return false
        if (endpoint.direction != UsbConstants.USB_DIR_OUT ||
            endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT
        ) {
            return false
        }

        val request = UsbRequest()
        if (!request.initialize(currentConnection, endpoint)) {
            request.close()
            Log.w(TAG, "No se pudo inicializar UsbRequest para interrupt OUT")
            return false
        }

        val buffer = ByteBuffer.wrap(report)
        return try {
            @Suppress("DEPRECATION")
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                request.queue(buffer)
            } else {
                request.queue(buffer, report.size)
            }
            if (!queued) {
                Log.w(TAG, "No se pudo encolar interrupt OUT")
                false
            } else {
                val completed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        currentConnection.requestWait(USB_TIMEOUT_MS.toLong())
                    } catch (_: TimeoutException) {
                        request.cancel()
                        null
                    }
                } else {
                    currentConnection.requestWait()
                }
                completed === request && buffer.position() >= report.size
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Falló interrupt OUT", error)
            false
        } finally {
            request.close()
        }
    }

    private fun configureKeyboardProtocolLocked(target: Target) {
        if (!target.isBootKeyboard) {
            Log.d(TAG, "SET_PROTOCOL omitido: interfaz HID no es Boot Keyboard")
            return
        }
        val currentConnection = connection ?: return
        val currentInterface = keyboardInterface ?: return

        val protocolResult = currentConnection.controlTransfer(
            HID_CLASS_OUT,
            HID_SET_PROTOCOL,
            HID_PROTOCOL_BOOT,
            currentInterface.id,
            ByteArray(0),
            0,
            USB_TIMEOUT_MS
        )
        val idleResult = currentConnection.controlTransfer(
            HID_CLASS_OUT,
            HID_SET_IDLE,
            0,
            currentInterface.id,
            ByteArray(0),
            0,
            USB_TIMEOUT_MS
        )
        Log.d(TAG, "SET_PROTOCOL result=$protocolResult, SET_IDLE result=$idleResult")
    }

    private fun findKeyboardTarget(): Target? {
        return usbManager.deviceList.values
            .flatMap { findKeyboardTargets(it) }
            .minByOrNull { it.priority }
    }

    private fun findKeyboardTarget(device: UsbDevice): Target? {
        return findKeyboardTargets(device).minByOrNull { it.priority }
    }

    private fun findKeyboardTargets(device: UsbDevice): List<Target> {
        val targets = mutableListOf<Target>()
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_HID) continue

            val priority = when {
                usbInterface.interfaceSubclass == USB_INTERFACE_SUBCLASS_BOOT &&
                    usbInterface.interfaceProtocol == USB_INTERFACE_PROTOCOL_KEYBOARD -> 0
                usbInterface.interfaceProtocol == USB_INTERFACE_PROTOCOL_KEYBOARD -> 1
                usbInterface.interfaceSubclass == USB_INTERFACE_SUBCLASS_BOOT -> 2
                else -> 3
            }
            targets += Target(
                device = device,
                usbInterface = usbInterface,
                outEndpoint = findInterruptOutEndpoint(usbInterface),
                priority = priority
            )
        }
        return targets
    }

    private fun findInterruptOutEndpoint(usbInterface: UsbInterface): UsbEndpoint? {
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.direction == UsbConstants.USB_DIR_OUT &&
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT
            ) {
                return endpoint
            }
        }
        return null
    }

    private fun logDeviceDetails(target: Target) {
        val device = target.device
        val usbInterface = target.usbInterface
        val endpoint = target.outEndpoint
        Log.i(
            TAG,
            "USB keyboard candidate: deviceId=${device.deviceId} " +
                "vid=${device.vendorId.toHex4()} pid=${device.productId.toHex4()} " +
                "interface=${usbInterface.id} class=${usbInterface.interfaceClass} " +
                "subclass=${usbInterface.interfaceSubclass} protocol=${usbInterface.interfaceProtocol} " +
                "claim=$claimMode interruptOut=${endpoint?.address ?: "none"} " +
                "maxPacket=${endpoint?.maxPacketSize ?: 0}"
        )
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun reportStatus(message: String, priority: Int = Log.INFO) {
        when (priority) {
            Log.ERROR -> Log.e(TAG, message)
            Log.WARN -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
        onStatus(message)
    }

    private fun MovementCommand.toLedMask(): Int = when (this) {
        MovementCommand.STOP -> 0b000
        MovementCommand.FORWARD -> 0b001
        MovementCommand.BACKWARD -> 0b010
        MovementCommand.LEFT -> 0b011
        MovementCommand.RIGHT -> 0b100
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            getParcelableExtra(name)
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = " ") {
        "%02X".format(it.toInt() and 0xFF)
    }

    private fun Int.toHex4(): String = "0x%04X".format(this and 0xFFFF)

    private data class ControlReport(
        val reportId: Int,
        val data: ByteArray
    )

    companion object {
        private const val TAG = "MechBotUsbHid"
        private const val ACTION_USB_PERMISSION = "com.mechrobotix.mechbot.USB_PERMISSION"
        private const val HID_CLASS_OUT = 0x21
        private const val HID_SET_REPORT = 0x09
        private const val HID_SET_IDLE = 0x0A
        private const val HID_SET_PROTOCOL = 0x0B
        private const val HID_OUTPUT_REPORT = 0x02
        private const val HID_PROTOCOL_BOOT = 0
        private const val USB_INTERFACE_SUBCLASS_BOOT = 1
        private const val USB_INTERFACE_PROTOCOL_KEYBOARD = 1
        private const val REPORT_ID_FALLBACK = 1
        private const val LED_MASK = 0b111
        private const val USB_TIMEOUT_MS = 1000
    }
}
