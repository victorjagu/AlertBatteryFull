package com.victor.alertbatteryfull

import android.app.Service
import android.content.Context
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        // Inicializamos el motor TextToSpeech nativo de Google utilizando el contexto global de la aplicación
        // Lo hacemos en el onCreate del servicio para que el motor esté listo antes de recibir las órdenes acústicas
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                // Si llegó una orden de voz mientras el motor se estaba encendiendo, la procesamos inmediatamente ahora
                pendingIntent?.let { intent ->
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val targetVolume = intent.getIntExtra("volume", 80)
                    val targetPct = intent.getIntExtra("target_pct", 100)
                    val isTest = intent.getBooleanExtra("is_test", false)
                    executeVoiceAlert(am, originalVolume, targetVolume, targetPct, isTest)
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
            val isTest = intent.getBooleanExtra("is_test", false)     // Saber si es solo una prueba manual
            
            // 3. Accedemos al gestor de audio nativo del teléfono
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // 4. Consultamos el volumen multimedia (música) actual del dispositivo del usuario
            val originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)

            // Si el usuario tiene el volumen multimedia del móvil a cero (modo noche),
            // respetamos el silencio absoluto, no reproducimos acústica y detenemos el servicio de inmediato.
            if (originalVolume == 0) {
                stopSelf() // El servicio se auto-destruye y se borra de la RAM al instante
                return START_NOT_STICKY
            }

            // Evitamos que el bucle repetitivo interrumpa si la app ya está hablando
            if (soundType == 0 && isSpeaking) {
                return START_STICKY
            }
            
            // 5. Si el volumen del móvil es mayor que cero, ejecutamos la acción correspondiente
            when (soundType) {
                0 -> {    // Lanzamos Voz
                    // Si el motor de Google ya está listo en el sistema, habla inmediatamente
                    if (isTtsReady) {
                        isSpeaking = true
                        executeVoiceAlert(am, originalVolume, targetVolume, targetPct, isTest)
                    } else {
                        // Si el motor aún se está conectando de fondo, guardamos este intento para lanzarlo al revivir
                        pendingIntent = intent
                    }
                }
                1 -> {    // Lanzar el Pitido (Beep)
                    playBeepAlert(am, originalVolume, targetVolume)
                }
                else -> stopSelf() // Por seguridad, si llega un tipo desconocido, nos apagamos
            }
        } else {
            // Si el servicio se inició por error sin datos, se apaga de inmediato para ahorrar energía
            stopSelf()
        }

        // Retornamos STICKY para que Android 14+ mantenga el proceso prioritario mientras la app habla de fondo
        return START_STICKY
    }

    private fun executeVoiceAlert(am: AudioManager, originalVolume: Int, targetVolume: Int, pct: Int, isTest: Boolean) {
        // 1. Construimos de forma dinámica la frase exacta según el porcentaje objetivo configurado
        val message = if (pct == 100) {
            getString(R.string.msg_voice_full)
        } else {
            getString(R.string.msg_voice_custom, pct)
        }
    
        // Subimos el volumen del móvil justo en el milisegundo antes de hablar
        try {
            val maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val computedVol = (targetVolume * maxMusicVol) / 100
            am.setStreamVolume(AudioManager.STREAM_MUSIC, computedVol, 0)
        } catch (e: Exception) {}
    
        tts?.let { t ->
            // 3. Establecemos el idioma por defecto configurado en el teléfono del usuario
            t.language = Locale.getDefault()

            // 5. Forzamos al motor a usar el canal multimedia con categoría de ALERTA
            // Esto permite el "ducking" (bajar el volumen de Spotify/música de fondo de forma nativa)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            t.setAudioAttributes(audioAttributes)

            // Creamos una función interna/reutilizable justo antes del escuchador para restaurar el sonido original
            fun finalizarAvisoConRetraso() {
                // Usamos postDelayed en lugar de post para dar un margen de 300ms al hardware
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // Devolvemos el móvil al volumen que tenía el usuario antes del aviso
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                    } catch (e: Exception) {}
                    isSpeaking = false
                    stopSelf()
                }, 300)
            }

            // 6. ESCUCHADOR PROFESIONAL: El motor nos avisará en el milisegundo exacto en que termine la frase
            t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                
                override fun onDone(utteranceId: String?) {
                    finalizarAvisoConRetraso()
                }

                @Deprecated("Inutilizado en niveles de API modernos, pero obligatorio de declarar")
                override fun onError(utteranceId: String?) {
                    finalizarAvisoConRetraso()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    super.onStop(utteranceId, interrupted)
                    finalizarAvisoConRetraso()
                }
            })

            // 7. Empaquetamos el ID de la alerta exigido por Android 14+ para sincronizar el escuchador
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "BatteryAlertID")
                // NUEVO: Pasamos el volumen seleccionado convertido a la escala flotante nativa (0.0 a 1.0)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, targetVolume / 100f)
            }

            // 8. Ordenamos al sintetizador de voz que reproduzca el mensaje
            t.speak(message, TextToSpeech.QUEUE_FLUSH, params, "BatteryAlertID")
        }
    }

    private fun playBeepAlert(am: AudioManager, originalVolume: Int, targetVolume: Int) {
        try {
            // 1. Subimos el volumen del sistema multimedia justo antes de reproducir el pitido
            try {
                val maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val computedVol = (targetVolume * maxMusicVol) / 100
                am.setStreamVolume(AudioManager.STREAM_MUSIC, computedVol, 0)
            } catch (e: Exception) {}
    
            // 2. Inicializamos el pitido pasándole el volumen nativo máximo (100) ya que controlamos el canal globalmente
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            
            // 3. Emitimos un único pitido limpio de hardware con una duración de 250 milisegundos
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
            
            // 4. Programamos el retraso de 300ms para asegurar que termine de sonar completo
            Handler(Looper.getMainLooper()).postDelayed({ 
                try {
                    // 5. Devolvemos el móvil al volumen que tenía el usuario originalmente
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                } catch (e: Exception) {}
    
                try {
                    // Liberamos los controladores físicos de audio para que el chip de sonido pueda entrar en reposo
                    toneGen.release() 
                } catch (e: Exception) {}
                
                // Al terminar el proceso acústico, destruimos el servicio por completo de la memoria RAM
                stopSelf()
            }, 300)
            
        } catch (e: Exception) {
            // En caso de un fallo inesperado de hardware, nos aseguramos de restaurar el volumen y apagar el servicio
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            } catch (ex: Exception) {}
            stopSelf()
        }
    }

    override fun onDestroy() {
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
}
