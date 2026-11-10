package com.ancsbridge.watch.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Monitor directo de sensores de hardware del Galaxy Watch 6 (TYPE_STEP_COUNTER y TYPE_HEART_RATE).
 * Soporta medición continua de pasos y ráfagas de alta precisión bajo demanda para el sensor óptico PPG.
 */
class HardwareSensorMonitor(
    context: Context,
    private val scope: CoroutineScope
) : SensorEventListener {

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

    private val _isMeasuringHr = MutableStateFlow(false)
    val isMeasuringHr: StateFlow<Boolean> = _isMeasuringHr.asStateFlow()

    private var isListening = false
    private var burstJob: Job? = null
    var onHeartRateUpdated: ((Long) -> Unit)? = null

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

    /**
     * Activa una medición activa de pulso de 14 segundos a máxima frecuencia (SENSOR_DELAY_FASTEST)
     * cuando el usuario toca "Medir pulso" en el reloj o en la app del iPhone.
     */
    fun triggerHeartRateBurst() {
        val sm = sensorManager ?: return
        val hrSensor = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return

        _isMeasuringHr.value = true
        runCatching {
            sm.unregisterListener(this, hrSensor)
            sm.registerListener(this, hrSensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        burstJob?.cancel()
        burstJob = scope.launch(Dispatchers.Main) {
            delay(14_000L)
            _isMeasuringHr.value = false
            runCatching {
                sm.unregisterListener(this@HardwareSensorMonitor, hrSensor)
                sm.registerListener(this@HardwareSensorMonitor, hrSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    fun stopListening() {
        burstJob?.cancel()
        _isMeasuringHr.value = false
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
                    if (_isMeasuringHr.value) {
                        _isMeasuringHr.value = false
                    }
                    onHeartRateUpdated?.invoke(bpm)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
