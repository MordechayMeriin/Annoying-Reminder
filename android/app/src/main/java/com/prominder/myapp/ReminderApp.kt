package com.prominder.myapp

import android.app.Application
import com.prominder.myapp.auth.AuthRepository
import com.prominder.myapp.auth.BackendAuth
import com.prominder.myapp.auth.PrefsSessionStore
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.notify.Channels
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Runs first, whenever Android starts the app: for the screen, an alarm, or after a restart. */
class ReminderApp : Application() {
    lateinit var auth: AuthRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        logCrashes()
        Channels.ensure(this)
        // Long enough for Render's free plan, which can take most of a minute to wake the server.
        val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
        auth = AuthRepository(BackendAuth(BuildConfig.API_URL, http), PrefsSessionStore(this))
        // Seeing this often without a reason (no restart, no update) means Android keeps closing the app.
        Diag.log("app", "App started (${BuildConfig.VERSION_NAME})")
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
