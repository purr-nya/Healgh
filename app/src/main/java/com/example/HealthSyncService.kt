package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HealthSyncService : Service(), SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var stepSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var syncJob: Job? = null
    private var webSocket: WebSocket? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
        
    companion object {
        private const val CHANNEL_ID = "HealthSyncChannel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HealthSync::BackgroundWakelock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours timeout to prevent infinite drain

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val allSensors = sensorManager?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
        heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            ?: allSensors.firstOrNull { it.stringType == "android.sensor.HRN" || it.name.contains("HEART_RATE", ignoreCase = true) }
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        heartRateSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        stepSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        
        HealthRepository.setMonitoring(true)
        startSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        syncJob?.cancel()
        serviceScope.cancel()
        webSocket?.close(1000, "Service destroyed")
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        HealthRepository.setMonitoring(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectWebSocket() {
        val prefs = getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
        var urlStr = prefs.getString("webhook_url", "") ?: ""
        if (urlStr.isBlank()) return

        if (urlStr.startsWith("http://")) urlStr = urlStr.replaceFirst("http://", "ws://")
        else if (urlStr.startsWith("https://")) urlStr = urlStr.replaceFirst("https://", "wss://")

        val request = Request.Builder().url(urlStr).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("HealthSyncService", "WebSocket Connected")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("HealthSyncService", "WebSocket Error: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("HealthSyncService", "WebSocket Closed")
            }
        })
    }

    private fun startSyncLoop() {
        connectWebSocket()
        syncJob = serviceScope.launch {
            HealthRepository.currentData.collect { data ->
                val json = JSONObject().apply {
                    put("heart_rate", data.heartRate)
                    put("steps", data.steps)
                    put("timestamp", System.currentTimeMillis())
                }
                val success = webSocket?.send(json.toString()) ?: false
                if (webSocket != null && !success) {
                    // Try to reconnect if send fails
                    webSocket?.cancel()
                    connectWebSocket()
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                HealthRepository.updateData(heartRate = event.values[0])
            }
            Sensor.TYPE_STEP_COUNTER -> {
                HealthRepository.updateData(steps = event.values[0])
            }
            else -> {
                if (event.sensor.stringType == "android.sensor.HRN" || event.sensor.name.contains("HEART_RATE", ignoreCase = true)) {
                    HealthRepository.updateData(heartRate = event.values[0])
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "后台健康同步服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("健康同步运行中")
            .setContentText("正在后台监测心率并同步数据...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
