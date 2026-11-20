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
            refreshPrompts(app)
            Result.success()
        } catch (e: SyncException) {
            app.sync.recordError(e.message.orEmpty())
            Diag.log("sync", "Backup failed: ${e.message?.lineSequence()?.first()}" + if (e.network) " (will retry)" else "")
            if (e.network && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    /** At most twice a day: the backend's prompt files, if they changed (a prompt edited without a new app). */
    private suspend fun refreshPrompts(app: ReminderApp) {
        val prefs = app.getSharedPreferences("prompts", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked_at", 0) < PROMPTS_EVERY_MS) return
        try {
            val set = app.syncApi.prompts()
            prefs.edit().putLong("checked_at", now).apply()
            if (set.version != app.promptFiles.version() && app.promptFiles.save(set)) {
                Diag.log("llm", "Prompts updated from the server (${set.version})")
            }
        } catch (e: SyncException) {
            Diag.log("llm", "Couldn't check for newer prompts: ${e.message?.lineSequence()?.first()}")
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
        private const val PROMPTS_EVERY_MS = 12 * 3600 * 1000L
    }
}

object SyncScheduler {
    private const val CONVENIENT = "sync-convenient"
    private const val DAILY = "sync-daily"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * The cheap extra run, when you leave the app with changes not yet backed up: only on Wi-Fi (or another network
     * that isn't metered) and with the battery not low, whenever Android finds that convenient. A lost or reset phone
     * then loses minutes rather than up to a day. Changes made meanwhile go into the same upload.
     */
    fun whenConvenient(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager(context)?.enqueueUniqueWork(CONVENIENT, ExistingWorkPolicy.KEEP, request)
    }

    /** Once a day whatever happens, on any network: the backup itself, and the "phone is alive" signal. */
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
