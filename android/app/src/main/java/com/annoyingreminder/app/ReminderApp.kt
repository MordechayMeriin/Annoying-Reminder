package com.annoyingreminder.app

import android.app.Application
import com.annoyingreminder.app.auth.AuthRepository
import com.annoyingreminder.app.auth.PrefsSessionStore
import com.annoyingreminder.app.auth.SupabaseAuth
import com.annoyingreminder.app.diag.Diag
import com.annoyingreminder.app.notify.Channels
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
        val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        auth = AuthRepository(SupabaseAuth(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, http), PrefsSessionStore(this))
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
