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
 * honours Doze — the OS batches our daily download with other work instead of waking the radio.
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
            // UPDATE, not KEEP: KEEP ignores the new request entirely, so installs that registered
            // the old 7-day job would stay weekly forever. UPDATE swaps in this request's period
            // and constraints but keeps the original enqueue time, so re-scheduling on every app
            // start still doesn't reset the clock. (Name kept as-is: renaming would orphan the
            // job already registered on existing installs.)
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
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
