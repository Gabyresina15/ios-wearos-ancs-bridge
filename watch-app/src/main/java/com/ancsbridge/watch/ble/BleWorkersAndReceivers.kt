package com.ancsbridge.watch.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.ancsbridge.watch.health.TelemetrySyncManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BlePassiveScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                BleReconnectWorker.enqueuePeriodicWatchdog(context)
                val serviceIntent = Intent(context, AncsBleService::class.java)
                context.startForegroundService(serviceIntent)
            }

            "com.ancsbridge.watch.ACTION_PASSIVE_BLE_FOUND" -> {
                val results = intent.getParcelableArrayListExtra(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                    ScanResult::class.java
                )
                val foundDevice: BluetoothDevice? = results?.firstOrNull()?.device
                if (foundDevice != null) {
                    Log.i("BlePassiveReceiver", "iPhone detectado por hardware BLE filter: ${foundDevice.address}")
                    val serviceIntent = Intent(context, AncsBleService::class.java).apply {
                        action = AncsBleService.ACTION_FAST_RECONNECT
                        putExtra(BluetoothDevice.EXTRA_DEVICE, foundDevice)
                    }
                    context.startForegroundService(serviceIntent)
                }
            }
        }
    }
}

class BleReconnectWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val serviceIntent = Intent(applicationContext, AncsBleService::class.java).apply {
            action = AncsBleService.ACTION_WATCHDOG_TICK
        }
        applicationContext.startForegroundService(serviceIntent)

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        val telemetryManager = TelemetrySyncManager(applicationContext, httpClient)
        val snapshot = telemetryManager.collectTelemetrySnapshot()
        telemetryManager.pushTelemetryViaHttp(snapshot)

        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "ancs_doze_watchdog_work"
        private const val BACKOFF_WORK_NAME = "ancs_exponential_reconnect_work"

        fun enqueuePeriodicWatchdog(context: Context) {
            val request = PeriodicWorkRequestBuilder<BleReconnectWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun scheduleExponentialReconnect(context: Context, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<BleReconnectWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                BACKOFF_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
