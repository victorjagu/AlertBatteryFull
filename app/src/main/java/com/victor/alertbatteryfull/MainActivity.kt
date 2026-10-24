package com.victor.alertbatteryfull

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.Toast
import android.widget.TextView

class MainActivity : Activity() {

    // ALMACENAMIENTO: Archivo plano interno para guardar las preferencias del usuario
    private val prefs by lazy { getSharedPreferences("prefs_battery", Context.MODE_PRIVATE) }
    
    // HARDWARE: Gestor de energía del sistema operativo para verificar el ahorro de batería
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    // Variable de control global para saber si el usuario salió a la pantalla de configuración manual
    private var isWaitingForSettings = false
    
    // CONTROL DE TOASTS: Bandera lógica para identificar si se aceptó un permiso en esta interacción exacta
    private var isPermissionGrantedNow = false
    
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
    private lateinit var switchSoundDelay: Switch
    private lateinit var switchIgnoreSilence: Switch
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MODO OSCURO: Aplicamos el tema negro puro OLED antes de dibujar las vistas
        setTheme(R.style.Theme_AlertBattery)
        
        // INTERFAZ: Inflamos el diseño visual definido en el archivo XML
        setContentView(R.layout.activity_main)
        
        // AUDIO DE NOTIFICACIONES: Sincroniza el slider físico del móvil con el canal de notificaciones para máxima suavidad
        volumeControlStream = android.media.AudioManager.STREAM_NOTIFICATION
        
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
        switchSoundDelay = findViewById(R.id.switch_sound_delay)
        switchIgnoreSilence = findViewById(R.id.switch_ignore_silence)

