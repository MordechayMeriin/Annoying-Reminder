package com.annoyingreminder.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.annoyingreminder.app.setup.Checklist
import com.annoyingreminder.app.setup.DeviceFacts
import com.annoyingreminder.app.ui.theme.Palette
import com.annoyingreminder.app.ui.theme.ReminderTheme
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
    fun login_email() = shot("1_login_email") {
        LoginScreen(LoginState(email = "me@example.com"), {}, {}, {}, {}, {})
    }

    @Test
    fun login_code() = shot("2_login_code") {
        LoginScreen(LoginState(email = "me@example.com", codeSent = true, code = "1234", message = "Check your email for the code."), {}, {}, {}, {}, {})
    }

    @Test
    fun login_unconfigured() = shot("3_login_unconfigured") {
        LoginScreen(LoginState(configured = false), {}, {}, {}, {}, {})
    }

    @Test
    fun home() = shot("4_home") {
        HomeScreen(
            HomeState(
                email = "me@example.com", setupDone = 2, setupTotal = 4,
                pendingAlarm = "Test alarm set for 15:04:00",
                lastAlarmResult = "Set for 2026-11-13 14:00:00, rang on time",
            ),
            {}, {}, {}, {}, {}, {}, {}, {},
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
            onShare = {}, onClear = {}, onBack = {},
        )
    }

    @Test
    fun alarm() = shot("7_alarm") {
        AlarmScreen("Test alarm", "Rang on time. Tap Dismiss to stop it.", 1_794_571_200_000, ZoneId.of("Asia/Jerusalem"), {})
    }
}
