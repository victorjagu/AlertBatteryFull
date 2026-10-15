package com.victor.alertbatterylevel

// Importaciones oficiales requeridas para la gestión de energía, redes y accesibilidad de audio
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import androidx.core.app.NotificationCompat
import java.util.Locale

class BatteryMonitorService : Service(), TextToSpeech.OnInitListener {

    private var powerReceiver: BroadcastReceiver? = null
    private var periodicReceiver: BroadcastReceiver? = null
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentSsid: String = ""

    // Identificadores numéricos únicos para los canales de notificaciones de Android
    private val CHANNEL_ID = "BatteryMonitorChannel"
    private val NOTIFICATION_ID = 1
    private val ALERT_HIGH_ID = 2
    private val ALERT_LOW_ID = 3

    // Banderas de control para evitar que la alerta suene repetidas veces en el mismo porcentaje
    private var triggeredHigh = false
    private var triggeredLow = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("BatteryAlertPrefs", Context.MODE_PRIVATE)
    
        // Creación obligatoria del canal de comunicación visual
        createNotificationChannel()
        
        // CORRECCIÓN ANDROID 14: Gestión segura del tipo de servicio en primer plano
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildForegroundNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Exception) {
                // Fallback de emergencia si el sistema deniega el token en el primer milisegundo
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
            }
        } else {
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
        }
    
        // Inicialización del motor de síntesis de voz
        tts = TextToSpeech(this, this)
        registrarCallbackWifi()
        
        // --- OPTIMIZACIÓN ENERGÉTICA ---
        // 1. Evaluamos el estado actual inmediatamente al arrancar el servicio
        evaluarEstadoActual()

        // 2. Escuchamos de forma pasiva SOLO cuando se conecta o desconecta el cargador físico
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                evaluarEstadoActual()
                gestionarReceptorPeriodico() // Activa o desactiva el monitoreo continuo dinámicamente
            }
        }
        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, powerFilter)

        // 3. Inicializa el comportamiento del receptor periódico adaptativo
        gestionarReceptorPeriodico()
    }

    /**
     * Evalúa el porcentaje y la carga usando consultas directas a la API del sistema (sin eventos continuos).
     */
    private fun evaluarEstadoActual() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        // Consultar de forma segura si está cargando a través del registro pasivo del sistema
        val intentBateria = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intentBateria?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                         status == BatteryManager.BATTERY_STATUS_FULL

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (isCharging) {
            // Si el móvil está cargando, reseteamos las banderas de batería baja de forma inmediata
            triggeredLow = false
            manager.cancel(ALERT_LOW_ID)

            val highEnabled = prefs.getBoolean("high_enabled", false)
            val highLevel = prefs.getInt("high_level", 100)

            if (highEnabled && batteryPct >= highLevel) {
                if (!triggeredHigh) {
                    lanzarAlerta(true) // Disparar alerta de carga completa
                    triggeredHigh = true
                }
            } else if (batteryPct < highLevel) {
                triggeredHigh = false
            }
        } else {
            // Si el móvil se desconecta, reseteamos las banderas de batería alta
            triggeredHigh = false
            manager.cancel(ALERT_HIGH_ID)

            val lowEnabled = prefs.getBoolean("low_enabled", false)
            val lowLevel = prefs.getInt("low_level", 20)

            if (lowEnabled && batteryPct <= lowLevel) {
                if (!triggeredLow) {
                    lanzarAlerta(false) // Disparar alerta de batería crítica
                    triggeredLow = true
                }
            } else if (batteryPct > lowLevel) {
                triggeredLow = false
            }
        }
    }

    /**
     * CONTROL ADAPTATIVO: Solo escucha cambios de porcentaje si es estrictamente necesario.
     */
    private fun gestionarReceptorPeriodico() {
        val intentBateria = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intentBateria?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                         status == BatteryManager.BATTERY_STATUS_FULL

        val highEnabled = prefs.getBoolean("high_enabled", false)
        val lowEnabled = prefs.getBoolean("low_enabled", false)

        // ¿Es necesario escuchar los cambios continuos de batería ahora mismo?
        val necesitaMonitoreo = (isCharging && highEnabled && !triggeredHigh) || 
                                (!isCharging && lowEnabled && !triggeredLow)

        if (necesitaMonitoreo) {
            if (periodicReceiver == null) {
                periodicReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                            evaluarEstadoActual()
                        }
                    }
                }
                // Usamos el filtro normal, pero SOLO estará activo en las ventanas de tiempo críticas
                registerReceiver(periodicReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        } else {
            // Si ya se disparó la alerta o el interruptor está apagado, removemos el receptor para ahorrar batería
            periodicReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: Exception) { e.printStackTrace() }
                periodicReceiver = null
            }
        }
    }

    private fun registrarCallbackWifi() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
    
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            networkCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val wifiInfo = caps.transportInfo as? WifiInfo
                    if (wifiInfo != null) {
                        var ssid = wifiInfo.ssid ?: ""
                        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                            ssid = ssid.substring(1, ssid.length - 1)
                        }
                        currentSsid = ssid
                    }
                }
                override fun onLost(network: Network) { currentSsid = "" }
            }
        } else {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val info = wifiManager.connectionInfo
                    var ssid = info?.ssid ?: ""
                    if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length - 1)
                    }
                    currentSsid = ssid
                }
                override fun onLost(network: Network) { currentSsid = "" }
            }
        }
        networkCallback?.let { connectivityManager.registerNetworkCallback(networkRequest, it) }
    }

    private fun lanzarAlerta(isHigh: Boolean) {
        val prefix = if (isHigh) "high_" else "low_"
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode = audioManager.ringerMode
        val sonarSiempre = prefs.getBoolean("${prefix}always_sound", true)
        
        if (!sonarSiempre && ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            lanzarNotificacionVisual(isHigh)
            return
        }

        val wifiFiltrado = prefs.getString("wifi_ssids", "") ?: ""
        if (wifiFiltrado.isNotEmpty()) {
            val listaSsids = wifiFiltrado.split(",").map { it.trim() }
            if (currentSsid !in listaSsids) {
                lanzarNotificacionVisual(isHigh)
                return 
            }
        }

        lanzarNotificacionVisual(isHigh)

        val volumenBeep = prefs.getFloat("${prefix}vol_beep", 0.5f)
        val volumenVoz = prefs.getFloat("${prefix}vol_tts", 0.5f)

        if (prefs.getBoolean("${prefix}use_beep", false)) {
            reproducirBeep(volumenBeep)
        }

        if (prefs.getBoolean("${prefix}use_tts", false)) {
            val mensajeVoz = prefs.getString("${prefix}msg_tts", "Aviso de batería") ?: ""
            reproducirVoz(mensajeVoz, volumenVoz)
        }

        if (prefs.getBoolean("${prefix}use_vibrate", false)) {
            ejecutarVibracion()
        }
        
        // Apagamos el monitoreo continuo inmediatamente tras dar la alerta para congelar el consumo de energía
        gestionarReceptorPeriodico()
    }

    private fun lanzarNotificacionVisual(isHigh: Boolean) {
        val prefix = if (isHigh) "high_" else "low_"
        val idNotif = if (isHigh) ALERT_HIGH_ID else ALERT_LOW_ID
        val titulo = if (isHigh) "BatteryAlert: Carga Completa" else "BatteryAlert: Batería Baja"
        val msg = prefs.getString("${prefix}msg_notif", "Revisa el cargador") ?: ""

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle(titulo)
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(idNotif, builder.build())
    }

    private fun reproducirBeep(volumen: Float) {
        try {
            val volumeInt = (volumen * 100).toInt()
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, volumeInt)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun reproducirVoz(texto: String, volumen: Float) {
        if (isTtsInitialized && tts != null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
                
            tts?.setAudioAttributes(audioAttributes)
            
            val params = android.os.Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumen)
            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, params, "BatteryAlertTTS")
        }
    }

    private fun ejecutarVibracion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(500)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BatteryAlert Activo")
            .setContentText("Vigilando el nivel de batería de manera eficiente...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitor de Batería Servicio",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Canal para las alertas en segundo plano de BatteryAlert"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            isTtsInitialized = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            powerReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { e.printStackTrace() }
        
        try {
            periodicReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { e.printStackTrace() }
        
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) { e.printStackTrace() }
        
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
        }
    }
}
