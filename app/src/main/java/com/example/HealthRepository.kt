package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HealthRepository {
    private val _currentData = MutableStateFlow(HealthData())
    val currentData: StateFlow<HealthData> = _currentData.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _history = MutableStateFlow<List<HealthData>>(emptyList())
    val history: StateFlow<List<HealthData>> = _history.asStateFlow()

    fun updateData(heartRate: Float? = null, steps: Float? = null) {
        val current = _currentData.value
        val newHr = heartRate ?: current.heartRate
        val newSteps = steps ?: current.steps
        val newData = current.copy(
            heartRate = newHr,
            steps = newSteps,
            timestamp = System.currentTimeMillis()
        )
        _currentData.value = newData
        
        if (heartRate != null) {
            val currentHistory = _history.value.toMutableList()
            currentHistory.add(newData)
            if (currentHistory.size > 50) {
                currentHistory.removeAt(0)
            }
            _history.value = currentHistory
        }
    }
    
    fun setMonitoring(isMonitoring: Boolean) {
        _isMonitoring.value = isMonitoring
    }
}
