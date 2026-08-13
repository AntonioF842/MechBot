package com.mechrobotix.mechbot

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<MaterialButton>(R.id.btnDirectMode).setOnClickListener {
            openDirectControl()
        }
        findViewById<MaterialButton>(R.id.btnRemoteMode).setOnClickListener {
            startActivity(Intent(this, RemoteControlActivity::class.java))
        }
        handleUsbAttach(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttach(intent)
    }

    private fun handleUsbAttach(source: Intent?) {
        if (source?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        openDirectControl(source)
    }

    private fun openDirectControl(source: Intent? = null) {
        val target = Intent(this, DirectControlActivity::class.java)
        if (source?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            target.action = source.action
            target.replaceExtras(source)
            target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(target)
    }
}
