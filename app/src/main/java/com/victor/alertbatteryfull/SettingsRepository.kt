package com.victor.alertbatteryfull

import android.content.Context
import android.content.SharedPreferences

/**
 * Centraliza todas las preferencias de la aplicación.
 *
 * El resto de clases debe utilizar este objeto para leer
 * y guardar ajustes, evitando acceder directamente
 * a SharedPreferences.
 */
object SettingsRepository {

    // Conservamos el nombre del archivo actual.
    private const val PREFS_NAME = "prefs_battery"

    // Claves originales: no modificarlas para conservar los ajustes.
    const val KEY_ENABLED = "enabled"
    const val KEY_LOOP = "loop"
    const val KEY_VIBRATE = "vibrate"
    const val KEY_SOUND_DELAY = "sound_delay"
    const val KEY_IGNORE_SILENCE = "ignore_silence"
    const val KEY_CUSTOM_VOLUME = "custom_volume"
    const val KEY_TARGET_PCT = "target_pct"
    const val KEY_VOLUME = "volume"
    const val KEY_SOUND_TYPE = "sound_type"

    // Valores predeterminados centralizados.
    private const val DEFAULT_ENABLED = false
    private const val DEFAULT_LOOP = false
    private const val DEFAULT_VIBRATE = false
    private const val DEFAULT_SOUND_DELAY = false
    private const val DEFAULT_IGNORE_SILENCE = false
    private const val DEFAULT_CUSTOM_VOLUME = false
    private const val DEFAULT_TARGET_PCT = 100
    private const val DEFAULT_VOLUME = 80
    private const val DEFAULT_SOUND_TYPE = 0

    // Obtiene las preferencias utilizando el contexto de aplicación.
    private fun getPreferences(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    // --------------------------------------------------
    // ACTIVACIÓN DE LA APLICACIÓN
    // --------------------------------------------------

    fun isEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(
            KEY_ENABLED,
            DEFAULT_ENABLED
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    // --------------------------------------------------
    // REPETICIÓN DE ALERTAS
    // --------------------------------------------------

    fun isLoopEnabled(
        context: Context,
        defaultValue: Boolean = DEFAULT_LOOP
    ): Boolean {
        return getPreferences(context).getBoolean(
            KEY_LOOP,
            defaultValue
        )
    }

    fun setLoopEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_LOOP, enabled)
            .apply()
    }

    // --------------------------------------------------
    // VIBRACIÓN
    // --------------------------------------------------

    fun isVibrationEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(
            KEY_VIBRATE,
            DEFAULT_VIBRATE
        )
    }

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_VIBRATE, enabled)
            .apply()
    }

    // --------------------------------------------------
    // RETARDO DEL SONIDO
    // --------------------------------------------------

    fun isSoundDelayEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(
            KEY_SOUND_DELAY,
            DEFAULT_SOUND_DELAY
        )
    }

    fun setSoundDelayEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SOUND_DELAY, enabled)
            .apply()
    }

    // --------------------------------------------------
    // OPCIONES DE AUDIO
    // --------------------------------------------------

    fun isIgnoreSilenceEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(
            KEY_IGNORE_SILENCE,
            DEFAULT_IGNORE_SILENCE
        )
    }

    fun setIgnoreSilenceEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_IGNORE_SILENCE, enabled)
            .apply()
    }

    fun isCustomVolumeEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(
            KEY_CUSTOM_VOLUME,
            DEFAULT_CUSTOM_VOLUME
        )
    }

    fun setCustomVolumeEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_CUSTOM_VOLUME, enabled)
            .apply()
    }

    // --------------------------------------------------
    // PORCENTAJE OBJETIVO
    // --------------------------------------------------

    fun getTargetPercentage(context: Context): Int {
        return getPreferences(context).getInt(
            KEY_TARGET_PCT,
            DEFAULT_TARGET_PCT
        )
    }

    fun setTargetPercentage(context: Context, percentage: Int) {
        require(percentage in 1..100) {
            "El porcentaje debe estar entre 1 y 100"
        }

        getPreferences(context).edit()
            .putInt(KEY_TARGET_PCT, percentage)
            .apply()
    }

    // --------------------------------------------------
    // VOLUMEN
    // --------------------------------------------------

    fun getVolume(context: Context): Int {
        return getPreferences(context).getInt(
            KEY_VOLUME,
            DEFAULT_VOLUME
        )
    }

    fun setVolume(context: Context, volume: Int) {
        require(volume in 0..100) {
            "El volumen debe estar entre 0 y 100"
        }

        getPreferences(context).edit()
            .putInt(KEY_VOLUME, volume)
            .apply()
    }

    // --------------------------------------------------
    // TIPO DE SONIDO
    // 0 = Voz, 1 = Beep, 2 = Silencioso
    // --------------------------------------------------

    fun getSoundType(context: Context): Int {
        return getPreferences(context).getInt(
            KEY_SOUND_TYPE,
            DEFAULT_SOUND_TYPE
        )
    }

    fun setSoundType(context: Context, soundType: Int) {
        require(soundType in 0..2) {
            "El tipo de sonido debe ser 0, 1 o 2"
        }

        getPreferences(context).edit()
            .putInt(KEY_SOUND_TYPE, soundType)
            .apply()
    }

}
