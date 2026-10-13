package com.victor.alertbatterylevel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        // Verificamos de forma segura que el evento recibido sea efectivamente el encendido del móvil
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            
            // Abrimos el almacenamiento local SharedPreferences para comprobar las preferencias del usuario
            val prefs = context.getSharedPreferences("BatteryAlertPrefs", Context.MODE_PRIVATE)
            val isAppEnabled = prefs.getBoolean("app_enabled", false)

            // Si el usuario tenía el interruptor general encendido antes del reinicio, procedemos
            if (isAppEnabled) {
                val serviceIntent = Intent(context, BatteryMonitorService::class.java)
                
                try {
                    // En Android 8.0 (API 26) o superior se debe arrancar como servicio en primer plano obligatoriamente
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    // CAPA DE SEGURIDAD PARA ANDROID 14+: Si el sistema bloquea el inicio directo desde el Boot,
                    // guardamos el estado para que se lance automáticamente en cuanto el usuario desbloquee la pantalla.
                    e.printStackTrace()
                }
            }
        }
    }
}
