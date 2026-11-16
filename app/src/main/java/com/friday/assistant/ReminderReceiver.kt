package com.friday.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Scheduled Protocol Alert"
        val id = intent.getIntExtra("id", (System.currentTimeMillis() % 10000).toInt())

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "friday_alarm_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                channelId,
                "F.R.I.D.A.Y. Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setSound(soundUri, attrs)
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_friday_logo)
            .setContentTitle("F.R.I.D.A.Y. Reminder")
            .setContentText("Boss, it's time for: $title")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        manager.notify(id, notification)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("reminders_json", "[]") ?: "[]"
            try {
                val arr = JSONArray(jsonStr)
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val triggerAt = obj.optLong("triggerAt", 0L)
                    if (triggerAt > now) {
                        PhoneController.scheduleAlarmInternal(
                            context,
                            obj.optInt("id", i),
                            obj.optString("title", "Reminder"),
                            triggerAt
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
