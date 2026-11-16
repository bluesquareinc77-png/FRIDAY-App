package com.friday.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class FridayOrbService : Service() {

    private var windowManager: WindowManager? = null
    private var orbView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeRecognizer: SpeechRecognizer? = null

    @Volatile
    private var running = false
    @Volatile
    private var checkingSpeech = false
    private var lastWakeMs = 0L

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground()
        showFloatingOrb()
        startHandsFreeWakeLoop()
    }

    private fun startAsForeground() {
        val channelId = "friday_orb_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "F.R.I.D.A.Y. Hands-Free Wake Word",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, AssistActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_friday_logo)
            .setContentTitle("F.R.I.D.A.Y. Hands-Free Active")
            .setContentText("Say 'Hey Friday' anytime to wake me up")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                101,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(101, notification)
        }
    }

    private fun startHandsFreeWakeLoop() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        thread(start = true, name = "FridayWakeVAD") {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@thread
            val buffer = ShortArray(minBuf)

            while (running) {
                if (checkingSpeech || System.currentTimeMillis() - lastWakeMs < 5000L) {
                    try { Thread.sleep(300) } catch (_: Exception) {}
                    continue
                }

                var recorder: AudioRecord? = null
                try {
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        recorder.release()
                        Thread.sleep(1000)
                        continue
                    }
                    recorder.startRecording()

                    while (running && !checkingSpeech) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            var sum = 0.0
                            for (i in 0 until read) {
                                sum += buffer[i].toDouble() * buffer[i].toDouble()
                            }
                            val rms = sqrt(sum / read)
                            if (rms > 1600.0 && System.currentTimeMillis() - lastWakeMs > 5000L) {
                                checkingSpeech = true
                                break
                            }
                        }
                    }
                } catch (_: Exception) {
                    try { Thread.sleep(1000) } catch (_: Exception) {}
                } finally {
                    try { recorder?.stop() } catch (_: Exception) {}
                    try { recorder?.release() } catch (_: Exception) {}
                }

                if (running && checkingSpeech) {
                    mainHandler.post { checkWakePhraseOnMain() }
                    val waitStart = System.currentTimeMillis()
                    while (running && checkingSpeech && System.currentTimeMillis() - waitStart < 6000L) {
                        try { Thread.sleep(200) } catch (_: Exception) {}
                    }
                    checkingSpeech = false
                }
            }
        }
    }

    private fun checkWakePhraseOnMain() {
        if (!running || !SpeechRecognizer.isRecognitionAvailable(this)) {
            checkingSpeech = false
            return
        }
        try {
            wakeRecognizer?.destroy()
            wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            wakeRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    checkingSpeech = false
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ")?.lowercase() ?: ""
                    if (isWakeWord(text)) {
                        triggerWakeSuccess()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ")?.lowercase() ?: ""
                    if (isWakeWord(text)) {
                        triggerWakeSuccess()
                    } else {
                        checkingSpeech = false
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            wakeRecognizer?.startListening(intent)
        } catch (_: Exception) {
            checkingSpeech = false
        }
    }

    private fun isWakeWord(spoken: String): Boolean {
        return spoken.contains("friday") || spoken.contains("ફ્રાઈડે") || spoken.contains("फ्राइडे")
    }

    private fun triggerWakeSuccess() {
        if (System.currentTimeMillis() - lastWakeMs < 5000L) return
        lastWakeMs = System.currentTimeMillis()
        try { wakeRecognizer?.cancel() } catch (_: Exception) {}
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {}
        checkingSpeech = false
        launchAssistant()
    }

    private fun showFloatingOrb() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val sizePx = (44 * density).toInt()

        val orb = TextView(this).apply {
            text = "F"
            textSize = 16f
            setTextColor(Color.parseColor("#E0F2FE"))
            gravity = Gravity.CENTER
            alpha = 0.75f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#DD082F49"))
                setStroke((2f * density).toInt(), Color.parseColor("#22D3EE"))
            }
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (320 * density).toInt()
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        orb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = initialX + dx; params.y = initialY + dy
                    try { windowManager?.updateViewLayout(orb, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) launchAssistant()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(orb, params)
            orbView = orb
        } catch (_: Exception) {}
    }

    private fun launchAssistant() {
        val intent = Intent(this, AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        running = false
        mainHandler.post {
            try { wakeRecognizer?.destroy() } catch (_: Exception) {}
        }
        orbView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        orbView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
