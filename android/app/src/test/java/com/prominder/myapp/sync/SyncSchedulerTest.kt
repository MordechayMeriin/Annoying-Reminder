package com.prominder.myapp.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.prominder.myapp.ReminderApp
import com.prominder.myapp.data.TaskForm
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** When the backup runs (Phase F): once a day on any network, and when convenient (Wi-Fi) after you leave the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SyncSchedulerTest {
    private val app: ReminderApp = ApplicationProvider.getApplicationContext()
    private lateinit var work: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        work = WorkManager.getInstance(app)
    }

    private fun unique(name: String): List<WorkInfo> = work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)

    @Test fun `the daily backup runs on any network, every 24 hours`() {
        SyncScheduler.daily(app)
        val info = unique("sync-daily").single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(TimeUnit.HOURS.toMillis(24), info.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test fun `leaving the app backs up only on Wi-Fi with the battery not low, once however often you leave`() {
        SyncScheduler.whenConvenient(app)
        SyncScheduler.whenConvenient(app)
        val info = unique("sync-convenient").single()
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
    }

    @Test fun `a change to your data no longer schedules a backup by itself`() = runBlocking {
        app.store.create(TaskForm("Call the bank"))
        assertTrue(unique("sync-soon").isEmpty())
        assertTrue(unique("sync-convenient").isEmpty())
    }
}
