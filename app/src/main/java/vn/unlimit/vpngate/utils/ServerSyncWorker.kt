package vn.unlimit.vpngate.utils

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import vn.unlimit.vpngate.App
import java.util.concurrent.TimeUnit

/**
 * Refreshes the cached VPN Gate server list in the background on the interval configured in
 * Settings ("Cache save time" - [DataUtil.SETTING_CACHE_TIME_KEY], minimum 15 minutes since
 * that's WorkManager's own minimum periodic interval). New servers are added and servers that
 * disappeared from every source are dropped, because [ServerListRepository.syncNow] replaces the
 * whole local snapshot on each successful run.
 */
class ServerSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val count = ServerListRepository.syncNow(applicationContext)
            Log.i(TAG, "Background server list sync stored $count servers")
            Result.success()
        } catch (e: Throwable) {
            Log.w(TAG, "Background server list sync failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ServerSyncWorker"
        private const val UNIQUE_WORK_NAME = "vpngate_server_sync"

        // Matches the "Cache save time" setting options (index -> minutes); WorkManager itself
        // enforces a 15-minute floor for periodic work, so shorter settings just run at 15 min.
        private val cacheMinutesByIndex = intArrayOf(15, 30, 60, 120, 240, 480, 960)

        /**
         * (Re)schedules the periodic sync using the interval currently selected in Settings.
         * Call with [ExistingPeriodicWorkPolicy.UPDATE] after the user changes the interval, or
         * [ExistingPeriodicWorkPolicy.KEEP] at app startup so an already-scheduled job is left
         * alone.
         */
        fun schedule(context: Context, policy: ExistingPeriodicWorkPolicy) {
            val dataUtil = App.instance?.dataUtil ?: return
            val index = dataUtil.getIntSetting(DataUtil.SETTING_CACHE_TIME_KEY, 0)
                .coerceIn(0, cacheMinutesByIndex.lastIndex)
            val minutes = cacheMinutesByIndex[index].coerceAtLeast(15)

            val request = PeriodicWorkRequestBuilder<ServerSyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, policy, request)
        }
    }
}