        // INTERRUPTOR DE RETARDO DE SONIDO
        switchSoundDelay.setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean("sound_delay", isChecked).apply() 
        }

        // INTERRUPTOR PARA IGNORAR EL MODO SILENCIO/VIBRACION DEL MOVIL
        switchIgnoreSilence.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("ignore_silence", isChecked).apply()
        }

        // MEMORIA: Leemos el almacenamiento para restaurar el estado anterior de la app
        loadPreferences()

        // CONTROL DE PORCENTAJE OBJETIVO
        seekPercentage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = if (progress < 1) 1 else progress
                tvPercentageLabel.text = getString(R.string.ui_battery_target, pct)
                prefs.edit().putInt("target_pct", pct).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // INTERRUPTOR PRINCIPAL: Activa la app y arranca el flujo unificado con reinicio de banderas
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Reiniciamos el estado del permiso concedido antes de evaluar el hardware
                isPermissionGrantedNow = false 
                verificarYActivarPermisos()
            } else {
                saveEnabledState(false)
                toggleAppComponents(false)
                // MENSAJE DE APAGADO
                Toast.makeText(this, getString(R.string.toast_app_disabled, getString(R.string.app_name)), Toast.LENGTH_SHORT).show()
            }
        }

        // INTERRUPTOR DEL BUCLE DE REPETICIÓN
        switchLoop.setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean("loop", isChecked).apply() 
        }

        // INTERRUPTOR DE LA VIBRACIÓN NATIVA CON PULSO DE PRUEBA INSTANTÁNEO
        switchVibrate.setOnCheckedChangeListener { _, isChecked -> 
            // Guardamos la preferencia en el almacenamiento interno
            prefs.edit().putBoolean("vibrate", isChecked).apply() 
            
            // Si el usuario enciende la vibración con el dedo, hacemos latir el motor de hardware
            if (isChecked) {
                try {
                    // Accedemos de forma directa al chip del motor háptico del teléfono
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Creamos los atributos de audio indicando que es una alarma de notificación
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        // Enviamos el efecto de vibración junto con los atributos exigidos por Android 14
                        vibrator.vibrate(
                            android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                            audioAttributes
                        )
                    } else {
                        // Versiones antiguas de rescate para asegurar retrocompatibilidad
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(200)
                    }
                } catch (e: Exception) {
                    // Salvaguarda por si el fabricante tiene el motor ocupado por el sistema
                }
            }
        }

        // DESLIZADOR DE VOLUMEN CON EMISIÓN DE BEEP DE PRUEBA
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // Actualizamos el número del volumen dinámicamente en la pantalla (Ej: 65%)
                tvVolumeLabel.text = getString(R.string.ui_volume_alert, progress)
                // Registramos el valor en el almacenamiento interno
                prefs.edit().putInt("volume", progress).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // El usuario ha soltado la barra: calculamos el volumen final real
                val currentProgress = sb?.progress ?: 80
                try {
                    // Creamos la orden directa para encender nuestro servicio acústico especializado
                    val serviceIntent = Intent(this@MainActivity, AudioService::class.java).apply {
                        putExtra("sound_type", 1)               // Tipo 1: Reproducir el Beep físico de hardware
                        putExtra("volume", currentProgress)   // Le pasamos el volumen seleccionado por el usuario
                        putExtra("target_pct", seekPercentage.progress) // Porcentaje actual de la interfaz
                        putExtra("is_test", true)           // Marcamos que es solo una prueba manual desde la pantalla
                    }
                    // Arrancamos el servicio ligero de forma nativa
                    startService(serviceIntent)
                } catch (e: Exception) {
                    // Salvaguarda por si el sistema operativo está ocupado
                }
            }
        })

        // SELECTOR DE TIPO DE SONIDO CON PRUEBAS FÍSICAS EN TIEMPO REAL
        rgSoundType.setOnCheckedChangeListener { _, checkedId ->
            // 1. Calculamos el tipo y el volumen actual para la prueba
            val type = when (checkedId) {
                R.id.rb_beep -> 1    // Modo 1: Beep de hardware
                R.id.rb_silent -> 2  // Modo 2: Solo mensaje visual
                else -> 0            // Modo 0: Alerta por Voz
            }
            prefs.edit().putInt("sound_type", type).apply()
            
            val currentVolume = seekVolume.progress
            val currentTargetPct = seekPercentage.progress

            // 2. Ejecutamos la prueba con los mensajes reales de la aplicación
            when (type) {
                0, 1 -> { // Invoca al servicio de audio de forma unificada (0 = Voz, 1 = Beep)
                    try {
                        val serviceIntent = Intent(this, AudioService::class.java).apply {
                            putExtra("sound_type", type)             // Pasa directamente la variable 'type' ya calculada arriba (0 = Voz, 1 = Beep)
                            putExtra("volume", currentVolume)        // Volumen seleccionado en la barra
                            putExtra("target_pct", currentTargetPct) // Porcentaje seleccionado en la barra
                            putExtra("is_test", true)                // Marcamos que es una prueba manual
                        }
                        startService(serviceIntent)
                    } catch (e: Exception) {}
                }
                
                2 -> { // CASO SOLO MENSAJE: Muestra en la ventana el texto idéntico que se enviará como notificación
                    try {
                        // 1. Muestra la notificación nativa real de forma inmediata
                        NotificationHelper.showNotification(this, currentTargetPct)
                        
                        // 2. Usamos la ventana de la pantalla para cancelarla a los 7 segundos sin crear hilos en la RAM
                        window.decorView.postDelayed({
                            try {
                                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                nm.cancel(9911) // Usamos el ID 9911 fijado en el BatteryReceiver
                            } catch (e: Exception) {}
                        }, 7000)
                    } catch (e: Exception) {}
                }
            }
        }

        // BOTÓN MANUAL DE AUXILIO: Lanza primero el diálogo explicativo con las instrucciones
        btnBatterySettings.setOnClickListener { showManualInstructionsDialog() }

    }

    // MÉTODO UNIFICADO EVALUADO: Revisa la cadena de permisos requeridos uno a uno y gestiona los Toasts solicitados
    private fun verificarYActivarPermisos() {
        
        // 1. COMPROBACIÓN DEL PERMISO DE NOTIFICACIONES (Requerido obligatoriamente en Android 13/14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = "android.permission.POST_NOTIFICATIONS"
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), 101) // Lanza el cuadro emergente del sistema operativo
                return
            }
        }

        // 2. COMPROBACIÓN DE OPTIMIZACIÓN DE BATERÍA (Evita que el sistema destruya la app en segundo plano)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    isWaitingForSettings = true // Marcamos que salimos de la app hacia los ajustes
                    startActivityForResult(intent, 102) // Envía al usuario a la ventana de exclusión del sistema
                } catch (e: Exception) {
                    showManualInstructionsDialog() // Rescate si el dispositivo bloquea el intent directo
                }
                return
            }
        }

        // 3. PROCESO DE ACTIVACIÓN DE COMPONENTES INTERNOS TRAS SUPERAR LOS PERMISOS
        confirmarActivacionExitosa()
    }
        
    // Función centralizada única para guardar el estado y emitir los Toasts ordenados sin duplicar código
    private fun confirmarActivacionExitosa() {
        saveEnabledState(true)
        switchEnable.isChecked = true
        toggleAppComponents(true)

        // Si el usuario acaba de otorgar un permiso en esta interacción, mostramos el aviso intermedio de éxito
        if (flagPermisoConcedidoAhora) {
            Toast.makeText(this, getString(R.string.toast_permissions_granted), Toast.LENGTH_SHORT).show()
        }
        // Indicamos si app encendida o apagada
        Toast.makeText(this, getString(R.string.toast_app_started, getString(R.string.app_name)), Toast.LENGTH_SHORT).show()
    }
    
    // RETORNO DE PETICIÓN EN TIEMPO REAL: Analiza la acción del usuario sobre el diálogo de notificaciones
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                isPermissionGrantedNow = true // Almacena que el permiso fue aprobado en caliente
                verificarYActivarPermisos() // Continúa evaluando el siguiente eslabón (Optimización de batería)
            } else {
                bloquearSwitchPorFaltaDePermisos()
            }
        }
    }

    // RETORNO DE ACTIVIDAD EXTERNA: Analiza el comportamiento tras volver del menú nativo de batería
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102) {
            // Evaluamos si de regreso de la configuración externa, el permiso fue finalmente aprobado
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager.isIgnoringBatteryOptimizations(packageName)) {
                isPermissionGrantedNow = true
            }
            isWaitingForSettings = false // Restablecemos el monitor general de salida
            verificarYActivarPermisos() // Ejecuta el proceso de activación final
        }
    }

    // CIERRE SEGURIZADO: Apaga el switch principal si se deniega algún requerimiento fundamental de funcionamiento
    private fun bloquearSwitchPorFaltaDePermisos() {
        saveEnabledState(false)
        switchEnable.isChecked = false
        toggleAppComponents(false)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_permission_required_title))
            .setMessage(getString(R.string.dialog_permission_required_text))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.alertdialog_btn)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // FUNCIÓN: Muestra la guía y redirige al usuario de forma directa a los Ajustes de la Aplicación
    private fun showManualInstructionsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_reliability_title))
            .setMessage(getString(R.string.dialog_reliability_text))
            .setCancelable(false) // Impide que se cierre si el usuario pulsa fuera
            .setPositiveButton(getString(R.string.dialog_reliability_btn)) { dialog, _ ->
                dialog.dismiss()
                isWaitingForSettings = true    // Avisamos que el usuario está saliendo activamente a configurar los menús
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
            .setPositiveButton(getString(R.string.alertdialog_btn)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun saveEnabledState(state: Boolean) {
        prefs.edit().putBoolean("enabled", state).apply()
    }

    private fun loadPreferences() {
        switchEnable.isChecked = prefs.getBoolean("enabled", false)
        switchLoop.isChecked = prefs.getBoolean("loop", true)
        switchVibrate.isChecked = prefs.getBoolean("vibrate", true)
        switchSoundDelay.isChecked = prefs.getBoolean("sound_delay", false)
        switchIgnoreSilence.isChecked = prefs.getBoolean("ignore_silence", false)

        val pct = prefs.getInt("target_pct", 100)
        seekPercentage.progress = pct
        tvPercentageLabel.text = getString(R.string.ui_battery_target, pct)
        
        val vol = prefs.getInt("volume", 80)
        seekVolume.progress = vol
        tvVolumeLabel.text = getString(R.string.ui_volume_alert, vol)
        
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            val isAppSavedAsEnabled = prefs.getBoolean("enabled", false)
            
            // EL CANDADO REAL: Solo comprobamos el éxito si el usuario estaba editando los ajustes y acaba de volver
            if (isIgnoring && !isAppSavedAsEnabled && isWaitingForSettings) {
                isWaitingForSettings = false
                isPermissionGrantedNow = true // Marcamos que el ajuste de optimización de batería manual tuvo éxito
                
                // Llamamos a la función centralizada informando del resultado
                confirmarActivacionExitosa()
            }

            // Si la app estaba activa en disco pero el usuario revocó el permiso por detrás en el móvil
            else if (!isIgnoring && isAppSavedAsEnabled) {
                turnOffAppWithDialog(
                    getString(R.string.dialog_permissions_modified_title),
                    getString(R.string.dialog_permissions_modified_text)
                )
            }
        }
    }
}
