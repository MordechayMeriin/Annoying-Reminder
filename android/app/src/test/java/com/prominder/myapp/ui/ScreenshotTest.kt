package com.prominder.myapp.ui

import androidx.compose.ui.platform.LocalResources
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

    /** Phase E: a session running with its timer closed (the bar), and "Where did you stop?" for an earlier one. */
    @Test
    fun home_session() = shot("4f_home_session") {
        val start = 1_794_571_200_000
        HomeScreen(
            HomeState(
                focus = listOf(
                    FocusItem(1, "Write the quarterly report", "Today 15:00 · Important · ~25 min", done = false),
                    FocusItem(2, "Call the plumber", "", done = true),
                    FocusItem(3, "Book flights", "Tomorrow 09:00", done = false),
                ),
                messages = listOf(
                    ChatItem(1, "user", "event", "[Stopped after 12 min: Book flights]", "09:40", day = "Today"),
                    ChatItem(2, "user", "event", "[▶ 25 min: Open the doc and write three headings (Write the quarterly report)]", "10:00"),
                ),
                openCount = 12, setupDone = 4, setupTotal = 4,
                session = SessionBarUi("Write the quarterly report", start + 25 * 60_000),
                breadcrumb = BreadcrumbUi(7, "Book flights"),
                sessionsToday = 2,
                fixedNow = start + 7 * 60_000 + 30_000,
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    /** Phase D: the assistant at work: your message sent, its steps, the typing dots, and a check-in's reply buttons. */
    @Test
    fun chat_working() = shot("4d_chat_working") {
        val chips = com.prominder.myapp.logic.ChipRules.chipsJson(com.prominder.myapp.logic.ChipRules.chipsFor("checkin", "planned"), "en", 1)
        HomeScreen(
            HomeState(
                focus = listOf(FocusItem(1, "Write the quarterly report", "Today 15:00 · Important", done = false)),
                messages = listOf(
                    ChatItem(1, "assistant", "nudge", "Report time: open the doc and write the three headings? 15 minutes is enough.", "10:00",
                        day = "Today", chips = chips, model = "google/gemini-2.5-flash"),
                    ChatItem(2, "user", "chat", "after lunch. also dentist thursday at 10", "10:04"),
                ),
                chat = ChatUi(sentId = 2, sending = "after lunch. also dentist thursday at 10",
                    steps = listOf("⏰ Scheduling a follow-up: Write the quarterly report", "📅 Saving a new meeting: Dentist"), typing = true),
                canDictate = true,
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    /** The reply buttons under the latest message, and the model line (⚙ Show which AI model replied). */
    @Test
    fun chat_chips() = shot("4e_chat_chips") {
        val chips = com.prominder.myapp.logic.ChipRules.chipsJson(com.prominder.myapp.logic.ChipRules.chipsFor("checkin", "planned"), "en", 1)
        HomeScreen(
            HomeState(
                messages = listOf(
                    ChatItem(1, "user", "chat", "remind me to call the bank", "09:58", day = "Today"),
                    ChatItem(2, "assistant", "chat", "Saved: Call the bank. First step: find the account number in the app.", "09:58",
                        model = "google/gemini-2.5-flash"),
                    ChatItem(3, "assistant", "nudge", "📅 Standup — 10:15 (in 15 min)\nAnything to prepare?", "10:00"),
                    ChatItem(4, "assistant", "nudge", "Call the bank: open the app and find the account number?", "10:20", chips = chips,
                        model = "google/gemini-2.5-flash"),
                ),
                chat = ChatUi(error = "The assistant is unavailable right now. Try again in a minute.", canRetry = true),
                canDictate = true,
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {},
            draft = { "תזכיר לי מחר" },
        )
    }

    @Test
    fun home_empty() = shot("4b_home_empty") {
        HomeScreen(HomeState(setupDone = 4, setupTotal = 4, notice = "Later: next check-in Today 12:00"), {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    /** In Hebrew (the phone's language, or picked in ⚙ Settings): the app's words in Hebrew, laid out right-to-left. */
    @Test
    @Config(qualifiers = "+iw")
    fun handover_on_a_hebrew_phone() = shot("8c_handover_hebrew_phone") {
        HandoverScreen(HandoverState(alreadyHandedOver = false, message = "The server ran into a problem (HTTP 503). Try again in a minute."), {}, {})
    }

    /** In Hebrew: right-to-left, and an English message in the chat still reads left-to-right. */
    @Test
    @Config(qualifiers = "+iw")
    fun home_on_a_hebrew_phone() = shot("4c_home_hebrew_phone") {
        HomeScreen(
            HomeState(
                focus = listOf(FocusItem(1, "לשלם חשבון חשמל", "היום 15:00 · חשובה", done = false)),
                messages = listOf(
                    ChatItem(1, "user", "chat", "תזכיר לי לשלם את החשבון מחר בבוקר.", "07:41", day = "היום"),
                    ChatItem(2, "assistant", "chat", "Done. I'll remind you tomorrow at 08:00.", "07:41"),
                ),
                openCount = 5, setupDone = 3, setupTotal = 4, sessionsToday = 2,
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    /** Hebrew on a narrow screen with a large font (the phone's font size setting): the title stays on one line. */
    @Test
    @Config(qualifiers = "+iw-w360dp-h740dp", fontScale = 1.3f)
    fun home_hebrew_large_font() = shot("4g_home_hebrew_large_font") {
        HomeScreen(
            HomeState(
                messages = listOf(ChatItem(1, "user", "chat", "צריך להירשם לעבודה סמינריונית", "19:16", day = "היום")),
                openCount = 2, setupDone = 4, setupTotal = 6,
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
        SettingsScreen(settingItems(LocalResources.current, s), listOf(1L to "Shorter messages"), { _, _ -> null }, {}, {})
    }

    /** ⚙ Settings with Hebrew picked: the settings' names, groups and weekday in Hebrew. */
    @Test
    @Config(qualifiers = "+iw")
    fun settings_hebrew() = shot("12c_settings_hebrew") {
        val s = com.prominder.myapp.data.AppSettings(mapOf("morning_time" to "08:00"))
        SettingsScreen(settingItems(LocalResources.current, s), listOf(1L to "הודעות קצרות יותר"), { _, _ -> null }, {}, {}, language = "he")
    }

    /** The focus timer in Hebrew. */
    @Test
    @Config(qualifiers = "+iw")
    fun session_hebrew() = shot("12d_session_hebrew") {
        val start = 1_794_571_200_000
        SessionScreen(
            SessionUi("לכתוב את הדוח הרבעוני", "לפתוח את המסמך ולכתוב שלוש כותרות", "המבוא", 25, start, start + 25 * 60_000),
            now = start + 18 * 60_000 + 25_000, onDone = {}, onStop = {}, onAnother = {}, onLength = {}, onClose = {},
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
    fun diagnosticsTicks() = shot("6b_diagnostics_ticks") {
        DiagnosticsScreen(
            facts = listOf(
                "Last backup" to "2026-11-17 09:02:11", "Waiting for backup" to "0 changes",
                "Next reminder check" to "2026-11-17 13:00:00", "Last tick" to "2026-11-17 10:20:00",
            ),
            logLines = listOf(
                "2026-11-17 07:30:00 [tick] Tick: alarm (on time)",
                "2026-11-17 07:30:00 [tick]   Sent Morning plan (message #412)",
                "2026-11-17 10:00:01 [tick] Tick: alarm (1 s late)",
                "2026-11-17 10:00:01 [tick]   Sent message #413 (nudge, normal) about #57 \"Send the invoice\"",
                "2026-11-17 10:20:00 [tick] Tick: alarm (on time)",
                "2026-11-17 10:20:00 [tick]   Held #61 \"Call the bank\" until Tue 10:20: min gap, 0 of 20 min",
                "2026-11-17 10:21:40 [notify] ✓ Done from the notification: Send the invoice",
            ),
            onShare = {}, onClear = {}, onBack = {}, onSyncNow = {}, onTickNow = {},
        )
    }

    @Test
    fun alarm() = shot("7_alarm") {
        AlarmScreen(AlarmUi("Test alarm", "Rang on time. Tap Dismiss to stop it.", 1_794_571_200_000), ZoneId.of("Asia/Jerusalem"), onDismiss = {})
    }

    /** Phase E: a message's alarm, ringing for the third time: ✓ Done, ⏰ Later, Open, Dismiss. */
    @Test
    fun alarm_task() = shot("7b_alarm_task") {
        AlarmScreen(
            AlarmUi("🚨 Pay the electricity bill", "Pay the electricity bill — due today 18:00. Ignored twice: on it now?", 1_794_571_200_000,
                doneLabel = "✓ Done", laterLabel = "Later", canOpen = true, rings = 3),
            ZoneId.of("Asia/Jerusalem"), onDismiss = {},
        )
    }

    /** Phase E: the focus timer, 18 minutes into 25, with where you stopped last time. */
    @Test
    fun session() = shot("12_session") {
        val start = 1_794_571_200_000
        SessionScreen(
            SessionUi("Write the quarterly report", "Open the doc and write three headings", "the intro", 25, start, start + 25 * 60_000),
            now = start + 18 * 60_000 + 25_000, onDone = {}, onStop = {}, onAnother = {}, onLength = {}, onClose = {},
        )
    }

    /** Phase E: the time is up: the ring closes in green, ✓ Done · ▶ Another round · Stop. */
    @Test
    fun session_over() = shot("12b_session_over") {
        val start = 1_794_571_200_000
        SessionScreen(
            SessionUi("Write the quarterly report", "Open the doc and write three headings", null, 25, start, start + 25 * 60_000),
            now = start + 26 * 60_000, onDone = {}, onStop = {}, onAnother = {}, onLength = {}, onClose = {},
        )
    }
}
