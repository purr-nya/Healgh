package com.example

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HealthData(
    val heartRate: Float = 0f,
    val steps: Float = 0f,
    val timestamp: Long = 0
)

class HealthViewModel : ViewModel() {

    val currentData: StateFlow<HealthData> = HealthRepository.currentData
    val isMonitoring: StateFlow<Boolean> = HealthRepository.isMonitoring
    val history: StateFlow<List<HealthData>> = HealthRepository.history

    private val _webhookUrl = MutableStateFlow("")
    val webhookUrl: StateFlow<String> = _webhookUrl.asStateFlow()

    private val _syncFrequency = MutableStateFlow(5) // in seconds
    val syncFrequency: StateFlow<Int> = _syncFrequency.asStateFlow()

    private var sharedPrefs: SharedPreferences? = null

    fun init(context: Context) {
        sharedPrefs = context.getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
        _webhookUrl.value = sharedPrefs?.getString("webhook_url", "") ?: ""
        _syncFrequency.value = sharedPrefs?.getInt("sync_frequency", 5) ?: 5
    }

    fun updateSettings(url: String, frequency: Int) {
        _webhookUrl.value = url
        _syncFrequency.value = frequency
        sharedPrefs?.edit()?.apply {
            putString("webhook_url", url)
            putInt("sync_frequency", frequency)
            apply()
        }
    }

    fun startMonitoring(context: Context) {
        if (isMonitoring.value) return
        val intent = Intent(context, HealthSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopMonitoring(context: Context) {
        val intent = Intent(context, HealthSyncService::class.java)
        context.stopService(intent)
    }
}
