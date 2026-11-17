package com.prominder.myapp

import android.app.Application
import android.os.Build
import com.prominder.myapp.auth.AuthRepository
import com.prominder.myapp.auth.BackendAuth
import com.prominder.myapp.auth.PrefsSessionStore
import com.prominder.myapp.data.AppDatabase
import com.prominder.myapp.data.Store
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.notify.Channels
import com.prominder.myapp.sync.SyncApi
import com.prominder.myapp.sync.SyncEngine
import com.prominder.myapp.sync.SyncScheduler
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Runs first, whenever Android starts the app: for the screen, an alarm, or after a restart. */
class ReminderApp : Application() {
    lateinit var auth: AuthRepository
        private set

    /** Your data: the phone's database, and every change to it (each one schedules the backup). */
    val db: AppDatabase by lazy { AppDatabase.open(this) }
    val store: Store by lazy { Store(db, onChange = { SyncScheduler.soon(this) }) }
    /** The backup on the backend: restore (the handover) and upload. */
    val sync: SyncEngine by lazy { SyncEngine(db, SyncApi(BuildConfig.API_URL, http) { auth.token() }, "${Build.MANUFACTURER} ${Build.MODEL}") }

    private lateinit var http: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        logCrashes()
        Channels.ensure(this)
        // Long enough for Render's free plan, which can take most of a minute to wake the server.
        http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
        auth = AuthRepository(BackendAuth(BuildConfig.API_URL, http), PrefsSessionStore(this))
        // Seeing this often without a reason (no restart, no update) means Android keeps closing the app.
        Diag.log("app", "App started (${BuildConfig.VERSION_NAME})")
        SyncScheduler.daily(this)
    }

    /** A crash is written to the Diagnostics log first, then Android handles it as usual. */
    private fun logCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val where = e.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                Diag.log("crash", "${e::class.java.simpleName}: ${e.message} at $where")
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
