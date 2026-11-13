package com.victor.alertbatteryfull

import android.content.Context
import android.content.SharedPreferences

// Centraliza el acceso a las preferencias de la aplicación.
object SettingsRepository {

    // Conserva el mismo archivo de preferencias que utiliza la versión actual.
    private const val PREFS_NAME = "prefs_battery"

    // Nombres centralizados de todas las preferencias existentes.
    const val KEY_ENABLED = "enabled"
    const val KEY_LOOP = "loop"
    const val KEY_VIBRATE = "vibrate"
    const val KEY_SOUND_DELAY = "sound_delay"
    const val KEY_IGNORE_SILENCE = "ignore_silence"
    const val KEY_CUSTOM_VOLUME = "custom_volume"
    const val KEY_TARGET_PCT = "target_pct"
    const val KEY_VOLUME = "volume"
    const val KEY_SOUND_TYPE = "sound_type"

    // Obtiene el almacenamiento privado de preferencias de la aplicación.
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    // Lee una preferencia de tipo Boolean.
    fun getBoolean(
        context: Context,
        key: String,
        defaultValue: Boolean
    ): Boolean {
        return getPreferences(context).getBoolean(key, defaultValue)
    }

    // Lee una preferencia de tipo Int.
    fun getInt(
        context: Context,
        key: String,
        defaultValue: Int
    ): Int {
        return getPreferences(context).getInt(key, defaultValue)
    }

    // Guarda una preferencia de tipo Boolean.
    fun putBoolean(
        context: Context,
        key: String,
        value: Boolean
    ) {
        getPreferences(context).edit().putBoolean(key, value).apply()
    }

    // Guarda una preferencia de tipo Int.
    fun putInt(
        context: Context,
        key: String,
        value: Int
    ) {
        getPreferences(context).edit().putInt(key, value).apply()
    }
}
