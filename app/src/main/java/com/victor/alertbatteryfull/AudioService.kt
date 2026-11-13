package com.victor.alertbatteryfull

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

class AudioService : Service() {
    
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingIntent: Intent? = null
    private var isSpeaking = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun onCreate() {
        super.onCreate()
        // Inicializamos el motor TextToSpeech nativo de Google utilizando el contexto global de la aplicación
        // Lo hacemos en el onCreate del servicio para que el motor esté listo antes de recibir las órdenes acústicas
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                tts?.language = Locale.getDefault()

                // Si llegó una orden de voz mientras el motor se estaba encendiendo, la procesamos inmediatamente ahora
                // Recupera y procesa la orden de voz que estaba pendiente.
                pendingIntent?.let { intent ->

                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

                    val useCustomVolume =
                        intent.getBooleanExtra("custom_volume", false)

                    val ignoreSilence =
                        intent.getBooleanExtra("ignore_silence", false)

                    // Selecciona el canal correspondiente.
                    val streamType = if (ignoreSilence) {
                        AudioManager.STREAM_ALARM
                    } else {
                        AudioManager.STREAM_NOTIFICATION
                    }

                    val originalVolume = am.getStreamVolume(streamType)

                    // Vuelve a comprobar el silencio antes de reproducir.
                    val soundBlocked = if (ignoreSilence) {
                        !useCustomVolume && originalVolume == 0
                    } else {
                        am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                        originalVolume == 0
                    }

                    if (soundBlocked) {
                        stopSelf()
                    } else {
                        val targetVolume = intent.getIntExtra("volume", 80)
                        val targetPct = intent.getIntExtra("target_pct", 100)

                        isSpeaking = true

                        executeVoiceAlert(
                            am,
                            originalVolume,
                            targetVolume,
                            targetPct,
                            streamType,
                            useCustomVolume
                        )
                    }

                    pendingIntent = null
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // 1. Verificamos que el paquete de datos recibido no sea nulo para evitar cierres inesperados
        if (intent != null) {
            // 2. Extraemos los parámetros enviados por la pantalla o por el receptor de batería
            val soundType = intent.getIntExtra("sound_type", 0)       // 0 = Voz, 1 = Beep
            val targetVolume = intent.getIntExtra("volume", 80)       // Volumen elegido en nuestra barra
            val targetPct = intent.getIntExtra("target_pct", 100)     // Porcentaje actual de la alerta

            // Comprueba si la orden recibida solicita ejecutar una vibración.
            val shouldVibrate = intent.getBooleanExtra("vibrate", false)
            
            // Ejecuta la vibración antes de comprobar el volumen,
            // para que el volumen a cero no impida realizar el aviso háptico.
            if (shouldVibrate) {
                executeVibrationAlert()
            }

            // Obtiene el gestor de audio del sistema.
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Recupera las preferencias de volumen y modo silencio.
            val useCustomVolume = intent.getBooleanExtra("custom_volume", false)
            val ignoreSilence = intent.getBooleanExtra("ignore_silence", false)

            // Selecciona el canal de notificaciones o el de alarmas.
            val streamType = if (ignoreSilence) {
                AudioManager.STREAM_ALARM
            } else {
                AudioManager.STREAM_NOTIFICATION
            }

            // Guarda el volumen original del canal seleccionado.
            val originalVolume = am.getStreamVolume(streamType)

            // Si no se permite ignorar el silencio, respeta el modo silencio/vibración
            // y el volumen de notificaciones a cero.
            if (
                !ignoreSilence &&
                (
                    am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                    originalVolume == 0
                )
            ) {
                stopSelf(startId)
                return START_NOT_STICKY
            }

            // Si se ignora el silencio, utiliza el canal de alarmas.
            // Si el volumen personalizado está desactivado, respeta también
            // el volumen configurado por el usuario para las alarmas.
            if (ignoreSilence && !useCustomVolume && originalVolume == 0) {
                stopSelf(startId)
                return START_NOT_STICKY
            }

            // Evita iniciar otra locución mientras la anterior sigue activa.
            if (soundType == 0 && isSpeaking) {
                return START_STICKY
            }

            // Ejecuta el sonido solicitado.
            when (soundType) {
                0 -> {    // Voz
                    // Si el motor de Google ya está listo en el sistema, habla inmediatamente
                    if (isTtsReady) {
                        isSpeaking = true
                        executeVoiceAlert(am, originalVolume, targetVolume, targetPct, streamType, useCustomVolume)
                    } else {
                        // Si el motor aún se está conectando de fondo, guardamos este intento para lanzarlo al revivir
                        pendingIntent = intent
                    }
                }

                1 -> {    // Lanzar el Pitido (Beep)
                    executeBeepAlert(am, originalVolume, targetVolume, streamType, useCustomVolume)
                }

                // El tipo 2 es silencioso: no reproduce sonido.
                2 -> stopSelf(startId)

                else -> stopSelf(startId)    // Por seguridad, si llega un tipo desconocido, nos apagamos
            }
        } else {
            // Si el servicio se inició por error sin datos, se apaga de inmediato para ahorrar energía
            stopSelf()
        }

        // Retornamos STICKY para que Android 14+ mantenga el proceso prioritario mientras la app habla de fondo
        return START_STICKY
    }

    // Reproduce la locución utilizando el canal y volumen seleccionados.
    private fun executeVoiceAlert(
        am: AudioManager,
        originalVolume: Int,
        targetVolume: Int,
        pct: Int,
        streamType: Int,
        useCustomVolume: Boolean
    ) {
        // Construye el mensaje según el porcentaje objetivo.
        val message = if (pct == 100) {
            getString(R.string.msg_voice_full)
        } else {
            getString(R.string.msg_voice_custom, pct)
        }

        // Si se utiliza volumen personalizado, prepara el canal al máximo.
        if (useCustomVolume) {
            aplicarVolumenMaximo(am, streamType)
        }

        tts?.let { t ->

            // Configura la categoría de audio correspondiente al canal elegido.
            val usage = if (streamType == AudioManager.STREAM_ALARM) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_NOTIFICATION
            }

            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            // Define una única finalización para restaurar el volumen.
            val finalizarAviso = Runnable {
                try {
                    am.setStreamVolume(streamType, originalVolume, 0)
                } catch (e: Exception) {
                }

                isSpeaking = false
                stopSelf()
            }

            t.setOnUtteranceProgressListener(
                object : android.speech.tts.UtteranceProgressListener() {

                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        mainHandler.postDelayed(finalizarAviso, 300)
                    }

                    @Deprecated("Método requerido por la API")
                    override fun onError(utteranceId: String?) {
                        mainHandler.postDelayed(finalizarAviso, 300)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        super.onStop(utteranceId, interrupted)
                        mainHandler.postDelayed(finalizarAviso, 300)
                    }
                }
            )

            // El volumen TTS es el porcentaje personalizado o el máximo normal.
            val volume = if (useCustomVolume) {
                targetVolume.coerceIn(0, 100) / 100f
            } else {
                1f
            }

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            }

