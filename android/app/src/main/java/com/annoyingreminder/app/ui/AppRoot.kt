package com.annoyingreminder.app.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.annoyingreminder.app.BuildConfig
import com.annoyingreminder.app.alarm.TestAlarm
import com.annoyingreminder.app.auth.AuthException
import com.annoyingreminder.app.auth.AuthRepository
import com.annoyingreminder.app.auth.Session
import com.annoyingreminder.app.diag.Diag
import com.annoyingreminder.app.notify.Notifier
import com.annoyingreminder.app.setup.CheckId
import com.annoyingreminder.app.setup.Checklist
import com.annoyingreminder.app.setup.DeviceFacts
import com.annoyingreminder.app.setup.DeviceSetup
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
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(LoginState(configured = auth.isConfigured)) }

    fun run(block: suspend () -> Unit) {
        state = state.copy(busy = true, message = null)
        scope.launch {
            try {
                block()
            } catch (e: AuthException) {
                state = state.copy(message = e.message)
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    LoginScreen(
        state = state,
        onEmailChange = { state = state.copy(email = it, message = null) },
        onCodeChange = { state = state.copy(code = it, message = null) },
        onSendCode = {
            run {
                auth.sendCode(state.email)
                state = state.copy(codeSent = true, code = "", message = "Check your email for the code.")
            }
        },
        onVerify = { run { auth.verifyCode(state.email, state.code) } },
        onUseOtherEmail = { state = state.copy(codeSent = false, code = "", message = null) },
    )
}

private enum class Screen { Home, Checklist, Diagnostics }

@Composable
private fun SignedInRoute(auth: AuthRepository, session: Session) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var facts by remember { mutableStateOf(DeviceSetup.facts(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    // The log changes whenever something happens (an alarm rings, a setting is opened): reload then.
    val logVersion by Diag.log.version.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        // Right after the first login, the checklist opens by itself, once.
        if (!DeviceSetup.checklistSeen(context)) {
            DeviceSetup.markChecklistSeen(context)
            screen = Screen.Checklist
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

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    val items = Checklist.build(facts)
    when (screen) {
        Screen.Home -> {
            // Recomputed with the log so a ringing alarm updates the screen.
            val pending = remember(logVersion) { TestAlarm.pending(context) }
            val last = remember(logVersion) { TestAlarm.lastResult(context) }
            HomeScreen(
                state = HomeState(
                    email = session.email,
                    setupDone = Checklist.doneCount(items),
                    setupTotal = items.size,
                    pendingAlarm = pending?.let {
                        "Test alarm set for ${Diag.log.format(it.at).substringAfter(' ')}" + if (it.exact) "" else " (inexact)"
                    },
                    lastAlarmResult = last,
                    notice = notice,
                ),
                onOpenChecklist = { screen = Screen.Checklist },
                onOpenDiagnostics = { screen = Screen.Diagnostics },
                onNotifyNow = { notice = notifyNotice(context, urgent = false) },
                onUrgentNow = { notice = notifyNotice(context, urgent = true) },
                onAlarmIn1Min = { notice = alarmNotice(context, 60_000L, facts) },
                onAlarmIn1Hour = { notice = alarmNotice(context, 3_600_000L, facts) },
                onCancelAlarm = { TestAlarm.cancel(context); notice = null },
                onLogout = { scope.launch { auth.logout() } },
            )
        }
        Screen.Checklist -> ChecklistScreen(
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
            onBack = { screen = Screen.Home },
        )
        Screen.Diagnostics -> {
            val lines = remember(logVersion) { Diag.log.lines() }
            val summary = diagnosticsFacts(context, session, facts, logVersion)
            DiagnosticsScreen(
                facts = summary,
                logLines = lines,
                onShare = { share(context, summary, lines) },
                onClear = { Diag.log.clear() },
                onBack = { screen = Screen.Home },
            )
        }
    }
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
        appendLine("Annoying Reminder: diagnostics")
        facts.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("Log (oldest first):")
        lines.takeLast(500).forEach(::appendLine)
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, "Reminder diagnostics")
    context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
