package com.prominder.myapp

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import android.os.Build
import com.prominder.myapp.alarm.AlarmRings
import com.prominder.myapp.auth.AuthRepository
import com.prominder.myapp.auth.BackendAuth
import com.prominder.myapp.auth.PrefsSessionStore
import com.prominder.myapp.data.AppDatabase
import com.prominder.myapp.data.Store
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.i18n.AppLanguage
import com.prominder.myapp.llm.Agent
import com.prominder.myapp.llm.BackendLlm
import com.prominder.myapp.llm.Chat
import com.prominder.myapp.llm.Llm
import com.prominder.myapp.llm.PromptFiles
import com.prominder.myapp.notify.Channels
import com.prominder.myapp.remind.Reminders
import com.prominder.myapp.remind.Replanner
import com.prominder.myapp.sync.SyncApi
import com.prominder.myapp.sync.SyncEngine
import com.prominder.myapp.sync.SyncScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Runs first, whenever Android starts the app: for the screen, an alarm, or after a restart. */
class ReminderApp : Application() {
    lateinit var auth: AuthRepository
        private set

    /** Background work that outlives a screen: ticks, notification buttons. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Your data: the phone's database, and every change to it (each one re-plans the next tick). The backup runs once
     * a day, and when you leave the app on Wi-Fi (SyncScheduler), not after every change.
     */
    val db: AppDatabase by lazy { AppDatabase.open(this) }
    /** Re-plans the next tick after changes, once per burst (a chat turn's tool calls, a tick's writes). */
    val replanner: Replanner by lazy { Replanner(scope) { Reminders.plan(this) } }
    val store: Store by lazy {
        Store(db, onChange = {
            replanner.request()
        }, onUserActivity = {
            // Writing in the chat or tapping anything means you've seen it.
            AlarmRings.stop(this, "you wrote or tapped something in the app")
        })
    }
    /** The backup on the backend: restore (the handover) and upload. */
    val syncApi: SyncApi by lazy { SyncApi(BuildConfig.API_URL, http) { auth.token() } }
    val sync: SyncEngine by lazy { SyncEngine(db, syncApi, "${Build.MANUFACTURER} ${Build.MODEL}") }

    /** The assistant (Phase D): the model through the backend, the prompts, and the chat that runs its turns. */
    val llm: Llm by lazy { BackendLlm(BuildConfig.API_URL, http) { auth.token() } }
    val promptFiles: PromptFiles by lazy { PromptFiles(this) }
    val chat: Chat by lazy { Chat(scope) { Agent(store, llm, promptFiles.prompts) } }

    /** Whether a screen of the app is showing (a reply from a notification is then shown in the chat, not notified). */
    @Volatile var visible = false

    /** Text shared from another app, until the chat takes it. */
    val shared = MutableStateFlow<String?>(null)

    /** Counts the "New task" shortcut's taps: the chat's field takes the focus each time. */
    val newTask = MutableStateFlow(0)

    /** Counts the requests to show the focus timer (▶ Start on a notification, a tap on the countdown). */
    val openSession = MutableStateFlow(0)

    private lateinit var http: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        logCrashes()
        AppLanguage.restore(this)
        Channels.ensure(this)
        // A request may take up to 90 seconds in all (a slow model call, a throttled backup), and the server may be
        // silent for most of that while it works: OkHttp's default 10-second read timeout would cut it off.
        http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).build()
        auth = AuthRepository(BackendAuth(BuildConfig.API_URL, http), PrefsSessionStore(this))
        // Seeing this often without a reason (no restart, no update) means Android keeps closing the app.
        Diag.log("app", "App started (${BuildConfig.VERSION_NAME})")
        SyncScheduler.daily(this)
        Reminders.startSafetyNet(this)
        // At once, not through the replanner: starting up is one event, not a burst of changes.
        scope.launch { Reminders.plan(this@ReminderApp) }
        addNewTaskShortcut()
    }

    /** The language changed (in the app, or Android 13+'s settings): the words Android shows outside the app too. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        languageChanged()
    }

    /** Notification channels and the shortcut are shown by Android, in the words they were last given. */
    fun languageChanged(tag: String = AppLanguage.current(this)) {
        Channels.ensure(this, tag)
        addNewTaskShortcut(tag)
    }

    /** "New task" when you long-press the app icon: opens the chat, ready to type or dictate. */
    private fun addNewTaskShortcut(tag: String = AppLanguage.current(this)) {
        runCatching {
            val words = AppLanguage.localized(this, tag)
            val shortcut = ShortcutInfoCompat.Builder(this, "new_task")
                .setShortLabel(words.getString(R.string.shortcut_new_task))
                .setLongLabel(words.getString(R.string.shortcut_new_task_long))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_TASK))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        }.onFailure { Diag.log("app", "Couldn't add the New task shortcut: ${it.message}") }
    }

    /** A crash is written to the Diagnostics log first, then Android handles it as usual. */
    private fun logCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val where = e.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                Diag.log("crash", "${e::class.java.simpleName}: ${e.message} at $where")
                Diag.log.flush()   // written before the app dies
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