            t.speak(message, TextToSpeech.QUEUE_FLUSH, params, "BatteryAlertID")
        }
    }
    
        // Reproduce el pitido usando el canal y volumen seleccionados.
    private fun executeBeepAlert(
        am: AudioManager,
        originalVolume: Int,
        targetVolume: Int,
        streamType: Int,
        useCustomVolume: Boolean
    ) {
        var toneGen: ToneGenerator? = null

        try {
            // Prepara el canal al máximo si se ha elegido volumen personalizado.
            if (useCustomVolume) {
                aplicarVolumenMaximo(am, streamType)
            }

            // Aplica el porcentaje personalizado o el volumen máximo del tono.
            val toneVolume = if (useCustomVolume) {
                targetVolume.coerceIn(0, 100)
            } else {
                100
            }

            toneGen = ToneGenerator(streamType, toneVolume)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 250)

        } catch (e: Exception) {
            // Evita que un error de audio cierre el servicio.
        } finally {

            val generatorToRelease = toneGen

            mainHandler.postDelayed({
                try {
                    // Restaura el volumen original del sistema.
                    am.setStreamVolume(streamType, originalVolume, 0)
                } catch (e: Exception) {
                }

                try {
                    generatorToRelease?.release()
                } catch (e: Exception) {
                }

                stopSelf()
            }, 300)
        }
    }

    // Ejecuta una vibración
    private fun executeVibrationAlert() {
    
        // Obtiene el administrador de vibración del dispositivo.
        val vibratorManager = getSystemService(VibratorManager::class.java) ?: return
    
        // Obtiene el vibrador principal del dispositivo.
        val vibrator = vibratorManager.defaultVibrator
    
        // Comprueba que el dispositivo dispone de un vibrador.
        if (!vibrator.hasVibrator()) return
    
        // Crea una vibración de X milisegundos con la amplitud predeterminada.
        val effect = VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
    
        // Clasifica la vibración como una alarma del sistema.
        val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
    
        // Ejecuta la vibración con el efecto y los atributos definidos.
        vibrator.vibrate(effect, attributes)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        // LIMPIEZA ABSOLUTA DE RAM: Al morir el servicio de forma definitiva, desconectamos el motor de voz por completo del sistema operativo
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {}
        super.onDestroy()
    }

    // Este método es obligatorio por ley en los servicios de Android, 
    // pero como el nuestro es independiente y no se "ata" a ninguna pantalla, lo devolvemos nulo de forma segura.
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // Establece temporalmente el canal seleccionado en su volumen máximo.
    private fun aplicarVolumenMaximo(
        am: AudioManager,
        streamType: Int
    ) {
        try {
            val maxVolume = am.getStreamMaxVolume(streamType)
            am.setStreamVolume(streamType, maxVolume, 0)
        } catch (e: Exception) {
            // Si Android no permite cambiar el volumen, evita el cierre del servicio.
        }
    }

}
