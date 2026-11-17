package com.prominder.myapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prominder.myapp.BuildConfig
import com.prominder.myapp.ReminderApp
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.Times
import com.prominder.myapp.sync.SyncException
import com.prominder.myapp.alarm.TestAlarm
import com.prominder.myapp.remind.Reminders
import com.prominder.myapp.auth.AuthException
import com.prominder.myapp.auth.AuthRepository
import com.prominder.myapp.auth.GoogleSignIn
import com.prominder.myapp.auth.Session
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.notify.Notifier
import com.prominder.myapp.setup.CheckId
import com.prominder.myapp.setup.Checklist
import com.prominder.myapp.setup.DeviceFacts
import com.prominder.myapp.setup.DeviceSetup
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Logged out → the login screen; logged in → home, the checklist and Diagnostics. */
@Composable
fun AppRoot(auth: AuthRepository) {
    val session by auth.session.collectAsStateWithLifecycle()
    val current = session
    if (current == null) LoginRoute(auth) else SignedInRoute(auth, current)
}

@Composable
private fun LoginRoute(auth: AuthRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configured = auth.isConfigured && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
    var state by remember { mutableStateOf(LoginState(configured = configured)) }

    LoginScreen(
        state = state,
        onSignIn = {
            state = state.copy(busy = true, message = null)
            scope.launch {
                try {
                    // Null: you closed Google's sheet.
                    val idToken = GoogleSignIn.idToken(context, BuildConfig.GOOGLE_WEB_CLIENT_ID)
                    if (idToken != null) {
                        state = state.copy(message = "Signing in… The server can take a minute to wake up.")
                        auth.signIn(idToken)
                    }
                } catch (e: AuthException) {
                    Diag.log("auth", "Sign-in failed: ${e.message}")
                    state = state.copy(message = e.message)
                } finally {
                    state = state.copy(busy = false)
                }
            }
        },
    )
}

/** Logged in: the handover first (the phone has none of your data yet), then the app itself. */
@Composable
private fun SignedInRoute(auth: AuthRepository, session: Session) {
    val context = LocalContext.current
    val app = context.applicationContext as ReminderApp
    var hasData by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { hasData = app.store.hasData() }
    when (hasData) {
        null -> Muted("Loading…")
        false -> HandoverRoute(app, onDone = { hasData = true }, onLogout = { logout(context, auth) })
        true -> MainRoutes(app, auth, session)
    }
}

@Composable
private fun HandoverRoute(app: ReminderApp, onDone: () -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(HandoverState()) }
    LaunchedEffect(Unit) {
        // Only to choose the wording (move or restore); the button works either way.
        state = try {
            val st = app.sync.status()
            state.copy(alreadyHandedOver = st.handedOverAt != null,
                lastBackup = Times.parseOrNull(st.lastUploadAt)?.let { Format.time(it, java.time.ZoneId.systemDefault(), Instant.now()) })
        } catch (e: SyncException) {
            state
        }
    }
    HandoverScreen(
        state = state,
        onMove = {
            state = state.copy(busy = true, message = "Downloading… The server can take a minute to wake up.")
            scope.launch {
                try {
                    val r = app.sync.restore(handover = true)
                    Diag.log("sync", "Handover done: ${r.tasks} tasks, ${r.messages} messages on the phone")
                    Reminders.tickSoon(app, "your data arrived")
                    onDone()
                } catch (e: SyncException) {
                    state = state.copy(busy = false, message = e.message)
                }
            }
        },
        onLogout = onLogout,
    )
}

private fun logout(context: Context, auth: AuthRepository) {
    auth.logout()
    CoroutineScope(Dispatchers.Main).launch { GoogleSignIn.forget(context) }
}

