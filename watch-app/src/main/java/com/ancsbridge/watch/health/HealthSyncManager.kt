package com.ancsbridge.watch.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.time.LocalDate

/**
 * Monitor directo de sensores de hardware del Galaxy Watch 6 (TYPE_STEP_COUNTER y TYPE_HEART_RATE).
 * Garantiza lecturas reales de pasos y frecuencia cardíaca en tiempo real incluso cuando
 * Health Connect no está instalado o configurado en un reloj vinculado a iOS.
 */
class HardwareSensorMonitor(context: Context) : SensorEventListener {

    companion object {
        private const val PREFS_NAME = "watch_hw_sensors_prefs"
        private const val KEY_DATE_ISO = "step_baseline_date"
        private const val KEY_BASELINE_COUNT = "step_baseline_raw"
        private const val KEY_CACHED_TODAY_STEPS = "step_cached_today"
        private const val KEY_CACHED_HR_BPM = "hr_cached_bpm"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    @Volatile
    var stepsToday: Long = prefs.getLong(KEY_CACHED_TODAY_STEPS, 0L)
        private set

    @Volatile
    var latestHeartRateBpm: Long = prefs.getLong(KEY_CACHED_HR_BPM, 0L)
        private set

    private var isListening = false

    fun startListening() {
        if (isListening) return
        val sm = sensorManager ?: return
        isListening = true

        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { stepSensor ->
            sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { hrSensor ->
            sm.registerListener(this, hrSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopListening() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val ev = event ?: return
        when (ev.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSinceBoot = ev.values.firstOrNull()?.toLong() ?: return
                if (totalSinceBoot < 0L) return

                val todayIso = LocalDate.now().toString()
                val savedDate = prefs.getString(KEY_DATE_ISO, null)
                var baseline = prefs.getLong(KEY_BASELINE_COUNT, -1L)

                if (savedDate != todayIso || baseline < 0L || totalSinceBoot < baseline) {
                    baseline = totalSinceBoot
                    prefs.edit()
                        .putString(KEY_DATE_ISO, todayIso)
                        .putLong(KEY_BASELINE_COUNT, baseline)
                        .apply()
                }

                val computedSteps = (totalSinceBoot - baseline).coerceAtLeast(0L)
                stepsToday = computedSteps
                prefs.edit().putLong(KEY_CACHED_TODAY_STEPS, computedSteps).apply()
            }

            Sensor.TYPE_HEART_RATE -> {
                val bpm = ev.values.firstOrNull()?.toLong() ?: return
                if (bpm in 30L..240L) {
                    latestHeartRateBpm = bpm
                    prefs.edit().putLong(KEY_CACHED_HR_BPM, bpm).apply()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
