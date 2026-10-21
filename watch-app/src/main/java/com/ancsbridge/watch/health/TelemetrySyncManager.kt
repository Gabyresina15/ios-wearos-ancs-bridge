package com.ancsbridge.watch.health

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.ancsbridge.watch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class WatchTelemetrySnapshot(
    val watchBatteryPercent: Int,
    val isCharging: Boolean,
    val stepsToday: Long,
    val latestHeartRateBpm: Long,
    val weightKg: Double?,
    val bodyFatPercentage: Double?,
    val timestamp: Long = System.currentTimeMillis()
)

class TelemetrySyncManager(
    private val context: Context,
    private val httpClient: OkHttpClient
) {

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

    suspend fun collectTelemetrySnapshot(): WatchTelemetrySnapshot {
        val (batteryPct, isCharging) = readBatteryState()

        var totalSteps = 0L
        var latestHr = 0L
        var latestWeightKg: Double? = null
        var latestBodyFatPct: Double? = null

        runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val now = Instant.now()
            val startOfDay = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS).toInstant()
            val last30Days = now.minus(30, ChronoUnit.DAYS)

            val stepsResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )
            totalSteps = stepsResponse.records.sumOf { it.count }

            val hrResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )
            latestHr = hrResponse.records
                .flatMap { it.samples }
                .maxByOrNull { it.time }
                ?.beatsPerMinute ?: 0L

            val weightResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(last30Days, now)
                )
            )
            latestWeightKg = weightResponse.records
                .maxByOrNull { it.time }
                ?.weight?.inKilograms

            val bodyFatResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = BodyFatRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(last30Days, now)
                )
            )
            latestBodyFatPct = bodyFatResponse.records
                .maxByOrNull { it.time }
                ?.percentage?.value
        }

        return WatchTelemetrySnapshot(
            watchBatteryPercent = batteryPct,
            isCharging = isCharging,
            stepsToday = totalSteps,
            latestHeartRateBpm = latestHr,
            weightKg = latestWeightKg,
            bodyFatPercentage = latestBodyFatPct
        )
    }

    fun toJson(snapshot: WatchTelemetrySnapshot): JSONObject {
        return JSONObject().apply {
            put("type", "TELEMETRY_SYNC")
            put("watchBatteryPercent", snapshot.watchBatteryPercent)
            put("isCharging", snapshot.isCharging)
            put("stepsToday", snapshot.stepsToday)
            put("latestHeartRateBpm", snapshot.latestHeartRateBpm)
            put("weightKg", snapshot.weightKg ?: JSONObject.NULL)
            put("bodyFatPercentage", snapshot.bodyFatPercentage ?: JSONObject.NULL)
            put("timestamp", snapshot.timestamp)
        }
    }

    suspend fun pushTelemetryViaHttp(snapshot: WatchTelemetrySnapshot): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = toJson(snapshot)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val req = Request.Builder()
                    .url("${BuildConfig.HTTP_BACKEND_URL}/api/telemetry")
                    .post(body)
                    .build()

                httpClient.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
}
