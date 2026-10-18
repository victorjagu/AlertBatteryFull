package com.victor.alertbatterylevel

import android.app.Activity
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
import android.widget.Toast

class MainActivity : Activity() {

    // ALMACENAMIENTO: Archivo plano interno para guardar las preferencias del usuario
    private val prefs by lazy { getSharedPreferences("prefs_battery", Context.MODE_PRIVATE) }
    
    // HARDWARE: Gestor de energía del sistema operativo para verificar el ahorro de batería
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    
    // COMPONENTES VISUALES: Declaración tardía de los interruptores y selectores de la pantalla
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
        
        // MODO OSCURO: Avisamos al hardware que aplique el tema negro puro OLED antes de dibujar
        setTheme(R.style.Theme.Theme_AlertBattery)
        
        // INTERFAZ: Inflamos y pintamos el diseño visual definido en el archivo XML
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

        // MEMORIA: Leemos el disco duro para restaurar el estado en el que el usuario dejó la app
        loadPreferences()

        // BLOQUE 1: DESLIZADOR DEL PORCENTAJE DE BATERÍA OBJETIVO
        seekPercentage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // Forzamos un mínimo del 1% para evitar errores de división en el procesador
                val pct = if (progress < 1) 1 else progress
                // Actualizamos el texto en pantalla en tiempo real (Ej: Porcentaje objetivo: 80%)
                tvPercentageLabel.text = "Porcentaje objetivo: $pct%"
                // Guardamos el nuevo porcentaje de forma instantánea en el archivo interno
                prefs.edit().putInt("target_pct", pct).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // BLOQUE 2: INTERRUPTOR PRINCIPAL (ENCENDER / APAGAR LA APP)
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            // Guardamos el estado del interruptor en el disco duro interno
            prefs.edit().putBoolean("enabled", isChecked).apply()
            // Activamos o congelamos los receptores a nivel de sistema operativo
            toggleAppComponents(isChecked)

            // Si el usuario enciende la app, disparamos la auditoría automática de requisitos
            if (isChecked) {
                auditApplicationRequirements()
            }
        }

        // BLOQUE 3: INTERRUPTOR DEL BUCLE DE REPETICIÓN (CADA 10 SEGUNDOS)
        switchLoop.setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean("loop", isChecked).apply() 
        }

        // BLOQUE 4: INTERRUPTOR DE LA VIBRACIÓN NATIVA DE ALERTA
        switchVibrate.setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean("vibrate", isChecked).apply() 
        }

        // BLOQUE 5: DESLIZADOR DE VOLUMEN CON EMISIÓN DE BEEP DE PRUEBA
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var toneGenerator: ToneGenerator? = null

            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // Actualizamos el número del volumen dinámicamente en la pantalla (Ej: 65%)
                tvVolumeLabel.text = "Volumen del aviso: $progress%"
                // Registramos el valor del volumen en el almacenamiento interno
                prefs.edit().putInt("volume", progress).apply()

                // Si el cambio lo hace el usuario arrastrando el dedo, probamos el altavoz
                if (fromUser) {
                    try {
                        if (toneGenerator == null) {
                            // Inicializamos el motor de ondas de hardware con el volumen seleccionado
                            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, progress)
                        }
                        // Emitimos un tono puro digital (Beep) de 100 milisegundos
                        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                    } catch (e: Exception) {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Liberamos el chip de sonido al soltar la barra para dejar la memoria RAM vacía
                toneGenerator?.release()
                toneGenerator = null
            }
        })

        // BLOQUE 6: GRUPO DE BOTONES DE RADIO PARA EL TIPO DE ALERTA (VOZ / BEEP / SILENCIOSO)
        rgSoundType.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.rb_beep -> 1    // Modo 1: Beep de hardware puro
                R.id.rb_silent -> 2  // Modo 2: Solo notificación visual para el Smartwatch
                else -> 0            // Modo 0: Motor de Voz hablada de Google
            }
            // Guardamos el tipo de sonido seleccionado en las preferencias del sistema
            prefs.edit().putInt("sound_type", type).apply()
        }

        // BLOQUE 7: BOTÓN MANUAL DE AUXILIO PARA ABRIR LOS AJUSTES DEL SISTEMA
        btnBatterySettings.setOnClickListener { launchSystemSettings() }
    }

    // FUNCIÓN: Carga los datos guardados en el almacenamiento para poner los botones en su sitio
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

    // FUNCIÓN: Lanzamiento secuencial y obligatorio de los permisos de salud de la aplicación
    private fun auditApplicationRequirements() {
        // CONTROL 1: Permisos de Notificación (Obligatorio a partir de Android 13/14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = "android.permission.POST_NOTIFICATIONS"
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                // Desplegamos la ventana emergente oficial del sistema en mitad de la pantalla
                requestPermissions(arrayOf(permission), 101)
                Toast.makeText(this, "¡OBLIGATORIO! Permite las notificaciones para recibir los avisos de carga.", Toast.LENGTH_LONG).show()
                return // Frenamos la ejecución aquí para obligar al usuario a responder al cuadro
            }
        }

        // CONTROL 2: Optimización de Batería (Saltarse el reposo profundo de Samsung/Xiaomi/Oppo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Comprobamos si el teléfono mantiene la app monitorizada en modo ahorro
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // Forzamos al usuario a ir a la pantalla de configuración interna del móvil
                launchSystemSettings()
                Toast.makeText(this, "¡ATENCIÓN! Cambia la batería de la app a 'SIN RESTRICCIONES' para que funcione en reposo.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // FUNCIÓN: Crea un puente de comunicación para abrir el menú de Ajustes nativo de la app en Android
    private fun launchSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Abre Ajustes -> Aplicaciones para dar permisos manualmente", Toast.LENGTH_SHORT).show()
        }
    }

    // FUNCIÓN: Apagado y encendido radical de componentes para lograr un consumo 0% en OFF
    private fun toggleAppComponents(enable: Boolean) {
        // Si es false, Android borra la app de la lista de ejecución en disco; el móvil la ignora por completo
        val state = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val receiver = ComponentName(this, BatteryReceiver::class.java)
        packageManager.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
    }

    // FUNCIÓN DE VIGILANCIA: Se activa cada vez que la aplicación pasa a primer plano en la pantalla
    override fun onResume() {
        super.onResume()
        // BUCLE DE INSISTENCIA: Si la app está encendida, comprobamos que no hayan quitado los permisos por detrás
        if (switchEnable.isChecked) {
            var needsAudit = false
            
            // Verificación persistente de la alerta de Notificaciones
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    needsAudit = true
                }
            }
            
            // Verificación persistente del permiso de la Batería sin restricciones
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    needsAudit = true
                }
            }

            // Si falta cualquiera de los dos pilares de seguridad, bloqueamos la pantalla y volvemos a insistir
            if (needsAudit) {
                auditApplicationRequirements()
            }
        }
    }

    // FUNCIÓN DE CONSERVACIÓN: Se activa cuando el usuario minimiza la app o bloquea el teléfono
    override fun onPause() {
        super.onPause()
        // Si el interruptor principal está apagado, destruimos la interfaz de la RAM inmediatamente para liberar espacio
        if (!switchEnable.isChecked) {
            finish()
        }
    }
}
