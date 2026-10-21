package com.ancsbridge.watch.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class WatchHealthSnapshot(
    val stepsToday: Long,
    val latestHeartRateBpm: Long,
    val weightKg: Double?,
    val bodyFatPercentage: Double?,
    val timestamp: Long = System.currentTimeMillis()
)

class HealthSyncManager(private val context: Context) {

    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    /**
     * Extrae el total de pasos del día, última FC, último peso y último % de grasa corporal (BIA)
     * registrados por Samsung Health en el Galaxy Watch 6 Classic.
     */
    suspend fun collectDailyHealthSnapshot(): WatchHealthSnapshot {
        val now = Instant.now()
        val startOfDay = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS).toInstant()
        val last30Days = now.minus(30, ChronoUnit.DAYS)

        // 1. Pasos acumulados hoy
        val stepsResponse = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
            )
        )
        val totalSteps = stepsResponse.records.sumOf { it.count }

        // 2. Última frecuencia cardíaca
        val hrResponse = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
            )
        )
        val latestHr = hrResponse.records
            .flatMap { it.samples }
            .maxByOrNull { it.time }
            ?.beatsPerMinute ?: 0L

        // 3. Último Peso registrado (en Kg)
        val weightResponse = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(last30Days, now)
            )
        )
        val latestWeightKg = weightResponse.records
            .maxByOrNull { it.time }
            ?.weight?.inKilograms

        // 4. Último Porcentaje de Grasa Corporal (sensor BIA del Galaxy Watch 6)
        val bodyFatResponse = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = BodyFatRecord::class,
                timeRangeFilter = TimeRangeFilter.between(last30Days, now)
            )
        )
        val latestBodyFatPct = bodyFatResponse.records
            .maxByOrNull { it.time }
            ?.percentage?.value

        return WatchHealthSnapshot(
            stepsToday = totalSteps,
            latestHeartRateBpm = latestHr,
            weightKg = latestWeightKg,
            bodyFatPercentage = latestBodyFatPct
        )
    }

    fun toWebSocketPayload(snapshot: WatchHealthSnapshot): String {
        return JSONObject().apply {
            put("type", "HEALTH_SYNC")
            put("stepsToday", snapshot.stepsToday)
            put("latestHeartRateBpm", snapshot.latestHeartRateBpm)
            put("weightKg", snapshot.weightKg ?: JSONObject.NULL)
            put("bodyFatPercentage", snapshot.bodyFatPercentage ?: JSONObject.NULL)
            put("timestamp", snapshot.timestamp)
        }.toString()
    }
}
