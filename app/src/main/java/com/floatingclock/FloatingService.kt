package com.floatingclock

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import java.util.*

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var container: LinearLayout
    private lateinit var timeText: TextView
    private lateinit var cdText: TextView
    private var params: WindowManager.LayoutParams? = null
    private var downX = 0; private var downY = 0
    private var downRawX = 0f; private var downRawY = 0f
    private var isHovering = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildFloatingView()
        windowManager.addView(container, params)
        startTicking()
    }

    private fun buildFloatingView() {
        // Programmatic layout — no XML needed
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0x00000000.toInt()) // fully transparent bg
        }

        timeText = TextView(this).apply {
            text = "00:00:00"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(5f, 0f, 0f, 0xCC000000.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        container.addView(timeText)

        cdText = TextView(this).apply {
            text = "00:05:00"
            textSize = 16f
            setTextColor(0xFFDDDDDD.toInt())
            setShadowLayer(4f, 0f, 0f, 0xCC000000.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        container.addView(cdText)

        // Window params
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 300
        }

        // Touch: drag + hover detection
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = params!!.x
                    downY = params!!.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    setHover(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                        params!!.x = downX + dx.toInt()
                        params!!.y = downY + dy.toInt()
                        windowManager.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setHover(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun setHover(on: Boolean) {
        if (isHovering == on) return
        isHovering = on
        container.setBackgroundColor(
            if (on) 0x80E8E8E8.toInt()  // 50% light gray
            else 0x00000000.toInt()      // transparent
        )
    }

    private fun startTicking() {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                updateDisplay()
                handler.postDelayed(this, 200)
            }
        }
        handler.post(tick)
    }

    private fun updateDisplay() {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        val s = now.get(Calendar.SECOND)

        timeText.text = String.format("%02d:%02d:%02d", h, m, s)

        // Countdown to next 0/5 minute boundary
        val totalMin = h * 60 + m
        val delta = if (totalMin % 5 == 0) 5 else 5 - (totalMin % 5)

        val target = Calendar.getInstance().apply {
            add(Calendar.MINUTE, delta)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val diffSec = ((target.timeInMillis - now.timeInMillis) / 1000).coerceAtLeast(0)
        val cdH = diffSec / 3600
        val cdM = (diffSec % 3600) / 60
        val cdS = diffSec % 60
        cdText.text = String.format("%02d:%02d:%02d", cdH, cdM, cdS)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "fc", "悬浮钟",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮钟后台运行"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "fc")
                .setContentTitle("悬浮钟运行中")
                .setContentText("点此关闭")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(stopIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("悬浮钟运行中")
                .setContentText("点此关闭")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(stopIntent)
                .setOngoing(true)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::container.isInitialized) {
            try { windowManager.removeView(container) } catch (_: Exception) {}
        }
    }
}
