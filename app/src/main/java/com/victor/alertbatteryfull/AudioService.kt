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

    companion object {
        private var tts: TextToSpeech? = null
    }

    // Este método es obligatorio por ley en los servicios de Android, 
    // pero como el nuestro es independiente y no se "ata" a ninguna pantalla, lo devolvemos nulo de forma segura.
    override fun onBind(intent: Intent?): IBinder? {
        return null
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

            // 5. Si el volumen del móvil es mayor que cero, ejecutamos la acción correspondiente
            when (soundType) {
                0 -> playVoiceAlert(am, originalVolume, targetVolume, targetPct, isTest) // Lanzar la Alerta por Voz
                1 -> playBeepAlert(am, originalVolume, targetVolume)                    // Lanzar el Pitido (Beep)
                else -> stopSelf() // Por seguridad, si llega un tipo desconocido, nos apagamos
            }
        } else {
            // Si el servicio se inició por error sin datos, se apaga de inmediato para ahorrar energía
            stopSelf()
        }

        // Le indicamos al sistema operativo que si el servicio es destruido por falta de RAM, no intente revivirlo solo
        return START_NOT_STICKY
    }

    private fun playBeepAlert(am: AudioManager, originalVolume: Int, targetVolume: Int) {
        try {
            // 1. Calculamos la equivalencia del volumen seleccionado según el máximo que permita el hardware del móvil
            val maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetSystemVol = (targetVolume * maxMusicVol) / 100
            
            // 2. Subimos temporalmente el canal multimedia del sistema al nivel que marca nuestra barra
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetSystemVol, 0)

            // 3. Inicializamos el generador de tonos físicos atado al canal multimedia (STREAM_MUSIC)
            // Configuramos la potencia al máximo relativo (100) ya que el volumen real lo acabamos de fijar arriba
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            
            // 4. Emitimos un único pitido limpio de hardware con una duración de 250 milisegundos
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
            
            // 5. Programamos un retraso ligeramente mayor (300ms) para garantizar que el pitido termine de sonar completo
            Handler(Looper.getMainLooper()).postDelayed({ 
                try {
                    // Liberamos los controladores físicos de audio para que el chip de sonido pueda entrar en reposo
                    toneGen.release() 
                    
                    // RESTAURAMOS el volumen original que tenía el usuario en su móvil antes de sonar la alerta
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                } catch (e: Exception) {}
                
                // IMPORTANTE: Al terminar el proceso acústico, destruimos el servicio por completo de la memoria RAM
                stopSelf()
            }, 300)
            
        } catch (e: Exception) {
            // En caso de un fallo inesperado de hardware, nos aseguramos de restaurar el volumen y apagar el servicio
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0) } catch (ex: Exception) {}
            stopSelf()
        }
    }

    private fun playVoiceAlert(am: AudioManager, originalVolume: Int, targetVolume: Int, pct: Int, isTest: Boolean) {
        // 1. Construimos de forma dinámica la frase exacta según el porcentaje objetivo configurado
        val message = if (pct == 100) {
            getString(R.string.msg_voice_full)
        } else {
            getString(R.string.msg_voice_custom, pct)
        }

        // 2. Inicializamos el motor TextToSpeech nativo de Google utilizando el contexto seguro del servicio
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { t ->
                    // 3. Establecemos el idioma por defecto configurado en el teléfono del usuario
                    t.language = Locale.getDefault()

                    // 4. Subimos temporalmente el volumen del sistema al nivel exacto que marca nuestra barra
                    val maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetSystemVol = (targetVolume * maxMusicVol) / 100
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, targetSystemVol, 0)

                    // 5. BLINDAJE ANDROID 14+: Forzamos al motor a usar el canal multimedia con categoría de ALERTA
                    // Esto activa el efecto "ducking" (atenúa un instante la música de Spotify sin pausarla)
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    t.setAudioAttributes(audioAttributes)

                    // 6. ESCUCHADOR PROFESIONAL: El motor nos avisará en el milisegundo exacto en que termine la frase
                    t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            // Usamos un Handler atado al hilo principal para manipular los componentes de audio de forma segura
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    // RESTAURAMOS el volumen original que tenía el móvil del usuario inmediatamente
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                                    
                                    // Si es una prueba manual de la pantalla, liberamos el motor de la RAM de inmediato
                                    if (isTest) {
                                        tts?.stop()
                                        tts?.shutdown()
                                        tts = null
                                    }
                                } catch (e: Exception) {}
                                
                                // El trabajo acústico de la voz ha terminado: destruimos el servicio por completo
                                stopSelf()
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            Handler(Looper.getMainLooper()).post {
                                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0) } catch (e: Exception) {}
                                stopSelf()
                            }
                        }
                    })

                    // 7. Empaquetamos el ID de la alerta exigido por Android 14+ para sincronizar el escuchador
                    val params = Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "BatteryAlertID")
                    }

                    // 8. Ordenamos al sintetizador de voz que reproduzca el mensaje
                    t.speak(message, TextToSpeech.QUEUE_FLUSH, params, "BatteryAlertID")
                }
            } else {
                // Si el motor de Google falla al inicializarse, nos aseguramos de no dejar el servicio colgado
                stopSelf()
            }
        }
    }
}