@Composable
private fun MainRoutes(app: ReminderApp, auth: AuthRepository, session: Session) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stack by rememberSaveable { mutableStateOf(listOf(Route.Home.key)) }
    val route = Route.parse(stack.last())
    fun go(r: Route) { stack = stack + r.key }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    var facts by remember { mutableStateOf(DeviceSetup.facts(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    var testNotice by remember { mutableStateOf<String?>(null) }
    // The log changes whenever something happens (an alarm rings, a backup runs): reload then.
    val logVersion by Diag.log.version.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        // Right after the first login, the checklist opens by itself, once.
        if (!DeviceSetup.checklistSeen(context)) {
            DeviceSetup.markChecklistSeen(context)
            go(Route.Checklist)
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5000)
            notice = null
        }
    }
    // Coming back from a settings screen: check again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { facts = DeviceSetup.facts(context) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Diag.log("setup", "Notification permission " + if (granted) "granted" else "denied")
        facts = DeviceSetup.facts(context)
        // Denied before, Android no longer shows the dialog: the switch is in settings.
        if (!granted) DeviceSetup.open(context, CheckId.NOTIFICATIONS, facts)
    }

    BackHandler(enabled = stack.size > 1) { back() }

    val items = Checklist.build(facts)
    when (route) {
        Route.Home -> HomeRoute(
            store = app.store,
            setupDone = Checklist.doneCount(items),
            setupTotal = items.size,
            notice = notice,
            onNotice = { notice = it },
            go = ::go,
            onOpenChecklist = { go(Route.Checklist) },
            onLogout = { logout(context, auth) },
        )
        Route.Tasks -> TasksRoute(app.store, ::go, ::back)
        is Route.Task -> TaskDetailRoute(app.store, route.id, onNotice = { notice = it }, go = ::go, onBack = ::back)
        is Route.NewTask -> TaskFormRoute(app.store, null, route.parentId,
            onSaved = { id -> stack = stack.dropLast(1) + if (route.parentId == null) listOf(Route.Task(id).key) else emptyList() },
            onBack = ::back)
        is Route.EditTask -> TaskFormRoute(app.store, route.id, null, onSaved = { back() }, onBack = ::back)
        Route.Settings -> SettingsRoute(app.store, ::back)
        Route.Checklist -> ChecklistScreen(
            items = items,
            onOpen = { id ->
                if (id == CheckId.NOTIFICATIONS && Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    DeviceSetup.open(context, id, facts)
                }
            },
            onConfirm = { id, checked ->
                if (id == CheckId.SAMSUNG_SLEEP) DeviceSetup.setSamsungConfirmed(context, checked)
                facts = DeviceSetup.facts(context)
            },
            onBack = ::back,
        )
        Route.Diagnostics -> {
            val lines = remember(logVersion) { Diag.log.lines() }
            val syncFacts by produceState(emptyList<Pair<String, String>>(), logVersion) { value = syncFacts(app) }
            val summary = diagnosticsFacts(context, session, facts, logVersion) + syncFacts
            val pending = remember(logVersion) { TestAlarm.pending(context) }
            DiagnosticsScreen(
                facts = summary,
                logLines = lines,
                onShare = { share(context, summary, lines) },
                onClear = { Diag.log.clear() },
                onBack = ::back,
                onSyncNow = {
                    scope.launch {
                        try {
                            app.sync.upload(force = true)
                        } catch (e: SyncException) {
                            app.sync.recordError(e.message.orEmpty())
                            Diag.log("sync", "Backup failed: ${e.message?.lineSequence()?.first()}")
                        }
                    }
                },
                onTickNow = { Reminders.tickSoon(app, "Check now (Diagnostics)") },
                tests = PhoneTests(
                    pendingAlarm = pending?.let {
                        "Test alarm set for ${Diag.log.format(it.at).substringAfter(' ')}" + if (it.exact) "" else " (inexact)"
                    },
                    lastAlarmResult = remember(logVersion) { TestAlarm.lastResult(context) },
                    notice = testNotice,
                    onNotifyNow = { testNotice = notifyNotice(context, urgent = false) },
                    onUrgentNow = { testNotice = notifyNotice(context, urgent = true) },
                    onAlarmIn1Min = { testNotice = alarmNotice(context, 60_000L, facts) },
                    onAlarmIn1Hour = { testNotice = alarmNotice(context, 3_600_000L, facts) },
                    onCancelAlarm = { TestAlarm.cancel(context); testNotice = null },
                ),
            )
        }
    }
}

