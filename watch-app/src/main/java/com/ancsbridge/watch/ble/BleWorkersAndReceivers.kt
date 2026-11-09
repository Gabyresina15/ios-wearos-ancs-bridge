package com.ancsbridge.watch.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

class BleReconnectWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val intent = Intent(applicationContext, AncsBleService::class.java).apply {
            action = AncsBleService.ACTION_WATCHDOG_TICK
        }
        runCatching {
            applicationContext.startForegroundService(intent)
        }
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

class BlePassiveScanReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PASSIVE_BLE_FOUND = "com.ancsbridge.watch.ACTION_PASSIVE_BLE_FOUND"
        const val ACTION_NOTIFICATION_ACTION = "com.ancsbridge.watch.ACTION_NOTIFICATION_ACTION"
        const val EXTRA_NOTIF_UID = "extra_notif_uid"
        const val EXTRA_NOTIF_OP = "extra_notif_op"

        const val OP_POSITIVE = "POSITIVE"
        const val OP_NEGATIVE = "NEGATIVE"
        const val OP_DISMISSED = "DISMISSED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PASSIVE_BLE_FOUND -> {
                // Soporta tanto el resultado de BluetoothLeScanner (EXTRA_LIST_SCAN_RESULT) como EXTRA_DEVICE directo
                val scanResults = intent.getParcelableArrayListExtra(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                    ScanResult::class.java
                )
                val device = scanResults?.firstOrNull()?.device
                    ?: intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

                device?.let { foundDevice ->
                    val serviceIntent = Intent(context, AncsBleService::class.java).apply {
                        action = AncsBleService.ACTION_FAST_RECONNECT
                        putExtra(BluetoothDevice.EXTRA_DEVICE, foundDevice)
                    }
                    runCatching { context.startForegroundService(serviceIntent) }
                }
            }

            ACTION_NOTIFICATION_ACTION -> {
                val uid = intent.getIntExtra(EXTRA_NOTIF_UID, -1)
                if (uid == -1) return
                val op = intent.getStringExtra(EXTRA_NOTIF_OP) ?: return

                val serviceIntent = Intent(context, AncsBleService::class.java).apply {
                    action = AncsBleService.ACTION_HANDLE_NOTIF_INTENT
                    putExtra(EXTRA_NOTIF_UID, uid)
                    putExtra(EXTRA_NOTIF_OP, op)
                }
                runCatching { context.startForegroundService(serviceIntent) }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                val serviceIntent = Intent(context, AncsBleService::class.java)
                runCatching { context.startForegroundService(serviceIntent) }
            }
        }
    }
}
