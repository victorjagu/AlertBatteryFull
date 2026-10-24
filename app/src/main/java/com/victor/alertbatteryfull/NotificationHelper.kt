package com.victor.alertbatteryfull

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "battery_alerts"
    private const val NOTIFICATION_ID = 9911

    fun showNotification(context: Context, percentage: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Solo forzamos la creación del canal si Android nos confirma que no existe previamente
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            val name = context.getString(R.string.channel_notif_name)
            val desc = context.getString(R.string.channel_notif_description)
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
            }
            nm.createNotificationChannel(channel)
        }

        // Intención de borrado (la que configuramos para el Smartwatch)
        val deleteIntent = Intent(context, BatteryReceiver::class.java).apply {
            action = "ACTION_CANCEL_ALERTS_MANUAL"
        }
        val deletePendingIntent = android.app.PendingIntent.getBroadcast(
            context, 0, deleteIntent, 
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon) // Usa el icono de tu app automáticamente
            .setContentTitle(context.getString(R.string.msg_notif_title))
            .setContentText(context.getString(R.string.msg_notif_text, percentage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDeleteIntent(deletePendingIntent)

        nm.notify(NOTIFICATION_ID, builder.build())
    }
}
