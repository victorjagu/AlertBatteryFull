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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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

    // Evita que el interruptor principal active la app durante una comprobación.
    private var isUpdatingEnableSwitch = false
    
    // Indica si se ha iniciado una solicitud de permisos y debe mostrarse el Toast de éxito.
    private var permissionRequestWasNeeded = false

    // Añade el Handler global para evitar fugas de memoria
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // COMPONENTES VISUALES: Inicialización perezosa de bajo consumo (evita sobrecargar el arranque de la app) de los interruptores y selectores de la pantalla
    private val switchEnable by lazy { findViewById<Switch>(R.id.switch_enable) }
    private val switchLoop by lazy { findViewById<Switch>(R.id.switch_loop) }
    private val switchVibrate by lazy { findViewById<Switch>(R.id.switch_vibrate) }
    private val seekPercentage by lazy { findViewById<SeekBar>(R.id.seek_percentage) }
    private val seekVolume by lazy { findViewById<SeekBar>(R.id.seek_volume) }
    private val tvPercentageLabel by lazy { findViewById<TextView>(R.id.tv_percentage_label) }
    private val tvVolumeLabel by lazy { findViewById<TextView>(R.id.tv_volume_label) }
    private val rgSoundType by lazy { findViewById<RadioGroup>(R.id.rg_sound_type) }
    private val btnBatterySettings by lazy { findViewById<Button>(R.id.btn_battery_settings) }
    private val switchSoundDelay by lazy { findViewById<Switch>(R.id.switch_sound_delay) }
    private val switchIgnoreSilence by lazy { findViewById<Switch>(R.id.switch_ignore_silence) }
    private val switchCustomVolume by lazy { findViewById<Switch>(R.id.switch_custom_volume)}
    private val layoutCustomVolumeContainer by lazy { findViewById<LinearLayout>(R.id.layout_volume_container)}
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MODO OSCURO: Aplicamos el tema negro puro OLED antes de dibujar las vistas
        setTheme(R.style.Theme_AlertBattery)

        // INTERFAZ: Inflamos el diseño visual definido en el archivo XML
        setContentView(R.layout.activity_main)
        
        // AUDIO DE NOTIFICACIONES: Sincroniza el slider físico con el canal del sistema
        volumeControlStream = android.media.AudioManager.STREAM_NOTIFICATION

        // INTERRUPTOR DE RETARDO DE SONIDO
        switchSoundDelay.setOnCheckedChangeListener { _, isChecked -> savePref("sound_delay", isChecked) }

        // INTERRUPTOR PARA IGNORAR EL MODO SILENCIO/VIBRACION DEL MOVIL
        switchIgnoreSilence.setOnCheckedChangeListener { _, isChecked -> savePref("ignore_silence", isChecked) }
        
        // INTERRUPTOR DE VOLUMEN PERSONALIZADO O DEL SISTEMA
        switchCustomVolume.setOnCheckedChangeListener { _, isChecked -> savePref("custom_volume", isChecked)
            updateCustomVolumeVisibility(isChecked)
        }
        
        // INTERRUPTOR DEL BUCLE DE REPETICIÓN
        switchLoop.setOnCheckedChangeListener { _, isChecked -> savePref("loop", isChecked) }

        // MEMORIA: Restauramos el estado guardado de la app
        loadPreferences()

        // CONTROL DE PORCENTAJE OBJETIVO (SeekBar con límites simplificados)
        seekPercentage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // Operación ultra-ligera: solo actualiza el texto en pantalla
                val pct = if (progress < 1) 1 else progress
                tvPercentageLabel.text = getString(R.string.ui_battery_target, pct)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Guarda en el disco del teléfono UNA SOLA VEZ cuando el usuario suelta el dedo
                val pct = sb?.progress ?: 100
                val finalPct = if (pct < 1) 1 else pct
                savePref("target_pct", finalPct)
            }
        })

        // INTERRUPTOR PRINCIPAL: Solo permite activar la app si ambos permisos están concedidos.
        switchEnable.setOnCheckedChangeListener { _, isChecked ->

            // Ignora los cambios internos realizados por el propio código.
            if (isUpdatingEnableSwitch) return@setOnCheckedChangeListener

            if (isChecked) {

                // Comprueba los dos permisos antes de activar la aplicación.
                if (!areBothPermissionsGranted()) {

                    // Mantiene el interruptor apagado si falta algún permiso.
                    actualizarInterruptorPrincipal(false)
                    saveEnabledState(false)
                    toggleAppComponents(false)

                    // Solicita los permisos que falten, pero no activa la aplicación.
                    permissionRequestWasNeeded = true
                    solicitarPermisosPendientes()

                } else {

                    // Los dos permisos están concedidos: permite activar la aplicación.
                    saveEnabledState(true)
                    toggleAppComponents(true)

                    Toast.makeText(
                        this,
                        getString(R.string.toast_app_started, getString(R.string.app_name)),
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } else {

                // Desactiva normalmente la aplicación.
                saveEnabledState(false)
                toggleAppComponents(false)

                Toast.makeText(
                    this,
                    getString(R.string.toast_app_disabled, getString(R.string.app_name)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // INTERRUPTOR DE LA VIBRACIÓN: Guarda la preferencia y ejecuta una prueba de vibración desde AudioService.
        switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            savePref("vibrate", isChecked)

            if (isChecked) {
                // Solicita una prueba de vibración sin reproducir sonido.
                val vibrationIntent = Intent(this, AudioService::class.java).apply {
                    putExtra("vibrate", true)
                    putExtra("play_sound", false)
                }
                startService(vibrationIntent)
            }
        }
        
        // DESLIZADOR DE VOLUMEN OPTIMIZADO
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // Operación ligera: solo cambia el texto visual mientras mueves el dedo
                tvVolumeLabel.text = getString(R.string.ui_custom_volume_level, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // El usuario soltó la barra: guardamos en disco y disparamos la prueba al mismo tiempo
                val currentProgress = sb?.progress ?: 80
                
                // 1. Guardado único en almacenamiento
                savePref("volume", currentProgress)
                
                // 2. Ejecución del pitido de prueba
                val serviceIntent = Intent(this@MainActivity, AudioService::class.java).apply {
                    putExtra("sound_type", 1)               
                    putExtra("volume", currentProgress)   
                    putExtra("target_pct", seekPercentage.progress)           
                }
                startService(serviceIntent)
            }
        })

        // SELECTOR DE TIPO DE SONIDO CON PRUEBAS FÍSICAS SIMPLIFICADAS
        rgSoundType.setOnCheckedChangeListener { _, checkedId ->
            // Calculamos el tipo y el volumen actual para la prueba
            val type = when (checkedId) {
                R.id.rb_beep -> 1    // Modo 1: Beep de hardware
                R.id.rb_silent -> 2  // Modo 2: Solo mensaje visual
                else -> 0            // Modo 0: Alerta por Voz
            }
            savePref("sound_type", type)
            
            val currentVolume = seekVolume.progress
            val currentTargetPct = seekPercentage.progress

            // Invoca al servicio de audio de forma unificada (0 = Voz, 1 = Beep)
            if (type == 0 || type == 1) {
                val serviceIntent = Intent(this, AudioService::class.java).apply {
                    putExtra("sound_type", type)             // Volumen seleccionado en la barra
                    putExtra("volume", currentVolume)        // Volumen seleccionado en la barra
                    putExtra("target_pct", currentTargetPct) // Porcentaje seleccionado en la barra
                }
                startService(serviceIntent)
                
            // CASO SOLO MENSAJE: Muestra en la ventana el texto idéntico que se enviará como notificación
            } else if (type == 2) {
                // Muestra la notificación nativa real de forma inmediata
                NotificationHelper.showNotification(this, currentTargetPct)
                // Usamos el handler centralizado cancelando cualquier temporizador de prueba previo y usamos la ventana de la pantalla para cancelarla a los 7 segundos
                mainHandler.removeCallbacksAndMessages(null)
                mainHandler.postDelayed({
                    (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.cancel(9911) // Usamos el ID 9911 fijado en el BatteryReceiver
                }, 7000)
            }
        }

        // BOTÓN MANUAL DE INSTRUCCIONES
        btnBatterySettings.setOnClickListener { showManualInstructionsDialog() }

        // Comprueba los permisos cada vez que se abre la pantalla principal.
        comprobarPermisosAlAbrir()
        
    }

    // Comprueba si los dos permisos obligatorios están concedidos.
    private fun areBothPermissionsGranted(): Boolean {

        // En Android 13 o superior comprueba el permiso de notificaciones.
        val notificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
            PackageManager.PERMISSION_GRANTED

        // En Android 6 o superior comprueba la exclusión de optimización de batería.
        val batteryGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            powerManager.isIgnoringBatteryOptimizations(packageName)

        return notificationsGranted && batteryGranted
    }

    // Comprueba los permisos al abrir la aplicación.
    private fun comprobarPermisosAlAbrir() {

        if (areBothPermissionsGranted()) {
            return
        }

        // Si la app estaba activada y falta un permiso, la desactiva.
        if (prefs.getBoolean("enabled", false)) {
            saveEnabledState(false)
            toggleAppComponents(false)
            actualizarInterruptorPrincipal(false)
        }

        permissionRequestWasNeeded = true
        solicitarPermisosPendientes()
    }

    // Solicita únicamente los permisos que todavía faltan.
    private fun solicitarPermisosPendientes() {

        // Primero comprueba las notificaciones.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf("android.permission.POST_NOTIFICATIONS"),
                101
            )
            return
        }

        // Después comprueba la exclusión de optimización de batería.
        solicitarExclusionBateriaSiFalta()
    }

    // Abre la pantalla del sistema si falta la exclusión de batería.
    private fun solicitarExclusionBateriaSiFalta() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !powerManager.isIgnoringBatteryOptimizations(packageName)
        ) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).apply {
                data = Uri.parse("package:$packageName")
            }

            try {
                isWaitingForSettings = true
                startActivityForResult(intent, 102)

            } catch (e: Exception) {
                isWaitingForSettings = false
                showManualInstructionsDialog()
            }

            return
        }

        finalizarComprobacionPermisos()
    }

    // Comprueba el resultado final de los dos permisos.
    private fun finalizarComprobacionPermisos() {

        val allGranted = areBothPermissionsGranted()

        if (allGranted) {

            // Solo muestra el Toast si realmente se había iniciado una solicitud.
            if (permissionRequestWasNeeded) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_permissions_granted),
                    Toast.LENGTH_SHORT
                ).show()
            }

        } else {

            // No permite activar la aplicación si falta algún permiso.
            bloquearSwitchPorFaltaDePermisos()
        }

        permissionRequestWasNeeded = false
    }

    // Actualiza el interruptor sin volver a ejecutar su listener.
    private fun actualizarInterruptorPrincipal(checked: Boolean) {

        isUpdatingEnableSwitch = true
        switchEnable.isChecked = checked
        isUpdatingEnableSwitch = false
    }

    // Resultado de la solicitud del permiso de notificaciones.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 101) {

            // Continúa comprobando la exclusión de batería, aunque se haya denegado notificaciones.
            solicitarExclusionBateriaSiFalta()
        }
    }

    // Resultado de la pantalla de exclusión de optimización de batería.
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 102) {

            isWaitingForSettings = false

            // Comprueba de nuevo ambos permisos, no solo el de batería.
            finalizarComprobacionPermisos()
        }
    }

    // CIERRE SEGURIZADO: Apaga el switch principal si se deniega algún requerimiento fundamental de funcionamiento
    private fun bloquearSwitchPorFaltaDePermisos() {
        saveEnabledState(false)
        // Apaga el interruptor sin volver a ejecutar su listener.
        actualizarInterruptorPrincipal(false)
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
                launchSystemSettings()         // Abre la ventana nativa de la app en el móvil
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

    private fun updateCustomVolumeVisibility(show: Boolean) {
        if (show) {
            layoutCustomVolumeContainer.visibility = View.VISIBLE
        } else {
            layoutCustomVolumeContainer.visibility = View.GONE
        }
    }
    
    // UNIFICAMOS ESCRITURA DE VALORES EN DISCO
    private fun savePref(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    private fun savePref(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    private fun saveEnabledState(state: Boolean) = savePref("enabled", state)

    private fun loadPreferences() {
        with(prefs) {
            switchEnable.isChecked = getBoolean("enabled", false)
            switchLoop.isChecked = getBoolean("loop", false)
            switchVibrate.isChecked = getBoolean("vibrate", false)
            switchSoundDelay.isChecked = getBoolean("sound_delay", false)
            switchIgnoreSilence.isChecked = getBoolean("ignore_silence", false)

            val isCustomVolume = getBoolean("custom_volume", false)
            switchCustomVolume.isChecked = isCustomVolume
            updateCustomVolumeVisibility(isCustomVolume)
            
            val pct = getInt("target_pct", 100)
            seekPercentage.progress = pct
            tvPercentageLabel.text = getString(R.string.ui_battery_target, pct)
            
            val vol = getInt("volume", 80)
            seekVolume.progress = vol
            tvVolumeLabel.text = getString(R.string.ui_custom_volume_level, vol)
            
            rgSoundType.check(when (getInt("sound_type", 0)) {
                1 -> R.id.rb_beep
                2 -> R.id.rb_silent
                else -> R.id.rb_voice
            })
        }
    }

    private fun launchSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        // Espera el regreso de Ajustes para comprobar de nuevo los permisos.
        startActivityForResult(intent, 102)
    }

    private fun toggleAppComponents(enable: Boolean) {
        val state = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val receiver = ComponentName(this, BatteryReceiver::class.java)
        packageManager.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
    }

    // Comprueba los permisos al regresar a la aplicación.
    override fun onResume() {
        super.onResume()

        // Si la aplicación estaba activa y se ha revocado algún permiso,
        // la desactiva inmediatamente.
        if (
            !isWaitingForSettings &&
            prefs.getBoolean("enabled", false) &&
            !areBothPermissionsGranted()
        ) {
            turnOffAppWithDialog(
                getString(R.string.dialog_permissions_modified_title),
                getString(R.string.dialog_permissions_modified_text)
            )
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

}
