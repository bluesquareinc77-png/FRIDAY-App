package com.friday.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.sqrt

class FridayOrbService : Service(), SensorEventListener {

    private var windowManager: WindowManager? = null
    private var orbView: TextView? = null
    private var sensorManager: SensorManager? = null
    private var lastTriggerMs = 0L

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        showFloatingOrb()
        setupShakeSensor()
    }

    private fun startAsForeground() {
        val channelId = "friday_orb_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "F.R.I.D.A.Y. Quick Trigger",
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
            .setContentTitle("F.R.I.D.A.Y. Active")
            .setContentText("Tap orb, shake phone, or tap here to speak")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
        startForeground(101, notification)
    }

    private fun showFloatingOrb() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val sizePx = (52 * density).toInt()

        val orb = TextView(this).apply {
            text = "F"
            textSize = 18f
            setTextColor(Color.parseColor("#E0F2FE"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#DD082F49"))
                setStroke((2.5f * density).toInt(), Color.parseColor("#22D3EE"))
            }
            elevation = 10f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
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
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
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
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun setupShakeSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel != null) {
            sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        if (gForce > 2.4) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerMs > 2500L) {
                lastTriggerMs = now
                launchAssistant()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun launchAssistant() {
        val intent = Intent(this, AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        orbView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        orbView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
