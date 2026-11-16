package com.friday.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class AssistActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var onTtsComplete: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { onTtsComplete?.invoke(utteranceId ?: "") }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { onTtsComplete?.invoke(utteranceId ?: "") }
            }
        })

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }

        setContent {
            SiriOverlayScreen(
                speechRecognizer = speechRecognizer,
                isTtsReady = { ttsReady },
                onSpeakWithId = { text, id -> speakOut(text, id) },
                setTtsCallback = { cb -> onTtsComplete = cb },
                onClose = { finish() }
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

    private fun speakOut(text: String, utteranceId: String) {
        if (!ttsReady) return
        val locale = when {
            Regex("[\\u0A80-\\u0AFF]").containsMatchIn(text) -> Locale("gu", "IN")
            Regex("[\\u0900-\\u097F]").containsMatchIn(text) -> Locale("hi", "IN")
            else -> Locale.UK
        }
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun SiriOverlayScreen(
    speechRecognizer: SpeechRecognizer?,
    isTtsReady: () -> Boolean,
    onSpeakWithId: (String, String) -> Unit,
    setTtsCallback: ((String) -> Unit) -> Unit,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE) }
    val apiKey = remember { prefs.getString("gemini_api_key", "") ?: "" }
    val micLang = remember { prefs.getString("mic_lang", "en-IN") ?: "en-IN" }
    val scope = rememberCoroutineScope()

    var isListening by remember { mutableStateOf(false) }
    var audioLevel by remember { mutableFloatStateOf(0.25f) }
    var spokenText by remember { mutableStateOf("Listening, Boss...") }
    var replyText by remember { mutableStateOf("") }

    lateinit var startListening: () -> Unit

    setTtsCallback { id ->
        when (id) {
            "followup" -> startListening()
            "autoclose" -> onClose()
        }
    }

    startListening = {
        if (speechRecognizer == null) {
            spokenText = "Speech recognition unavailable on device."
        } else {
            replyText = ""
            spokenText = "Listening, Boss..."
            isListening = true

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isListening = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    audioLevel = ((rmsdB + 2f) / 10f).coerceIn(0.2f, 1.6f)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListening = false }
                override fun onError(error: Int) {
                    isListening = false
                    audioLevel = 0.2f
                    spokenText = "Tap the orb to speak again, Boss."
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) spokenText = partial
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    audioLevel = 0.2f
                    val finalCmd = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!finalCmd.isNullOrBlank()) {
                        spokenText = finalCmd
                        replyText = "Processing..."
                        scope.launch {
                            val res = FridayBrain.process(context, finalCmd, apiKey)
                            replyText = res.reply
                            val uttId = when {
                                res.expectFollowUp -> "followup"
                                res.autoClose -> "autoclose"
                                else -> "normal"
                            }
                            if (isTtsReady()) {
                                onSpeakWithId(res.reply, uttId)
                            } else {
                                if (res.expectFollowUp) {
                                    delay(1200)
                                    startListening()
                                } else if (res.autoClose) {
                                    delay(1400)
                                    onClose()
                                }
                            }
                        }
                    } else {
                        spokenText = "Didn't catch that, Boss. Tap to retry."
                    }
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
    }

    LaunchedEffect(Unit) {
        startListening()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClose() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xF2082F49), Color(0xF8020617))))
                .border(1.5.dp, Color(0xFF22D3EE), RoundedCornerShape(28.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {}
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "F.R.I.D.A.Y.",
                color = Color(0xFF22D3EE),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(10.dp))

            Text(
                spokenText,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )

            if (replyText.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    replyText,
                    color = Color(0xFF67E8F9),
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF083344))
                    .border(2.dp, if (isListening) Color(0xFF22D3EE) else Color(0xFF0E7490), CircleShape)
                    .clickable { startListening() }
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                listOf(0.5f, 0.9f, 1.3f, 0.9f, 0.5f).forEach { mult ->
                    val barHeight by animateDpAsState(
                        targetValue = if (isListening) (14f * audioLevel * mult).coerceIn(8f, 36f).dp else 10.dp,
                        label = "bar"
                    )
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF22D3EE))
                    )
                }
            }
        }
    }
}
