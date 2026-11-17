package com.prominder.myapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.prominder.myapp.setup.Checklist
import com.prominder.myapp.setup.DeviceFacts
import com.prominder.myapp.ui.theme.Palette
import com.prominder.myapp.ui.theme.ReminderTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each screen to a PNG without a phone or emulator, at the Galaxy S9's screen size.
 * `./gradlew :app:recordRoborazziDebug` writes them to app/screenshots/; CI uploads that folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h846dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            ReminderTheme {
                Surface(color = Palette.Bg, modifier = Modifier.fillMaxSize()) { Box { content() } }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private val s9 = DeviceFacts(
        sdk = 29, isSamsung = true, notificationsOn = true, exactAlarmsOn = true, fullScreenOn = true,
        batteryUnrestricted = false, samsungNeverSleepingConfirmed = false,
        urgentBypassesDnd = true, alarmBypassesDnd = false,
    )

    @Test
    fun login() = shot("1_login") {
        LoginScreen(LoginState(), {})
    }

    @Test
    fun login_refused() = shot("2_login_refused") {
        LoginScreen(LoginState(message = "Only the owner's Google account can use this app. Choose me@example.com instead."), {})
    }

    @Test
    fun login_unconfigured() = shot("3_login_unconfigured") {
        LoginScreen(LoginState(configured = false), {})
    }

    @Test
    fun home() = shot("4_home") {
        HomeScreen(
            HomeState(
                focus = listOf(
                    FocusItem(1, "Write the quarterly report", "Today 15:00 · Important · ~25 min", done = false),
                    FocusItem(2, "Call the plumber", "", done = true),
                    FocusItem(3, "Book flights", "Tomorrow 09:00", done = false),
                ),
                messages = listOf(
                    ChatItem(1, "assistant", "morning", "Good morning! Today's 3: the report, the plumber and the flights. " +
                        "Start with the report's outline?", "07:30", day = "Today"),
                    ChatItem(2, "user", "chat", "yes, after coffee", "07:41"),
                    ChatItem(3, "user", "event", "[Marked done: Call the plumber]", "09:12"),
                    ChatItem(4, "user", "event", "[Later: Book flights — next check-in Tue 12:00]", "10:00"),
                ),
                openCount = 12, setupDone = 3, setupTotal = 4,
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun home_empty() = shot("4b_home_empty") {
        HomeScreen(HomeState(setupDone = 4, setupTotal = 4, notice = "Later: next check-in Today 12:00"), {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    /** A phone set to Hebrew: the layout stays left-to-right, Hebrew messages still read right-to-left. */
    @Test
    @Config(qualifiers = "+iw")
    fun handover_on_a_hebrew_phone() = shot("8c_handover_hebrew_phone") {
        HandoverScreen(HandoverState(alreadyHandedOver = false, message = "The server is starting up or unavailable. It'll try again."), {}, {})
    }

    @Test
    @Config(qualifiers = "+iw")
    fun home_on_a_hebrew_phone() = shot("4c_home_hebrew_phone") {
        HomeScreen(
            HomeState(
                focus = listOf(FocusItem(1, "לשלם חשבון חשמל", "Today 15:00", done = false)),
                messages = listOf(
                    ChatItem(1, "user", "chat", "תזכיר לי לשלם את החשבון מחר בבוקר.", "07:41", day = "Today"),
                    ChatItem(2, "assistant", "chat", "Done. I'll remind you tomorrow at 08:00.", "07:41"),
                ),
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun handover() = shot("8_handover") {
        HandoverScreen(HandoverState(alreadyHandedOver = false), {}, {})
    }

    @Test
    fun handover_restore() = shot("8b_handover_restore") {
        HandoverScreen(HandoverState(alreadyHandedOver = true, lastBackup = "Today 14:02", message = "Can't reach the server."), {}, {})
    }

    private val tasks = listOf(
        TaskItem(1, "Pay the electricity bill", "Yesterday 18:00", overdue = true),
        TaskItem(2, "Write the quarterly report", "Today 15:00 · Important", inFocus = true),
        TaskItem(3, "Standup", "📅 Meeting · Today 16:45 · 🔁 Sun–Thu"),
        TaskItem(4, "Outline", "", parent = "Step of: Write the quarterly report"),
        TaskItem(5, "Stop work", "⛔ Hard stop · Today 19:00", canBeDone = false),
    )

    @Test
    fun tasks_today() = shot("9_tasks_today") {
        TasksScreen(TasksState(0, tasks.take(4), listOf(TaskItem(6, "Dentist", "📅 Meeting · Thu 19 Nov 10:00")), tasks), {}, {}, {}, {}, {})
    }

    @Test
    fun tasks_all() = shot("9b_tasks_all") {
        TasksScreen(TasksState(1, emptyList(), emptyList(), tasks), {}, {}, {}, {}, {})
    }

    @Test
    fun task_detail() = shot("10_task") {
        TaskDetailScreen(
            TaskDetailState(
                id = 2, title = "Write the quarterly report", meta = "Today 15:00 · Important · ~25 min",
                nextAction = "Open the doc and write three headings", resumeNote = "Stopped at the intro",
                description = "For Dana. Numbers are in the shared sheet.", inFocus = true,
                steps = listOf(TaskItem(4, "Outline", ""), TaskItem(7, "Charts", "")),
                history = listOf(
                    HistoryItem("Postponed", "Today 10:00", "later (button)"),
                    HistoryItem("▶ Session started", "Yesterday 16:00"),
                ),
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun task_form() = shot("11_task_form") {
        TaskFormScreen(
            "New task",
            com.prominder.myapp.data.TaskForm("Dentist", kind = "meeting",
                dueAt = java.time.ZonedDateTime.of(2026, 11, 19, 10, 0, 0, 0, ZoneId.of("Asia/Jerusalem")).toInstant(), recurrence = null),
            ZoneId.of("Asia/Jerusalem"), { null }, {},
        )
    }

    @Test
    fun settings() = shot("12_settings") {
        val s = com.prominder.myapp.data.AppSettings(mapOf("morning_time" to "08:00"))
        SettingsScreen(
            com.prominder.myapp.data.SettingSpecs.ALL.map {
                SettingItem(it.key, it.label, it.help, it.group, it.kind, s.raw(it.key), it.key !in s.overrides,
                    com.prominder.myapp.data.SettingSpecs.canonical(it, it.default))
            },
            listOf(1L to "Shorter messages"), { _, _ -> null }, {}, {},
        )
    }

    @Test
    fun checklist() = shot("5_checklist") {
        ChecklistScreen(Checklist.build(s9), {}, { _, _ -> }, {})
    }

    @Test
    fun diagnostics() = shot("6_diagnostics") {
        DiagnosticsScreen(
            facts = listOf(
                "App" to "0.1.0 (12)", "Phone" to "samsung SM-G960F", "Android" to "10 (API 29)",
                "Exact alarms" to "yes", "Battery unrestricted" to "NO", "Last test alarm" to "Set for 14:00:00, rang on time",
            ),
            logLines = listOf(
                "2026-11-13 13:00:02 [alarm] Test alarm set for 2026-11-13 14:00:00 (exact)",
                "2026-11-13 13:41:10 [app] App started (0.1.0)",
                "2026-11-13 14:00:01 [alarm] Test alarm rang. Set for 2026-11-13 14:00:00, rang on time",
                "2026-11-13 14:00:09 [alarm] Alarm dismissed from the alarm screen",
            ),
            onShare = {}, onClear = {}, onBack = {}, onSyncNow = {},
            tests = PhoneTests(pendingAlarm = "Test alarm set for 15:04:00", lastAlarmResult = "Set for 2026-11-13 14:00:00, rang on time"),
        )
    }

    @Test
    fun alarm() = shot("7_alarm") {
        AlarmScreen("Test alarm", "Rang on time. Tap Dismiss to stop it.", 1_794_571_200_000, ZoneId.of("Asia/Jerusalem"), {})
    }
}
