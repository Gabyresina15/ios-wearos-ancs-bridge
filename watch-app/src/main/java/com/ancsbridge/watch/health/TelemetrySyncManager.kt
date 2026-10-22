package com.ancsbridge.watch.health

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
import com.ancsbridge.watch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

enum class HealthConnectStatus(val label: String) {
    UNKNOWN("Verificando HC..."),
    READY("Health Connect OK"),
    PERMISSIONS_MISSING("Faltan Permisos HC"),
    UNAVAILABLE("HC No Disponible")
}

data class WatchTelemetrySnapshot(
    val id: Long = 0L,
    val watchBatteryPercent: Int,
    val isCharging: Boolean,
    val stepsToday: Long,
    val latestHeartRateBpm: Long,
    val weightKg: Double?,
    val bodyFatPercentage: Double?,
    val timestamp: Long = System.currentTimeMillis()
)

class TelemetrySqliteQueue(context: Context) :
    SQLiteOpenHelper(context, "ancs_telemetry_queue.db", null, 1) {

    companion object {
        private const val TABLE_QUEUE = "telemetry_queue"
        private const val MAX_QUEUE_SIZE = 200
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_QUEUE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                battery INTEGER NOT NULL,
                charging INTEGER NOT NULL,
                steps INTEGER NOT NULL,
                hr INTEGER NOT NULL,
                weight REAL,
                body_fat REAL,
                ts INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_QUEUE")
        onCreate(db)
    }

    @Synchronized
    fun enqueue(snapshot: WatchTelemetrySnapshot) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("battery", snapshot.watchBatteryPercent)
            put("charging", if (snapshot.isCharging) 1 else 0)
            put("steps", snapshot.stepsToday)
            put("hr", snapshot.latestHeartRateBpm)
            if (snapshot.weightKg != null) put("weight", snapshot.weightKg) else putNull("weight")
            if (snapshot.bodyFatPercentage != null) put("body_fat", snapshot.bodyFatPercentage) else putNull("body_fat")
            put("ts", snapshot.timestamp)
        }
        db.insert(TABLE_QUEUE, null, values)

        db.execSQL(
            """
            DELETE FROM $TABLE_QUEUE WHERE id NOT IN (
                SELECT id FROM $TABLE_QUEUE ORDER BY ts DESC LIMIT $MAX_QUEUE_SIZE
            )
            """.trimIndent()
        )
    }

    @Synchronized
    fun peekPending(limit: Int = 50): List<WatchTelemetrySnapshot> {
        val list = mutableListOf<WatchTelemetrySnapshot>()
        val db = readableDatabase
        db.rawQuery("SELECT * FROM $TABLE_QUEUE ORDER BY ts ASC LIMIT $limit", null).use { cursor ->
            while (cursor.moveToNext()) {
                val weightIdx = cursor.getColumnIndexOrThrow("weight")
                val fatIdx = cursor.getColumnIndexOrThrow("body_fat")
                list.add(
                    WatchTelemetrySnapshot(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        watchBatteryPercent = cursor.getInt(cursor.getColumnIndexOrThrow("battery")),
                        isCharging = cursor.getInt(cursor.getColumnIndexOrThrow("charging")) == 1,
                        stepsToday = cursor.getLong(cursor.getColumnIndexOrThrow("steps")),
                        latestHeartRateBpm = cursor.getLong(cursor.getColumnIndexOrThrow("hr")),
                        weightKg = if (cursor.isNull(weightIdx)) null else cursor.getDouble(weightIdx),
                        bodyFatPercentage = if (cursor.isNull(fatIdx)) null else cursor.getDouble(fatIdx),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("ts"))
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val idCsv = ids.joinToString(",")
        writableDatabase.execSQL("DELETE FROM $TABLE_QUEUE WHERE id IN ($idCsv)")
    }

    @Synchronized
    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_QUEUE", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
}

class TelemetrySyncManager(private val context: Context) {

    companion object {
        private const val TAG = "TelemetrySyncManager"

        val REQUIRED_HEALTH_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class)
        )
    }

    private val queueDb = TelemetrySqliteQueue(context)

    private val coldStartHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _healthStatus = MutableStateFlow(HealthConnectStatus.UNKNOWN)
    val healthStatus: StateFlow<HealthConnectStatus> = _healthStatus.asStateFlow()

    private val _pendingQueueCount = MutableStateFlow(queueDb.count())
    val pendingQueueCount: StateFlow<Int> = _pendingQueueCount.asStateFlow()

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
            val hasStepsAndHr = granted.contains(HealthPermission.getReadPermission(StepsRecord::class))
            val status = if (granted.containsAll(REQUIRED_HEALTH_PERMISSIONS) || hasStepsAndHr) {
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

        var totalSteps = 0L
        var latestHr = 0L
        var latestWeightKg: Double? = null
        var latestBodyFatPct: Double? = null

        val status = checkHealthConnectPermissions()
        if (status == HealthConnectStatus.READY) {
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
            }.onFailure { err ->
                Log.e(TAG, "Error leyendo registros de Health Connect", err)
            }
        }

        return WatchTelemetrySnapshot(
            watchBatteryPercent = batteryPct,
            isCharging = isCharging,
            stepsToday = totalSteps,
            latestHeartRateBpm = latestHr,
            weightKg = latestWeightKg,
            bodyFatPercentage = latestBodyFatPct,
            timestamp = correctedNowMillis
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

    fun enqueueOffline(snapshot: WatchTelemetrySnapshot) {
        queueDb.enqueue(snapshot)
        _pendingQueueCount.value = queueDb.count()
    }

    suspend fun enqueueAndFlushToBackend(snapshot: WatchTelemetrySnapshot): Boolean =
        withContext(Dispatchers.IO) {
            queueDb.enqueue(snapshot)
            _pendingQueueCount.value = queueDb.count()
            flushOfflineQueue()
        }

    suspend fun flushOfflineQueue(): Boolean = withContext(Dispatchers.IO) {
        val pending = queueDb.peekPending(limit = 50)
        if (pending.isEmpty()) return@withContext true

        runCatching {
            val wakeReq = Request.Builder()
                .url("${BuildConfig.HTTP_BACKEND_URL}/health")
                .get()
                .build()
            coldStartHttpClient.newCall(wakeReq).execute().close()

            val batchArray = JSONArray()
            pending.forEach { item -> batchArray.put(toJson(item)) }
            val payload = JSONObject().apply {
                put("items", batchArray)
            }

            val body = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val postReq = Request.Builder()
                .url("${BuildConfig.HTTP_BACKEND_URL}/api/telemetry/batch")
                .post(body)
                .build()

            coldStartHttpClient.newCall(postReq).execute().use { response ->
                if (response.isSuccessful) {
                    queueDb.deleteByIds(pending.map { it.id })
                    _pendingQueueCount.value = queueDb.count()
                    Log.i(TAG, "Cola offline vaciada con éxito (${pending.size} registros).")
                    true
                } else {
                    false
                }
            }
        }.getOrElse { err ->
            Log.w(TAG, "No se pudo vaciar la cola hacia Render: ${err.message}")
            false
        }
    }
}
