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
 * Monitor directo de sensores de hardware del reloj Wear OS.
 * • Mantiene registrado únicamente el coprocesador de bajo consumo `TYPE_STEP_COUNTER` (con throttle de 3.5s).
 * • El sensor óptico PPG (`TYPE_HEART_RATE`) SOLO se enciende durante las ráfagas bajo demanda de 14s
 *   (`triggerHeartRateBurst`), eliminando por completo el drenaje continuo de batería.
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
        private const val STEP_PUSH_THROTTLE_MS = 3_500L
        private const val HR_PUSH_THROTTLE_MS = 12_000L
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
    private var lastStepNotifyMillis = 0L
    private var lastHrNotifyMillis = 0L
    private var burstFirstSampleDelivered = false

    var onHeartRateUpdated: ((Long) -> Unit)? = null
    var onStepsUpdated: ((Long) -> Unit)? = null

    fun startListening() {
        if (isListening) return
        val sm = sensorManager ?: return
        isListening = true

        // Solo el podómetro de hardware (ultra bajo consumo) queda activo en segundo plano
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { stepSensor ->
            sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    /**
     * Activa una medición puntual de pulso de 14 segundos (SENSOR_DELAY_FASTEST) y apaga el LED óptico
     * PPG inmediatamente al terminar para proteger la batería del reloj.
     */
    fun triggerHeartRateBurst() {
        val sm = sensorManager ?: return
        val hrSensor = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return

        burstFirstSampleDelivered = false
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
                val changed = computedSteps != stepsToday
                stepsToday = computedSteps
                prefs.edit().putLong(KEY_CACHED_TODAY_STEPS, computedSteps).apply()

                val now = System.currentTimeMillis()
                if (changed && now - lastStepNotifyMillis >= STEP_PUSH_THROTTLE_MS) {
                    lastStepNotifyMillis = now
                    onStepsUpdated?.invoke(computedSteps)
                }
            }

            Sensor.TYPE_HEART_RATE -> {
                val bpm = ev.values.firstOrNull()?.toLong() ?: return
                if (bpm in 30L..240L) {
                    latestHeartRateBpm = bpm
                    prefs.edit().putLong(KEY_CACHED_HR_BPM, bpm).apply()

                    val now = System.currentTimeMillis()
                    if (!burstFirstSampleDelivered || now - lastHrNotifyMillis >= HR_PUSH_THROTTLE_MS) {
                        burstFirstSampleDelivered = true
                        lastHrNotifyMillis = now
                        onHeartRateUpdated?.invoke(bpm)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
