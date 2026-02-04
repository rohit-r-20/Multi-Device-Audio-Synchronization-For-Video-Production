package com.example.multideviceaudiosync

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnHostMode = findViewById<MaterialButton>(R.id.btnHostMode)
        val btnMicMode = findViewById<MaterialButton>(R.id.btnMicMode)
        val themeSwitch = findViewById<SwitchMaterial>(R.id.themeSwitch)

        // Theme Switch Logic
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        btnHostMode.setOnClickListener {
            val intent = Intent(this, HostActivity::class.java)
            startActivity(intent)
        }

        btnMicMode.setOnClickListener {
            val intent = Intent(this, MicActivity::class.java)
            startActivity(intent)
        }
    }
}