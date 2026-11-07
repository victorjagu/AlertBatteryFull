package com.victor.alertbatteryfull

import android.content.Context
import android.content.Intent

/**
 * Datos necesarios para solicitar una alerta sonora.
 */
data class AudioRequest(
    val soundType: Int,
    val volume: Int,
    val targetPct: Int,
    val customVolume: Boolean,
    val audioChannel: Int
)

/**
 * Contrato único para comunicar AudioService mediante Intent.
 */
internal object AudioIntentKeys {
    const val SOUND_TYPE = "sound_type"
    const val VOLUME = "volume"
    const val TARGET_PCT = "target_pct"
    const val CUSTOM_VOLUME = "custom_volume"
    const val AUDIO_CHANNEL = "audio_channel"
}

/**
 * Centraliza las operaciones de lanzamiento y control de alertas.
 */
object AlertCoordinator {

    fun startAudio(context: Context, request: AudioRequest) {
        val serviceIntent = Intent(context, AudioService::class.java).apply {
            putExtra(AudioIntentKeys.SOUND_TYPE, request.soundType)
            putExtra(AudioIntentKeys.VOLUME, request.volume)
            putExtra(AudioIntentKeys.TARGET_PCT, request.targetPct)
            putExtra(AudioIntentKeys.CUSTOM_VOLUME, request.customVolume)
            putExtra(AudioIntentKeys.AUDIO_CHANNEL, request.audioChannel)
        }

        context.startService(serviceIntent)
    }

    fun showNotification(context: Context, targetPct: Int) {
        NotificationHelper.showNotification(context, targetPct)
    }

    fun stopAudio(context: Context) {
        context.stopService(Intent(context, AudioService::class.java))
    }
}
