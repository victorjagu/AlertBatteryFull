package com.victor.alertbatteryfull

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

class BatteryReceiver : BroadcastReceiver() {

    companion object {
        private const val NOTIFICATION_ID = 9911
        private const val CHANNEL_ID = "battery_alert_channel"
        
        // Hilos ligeros y variables estáticas para controlar los bucles en memoria sin servicios pesados
        private var loopHandler: Handler? = null
        private var loopRunnable: Runnable? = null
        private var isAlertActive = false
        private var monitoringHandler: Handler? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("prefs_battery", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)
        if (!isEnabled) return

        val action = intent.action

        // CASO NUEVO: El usuario borró la notificación desde el móvil o Smartwatch
        if (action == "ACTION_CANCEL_ALERTS_MANUAL") {
            // Apagamos los hilos de audio y el TTS para que no suene nada
            stopAlerts() 
            return
        }

        // CASO 1: SE DESCONECTA EL CARGADOR -> Limpieza absoluta inmediata de RAM y alertas
        if (action == Intent.ACTION_POWER_DISCONNECTED) {
            stopAlerts()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            return
        }

        // CASO 2: REINICIO DEL MÓVIL -> Android despierta la app un milisegundo para asegurar persistencia
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            // No hacemos nada más; el simple hecho de pasar por aquí con la app "enabled" 
            // hace que Android refresque los bindings de los receptores en su disco duro.
            return
        }

