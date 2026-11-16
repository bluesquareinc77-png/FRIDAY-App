package com.friday.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FridayBrain {

    private const val MODEL_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

    suspend fun process(context: Context, userInput: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val lower = userInput.lowercase().trim()

        if (apiKey.isBlank()) {
            return@withContext handleOffline(context, userInput, lower)
        }

        try {
            val payload = buildToolPayload(userInput)
            val resJson = postGemini(apiKey.trim(), payload)

            if (resJson.has("error")) {
                val msg = resJson.getJSONObject("error").optString("message", "API Error")
                return@withContext "API Notice: $msg. You can check your key in Settings, Boss."
            }

            val candidates = resJson.optJSONArray("candidates") ?: return@withContext "No response, Boss."
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("functionCall")) {
                    val fc = part.getJSONObject("functionCall")
                    val name = fc.getString("name")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    return@withContext executeTool(context, name, args, apiKey.trim())
                }
            }

            val text = parts.getJSONObject(0).optString("text", "Command processed, Boss.")
            return@withContext text.replace("*", "").trim()
        } catch (_: Exception) {
            return@withContext handleOffline(context, userInput, lower)
        }
    }

    private fun handleOffline(context: Context, raw: String, lower: String): String {
        return when {
            lower.contains("time") || lower.contains("સમય") ->
                "The current time is ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())}, Boss."
            lower.contains("date") || lower.contains("તારીખ") ->
                "Today is ${SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()).format(Date())}, Boss."
            lower.contains("torch on") || lower.contains("flashlight on") ->
                PhoneController.toggleFlashlight(context, true)
            lower.contains("torch off") || lower.contains("flashlight off") ->
                PhoneController.toggleFlashlight(context, false)
            lower.startsWith("open ") ->
                PhoneController.openApp(context, raw.substring(5).trim())
            lower.startsWith("call ") ->
                PhoneController.makeCall(context, raw.substring(5).trim())
            lower.contains("reminder") || lower.contains("કામ") ->
                PhoneController.getPendingReminders(context)
            else ->
                "Boss, add your Gemini API Key via the ⚙️ KEY button for smart AI answers, live web search, and natural voice commands!"
        }
    }

    private fun executeTool(context: Context, name: String, args: JSONObject, apiKey: String): String {
        return when (name) {
            "make_call" -> PhoneController.makeCall(context, args.optString("contact_name"))
            "open_app" -> PhoneController.openApp(context, args.optString("app_name"))
            "send_whatsapp" -> PhoneController.sendWhatsApp(
                context,
                args.optString("contact_name"),
                args.optString("message")
            )
            "toggle_flashlight" -> PhoneController.toggleFlashlight(context, args.optBoolean("turn_on", true))
            "adjust_volume" -> PhoneController.adjustVolume(context, args.optString("action", "up"))
            "navigate_maps" -> PhoneController.navigateMaps(context, args.optString("destination"))
            "play_youtube" -> PhoneController.playYouTube(context, args.optString("query"))
            "set_reminder" -> PhoneController.setReminder(
                context,
                args.optString("title", "Reminder"),
                args.optInt("delay_minutes", 0),
                args.optString("time_24h", "")
            )
            "get_reminders" -> PhoneController.getPendingReminders(context)
            "search_live_web" -> runLiveSearch(apiKey, args.optString("query"))
            else -> "Executed $name, Boss."
        }
    }

    private fun runLiveSearch(apiKey: String, query: String): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", "Answer concisely in 2 sentences as F.R.I.D.A.Y. calling the user Boss: $query")
                }))
            }))
            put("tools", JSONArray().put(JSONObject().apply {
                put("google_search", JSONObject())
            }))
        }
        val res = postGemini(apiKey, body)
        val text = res.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "")
        return if (!text.isNullOrBlank()) text.replace("*", "").trim()
        else "I couldn't fetch live web results right now, Boss."
    }

    private fun postGemini(apiKey: String, body: JSONObject): JSONObject {
        val url = URL("$MODEL_URL?key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun toolDecl(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", desc)
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", props)
                if (required.isNotEmpty()) put("required", JSONArray(required))
            })
        }
    }

    private fun prop(type: String, desc: String) = JSONObject().apply {
        put("type", type)
        put("description", desc)
    }

    private fun buildToolPayload(userInput: String): JSONObject {
        val decls = JSONArray().apply {
            put(toolDecl("make_call", "Call a contact or phone number.",
                JSONObject().put("contact_name", prop("STRING", "Name or phone number")), listOf("contact_name")))
            put(toolDecl("open_app", "Open any installed Android app like YouTube, WhatsApp, Camera, Settings, Instagram.",
                JSONObject().put("app_name", prop("STRING", "App name")), listOf("app_name")))
            put(toolDecl("send_whatsapp", "Send a WhatsApp message to a contact.",
                JSONObject()
                    .put("contact_name", prop("STRING", "Contact name or number"))
                    .put("message", prop("STRING", "Message text")), listOf("contact_name", "message")))
            put(toolDecl("toggle_flashlight", "Turn phone flashlight/torch on or off.",
                JSONObject().put("turn_on", prop("BOOLEAN", "true to turn on, false to turn off")), listOf("turn_on")))
            put(toolDecl("adjust_volume", "Change media volume.",
                JSONObject().put("action", prop("STRING", "One of: up, down, mute, unmute, max")), listOf("action")))
            put(toolDecl("navigate_maps", "Open Google Maps navigation to a place.",
                JSONObject().put("destination", prop("STRING", "Place or city name")), listOf("destination")))
            put(toolDecl("play_youtube", "Search and play videos or songs on YouTube.",
                JSONObject().put("query", prop("STRING", "Video or song query")), listOf("query")))
            put(toolDecl("set_reminder", "Set an alarm or reminder.",
                JSONObject()
                    .put("title", prop("STRING", "Reminder title"))
                    .put("delay_minutes", prop("INTEGER", "Minutes from now, 0 if specific time"))
                    .put("time_24h", prop("STRING", "24h time like 17:30 if specified")), listOf("title")))
            put(toolDecl("get_reminders", "List upcoming pending reminders.", JSONObject()))
            put(toolDecl("search_live_web", "Search live Google web for weather, news, cricket scores, stock prices, or recent facts.",
                JSONObject().put("query", prop("STRING", "Search query")), listOf("query")))
        }

        return JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text", "You are F.R.I.D.A.Y., an intelligent personal Android AI assistant. Call the user 'Boss'. Understand English, Gujarati, and Hindi. Reply concisely in 1-2 sentences in the user's language. Use tools whenever the user wants a phone action or live web info.")
            )))
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text", userInput)
            ))))
            put("tools", JSONArray().put(JSONObject().put("function_declarations", decls)))
        }
    }
}
