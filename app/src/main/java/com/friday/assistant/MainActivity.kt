package com.friday.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatItem(val sender: String, val text: String, val time: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }
        setContent {
            FridayHudApp(
                speechRecognizer = speechRecognizer,
                onSpeak = { text, enabled -> if (enabled) speakOut(text) }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.setPitch(1.08f)
            tts?.setSpeechRate(1.05f)
        }
    }

    private fun speakOut(text: String) {
        if (!ttsReady) return
        val locale = when {
            Regex("[\\u0A80-\\u0AFF]").containsMatchIn(text) -> Locale("gu", "IN")
            Regex("[\\u0900-\\u097F]").containsMatchIn(text) -> Locale("hi", "IN")
            else -> Locale.UK
        }
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "friday_tts")
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun FridayHudApp(
    speechRecognizer: SpeechRecognizer?,
    onSpeak: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var showKeyDialog by remember { mutableStateOf(false) }
    var voiceEnabled by remember { mutableStateOf(true) }
    var orbActive by remember { mutableStateOf(false) }
    var micLang by remember { mutableStateOf(prefs.getString("mic_lang", "en-IN") ?: "en-IN") }
    var inputText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("F.R.I.D.A.Y. ONLINE") }
    var liveClock by remember { mutableStateOf("00:00:00") }
    var isListening by remember { mutableStateOf(false) }
    var livePartial by remember { mutableStateOf("") }

    val messages = remember {
        mutableStateListOf(
            ChatItem(
                "F.R.I.D.A.Y.",
                "Turbo Siri Mode Active, Boss! Turn ON '🛸 ORB + SHAKE' to open me from anywhere in 0.1s without holding the power button.",
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            )
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(Unit) {
        while (true) {
            liveClock = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    fun toggleOrbService() {
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
            messages.add(ChatItem("F.R.I.D.A.Y.", "Please allow 'Display over other apps', then tap 🛸 ORB + SHAKE again, Boss.", liveClock))
            return
        }
        val svcIntent = Intent(context, FridayOrbService::class.java)
        if (orbActive) {
            context.stopService(svcIntent)
            orbActive = false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
            orbActive = true
        }
    }

    fun sendCommand(cmd: String) {
        val clean = cmd.trim()
        if (clean.isEmpty()) return
        val nowTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        messages.add(ChatItem("BOSS", clean, nowTime))
        statusText = "PROCESSING..."

        scope.launch {
            listState.animateScrollToItem(messages.size - 1)
            val result = FridayBrain.process(context, clean, apiKey)
            val rTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            messages.add(ChatItem("F.R.I.D.A.Y.", result.reply, rTime))
            statusText = "F.R.I.D.A.Y. ONLINE"
            onSpeak(result.reply, voiceEnabled)
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun toggleInlineMic() {
        if (speechRecognizer == null) {
            messages.add(ChatItem("F.R.I.D.A.Y.", "Inline speech service unavailable on this device.", liveClock))
            return
        }
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
            statusText = "F.R.I.D.A.Y. ONLINE"
            return
        }
        livePartial = ""
        statusText = "LISTENING..."
        isListening = true

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                livePartial = ""
                statusText = "F.R.I.D.A.Y. ONLINE"
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) livePartial = p
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                livePartial = ""
                statusText = "F.R.I.D.A.Y. ONLINE"
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) sendCommand(spoken)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, micLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        speechRecognizer.startListening(intent)
    }

    val pulse = rememberInfiniteTransition(label = "arc")
    val scale by pulse.animateFloat(
        initialValue = 0.96f,
        targetValue = if (isListening) 1.14f else 1.05f,
        animationSpec = infiniteRepeatable(tween(if (isListening) 500 else 1800), RepeatMode.Reverse),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF082F49), Color(0xFF030712))))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xAA020617))
                .border(1.dp, Color(0xFF0E7490), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("F.R.I.D.A.Y.", color = Color(0xFF22D3EE), fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("$statusText • $liveClock", color = if (isListening) Color(0xFFF43F5E) else Color(0xFF34D399), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { voiceEnabled = !voiceEnabled }) {
                    Text(if (voiceEnabled) "🔊" else "🔇", fontSize = 15.sp)
                }
                Button(
                    onClick = { toggleOrbService() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (orbActive) Color(0xFF059669) else Color(0xFF155E75)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (orbActive) "🛸 ON" else "🛸 ORB", fontSize = 10.sp, color = Color.White)
                }
                Button(
                    onClick = { showKeyDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E7490)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("⚙️ KEY", fontSize = 10.sp, color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xBB020617))
                .border(1.dp, if (isListening) Color(0xFF22D3EE) else Color(0xFF155E75), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(if (isListening) Color(0xFF164E63) else Color(0xFF083344))
                    .border(2.dp, if (isListening) Color(0xFFF43F5E) else Color(0xFF22D3EE), CircleShape)
                    .clickable { toggleInlineMic() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (isListening) "🔴" else "🎤", fontSize = 26.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isListening) (if (livePartial.isNotEmpty()) livePartial else "Listening inline, Boss...") else "Tap Core to Speak (0.1s Fast Mode)",
                    color = Color(0xFFA5F3FC),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("en-IN" to "EN", "gu-IN" to "ગુજ", "hi-IN" to "हिन्दी").forEach { (code, label) ->
                        val active = micLang == code
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0xFF06B6D4) else Color(0xFF0F172A))
                                .clickable {
                                    micLang = code
                                    prefs.edit().putString("mic_lang", code).apply()
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(label, color = if (active) Color.Black else Color(0xFF67E8F9), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x99020617))
                .border(1.dp, Color(0xFF164E63), RoundedCornerShape(16.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                val isAI = msg.sender == "F.R.I.D.A.Y."
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (isAI) 0.dp else 28.dp, end = if (isAI) 20.dp else 0.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isAI) Color(0xFF082F49) else Color(0xFF1E293B))
                        .border(1.dp, if (isAI) Color(0xFF0891B2) else Color(0xFF475569), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Text("${msg.sender} • ${msg.time}", color = if (isAI) Color(0xFF22D3EE) else Color(0xFF34D399), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(3.dp))
                    Text(msg.text, color = Color(0xFFE0F2FE), fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Command F.R.I.D.A.Y...", color = Color(0xFF0E7490), fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFE0F2FE),
                    unfocusedTextColor = Color(0xFFE0F2FE),
                    focusedBorderColor = Color(0xFF22D3EE),
                    unfocusedBorderColor = Color(0xFF155E75)
                )
            )
            Button(
                onClick = {
                    sendCommand(inputText)
                    inputText = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                modifier = Modifier.height(52.dp)
            ) {
                Text("RUN", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showKeyDialog) {
        var tempKey by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            containerColor = Color(0xFF020617),
            title = { Text("Gemini API Key", color = Color(0xFF22D3EE)) },
            text = {
                OutlinedTextField(
                    value = tempKey,
                    onValueChange = { tempKey = it },
                    placeholder = { Text("AIzaSy...", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            },
            confirmButton = {
                Button(onClick = {
                    apiKey = tempKey.trim()
                    prefs.edit().putString("gemini_api_key", apiKey).apply()
                    showKeyDialog = false
                }) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("CANCEL", color = Color(0xFF67E8F9)) }
            }
        )
    }
}
