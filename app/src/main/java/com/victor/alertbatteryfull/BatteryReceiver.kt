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
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

class BatteryReceiver : BroadcastReceiver() {

    companion object {
        private const val NOTIFICATION_ID = 9911
        private const val CHANNEL_ID = "battery_alert_channel"
        
        // Hilos ligeros y variables estáticas para controlar los bucles en memoria sin servicios pesados
        private var loopHandler: Handler? = null
        private var loopRunnable: Runnable? = null
        private var tts: TextToSpeech? = null
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
                    startModoBCheck(context, targetPct)
                }
            }
        }
    }
    
    private fun triggerAlert(context: Context, targetPct: Int) {
        val prefs = context.getSharedPreferences("prefs_battery", Context.MODE_PRIVATE)
        val soundType = prefs.getInt("sound_type", 0) // 0 = Voz, 1 = Beep, 2 = Silencioso
        val useLoop = prefs.getBoolean("loop", true)
        val useVibrate = prefs.getBoolean("vibrate", true)
        val targetVolume = prefs.getInt("volume", 80)
        val useSoundDelay = prefs.getBoolean("sound_delay", false)

        // 1. Mostrar notificación nativa en el móvil al instante (para el Smartwatch o por si el usuario tiene el movil en la mano encendido)
        NotificationHelper.showNotification(context, targetPct)

        // Variables de control de hilos independientes
        loopHandler = Handler(Looper.getMainLooper())
        var isAudioAllowedToPlay = !useSoundDelay // Si no hay retardo, el audio puede sonar desde el segundo cero

        // 2. BUCLE PRINCIPAL DE VIBRACIÓN (Se ejecuta de forma continua cada 10 segundos)
        loopRunnable = object : Runnable {
            override fun run() {
                // CONDICIÓN 1: La vibración depende exclusivamente del ajuste de la app
                if (useVibrate) {
                    executeVibration(context)
                }

                // CONDICIÓN 2: El audio solo suena si ya ha pasado el tiempo de cortesía o no estaba activo
                if (soundType != 2 && isAudioAllowedToPlay) {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                        if (soundType == 0) {
                            playVoiceAlert(context, targetPct, targetVolume)
                        } else if (soundType == 1) {
                            playBeepAlert(targetVolume)
                        }
                    }
                }

                // Planificar la siguiente repetición del bucle a los 10 segundos exactos
                if (useLoop && isAlertActive) {
                    loopHandler?.postDelayed(this, 10000)
                }
            }
        }

        // Arrancamos el bucle físico (vibración inmediata)
        loopHandler?.post(loopRunnable!!)

        // 3. HILO DE DESBLOQUEO DEL AUDIO (Esperará 1 minuto si el retardo está activo)
        if (soundType != 2 && useSoundDelay) {
            loopHandler?.postDelayed({
                if (isAlertActive) {
                    isAudioAllowedToPlay = true
                    // Forzamos una reproducción de audio inmediata al cumplir el minuto, 
                    // ya que el bucle principal podría estar en mitad de una cuenta atrás de 10s
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                        if (soundType == 0) playVoiceAlert(context, targetPct, targetVolume)
                        else if (soundType == 1) playBeepAlert(targetVolume)
                    }
                }
            }, 60000) // 1 minuto de retardo estricto
        }
    }

    private fun playVoiceAlert(context: Context, pct: Int, volume: Int) {
        // Recuperamos de forma dinámica el mensaje del diccionario según el porcentaje real
        val message = if (pct == 100) {
            context.getString(R.string.msg_voice_full)
        } else {
            context.getString(R.string.msg_voice_custom, pct)
        }

        // Inicialización ligera y al vuelo del motor TextToSpeech nativo de Google
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { t ->
                    t.language = Locale.getDefault()
                    // Configuramos el volumen del flujo de música/notificación de forma segura
                    val bundle = android.os.Bundle()
                    bundle.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume / 100f)
                    t.speak(message, TextToSpeech.QUEUE_FLUSH, bundle, "BatteryAlertID")
                }
            }
        }
    }

    private fun playBeepAlert(volume: Int) {
        try {
            // Usamos el ToneGenerator de hardware nativo para generar un Beep puro sin cargar archivos pesados en RAM
            // Mapeamos el volumen al rango oficial (0-100)
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 250) // Beep de 250 milisegundos
        } catch (e: Exception) {
            // Salvaguarda si el hardware de sonido está ocupado
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
        // Detener hilos de tiempo
        loopHandler?.removeCallbacks(loopRunnable ?: return)
        loopHandler = null
        loopRunnable = null
        // Detener el chequeo silencioso del startBatteryMonitorLoop, si existiera
        monitoringHandler = null 
        // Destruir por completo el motor de voz de la RAM
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun startBatteryMonitorLoop(context: Context, targetPct: Int) {
        if (monitoringHandler != null) return // Si ya está corriendo, no lo duplicamos
        
        monitoringHandler = Handler(Looper.getMainLooper())
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
                    monitoringHandler = null // Alarma activada, apagamos este chequeo
                } else {
                    // Si no ha llegado al porcentaje, vuelve a consultar en 60 segundos
                    monitoringHandler?.postDelayed(this, 60000)
                }
            }
        }
        monitoringHandler?.post(checkRunnable)
    }

}
