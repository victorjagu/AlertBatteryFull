package com.victor.alertbatterylevel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Verificamos que el evento recibido sea efectivamente el encendido del móvil
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            
            // Abrimos el almacenamiento local para comprobar si el interruptor general estaba encendido
            val prefs: SharedPreferences = context.getSharedPreferences("BatteryAlertPrefs", Context.MODE_PRIVATE)
            val isAppEnabled = prefs.getBoolean("app_enabled", false)

            // Si el usuario tenía la app encendida antes del reinicio, arrancamos el servicio
            if (isAppEnabled) {
                val serviceIntent = Intent(context, BatteryMonitorService::class.java)
                
                // En Android 8.0 (API 26) o superior se debe arrancar como servicio en primer plano obligatoriamente
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
