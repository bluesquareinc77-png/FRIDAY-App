package com.friday.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object PhoneController {

    private fun findContactNumber(context: Context, nameQuery: String): Pair<String, String>? {
        val cleanInput = nameQuery.replace(Regex("[^0-9+]"), "")
        if (cleanInput.length >= 7 && !nameQuery.any { it.isLetter() }) {
            return Pair(nameQuery, cleanInput)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numIdx) ?: continue
                if (name.contains(nameQuery.trim(), ignoreCase = true)) {
                    return Pair(name, number.replace(Regex("\\s+"), ""))
                }
            }
        }
        return null
    }

    fun makeCall(context: Context, contactName: String): String {
        val found = findContactNumber(context, contactName)
            ?: return "Sorry Boss, I couldn't find '$contactName' in your contacts, or contact permission is missing."
        val (name, number) = found
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return if (hasCallPerm) "Calling $name right now, Boss." else "Opening dialer for $name, Boss."
    }

    fun openApp(context: Context, appName: String): String {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        var bestPkg: String? = null
        var bestLabel = appName

        for (info in apps) {
            val label = info.loadLabel(pm).toString()
            if (label.equals(appName.trim(), ignoreCase = true)) {
                bestPkg = info.activityInfo.packageName
                bestLabel = label
                break
            } else if (label.contains(appName.trim(), ignoreCase = true) && bestPkg == null) {
                bestPkg = info.activityInfo.packageName
                bestLabel = label
            }
        }
        return if (bestPkg != null) {
            val launch = pm.getLaunchIntentForPackage(bestPkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launch != null) {
                context.startActivity(launch)
                "Opening $bestLabel, Boss."
            } else "Unable to launch $bestLabel, Boss."
        } else {
            "I couldn't find an app named '$appName' on your phone, Boss."
        }
    }

    fun sendWhatsApp(context: Context, contactName: String, message: String): String {
        val found = findContactNumber(context, contactName)
            ?: return "I couldn't find '$contactName' in your contacts to message on WhatsApp, Boss."
        val (name, rawNumber) = found
        var digits = rawNumber.replace(Regex("[^0-9]"), "")
        if (digits.length == 10) digits = "91$digits"

        val url = "https://wa.me/$digits?text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Opening WhatsApp chat with $name with your message ready, Boss."
        } catch (_: Exception) {
            "WhatsApp is not installed on this device, Boss."
        }
    }

    fun toggleFlashlight(context: Context, turnOn: Boolean): String {
        return try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = camManager.cameraIdList.firstOrNull { id ->
                camManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight hardware detected, Boss."
            camManager.setTorchMode(camId, turnOn)
            if (turnOn) "Flashlight turned ON, Boss." else "Flashlight turned OFF, Boss."
        } catch (_: Exception) {
            "Couldn't access the flashlight right now, Boss."
        }
    }

    fun adjustVolume(context: Context, action: String): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (action.lowercase()) {
            "up" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "Volume increased, Boss."
            }
            "down" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Volume decreased, Boss."
            }
            "mute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "Media muted, Boss."
            }
            "unmute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "Media unmuted, Boss."
            }
            "max" -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                "Volume set to maximum, Boss."
            }
            else -> "Volume adjusted, Boss."
        }
    }

    fun navigateMaps(context: Context, destination: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}")).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Starting Google Maps navigation to $destination, Boss."
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(destination)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            "Opening Maps for $destination, Boss."
        }
    }

    fun playYouTube(context: Context, query: String): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Searching '$query' on YouTube, Boss."
    }

    fun scheduleAlarmInternal(context: Context, id: Int, title: String, triggerAt: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("title", title)
        }
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun setReminder(context: Context, title: String, delayMinutes: Int, time24h: String?): String {
        val now = System.currentTimeMillis()
        var triggerAt = now + 60_000L

        if (delayMinutes > 0) {
            triggerAt = now + delayMinutes * 60_000L
        } else if (!time24h.isNullOrBlank() && time24h.contains(":")) {
            val parts = time24h.split(":")
            val h = parts[0].toIntOrNull() ?: 9
            val m = parts[1].toIntOrNull() ?: 0
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
            }
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            triggerAt = cal.timeInMillis
        }

        val id = (now % 100000).toInt()
        scheduleAlarmInternal(context, id, title, triggerAt)

        val prefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("reminders_json", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().apply {
            put("id", id)
            put("title", title)
            put("triggerAt", triggerAt)
        })
        prefs.edit().putString("reminders_json", arr.toString()).apply()

        val timeFmt = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()).format(Date(triggerAt))
        return "Reminder set for '$title' at $timeFmt, Boss."
    }

    fun getPendingReminders(context: Context): String {
        val prefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("reminders_json", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        val list = mutableListOf<String>()
        val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val t = obj.optLong("triggerAt", 0L)
            if (t > now) {
                list.add("${obj.optString("title")} at ${fmt.format(Date(t))}")
            }
        }
        return if (list.isEmpty()) "You have no pending reminders, Boss."
        else "Boss, your upcoming reminders are: ${list.joinToString("; ")}."
    }
}
