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

    private data class ContactMatch(val name: String, val number: String, val score: Int)

    private fun findBestContacts(context: Context, nameQuery: String): List<ContactMatch> {
        val q = nameQuery.trim()
        val cleanDigits = q.replace(Regex("[^0-9+]"), "")
        if (cleanDigits.length >= 7 && !q.any { it.isLetter() }) {
            return listOf(ContactMatch(q, cleanDigits, 100))
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val matches = linkedMapOf<String, ContactMatch>()
        val qLower = q.lowercase()
        val wordRegex = Regex("\\b${Regex.escape(qLower)}\\b")

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.trim() ?: continue
                val number = cursor.getString(numIdx)?.replace(Regex("\\s+"), "") ?: continue
                val nLower = name.lowercase()

                val score = when {
                    nLower == qLower -> 100                                           // Exact match: "Viraj"
                    nLower.startsWith("$qLower ") -> 85                               // First word match: "Viraj Patel"
                    nLower.startsWith(qLower) -> 75                                   // Prefix match: "Virajbhai"
                    wordRegex.containsMatchIn(nLower) && !nLower.contains("(") -> 50  // Word match outside brackets
                    nLower.contains(qLower) -> 20                                     // Inside brackets: "Dipakbhai (Viraj na mama)"
                    else -> 0
                }

                if (score > 0) {
                    val key = "${nLower}_$number"
                    val existing = matches[key]
                    if (existing == null || score > existing.score) {
                        matches[key] = ContactMatch(name, number, score)
                    }
                }
            }
        }
        return matches.values.sortedByDescending { it.score }
    }

    private sealed class ContactPick {
        data class Exact(val name: String, val number: String) : ContactPick()
        data class Ambiguous(val message: String) : ContactPick()
        data class NotFound(val message: String) : ContactPick()
    }

    private fun pickContact(context: Context, query: String): ContactPick {
        val matches = findBestContacts(context, query)
        if (matches.isEmpty()) {
            return ContactPick.NotFound("Sorry Boss, I couldn't find '$query' in your contacts, or contact permission is missing.")
        }

        val topScore = matches.first().score
        val topDistinctNames = matches.filter { it.score == topScore }.map { it.name }.distinct()

        return when {
            topScore < 50 -> {
                val options = topDistinctNames.take(3).joinToString(", ")
                ContactPick.Ambiguous("Boss, I didn't find '$query' directly. Did you mean $options? Please say the full name.")
            }
            topDistinctNames.size > 1 -> {
                val options = topDistinctNames.take(4).joinToString(", ")
                ContactPick.Ambiguous("Boss, I found multiple contacts: $options. Which one should I select?")
            }
            else -> ContactPick.Exact(matches.first().name, matches.first().number)
        }
    }

    fun makeCall(context: Context, contactName: String): String {
        return when (val pick = pickContact(context, contactName)) {
            is ContactPick.NotFound -> pick.message
            is ContactPick.Ambiguous -> pick.message
            is ContactPick.Exact -> {
                val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                val intent = Intent(if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${pick.number}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                if (hasCallPerm) "Calling ${pick.name} right now, Boss." else "Opening dialer for ${pick.name}, Boss."
            }
        }
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
        return when (val pick = pickContact(context, contactName)) {
            is ContactPick.NotFound -> pick.message
            is ContactPick.Ambiguous -> pick.message
            is ContactPick.Exact -> {
                var digits = pick.number.replace(Regex("[^0-9]"), "")
                if (digits.length == 10) digits = "91$digits"

                val url = "https://wa.me/$digits?text=${Uri.encode(message)}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    "Opening WhatsApp chat with ${pick.name} with your message ready, Boss."
                } catch (_: Exception) {
                    "WhatsApp is not installed on this device, Boss."
                }
            }
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
