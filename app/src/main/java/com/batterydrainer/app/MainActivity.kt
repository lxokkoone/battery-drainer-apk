package com.batterydrainer.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvBatteryLevel: TextView
    private lateinit var tvThreshold: TextView
    private lateinit var tvStatus: TextView
    private lateinit var seekBarThreshold: SeekBar
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRequestIgnoreBattery: Button

    private var isServiceRunning = false
    private var thresholdValue = 20

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (scale > 0) (level * 100) / scale else 0
                tvBatteryLevel.text = "当前电量: ${batteryPct}%"
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val status = it.getStringExtra("status") ?: ""
                val batteryLevel = it.getIntExtra("batteryLevel", -1)
                tvStatus.text = "状态: $status"
                if (status.contains("已暂停") || status.contains("已停止")) {
                    isServiceRunning = false
                    updateButtonState()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvBatteryLevel = findViewById(R.id.tv_battery_level)
        tvThreshold = findViewById(R.id.tv_threshold)
        tvStatus = findViewById(R.id.tv_status)
        seekBarThreshold = findViewById(R.id.seekbar_threshold)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnRequestIgnoreBattery = findViewById(R.id.btn_request_ignore_battery)

        // Load saved threshold
        val prefs = getSharedPreferences("battery_drainer", MODE_PRIVATE)
        thresholdValue = prefs.getInt("threshold", 20)
        seekBarThreshold.progress = thresholdValue
        tvThreshold.text = "暂停阈值: ${thresholdValue}%"

        seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                thresholdValue = progress
                tvThreshold.text = "暂停阈值: ${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                getSharedPreferences("battery_drainer", MODE_PRIVATE)
                    .edit().putInt("threshold", thresholdValue).apply()
            }
        })

        btnStart.setOnClickListener {
            if (checkPermissions()) {
                startDrainService()
            }
        }

        btnStop.setOnClickListener {
            stopDrainService()
        }

        btnRequestIgnoreBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        updateButtonState()
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
            return false
        }
        return true
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "请手动在设置中关闭电池优化", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDrainService() {
        isServiceRunning = true
        updateButtonState()

        val intent = Intent(this, BatteryDrainService::class.java)
        intent.putExtra("threshold", thresholdValue)
        intent.action = "START"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        tvStatus.text = "状态: 运行中..."
        Toast.makeText(this, "耗电服务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopDrainService() {
        isServiceRunning = false
        updateButtonState()

        val intent = Intent(this, BatteryDrainService::class.java)
        intent.action = "STOP"
        startService(intent)

        tvStatus.text = "状态: 已停止"
        Toast.makeText(this, "耗电服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonState() {
        btnStart.isEnabled = !isServiceRunning
        btnStop.isEnabled = isServiceRunning
        seekBarThreshold.isEnabled = !isServiceRunning
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(batteryReceiver, filter)
        // Also get initial battery level
        batteryStatus?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = if (scale > 0) (level * 100) / scale else 0
            tvBatteryLevel.text = "当前电量: ${batteryPct}%"
        }

        // Register status receiver
        val statusFilter = IntentFilter("com.batterydrainer.STATUS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, statusFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, statusFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }
}
