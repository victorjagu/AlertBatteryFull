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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var maxMusicVol = 1 // Guardará el límite de hardware en RAM
    
    override fun onCreate() {
        super.onCreate()
        
        // Consultamos el volumen máximo del hardware UNA SOLA VEZ aquí
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        // Inicializamos el motor TextToSpeech nativo de Google utilizando el contexto global de la aplicación
        // Lo hacemos en el onCreate del servicio para que el motor esté listo antes de recibir las órdenes acústicas
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                tts?.language = Locale.getDefault()
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)

                // Si llegó una orden de voz mientras el motor se estaba encendiendo, la procesamos inmediatamente ahora
                pendingIntent?.let { intent ->
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val targetPct = intent.getIntExtra("target_pct", 100)
                    val isCustomVolume = intent.getBooleanExtra("custom_volume", true)
                    // Si el usuario quiere el volumen del sistema, calculamos su porcentaje actual equivalente
                    val targetVolume = if (isCustomVolume) {
                        intent.getIntExtra("volume", 80)
                    } else {
                        if (maxMusicVol > 0) (originalVolume * 100) / maxMusicVol else 80
                    }
                    executeVoiceAlert(am, originalVolume, targetVolume, targetPct)
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
            val targetPct = intent.getIntExtra("target_pct", 100)     // Porcentaje actual de la alerta
            val isCustomVolume = intent.getBooleanExtra("custom_volume", true) // volumen personalizado o el del sistema
            
            // 3. Accedemos al gestor de audio nativo del teléfono
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // 4. Consultamos el volumen multimedia (música) actual del dispositivo del usuario
            val originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)

            // Si el usuario quiere el volumen del sistema, calculamos su porcentaje actual equivalente
            val targetVolume = if (isCustomVolume) {
                intent.getIntExtra("volume", 80)
            } else {
                if (maxMusicVol > 0) (originalVolume * 100) / maxMusicVol else 80
            }

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
                        executeVoiceAlert(am, originalVolume, targetVolume, targetPct)
                    } else {
                        // Si el motor aún se está conectando de fondo, guardamos este intento para lanzarlo al revivir
                        pendingIntent = intent
                    }
                }
                1 -> {    // Lanzar el Pitido (Beep)
                    executeBeepAlert(am, originalVolume, targetVolume)
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

    private fun executeVoiceAlert(am: AudioManager, originalVolume: Int, targetVolume: Int, pct: Int) {
        // Construimos de forma dinámica la frase exacta según el porcentaje objetivo configurado
        val message = if (pct == 100) {
            getString(R.string.msg_voice_full)
        } else {
            getString(R.string.msg_voice_custom, pct)
        }
    
        // Subimos el volumen del móvil justo en el milisegundo antes de hablar
        aplicarVolumenObjetivo(am, targetVolume)
    
        tts?.let { t ->
            // Creamos una función interna/reutilizable justo antes del escuchador para restaurar el sonido original
            val finalizarAvisoConRetraso = Runnable {
                try {
                    // Devolvemos el móvil al volumen que tenía el usuario antes del aviso
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                } catch (e: Exception) {}
                isSpeaking = false
                stopSelf()
            }

            // 6. ESCUCHADOR PROFESIONAL: El motor nos avisará en el milisegundo exacto en que termine la frase
            t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                
                override fun onDone(utteranceId: String?) {
                    mainHandler.postDelayed(finalizarAvisoConRetraso, 300)
                }

                @Deprecated("Inutilizado en niveles de API modernos, pero obligatorio de declarar")
                override fun onError(utteranceId: String?) {
                    mainHandler.postDelayed(finalizarAvisoConRetraso, 300)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    super.onStop(utteranceId, interrupted)
                    mainHandler.postDelayed(finalizarAvisoConRetraso, 300)
                }
            })

            // 7. Empaquetamos el ID de la alerta exigido por Android 14+ para sincronizar el escuchador
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "BatteryAlertID")
                // Pasamos el volumen seleccionado convertido a la escala flotante nativa (0.0 a 1.0)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, targetVolume / 100f)
            }

            // 8. Ordenamos al sintetizador de voz que reproduzca el mensaje
            t.speak(message, TextToSpeech.QUEUE_FLUSH, params, "BatteryAlertID")
        }
    }

    private fun executeBeepAlert(am: AudioManager, originalVolume: Int, targetVolume: Int) {
        // Declaramos la variable fuera del bloque para poder liberarla al final pase lo que pase
        var toneGen: ToneGenerator? = null
        try {
            // Bloque interno para intentar ajustar el volumen multimedia al nivel deseado (subimos el volumen del sistema multimedia justo antes de reproducir el pitido)
            aplicarVolumenObjetivo(am, targetVolume)
    
            // Inicializamos el generador de tonos y emitimos un pitido de hardware de 250ms
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 250)

        } catch (e: Exception) {
                // Si hay un fallo físico con el chip de sonido, el catch evita que la aplicación se cierre (crash)
    
        } finally {
            // El bloque FINALLY se ejecuta SIEMPRE, garantizando que el teléfono vuelva a la normalidad
            mainHandler.postDelayed({
                try {
                    // Devolvemos el móvil al volumen multimedia original que tenía el usuario
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                } catch (e: Exception) {}
                
                try {
                    // Liberamos los controladores físicos de audio para que el chip de sonido entre en reposo
                    toneGen?.release()
                } catch (e: Exception) {}
                
                // Destruimos el servicio por completo para que no consuma memoria RAM en segundo plano
                stopSelf()
            }, 300) // Damos un margen de 300 milisegundos para asegurar que el pitido termine de sonar completo
        }
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

    private fun aplicarVolumenObjetivo(am: AudioManager, targetVolume: Int) {
        try {
            val computedVol = (targetVolume * maxMusicVol) / 100
            am.setStreamVolume(AudioManager.STREAM_MUSIC, computedVol, 0)
        } catch (e: Exception) {}
    }

}
