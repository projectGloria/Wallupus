package dev.wallupus.filter

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager rather than AlarmManager/coroutines: it survives process death and reboots, and
 * honours Doze — the OS batches our weekly download with other work instead of waking the radio.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Partial success (some sources served from cache) still installed a working blocklist;
        // that counts as done — retrying would just hammer list hosts.
        return when {
            BlocklistUpdater.update(applicationContext).installed -> Result.success()
            runAttemptCount < 3 -> Result.retry() // exponential backoff by default
            else -> Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "blocklist-weekly"
        private const val MANUAL = "blocklist-now"
        const val STALE_MS = 24 * 60 * 60 * 1_000L

        fun schedulePeriodic(ctx: Context) {
            // Daily on unmetered Wi-Fi: conditional GETs make unchanged lists a tiny 304, while
            // daily-rotating DGA ad domains (AdGuard / HaGeZi) stay fresh instead of 6 days behind.
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED) // never burn mobile data
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            // KEEP: re-scheduling on every app start must not reset the clock.
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** "Update now" — any network, user asked for it explicitly. */
        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, req)
        }
    }
}
