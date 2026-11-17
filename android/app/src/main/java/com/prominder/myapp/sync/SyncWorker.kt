package com.prominder.myapp.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prominder.myapp.ReminderApp
import com.prominder.myapp.diag.Diag
import java.util.concurrent.TimeUnit

/**
 * The backup upload, run by WorkManager: Android runs it when there's internet, even if the app is closed or the
 * phone restarted in between, and retries it later when the server can't be reached.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ReminderApp
        // Before the handover there's nothing of yours on the phone to back up.
        if (!app.store.hasData() || app.auth.token() == null) return Result.success()
        return try {
            app.sync.upload()
            Result.success()
        } catch (e: SyncException) {
            app.sync.recordError(e.message.orEmpty())
            Diag.log("sync", "Backup failed: ${e.message?.lineSequence()?.first()}" + if (e.network) " (will retry)" else "")
            if (e.network && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
    }
}

object SyncScheduler {
    private const val SOON = "sync-soon"
    private const val NOW = "sync-now"
    private const val DAILY = "sync-daily"

    /**
     * While the app is being built (until Phase F) the backup runs a couple of minutes after every change, so a
     * reinstall loses minutes, not a day. Changes made meanwhile go into the same upload.
     */
    val SOON_DELAY_MIN = 2L

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun soon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(SOON_DELAY_MIN, TimeUnit.MINUTES)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager(context)?.enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
    }

    /** Right away (Sync now, the app going to the background). */
    fun now(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager(context)?.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Once a day whatever happens: the safety net, and the "phone is alive" signal. */
    fun daily(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        workManager(context)?.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Null (and logged) if WorkManager isn't available, e.g. in a test that didn't set it up: a change is never lost over it. */
    private fun workManager(context: Context): WorkManager? =
        runCatching { WorkManager.getInstance(context) }.onFailure { Diag.log("sync", "Can't schedule the backup: ${it.message}") }.getOrNull()
}
