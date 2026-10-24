package com.ancsbridge.watch.ble

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.ancsbridge.watch.health.TelemetrySyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BleReconnectWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val intent = Intent(applicationContext, AncsBleService::class.java).apply {
            action = AncsBleService.ACTION_WATCHDOG_TICK
        }
        applicationContext.startForegroundService(intent)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ble_reconnect_watchdog"

        fun scheduleExponentialReconnect(context: Context, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<BleReconnectWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun enqueueNetworkFlush(context: Context) {
            val request = OneTimeWorkRequestBuilder<BleReconnectWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun enqueuePeriodicWatchdog(context: Context) {
            val request = PeriodicWorkRequestBuilder<BleReconnectWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

class TelemetrySyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val telemetryManager = TelemetrySyncManager(applicationContext)
        val snapshot = telemetryManager.collectTelemetrySnapshot()
        telemetryManager.enqueueAndFlushToBackend(snapshot)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "telemetry_sync"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<TelemetrySyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

class BlePassiveScanReceiver : BroadcastReceiver() {
    companion object {
        fun scheduleDozeHeartbeatAlarm(context: Context, delayMinutes: Long) {
            // Stub
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.ancsbridge.watch.ACTION_PASSIVE_BLE_FOUND" -> {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                device?.let {
                    val serviceIntent = Intent(context, AncsBleService::class.java).apply {
                        action = AncsBleService.ACTION_FAST_RECONNECT
                        putExtra(BluetoothDevice.EXTRA_DEVICE, it)
                    }
                    context.startForegroundService(serviceIntent)
                }
            }
            "com.ancsbridge.watch.ACTION_DOZE_HEARTBEAT_ALARM",
            Intent.ACTION_BOOT_COMPLETED -> {
                val serviceIntent = Intent(context, AncsBleService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
