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

data class BrainResult(
    val reply: String,
    val expectFollowUp: Boolean = false,
    val autoClose: Boolean = false
)

object FridayBrain {

    private const val MODEL_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

    private val history = mutableListOf<JSONObject>()
    private var waitingForClarification = false

    private fun addToHistory(role: String, text: String) {
        synchronized(history) {
            history.add(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            })
            while (history.size > 8) history.removeAt(0)
        }
    }

    suspend fun process(context: Context, userInput: String, apiKey: String): BrainResult = withContext(Dispatchers.IO) {
        val clean = userInput.trim()
        val lower = clean.lowercase()

        // 1. Instant 0.1s Local Fast-Path (Skips API delay for everyday commands!)
        if (!waitingForClarification) {
            tryInstantLocal(context, clean, lower)?.let { fastRes ->
                addToHistory("user", clean)
                addToHistory("model", fastRes.reply)
                waitingForClarification = fastRes.expectFollowUp
                return@withContext fastRes
            }
        }

        if (apiKey.isBlank()) {
            val offline = handleOfflineFallback(context, clean, lower)
            waitingForClarification = offline.expectFollowUp
            return@withContext offline
        }

        try {
            addToHistory("user", clean)
            val payload = buildToolPayload()
            val resJson = postGemini(apiKey.trim(), payload)

            if (resJson.has("error")) {
                val msg = resJson.getJSONObject("error").optString("message", "API Error")
                waitingForClarification = false
                return@withContext BrainResult("API Notice: $msg. Check your key, Boss.")
            }

            val candidates = resJson.optJSONArray("candidates")
                ?: return@withContext BrainResult("No response, Boss.")
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")

            var result = BrainResult("Command processed, Boss.")
            var handledTool = false

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("functionCall")) {
                    val fc = part.getJSONObject("functionCall")
                    val name = fc.getString("name")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    result = executeTool(context, name, args, apiKey.trim())
                    handledTool = true
                    break
                }
            }

            if (!handledTool) {
                val txt = parts.getJSONObject(0).optString("text", result.reply).replace("*", "").trim()
                val asksQuestion = txt.endsWith("?")
                result = BrainResult(txt, expectFollowUp = asksQuestion, autoClose = false)
            }

            addToHistory("model", result.reply)
            waitingForClarification = result.expectFollowUp
            return@withContext result
        } catch (_: Exception) {
            val offline = handleOfflineFallback(context, clean, lower)
            waitingForClarification = offline.expectFollowUp
            return@withContext offline
        }
    }

    private fun tryInstantLocal(context: Context, raw: String, lower: String): BrainResult? {
        return when {
            lower == "time" || lower.contains("what time") || lower.contains("what is the time") ->
                BrainResult("The time is ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())}, Boss.")
            lower == "date" || lower.contains("today's date") ->
                BrainResult("Today is ${SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()).format(Date())}, Boss.")
            lower.contains("flashlight on") || lower.contains("torch on") ->
                BrainResult(PhoneController.toggleFlashlight(context, true), autoClose = true)
            lower.contains("flashlight off") || lower.contains("torch off") ->
                BrainResult(PhoneController.toggleFlashlight(context, false), autoClose = true)
            lower in listOf("volume up", "increase volume") ->
                BrainResult(PhoneController.adjustVolume(context, "up"), autoClose = true)
            lower in listOf("volume down", "decrease volume") ->
                BrainResult(PhoneController.adjustVolume(context, "down"), autoClose = true)
            lower in listOf("mute", "mute volume") ->
                BrainResult(PhoneController.adjustVolume(context, "mute"), autoClose = true)
            lower in listOf("unmute", "unmute volume") ->
                BrainResult(PhoneController.adjustVolume(context, "unmute"), autoClose = true)
            lower.startsWith("call ") && !lower.contains("whatsapp") -> {
                val target = raw.substring(5).trim()
                val msg = PhoneController.makeCall(context, target)
                val needsReply = msg.contains("?")
                BrainResult(msg, expectFollowUp = needsReply, autoClose = !needsReply && msg.startsWith("Calling"))
            }
            lower.startsWith("open ") && lower.split(" ").size <= 3 -> {
                val app = raw.substring(5).trim()
                val msg = PhoneController.openApp(context, app)
                BrainResult(msg, autoClose = msg.startsWith("Opening"))
            }
            lower.startsWith("navigate to ") -> {
                val dest = raw.substring(12).trim()
                BrainResult(PhoneController.navigateMaps(context, dest), autoClose = true)
            }
            else -> null
        }
    }

    private fun handleOfflineFallback(context: Context, raw: String, lower: String): BrainResult {
        return when {
            lower.contains("reminder") || lower.contains("કામ") ->
                BrainResult(PhoneController.getPendingReminders(context))
            else ->
                BrainResult("Boss, add your Gemini API Key via ⚙️ KEY for smart AI answers!")
        }
    }

    private fun executeTool(context: Context, name: String, args: JSONObject, apiKey: String): BrainResult {
        return when (name) {
            "make_call" -> {
                val msg = PhoneController.makeCall(context, args.optString("contact_name"))
                val needsReply = msg.contains("?")
                BrainResult(msg, expectFollowUp = needsReply, autoClose = !needsReply && msg.startsWith("Calling"))
            }
            "open_app" -> {
                val msg = PhoneController.openApp(context, args.optString("app_name"))
                BrainResult(msg, autoClose = msg.startsWith("Opening"))
            }
            "send_whatsapp" -> {
                val msg = PhoneController.sendWhatsApp(
                    context,
                    args.optString("contact_name"),
                    args.optString("message")
                )
                val needsReply = msg.contains("?")
                BrainResult(msg, expectFollowUp = needsReply, autoClose = !needsReply && msg.startsWith("Opening"))
            }
            "toggle_flashlight" ->
                BrainResult(PhoneController.toggleFlashlight(context, args.optBoolean("turn_on", true)), autoClose = true)
            "adjust_volume" ->
                BrainResult(PhoneController.adjustVolume(context, args.optString("action", "up")), autoClose = true)
            "navigate_maps" ->
                BrainResult(PhoneController.navigateMaps(context, args.optString("destination")), autoClose = true)
            "play_youtube" ->
                BrainResult(PhoneController.playYouTube(context, args.optString("query")), autoClose = true)
            "set_reminder" -> BrainResult(
                PhoneController.setReminder(
                    context,
                    args.optString("title", "Reminder"),
                    args.optInt("delay_minutes", 0),
                    args.optString("time_24h", "")
                ),
                autoClose = true
            )
            "get_reminders" -> BrainResult(PhoneController.getPendingReminders(context))
            "search_live_web" -> BrainResult(runLiveSearch(apiKey, args.optString("query")))
            else -> BrainResult("Executed $name, Boss.")
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

    private fun buildToolPayload(): JSONObject {
        val decls = JSONArray().apply {
            put(toolDecl("make_call", "Call a contact or phone number.",
                JSONObject().put("contact_name", prop("STRING", "Name or phone number")), listOf("contact_name")))
            put(toolDecl("open_app", "Open any installed Android app.",
                JSONObject().put("app_name", prop("STRING", "App name")), listOf("app_name")))
            put(toolDecl("send_whatsapp", "Send a WhatsApp message to a contact.",
                JSONObject()
                    .put("contact_name", prop("STRING", "Contact name or number"))
                    .put("message", prop("STRING", "Message text")), listOf("contact_name", "message")))
            put(toolDecl("toggle_flashlight", "Turn phone flashlight on or off.",
                JSONObject().put("turn_on", prop("BOOLEAN", "true for on, false for off")), listOf("turn_on")))
            put(toolDecl("adjust_volume", "Change media volume.",
                JSONObject().put("action", prop("STRING", "One of: up, down, mute, unmute, max")), listOf("action")))
            put(toolDecl("navigate_maps", "Open Google Maps navigation.",
                JSONObject().put("destination", prop("STRING", "Place or city")), listOf("destination")))
            put(toolDecl("play_youtube", "Search and play videos or songs on YouTube.",
                JSONObject().put("query", prop("STRING", "Query")), listOf("query")))
            put(toolDecl("set_reminder", "Set an alarm or reminder.",
                JSONObject()
                    .put("title", prop("STRING", "Reminder title"))
                    .put("delay_minutes", prop("INTEGER", "Minutes from now, 0 if specific time"))
                    .put("time_24h", prop("STRING", "24h time like 17:30")), listOf("title")))
            put(toolDecl("get_reminders", "List upcoming pending reminders.", JSONObject()))
            put(toolDecl("search_live_web", "Search live Google web for weather, news, cricket scores, prices, or facts.",
                JSONObject().put("query", prop("STRING", "Search query")), listOf("query")))
        }

        val contentsArr = JSONArray()
        synchronized(history) {
            history.forEach { contentsArr.put(it) }
        }

        return JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text", "You are F.R.I.D.A.Y., a fast Siri-like Android AI assistant. Call the user 'Boss'. Understand English, Gujarati, and Hindi. Remember recent context (e.g., if you asked which contact to call and user replies with a name, call it). Reply in 1-2 short sentences.")
            )))
            put("contents", contentsArr)
            put("tools", JSONArray().put(JSONObject().put("function_declarations", decls)))
        }
    }
}
