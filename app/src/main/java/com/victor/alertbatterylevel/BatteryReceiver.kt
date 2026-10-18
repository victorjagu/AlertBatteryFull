package com.victor.alertbatterylevel

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
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("prefs_battery", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)
        if (!isEnabled) return

        val action = intent.action

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
                // Si el cargador se conectó pero aún no llega al porcentaje, y estábamos en modo personalizado,
                // nos aseguramos de registrar dinámicamente el receptor paso a paso sólo mientras dure la carga.
                if (action == Intent.ACTION_POWER_CONNECTED && targetPct < 100) {
                    val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    context.applicationContext.registerReceiver(this, filter)
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

        // 1. Mostrar notificación nativa en el móvil (que se replica en cualquier Smartwatch: ZeppOS, WearOS, Huawei, etc.)
        showNotification(context, targetPct)

        // 2. Si es modo 100% silencioso visual, terminamos aquí para el mínimo consumo energético
        if (soundType == 2) return

        // 3. Crear el bucle temporal de alertas cada 10 segundos
        loopHandler = Handler(Looper.getMainLooper())
        loopRunnable = object : Runnable {
            override fun run() {
                // Comprobar el volumen y modo de sonido actual del sistema para respetar el silencio del usuario
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val ringerMode = am.ringerMode

                if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    // Ejecutar sonido según la configuración elegida
                    if (soundType == 0) {
                        playVoiceAlert(context, targetPct, targetVolume)
                    } else if (soundType == 1) {
                        playBeepAlert(targetVolume)
                    }
                }

                // Ejecutar vibración si está activa (funciona en modo Normal y modo Vibración del sistema)
                if (useVibrate && ringerMode != AudioManager.RINGER_MODE_SILENT) {
                    executeVibration(context)
                }

                // Si el bucle está activado por el usuario, re-programar para dentro de 10 segundos
                if (useLoop && isAlertActive) {
                    loopHandler?.postDelayed(this, 10000)
                }
            }
        }

        // Arrancar el primer aviso de inmediato
        loopHandler?.post(loopRunnable!!)
    }

    private fun playVoiceAlert(context: Context, pct: Int, volume: Int) {
        val message = if (pct == 100) {
            "¡Batería cargada! Desconéctame por favor."
        } else {
            "Batería al $pct por ciento. Desconéctame por favor."
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

    private fun showNotification(context: Context, pct: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Alertas de Batería", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisos de carga completa optimizados"
                enableLights(true)
            }
            nm.createNotificationChannel(channel)
        }

        val title = "¡Carga Alcanzada!"
        val text = if (pct == 100) "El dispositivo se ha cargado por completo." else "La batería ha llegado al $pct% establecido."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.resources.getIdentifier("ic_launcher", "mipmap", context.packageName))
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)

        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun stopAlerts() {
        isAlertActive = false
        // Detener hilos de tiempo
        loopHandler?.removeCallbacks(loopRunnable ?: return)
        loopHandler = null
        loopRunnable = null
        // Destruir por completo el motor de voz de la RAM
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
