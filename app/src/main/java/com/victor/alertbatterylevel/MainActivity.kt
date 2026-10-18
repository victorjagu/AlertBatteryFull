package com.victor.alertbatterylevel

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("prefs_battery", Context.MODE_PRIVATE) }
    
    private lateinit var switchEnable: Switch
    private lateinit var switchLoop: Switch
    private lateinit var switchVibrate: Switch
    private lateinit var seekPercentage: SeekBar
    private lateinit var seekVolume: SeekBar
    private lateinit var tvPercentageLabel: TextView
    private lateinit var rgSoundType: RadioGroup
    private lateinit var btnBatterySettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme.AlertBattery) // Carga el modo oscuro nativo por hardware
        setContentView(R.layout.activity_main)

        // Enlazamos con el diseño XML
        switchEnable = findViewById(R.id.switch_enable)
        switchLoop = findViewById(R.id.switch_loop)
        switchVibrate = findViewById(R.id.switch_vibrate)
        seekPercentage = findViewById(R.id.seek_percentage)
        seekVolume = findViewById(R.id.seek_volume)
        tvPercentageLabel = findViewById(R.id.tv_percentage_label)
        rgSoundType = findViewById(R.id.rg_sound_type)
        btnBatterySettings = findViewById(R.id.btn_battery_settings)

        loadPreferences()

        // Listeners optimizados con Lambdas de Kotlin
        seekPercentage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = if (progress < 1) 1 else progress
                tvPercentageLabel.text = "Porcentaje objetivo: $pct%"
                prefs.edit().putInt("target_pct", pct).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enabled", isChecked).apply()
            toggleAppComponents(isChecked)
        }

        switchLoop.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("loop", isChecked).apply() }
        switchVibrate.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("vibrate", isChecked).apply() }

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) { prefs.edit().putInt("volume", p).apply() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        rgSoundType.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.rb_beep -> 1
                R.id.rb_silent -> 2
                else -> 0 // Voz hablada
            }
            prefs.edit().putInt("sound_type", type).apply()
        }

        btnBatterySettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            try {
                startActivity(intent)
                Toast.makeText(this, "Busca 'Batería' y elige 'Sin Restricciones'. Activa 'Inicio automático' si aparece.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Abre los ajustes para quitar restricciones de batería", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadPreferences() {
        switchEnable.isChecked = prefs.getBoolean("enabled", false)
        switchLoop.isChecked = prefs.getBoolean("loop", true)
        switchVibrate.isChecked = prefs.getBoolean("vibrate", true)
        
        val pct = prefs.getInt("target_pct", 100)
        seekPercentage.progress = pct
        tvPercentageLabel.text = "Porcentaje objetivo: $pct%"
        
        seekVolume.progress = prefs.getInt("volume", 80)
        
        when (prefs.getInt("sound_type", 0)) {
            1 -> rgSoundType.check(R.id.rb_beep)
            2 -> rgSoundType.check(R.id.rb_silent)
            else -> rgSoundType.check(R.id.rb_voice)
        }
    }

    private fun toggleAppComponents(enable: Boolean) {
        // Desactivación radical a nivel de sistema operativo para consumo 0% en OFF
        val state = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val receiver = ComponentName(this, BatteryReceiver::class.java)
        packageManager.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
    }

    override fun onPause() {
        super.onPause()
        // Si el interruptor está apagado, destruimos la interfaz inmediatamente al salir para liberar RAM
        if (!switchEnable.isChecked) {
            finish()
        }
    }
}