/** The backup's state, for Diagnostics. */
private suspend fun syncFacts(app: ReminderApp): List<Pair<String, String>> {
    val dao = app.store.dao
    fun at(text: String?) = Times.parseOrNull(text)?.let { Diag.log.format(it.toEpochMilli()) } ?: "never"
    return listOf(
        "On the phone" to "${dao.taskCount()} tasks, ${dao.messageCount()} messages",
        "Handed over" to at(dao.meta(Store.META_HANDED_OVER_AT)),
        "Restored" to at(dao.meta(Store.META_RESTORED_AT)),
        "Last backup" to at(dao.meta(Store.META_LAST_UPLOAD_AT)),
        "Waiting for backup" to "${app.sync.pending()} changes",
        "Next reminder check" to (Reminders.next(app)?.let { Diag.log.format(it.at) + if (it.exact) "" else " (inexact)" } ?: "not set"),
        "Last tick" to (Reminders.lastTick(app)?.let { Diag.log.format(it) } ?: "never"),
    ) + listOfNotNull(dao.meta(Store.META_LAST_SYNC_ERROR)?.takeIf { it.isNotBlank() }?.let { "Last backup error" to it })
}

private fun notifyNotice(context: Context, urgent: Boolean): String? =
    if (Notifier.showTest(context, urgent)) null else "Notifications are off. Open Setup to allow them."

private fun alarmNotice(context: Context, delayMs: Long, facts: DeviceFacts): String {
    val pending = TestAlarm.schedule(context, delayMs)
    return buildString {
        append(if (delayMs < 3_600_000L) "Set. Lock the phone if you like; it rings in a minute." else "Set. Lock the phone and leave it for an hour.")
        if (!pending.exact) append(" 'Alarms & reminders' is off, so it may ring late.")
        if (!facts.notificationsOn) append(" Notifications are off, so it won't show: open Setup.")
    }
}

private fun diagnosticsFacts(context: Context, session: Session, facts: DeviceFacts, @Suppress("UNUSED_PARAMETER") version: Int): List<Pair<String, String>> {
    fun yesNo(b: Boolean) = if (b) "yes" else "NO"
    val pending = TestAlarm.pending(context)
    return buildList {
        add("App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        add("Phone" to "${Build.MANUFACTURER} ${Build.MODEL}")
        add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Time zone" to java.time.ZoneId.systemDefault().id)
        add("Logged in" to session.email)
        add("Notifications" to yesNo(facts.notificationsOn))
        add("Exact alarms" to yesNo(facts.exactAlarmsOn))
        add("Full-screen alarms" to yesNo(facts.fullScreenOn))
        add("Battery unrestricted" to yesNo(facts.batteryUnrestricted))
        if (facts.isSamsung) add("Samsung never sleeping" to if (facts.samsungNeverSleepingConfirmed) "ticked" else "not ticked")
        add("Urgent through DND" to yesNo(facts.urgentBypassesDnd))
        add("Alarm through DND" to yesNo(facts.alarmBypassesDnd))
        add("Test alarm waiting" to (pending?.let { Diag.log.format(it.at) + if (it.exact) " (exact)" else " (inexact)" } ?: "none"))
        add("Last test alarm" to (TestAlarm.lastResult(context) ?: "none yet"))
    }
}

private fun share(context: Context, facts: List<Pair<String, String>>, lines: List<String>) {
    val text = buildString {
        appendLine("ProMinder: diagnostics")
        facts.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("Log (oldest first):")
        lines.takeLast(500).forEach(::appendLine)
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, "Reminder diagnostics")
    context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
