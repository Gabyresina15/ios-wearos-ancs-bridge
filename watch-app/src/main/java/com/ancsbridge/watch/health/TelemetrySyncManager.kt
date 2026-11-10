package com.ancsbridge.watch.health

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.ancsbridge.watch.ble.NowPlayingState
import com.ancsbridge.watch.ble.WearDeviceIdentityResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.max

enum class HealthConnectStatus(val label: String) {
    UNKNOWN("Sensores Activos"),
    READY("Salud + Femmto Activo"),
    PERMISSIONS_MISSING("Sensores Directos"),
    UNAVAILABLE("Sensores Directos OK")
}

data class WatchTelemetrySnapshot(
    val watchBatteryPercent: Int,
    val isCharging: Boolean,
    val stepsToday: Long,
    val latestHeartRateBpm: Long,
    val weightKg: Double?,
    val bodyFatPercentage: Double?,
    val bmi: Double? = null,
    val leanMassKg: Double? = null,
    val bodyCompSource: String = "Femmto",
    val bodyCompTimestamp: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

class TelemetrySyncManager(
    private val context: Context,
    scope: CoroutineScope
) {

    companion object {
        private const val TAG = "TelemetrySyncManager"
        private const val PREFS_TELEMETRY = "ancs_telemetry_cache"

        val REQUIRED_HEALTH_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class)
        )
    }

    private val hardwareSensors = HardwareSensorMonitor(context, scope).also { it.startListening() }
    private val prefs = context.getSharedPreferences(PREFS_TELEMETRY, Context.MODE_PRIVATE)

    private val _healthStatus = MutableStateFlow(HealthConnectStatus.UNKNOWN)
    val healthStatus: StateFlow<HealthConnectStatus> = _healthStatus.asStateFlow()

    val isMeasuringHr: StateFlow<Boolean> get() = hardwareSensors.isMeasuringHr

    fun setOnHeartRateUpdatedListener(listener: (Long) -> Unit) {
        hardwareSensors.onHeartRateUpdated = listener
    }

    fun setOnStepsUpdatedListener(listener: (Long) -> Unit) {
        hardwareSensors.onStepsUpdated = listener
    }

    fun triggerHeartRateMeasurement() {
        hardwareSensors.triggerHeartRateBurst()
    }

    fun release() {
        hardwareSensors.stopListening()
    }

    fun resolveWatchHardwareName(): String {
        return WearDeviceIdentityResolver.resolveWatchName(context)
    }

    /**
     * Guarda la composición corporal enviada desde la app del iPhone (balanza inteligente Femmto / Apple Salud).
     */
    fun saveBodyCompositionFromPhone(
        weightKg: Double?,
        bodyFatPct: Double?,
        bmi: Double?,
        leanMassKg: Double?,
        source: String?,
        measuredAtMillis: Long?
    ) {
        val editor = prefs.edit()
        if (weightKg != null && weightKg > 15.0) editor.putFloat("last_weight_kg", weightKg.toFloat())
        if (bodyFatPct != null && bodyFatPct > 1.0) editor.putFloat("last_body_fat_pct", bodyFatPct.toFloat())
        if (bmi != null && bmi > 5.0) editor.putFloat("last_bmi", bmi.toFloat())
        if (leanMassKg != null && leanMassKg > 5.0) editor.putFloat("last_lean_mass_kg", leanMassKg.toFloat())
        if (!source.isNullOrBlank()) editor.putString("last_body_src", source)
        if (measuredAtMillis != null && measuredAtMillis > 0L) editor.putLong("last_body_ts", measuredAtMillis)
        editor.apply()
    }

    fun readBatteryState(): Pair<Int, Boolean> {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        return percent to isCharging
    }

    suspend fun checkHealthConnectPermissions(): HealthConnectStatus {
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            _healthStatus.value = HealthConnectStatus.UNAVAILABLE
            return HealthConnectStatus.UNAVAILABLE
        }

        return runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val hasStepsOrWeight = granted.contains(HealthPermission.getReadPermission(StepsRecord::class)) ||
                granted.contains(HealthPermission.getReadPermission(WeightRecord::class))
            val status = if (granted.containsAll(REQUIRED_HEALTH_PERMISSIONS) || hasStepsOrWeight) {
                HealthConnectStatus.READY
            } else {
                HealthConnectStatus.PERMISSIONS_MISSING
            }
            _healthStatus.value = status
            status
        }.getOrElse {
            _healthStatus.value = HealthConnectStatus.UNAVAILABLE
            HealthConnectStatus.UNAVAILABLE
        }
    }

    suspend fun collectTelemetrySnapshot(clockDriftMillis: Long = 0L): WatchTelemetrySnapshot {
        val (batteryPct, isCharging) = readBatteryState()
        val correctedNowMillis = System.currentTimeMillis() + clockDriftMillis

        var totalSteps = hardwareSensors.stepsToday
        var latestHr = hardwareSensors.latestHeartRateBpm
        val latestWeightKg: Double? = prefs.getFloat("last_weight_kg", -1f).takeIf { it > 0f }?.toDouble()
        val latestBodyFatPct: Double? = prefs.getFloat("last_body_fat_pct", -1f).takeIf { it > 0f }?.toDouble()
        val latestBmi: Double? = prefs.getFloat("last_bmi", -1f).takeIf { it > 0f }?.toDouble()
        val latestLeanMass: Double? = prefs.getFloat("last_lean_mass_kg", -1f).takeIf { it > 0f }?.toDouble()
        val bodySource = prefs.getString("last_body_src", "Femmto") ?: "Femmto"
        val bodyTs = prefs.getLong("last_body_ts", 0L)

        val status = checkHealthConnectPermissions()
        if (status == HealthConnectStatus.READY) {
            runCatching {
                val client = HealthConnectClient.getOrCreate(context)
                val now = Instant.now()
                val startOfDay = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS).toInstant()

                val stepsResponse = client.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                    )
                )
                val hcSteps = stepsResponse.records.sumOf { it.count }
                totalSteps = max(totalSteps, hcSteps)

                val hrResponse = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                    )
                )
                val hcHr = hrResponse.records
                    .flatMap { it.samples }
                    .maxByOrNull { it.time }
                    ?.beatsPerMinute ?: 0L
                if (hcHr > 0L && latestHr == 0L) latestHr = hcHr
            }.onFailure { err ->
                Log.w(TAG, "Usando sensores directos: ${err.message}")
            }
        }

        prefs.edit()
            .putInt("last_battery_pct", batteryPct)
            .putLong("last_steps", totalSteps)
            .putLong("last_hr_bpm", latestHr)
            .apply()

        return WatchTelemetrySnapshot(
            watchBatteryPercent = batteryPct,
            isCharging = isCharging,
            stepsToday = totalSteps,
            latestHeartRateBpm = latestHr,
            weightKg = latestWeightKg,
            bodyFatPercentage = latestBodyFatPct,
            bmi = latestBmi,
            leanMassKg = latestLeanMass,
            bodyCompSource = bodySource,
            bodyCompTimestamp = bodyTs,
            timestamp = correctedNowMillis
        )
    }

    /**
     * Serializa el snapshot en formato JSON compacto para transmisión BLE GATT, resolviendo
     * dinámicamente el nombre real del reloj desde el sistema sin ningún string hardcodeado.
     */
    fun toBleJson(
        snapshot: WatchTelemetrySnapshot,
        isWatchMuted: Boolean,
        isHfpConnected: Boolean,
        isHidConnected: Boolean,
        nowPlaying: NowPlayingState? = null
    ): String {
        val watchModelName = resolveWatchHardwareName()
        return JSONObject().apply {
            put("bat", snapshot.watchBatteryPercent)
            put("chg", snapshot.isCharging)
            put("steps", snapshot.stepsToday)
            put("hr", snapshot.latestHeartRateBpm)
            put("dnd", isWatchMuted)
            put("hfp", isHfpConnected)
            put("hid", isHidConnected)
            put("model", watchModelName)
            if (nowPlaying != null) {
                put("mTitle", nowPlaying.title.take(36))
                put("mArtist", nowPlaying.artist.take(28))
                put("mPlay", nowPlaying.isPlaying)
                nowPlaying.volumePercent?.let { put("mVol", it) }
            }
            put("ts", snapshot.timestamp)
        }.toString()
    }
}
