package com.batterydrainer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class BatteryDrainService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_drainer_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "BatteryDrainService"
    }

    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private var threshold = 20
    private var wakeLock: PowerManager.WakeLock? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (scale > 0) (level * 100) / scale else 100

                if (batteryPct <= threshold && isRunning.get()) {
                    pauseDrain(batteryPct)
                } else {
                    updateNotification(batteryPct)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                threshold = intent.getIntExtra("threshold", 20)
                startDraining()
            }
            "STOP" -> {
                stopDraining()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startDraining() {
        if (isRunning.get()) return

        isRunning.set(true)

        // Start foreground notification
        val notification = buildNotification(0, "运行中...")
        startForeground(NOTIFICATION_ID, notification)

        // Acquire wake lock to keep CPU running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BatteryDrainer::Wakelock"
        )
        wakeLock?.acquire()

        // Register battery receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)

        // Start CPU-intensive threads
        startCpuIntensiveTasks()

        // Start network drain tasks
        startNetworkDrainTasks()

        sendStatusUpdate("运行中")
    }

    private fun startCpuIntensiveTasks() {
        val numCores = Runtime.getRuntime().availableProcessors()
        for (i in 0 until numCores) {
            executor.submit {
                while (isRunning.get()) {
                    // Heavy floating-point math to drain battery
                    var result = 0.0
                    for (j in 0 until 100000) {
                        result += sqrt(j.toDouble()) * Math.sin(j.toDouble())
                        result += Math.cos(j.toDouble()) * Math.log(j.toDouble() + 1)
                        result += Math.pow(j.toDouble(), 0.5) * Math.E
                    }
                    // Small yield to prevent ANR but keep CPU busy
                    Thread.sleep(1)
                }
            }
        }
    }

    private fun startNetworkDrainTasks() {
        executor.submit {
            while (isRunning.get()) {
                try {
                    // Make network requests to drain battery
                    val url = URL("https://httpbin.org/bytes/1024")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.connect()
                    conn.inputStream.readBytes()
                    conn.disconnect()
                    Thread.sleep(500)
                } catch (e: Exception) {
                    Log.d(TAG, "Network task error: ${e.message}")
                    Thread.sleep(2000)
                }
            }
        }

        // Vibration drain
        executor.submit {
            while (isRunning.get()) {
                try {
                    vibrateDevice()
                    Thread.sleep(3000)
                } catch (e: Exception) {
                    Thread.sleep(5000)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(200)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Vibrate error: ${e.message}")
        }
    }

    private fun pauseDrain(currentBattery: Int) {
        isRunning.set(false)
        executor.shutdownNow()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        val notification = buildNotification(currentBattery, "已暂停 - 电量 ${currentBattery}%")
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)

        sendStatusUpdate("已暂停 (电量 ${currentBattery}% <= 阈值 ${threshold}%)")

        // Stop foreground but keep notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun stopDraining() {
        isRunning.set(false)
        executor.shutdownNow()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}

        sendStatusUpdate("已停止")
    }

    private fun updateNotification(batteryPct: Int) {
        val notification = buildNotification(batteryPct, "运行中 - 电量 ${batteryPct}%")
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(batteryPct: Int, status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("电池消耗器")
            .setContentText("$status | 阈值: ${threshold}%")
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "电池消耗服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "电池消耗器后台服务通知"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun sendStatusUpdate(status: String) {
        val intent = Intent("com.batterydrainer.STATUS_UPDATE")
        intent.setPackage(packageName)
        intent.putExtra("status", status)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopDraining()
        super.onDestroy()
    }
}
