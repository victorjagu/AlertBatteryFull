package com.victor.alertbatterylevel

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    // ALMACENAMIENTO: Archivo plano interno para guardar las preferencias del usuario
    private val prefs by lazy { getSharedPreferences("prefs_battery", Context.MODE_PRIVATE) }
    
    // HARDWARE: Gestor de energía del sistema operativo para verificar el ahorro de batería
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    
    // COMPONENTES VISUALES: Declaración de los interruptores y selectores de la pantalla
    private lateinit var switchEnable: Switch
    private lateinit var switchLoop: Switch
    private lateinit var switchVibrate: Switch
    private lateinit var seekPercentage: SeekBar
    private lateinit var seekVolume: SeekBar
    private lateinit var tvPercentageLabel: TextView
    private lateinit var tvVolumeLabel: TextView
    private lateinit var rgSoundType: RadioGroup
    private lateinit var btnBatterySettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MODO OSCURO: Aplicamos el tema negro puro OLED antes de dibujar las vistas
        setTheme(R.style.Theme_AlertBattery)
        
        // INTERFAZ: Inflamos el diseño visual definido en el archivo XML
        setContentView(R.layout.activity_main)

        // ENLACES: Conectamos las variables de Kotlin con los ID del plano visual XML
        switchEnable = findViewById(R.id.switch_enable)
        switchLoop = findViewById(R.id.switch_loop)
        switchVibrate = findViewById(R.id.switch_vibrate)
        seekPercentage = findViewById(R.id.seek_percentage)
        seekVolume = findViewById(R.id.seek_volume)
        tvPercentageLabel = findViewById(R.id.tv_percentage_label)
        tvVolumeLabel = findViewById(R.id.tv_volume_label)
        rgSoundType = findViewById(R.id.rg_sound_type)
        btnBatterySettings = findViewById(R.id.btn_battery_settings)

        // MEMORIA: Leemos el almacenamiento para restaurar el estado anterior de la app
        loadPreferences()

        // CONTROL DE PORCENTAJE OBJETIVO
        seekPercentage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = if (progress < 1) 1 else progress
                tvPercentageLabel.text = "Porcentaje objetivo: $pct%"
                prefs.edit().putInt("target_pct", pct).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // INTERRUPTOR PRINCIPAL: Activa la app y arranca el flujo inteligente de permisos
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Si el usuario enciende el interruptor, validamos primero las Notificaciones
                checkNotificationPermissionFlow()
            } else {
                // Si apaga, guardamos el estado y apagamos los receptores de hardware
                saveEnabledState(false)
                toggleAppComponents(false)
            }
        }

        // INTERRUPTOR DEL BUCLE DE REPETICIÓN
        switchLoop.setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean("loop", isChecked).apply() 
        }

        // INTERRUPTOR DE LA VIBRACIÓN NATIVA
        switchVibrate.setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean("vibrate", isChecked).apply() 
        }

        // DESLIZADOR DE VOLUMEN CON EMISIÓN DE BEEP DE PRUEBA
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var toneGenerator: ToneGenerator? = null

            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolumeLabel.text = "Volumen del aviso: $progress%"
                prefs.edit().putInt("volume", progress).apply()

                if (fromUser) {
                    try {
                        if (toneGenerator == null) {
                            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, progress)
                        }
                        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                    } catch (e: Exception) {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                toneGenerator?.release()
                toneGenerator = null
            }
        })

        // GRUPO DE BOTONES DE RADIO PARA EL TIPO DE ALERTA
        rgSoundType.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.rb_beep -> 1
                R.id.rb_silent -> 2
                else -> 0
            }
            prefs.edit().putInt("sound_type", type).apply()
        }

        // BOTÓN MANUAL DE AUXILIO: Mantiene la ruta tradicional si el usuario quiere revisar ajustes
        btnBatterySettings.setOnClickListener { launchSystemSettings() }
    }

    // FLUJO 1: Control estricto de Notificaciones con apagado automático si se deniega
    private fun checkNotificationPermissionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = "android.permission.POST_NOTIFICATIONS"
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                // Lanzamos el cuadro emergente del sistema operativo en la pantalla
                requestPermissions(arrayOf(permission), 101)
                return
            }
        }
        // Si ya tiene el permission concedido, pasamos al flujo de la batería
        checkBatteryOptimizationFlow()
    }

    // CAPTURA DE RESPUESTA DE NOTIFICACIÓN: Se ejecuta cuando el usuario pulsa "Permitir" o "Denegar"
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // PERMISO CONCEDIDO: Pasamos de inmediato a la verificación de la batería
                checkBatteryOptimizationFlow()
            } else {
                // PERMISO DENEGADO: Apagamos el interruptor y mostramos una ventana grande con botón Aceptar
                turnOffAppWithDialog(
                    "Permiso Requerido",
                    "Para poder enviarte los avisos de carga completa, es obligatorio que permitas las notificaciones."
                )
            }
        }
    }

    // FLUJO 2: Redirección segura a los ajustes para desactivar la optimización de batería
    private fun checkBatteryOptimizationFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Comprobamos si el móvil ya tiene a esta app en la lista blanca de "Sin Restricciones"
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // Al no estar permitida, abrimos el menú nativo del sistema para que el usuario la configure
                launchSystemSettings()
                // Mostramos un diálogo emergente con botón Aceptar explicando los pasos exactos
                turnOffAppWithDialog(
                    "Optimización de Batería",
                    "Para que los avisos suenen correctamente con la pantalla bloqueada, es obligatorio cambiar la batería a 'SIN RESTRICCIONES' en el menú que se acaba de abrir."
                )
                return
            }
        }
        // Si ya está en la lista blanca, la aplicación se activa oficialmente
        finalizeActivation()
    }


    // CAPTURA DE RESPUESTA DE BATERÍA: Se ejecuta cuando el usuario responde al cuadro flotante de la batería
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // CONFIGURACIÓN CORRECTA: Activación finalizada
                finalizeActivation()
            } else {
                // RESTRICCIÓN MANTENIDA: El usuario le dio a "Denegar". Apagamos la app y avisamos con diálogo estable
                turnOffAppWithDialog(
                    "Optimización Activa",
                    "Has denegado la ejecución libre en segundo plano. El sistema operativo matará la app en reposo y no sonará. La aplicación se ha desactivado por seguridad."
                )
            }
        }
    }

    // FUNCIÓN: Muestra un diálogo flotante de tamaño completo con un botón Aceptar para apagar el interruptor
    private fun turnOffAppWithDialog(title: String, message: String) {
        saveEnabledState(false)
        switchEnable.isChecked = false
        toggleAppComponents(false)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false) // Impide que la ventana se cierre si pulsa fuera de ella
            .setPositiveButton("Aceptar") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // FUNCIÓN: Guarda el éxito y despierta los receptores de hardware a nivel de sistema operativo
    private fun finalizeActivation() {
        saveEnabledState(true)
        toggleAppComponents(true)
        
        AlertDialog.Builder(this)
            .setTitle("¡App Lista!")
            .setMessage("Los permisos han sido configurados correctamente. El vigilante de batería ya se encuentra activo en segundo plano con consumo del 0% en reposo.")
            .setPositiveButton("Entendido") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun saveEnabledState(state: Boolean) {
        prefs.edit().putBoolean("enabled", state).apply()
    }

    private fun loadPreferences() {
        switchEnable.isChecked = prefs.getBoolean("enabled", false)
        switchLoop.isChecked = prefs.getBoolean("loop", true)
        switchVibrate.isChecked = prefs.getBoolean("vibrate", true)
        
        val pct = prefs.getInt("target_pct", 100)
        seekPercentage.progress = pct
        tvPercentageLabel.text = "Porcentaje objetivo: $pct%"
        
        val vol = prefs.getInt("volume", 80)
        seekVolume.progress = vol
        tvVolumeLabel.text = "Volumen del aviso: $vol%"
        
        when (prefs.getInt("sound_type", 0)) {
            1 -> rgSoundType.check(R.id.rb_beep)
            2 -> rgSoundType.check(R.id.rb_silent)
            else -> rgSoundType.check(R.id.rb_voice)
        }
    }

    private fun launchSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun toggleAppComponents(enable: Boolean) {
        val state = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val receiver = ComponentName(this, BatteryReceiver::class.java)
        packageManager.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
    }

    override fun onResume() {
        super.onResume()
        // CONTROL DE SEGURIDAD PASIVO: Si el interruptor estaba encendido pero el usuario desconfiguró 
        // los permisos por detrás desde los ajustes del móvil, apagamos la app de forma controlada al volver.
        if (switchEnable.isChecked) {
            var breached = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                breached = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                breached = true
            }
            if (breached) {
                turnOffAppWithDialog("Permisos Modificados", "Se han detectado cambios en los ajustes del sistema. La app no dispone de los permisos necesarios para trabajar en segundo plano y ha sido desactivada.")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!switchEnable.isChecked) {
            finish()
        }
    }
}
