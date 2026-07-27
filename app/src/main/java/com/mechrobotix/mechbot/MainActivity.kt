package com.mechrobotix.mechbot

import android.content.Intent
import android.os.Bundle
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<MaterialButton>(R.id.btnDirectMode).setOnClickListener {
            startActivity(Intent(this, DirectControlActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnRemoteMode).setOnClickListener {
            startActivity(Intent(this, RemoteControlActivity::class.java))
        }
    }
}