        // CASO 3: SE CONECTA EL CARGADOR O CAMBIA LA BATERÍA
        if (action == Intent.ACTION_POWER_CONNECTED || action == Intent.ACTION_BATTERY_CHANGED) {
            
            // FILTRO DE ULTRA-EFICIENCIA: Verificar tipo de carga (Descartar USB de ordenador/coche)
            val batteryStatus: Intent? = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            
            // Si está cargando por USB (PC/Android Auto), abortamos inmediatamente para consumir 0%
            if (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) {
                if (action == Intent.ACTION_POWER_CONNECTED) {
                    stopAlerts()
                }
                return
            }

            val currentPct = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val targetPct = prefs.getInt("target_pct", 100)

            // ARQUITECTURA HÍBRIDA INTELIGENTE:
            // Si el usuario quiere el 100% (Modo A), dependemos del estado nativo de Android "Cargada/Llena"
            // Si quiere menos del 100% (Modo B), monitorizamos el porcentaje numérico exacto
            val isFull = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL
            
            if ((targetPct == 100 && isFull) || (targetPct < 100 && currentPct >= targetPct)) {
                if (!isAlertActive) {
                    isAlertActive = true
                    triggerAlert(context, targetPct)
                }
            } else {
                // Si se conecta el cargador y el límite es personalizado (<100),
                // iniciamos el chequeo interno ligero y seguro de Android 14
                if (action == Intent.ACTION_POWER_CONNECTED && targetPct < 100) {
                    startBatteryMonitorLoop(context, targetPct)
                }
            }
        }
    }
    
    private fun triggerAlert(context: Context, targetPct: Int) {
        val prefs = context.getSharedPreferences("prefs_battery", Context.MODE_PRIVATE)
        val soundType = prefs.getInt("sound_type", 0)     // 0 = Voz, 1 = Beep, 2 = Silencioso
        val useLoop = prefs.getBoolean("loop", true)       // Saber si el usuario quiere repetición
        val useVibrate = prefs.getBoolean("vibrate", true) // Saber si quiere vibración
        val targetVolume = prefs.getInt("volume", 80)     // El volumen elegido en la barra
        val useSoundDelay = prefs.getBoolean("sound_delay", false) // El retardo de sonido de 1 minuto
        val ignoreSilence = prefs.getBoolean("ignore_silence", false)    //ignorar el modo silencio/vibracion del movil

        // 1. Pintamos de inmediato la notificación visual nativa y eficiente que optimizamos
        NotificationHelper.showNotification(context, targetPct)

        // 2. Preparamos el temporizador ligero en memoria de la app
        if (loopHandler == null) {
            loopHandler = Handler(Looper.getMainLooper())
        }
        loopHandler?.removeCallbacksAndMessages(null)
        
        var isAudioAllowedToPlay = ! useSoundDelay

        loopRunnable = object : Runnable {
            override fun run() {
                // Si el usuario activó la vibración, ejecutamos el latido de hardware de forma atómica
                if (useVibrate) executeVibration(context)

                // Si el tipo de sonido NO es silencioso (tipo 2) y la llave de tiempo permite sonar
                if (soundType != 2 && isAudioAllowedToPlay) {
                    // Respetamos estrictamente si el móvil general está con el ringer en modo normal
                    lanzarAudioService(context, soundType, targetVolume, targetPct)
                }

                // Si el bucle sigue activo, volvemos a programar esta comprobación en 10 segundos
                if (useLoop && isAlertActive) {
                    loopHandler?.postDelayed(this, 10000)
                }
            }
        }

        // Arrancamos el bucle de la alarma de inmediato
        loopHandler?.post(loopRunnable!!)

        // Lógica del interruptor de retardo (esperar 1 minuto antes de dar paso al sonido)
        if (soundType != 2 && useSoundDelay) {
            loopHandler?.postDelayed({
                if (isAlertActive) {
                    isAudioAllowedToPlay = true
                    lanzarAudioService(context, soundType, targetVolume, targetPct)
                }
            }, 60000) // Retardo de 60 segundos exactos
        }
    }

    private fun executeVibration(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(android.os.VibrationEffect.createOneShot(400, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(400)
        }
    }

    private fun stopAlerts() {
        isAlertActive = false
        // Detener hilos de tiempo de forma absoluta
        loopHandler?.removeCallbacksAndMessages(null)
        loopHandler = null
        loopRunnable = null
        // Detener el chequeo silencioso del startBatteryMonitorLoop, si existiera
        monitoringHandler?.removeCallbacksAndMessages(null)
        monitoringHandler = null
    }

    private fun startBatteryMonitorLoop(context: Context, targetPct: Int) {
        // Si no existe el handler, lo creamos una sola vez
        if (monitoringHandler == null) {
            monitoringHandler = Handler(Looper.getMainLooper())
        }
        // Cancelamos cualquier tarea programada vieja para que no se dupliquen hilos
        monitoringHandler?.removeCallbacksAndMessages(null)
    
        val checkRunnable = object : Runnable {
            override fun run() {
                val prefs = context.getSharedPreferences("prefs_battery", Context.MODE_PRIVATE)
                val isEnabled = prefs.getBoolean("enabled", false)
                
                // Si la app se desactivó o ya saltó la alarma, paramos el temporizador
                if (!isEnabled || isAlertActive) {
                    monitoringHandler = null
                    return
                }

                // Consultamos directamente al hardware usando el BatteryManager nativo
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val currentPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                if (currentPct >= targetPct) {
                    isAlertActive = true
                    triggerAlert(context, targetPct)
                    monitoringHandler?.removeCallbacksAndMessages(null)
                    monitoringHandler = null // Alarma activada, apagamos este chequeo
                } else {
                    // Si no ha llegado al porcentaje, vuelve a consultar en 60 segundos
                    monitoringHandler?.postDelayed(this, 60000)
                }
            }
        }
        monitoringHandler?.post(checkRunnable)
    }

    private fun lanzarAudioService(context: Context, soundType: Int, targetVolume: Int, targetPct: Int) {
        val prefs = context.getSharedPreferences("prefs_battery", Context.MODE_PRIVATE)
        val ignoreSilence = prefs.getBoolean("ignore_silence", false)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (ignoreSilence || am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                // NUEVO: Lanzamos la orden relámpago hacia nuestro AudioService especialista
                val serviceIntent = Intent(context, AudioService::class.java).apply {
                    putExtra("sound_type", soundType)   // Le pasamos el tipo (Voz o Beep)
                    putExtra("volume", targetVolume)     // Le pasamos el volumen de la barra
                    putExtra("target_pct", targetPct)   // El porcentaje real de carga
                    putExtra("is_test", false)          // Indicamos que es la alarma real, NO una prueba
                }
                context.startService(serviceIntent)
            } catch (e: Exception) {}
        }
    }
}
