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

    // FLUJO 2: Abre el cuadro nativo directo de Google para desactivar la optimización de batería
    private fun checkBatteryOptimizationFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivityForResult(intent, 102)
                } catch (e: Exception) {
                    showManualInstructionsDialog() // Ruta de rescate si falla el intent
                }
                return
            }
        }
        finalizeActivation()
    }

    // CAPTURA DE BATERÍA NATIVA: Se ejecuta tras responder al cuadro central de Google
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102) {
            // COMBO SECUENCIAL OBLIGATORIO: Haya aceptado o no, le enviamos a los Ajustes de la App
            showManualInstructionsDialog()
        }
    }

    // FUNCIÓN: Muestra la guía y redirige al usuario de forma directa a los Ajustes de la Aplicación
    private fun showManualInstructionsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Ajustes de Fiabilidad")
            .setMessage("Para garantizar que la alerta suene siempre con la pantalla bloqueada, realiza lo siguiente en el menú que se va a abrir:\n\n" +
                        "1. Entra en 'Batería' y selecciona 'SIN RESTRICCIONES' (u 'Optimizar consumo' -> Desactivar).\n\n" +
                        "2. Activa el 'INICIO AUTOMÁTICO' o 'Inicio secundario' si tu móvil dispone de esa opción.\n\n" +
                        "3. Verifica que los permisos de Notificaciones sigan activos.")
            .setCancelable(false) // Impide que se cierre si el usuario pulsa fuera
            .setPositiveButton("Entendido, ir a Ajustes") { dialog, _ ->
                dialog.dismiss()
                launchSystemSettings() // Abre la ventana nativa de la app en el móvil
            }
            .show()
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

    // FUNCIÓN: Comprobación final que se ejecuta si el móvil ya tenía los permisos dados de antemano
    private fun finalizeActivation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // Si el procesador confirma que ya estaba en la lista blanca, encendemos el interruptor de golpe
            saveEnabledState(true)
            switchEnable.isChecked = true
            toggleAppComponents(true)
            Toast.makeText(this, "Permisos otorgados!", Toast.LENGTH_LONG).show()
        }
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

    // FUNCIÓN DE VIGILANCIA: Se activa de forma automática cada vez que el usuario regresa a la pantalla de la app
    override fun onResume() {
        super.onResume()
        
        // Interrogamos directamente al hardware de Android para comprobar el estado de la batería
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            val isAppSavedAsEnabled = prefs.getBoolean("enabled", false)
            
            // CASO A: El usuario viene de los Ajustes y ha puesto con éxito el modo "Sin Restricciones"
            if (isIgnoring && !isAppSavedAsEnabled) {
                // Guardamos el éxito en memoria y encendemos el interruptor de la pantalla automáticamente
                saveEnabledState(true)
                switchEnable.isChecked = true
                toggleAppComponents(true)
                Toast.makeText(this, "¡${getString(R.string.app_name)} iniciada con éxito!", Toast.LENGTH_LONG).show()
            }
            // CASO B: La app estaba encendida pero el usuario ha revocado el permiso desde los ajustes del móvil
            else if (!isIgnoring && isAppSavedAsEnabled) {
                // Apagamos el interruptor de inmediato por seguridad y avisamos del fallo de fiabilidad
                turnOffAppWithDialog(
                    "Permisos Modificados",
                    "Faltan permisos esenciales para que la aplicación funcione correctamente."
                )
            }
        }
    }

}
