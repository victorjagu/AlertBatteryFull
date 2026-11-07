package com.victor.alertbatteryfull

import android.content.Context
import android.content.Intent

object AlertCoordinator {

    fun startAudio(
        context: Context,
        soundType: Int,
        volume: Int,
        targetPct: Int,
        customVolume: Boolean,
        audioChannel: Int,
        isTest: Boolean = false
    ) {
        val serviceIntent = Intent(context, AudioService::class.java).apply {
            putExtra("sound_type", soundType)
            putExtra("volume", volume)
            putExtra("target_pct", targetPct)
            putExtra("custom_volume", customVolume)
            putExtra("audio_channel", audioChannel)
            putExtra("is_test", isTest)
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
