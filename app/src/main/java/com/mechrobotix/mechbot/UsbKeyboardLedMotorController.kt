package com.mechrobotix.mechbot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Parcelable

class UsbKeyboardLedMotorController(
    context: Context,
    private val onStatus: (String) -> Unit
) : MotorController {

    private data class Target(
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val outEndpoint: UsbEndpoint?
    )

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    private val permissionIntent: PendingIntent
    private var connection: UsbDeviceConnection? = null
    private var keyboardInterface: UsbInterface? = null
    private var interruptOutEndpoint: UsbEndpoint? = null
    private var connectedDeviceId: Int? = null
    private var lastMask = 0
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
                    if (device != null && granted) {
                        synchronized(lock) {
                            closeLocked()
                            if (connectLocked(device)) sendReportLocked(lastMask)
                        }
                    } else {
                        onStatus("Permiso USB denegado.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleIntent(intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.parcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    synchronized(lock) {
                        if (device != null && device.deviceId == connectedDeviceId) {
                            closeLocked()
                            onStatus("Teclado USB desconectado.")
                        }
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

    fun prepare() {
        synchronized(lock) {
            if (connection == null) {
                connectOrRequestLocked()
            } else {
                sendReportLocked(lastMask)
            }
        }
    }

    fun handleIntent(intent: Intent?) {
        val device = intent?.parcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
        synchronized(lock) {
            if (device != null) {
                closeLocked()
                if (connectLocked(device)) sendReportLocked(lastMask)
            } else if (connection == null) {
                connectOrRequestLocked()
            }
        }
    }

    override fun send(command: MechbotCommand) {
        val mask = command.toLedMask() ?: return
        synchronized(lock) {
            lastMask = mask
            if (connection == null && !connectOrRequestLocked()) return
            if (!sendReportLocked(mask)) {
                val deviceId = connectedDeviceId
                closeLocked()
                val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
                if (device != null && connectLocked(device)) sendReportLocked(mask)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            lastMask = 0
            sendReportLocked(0)
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
            onStatus(if (devices.isEmpty()) "No hay USB enumerado." else "USB detectado, pero no teclado HID.")
            return false
        }
        if (!usbManager.hasPermission(target.device)) {
            usbManager.requestPermission(target.device, permissionIntent)
            onStatus("Solicitando permiso USB.")
            return false
        }
        return connectLocked(target.device)
    }

    private fun connectLocked(device: UsbDevice): Boolean {
        val target = findKeyboardTarget(device) ?: run {
            onStatus("El USB conectado no expone teclado HID.")
            return false
        }
        if (!usbManager.hasPermission(device)) {
            usbManager.requestPermission(device, permissionIntent)
            onStatus("Solicitando permiso USB.")
            return false
        }
        val opened = usbManager.openDevice(device) ?: run {
            onStatus("No se pudo abrir el teclado USB.")
            return false
        }
        if (!opened.claimInterface(target.usbInterface, true)) {
            opened.close()
            onStatus("No se pudo tomar control del teclado USB.")
            return false
        }

        connection = opened
        keyboardInterface = target.usbInterface
        interruptOutEndpoint = target.outEndpoint
        connectedDeviceId = device.deviceId
        setBootProtocolLocked()
        onStatus("Teclado USB listo.")
        return true
    }

    private fun closeLocked() {
        val currentConnection = connection
        val currentInterface = keyboardInterface
        if (currentConnection != null && currentInterface != null) {
            runCatching { currentConnection.releaseInterface(currentInterface) }
        }
        currentConnection?.close()
        connection = null
        keyboardInterface = null
        interruptOutEndpoint = null
        connectedDeviceId = null
    }

    private fun sendReportLocked(mask: Int): Boolean {
        val currentConnection = connection ?: return false
        val currentInterface = keyboardInterface ?: return false
        val value = (mask and 0b111).toByte()

        val controlReports = arrayOf(
            (HID_OUTPUT_REPORT shl 8) to byteArrayOf(value),
            ((HID_OUTPUT_REPORT shl 8) or 1) to byteArrayOf(value),
            ((HID_OUTPUT_REPORT shl 8) or 1) to byteArrayOf(1, value)
        )

        for ((reportValue, data) in controlReports) {
            val sent = currentConnection.controlTransfer(
                HID_CLASS_OUT,
                HID_SET_REPORT,
                reportValue,
                currentInterface.id,
                data,
                data.size,
                USB_TIMEOUT_MS
            )
            if (sent == data.size) return true
        }

        val endpoint = interruptOutEndpoint ?: run {
            onStatus("No se pudo enviar reporte HID.")
            return false
        }
        val endpointReports = arrayOf(
            byteArrayOf(value),
            byteArrayOf(1, value)
        )
        for (report in endpointReports) {
            val packet = ByteArray(maxOf(endpoint.maxPacketSize, report.size))
            report.copyInto(packet)
            if (currentConnection.bulkTransfer(endpoint, packet, packet.size, USB_TIMEOUT_MS) >= report.size) return true
        }

        onStatus("No se pudo enviar reporte HID.")
        return false
    }

    private fun setBootProtocolLocked() {
        val currentConnection = connection ?: return
        val currentInterface = keyboardInterface ?: return
        currentConnection.controlTransfer(
            HID_CLASS_OUT,
            HID_SET_PROTOCOL,
            0,
            currentInterface.id,
            ByteArray(0),
            0,
            USB_TIMEOUT_MS
        )
        currentConnection.controlTransfer(
            HID_CLASS_OUT,
            HID_SET_IDLE,
            0,
            currentInterface.id,
            ByteArray(0),
            0,
            USB_TIMEOUT_MS
        )
    }

    private fun findKeyboardTarget(): Target? {
        usbManager.deviceList.values
            .mapNotNull { findKeyboardTarget(it) }
            .firstOrNull { it.usbInterface.interfaceProtocol == USB_INTERFACE_PROTOCOL_KEYBOARD }
            ?.let { return it }

        return usbManager.deviceList.values.mapNotNull { findKeyboardTarget(it) }.firstOrNull()
    }

    private fun findKeyboardTarget(device: UsbDevice): Target? {
        var fallback: Target? = null
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            val endpoint = findOutEndpoint(usbInterface)
            val target = Target(device, usbInterface, endpoint)
            if (usbInterface.interfaceProtocol == USB_INTERFACE_PROTOCOL_KEYBOARD) return target
            if (fallback == null) fallback = target
        }
        return fallback
    }

    private fun findOutEndpoint(usbInterface: UsbInterface): UsbEndpoint? {
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) return endpoint
        }
        return null
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

    private fun MechbotCommand.toLedMask(): Int? = when (this) {
        MechbotCommand.STOP -> 0b000
        MechbotCommand.FORWARD -> 0b001
        MechbotCommand.BACKWARD -> 0b010
        MechbotCommand.LEFT -> 0b011
        MechbotCommand.RIGHT -> 0b100
        else -> null
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            getParcelableExtra(name)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.mechrobotix.mechbot.USB_PERMISSION"
        private const val HID_CLASS_OUT = 0x21
        private const val HID_SET_REPORT = 0x09
        private const val HID_SET_IDLE = 0x0A
        private const val HID_SET_PROTOCOL = 0x0B
        private const val HID_OUTPUT_REPORT = 0x02
        private const val USB_INTERFACE_PROTOCOL_KEYBOARD = 1
        private const val USB_TIMEOUT_MS = 1000
    }
}
