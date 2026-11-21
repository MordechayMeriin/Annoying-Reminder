package com.prominder.myapp.ui

import android.content.res.Resources
import com.prominder.myapp.R
import com.prominder.myapp.data.SettingSpec
import com.prominder.myapp.data.SettingSpecs

/**
 * The settings' words on screen, in the app's language. SettingSpecs keeps English ones for the assistant; this maps
 * each setting's key to its string resources. A setting missing here shows its English label (a test checks there's none).
 */
object SettingTexts {
    /** key → (label, help or null). */
    internal val TEXTS: Map<String, Pair<Int, Int?>> = mapOf(
        "timezone" to (R.string.setting_timezone to null),
        "morning_time" to (R.string.setting_morning_time to null),
        "checkin_times" to (R.string.setting_checkin_times to R.string.setting_checkin_times_help),
        "evening_time" to (R.string.setting_evening_time to null),
        "day_reset_time" to (R.string.setting_day_reset_time to R.string.setting_day_reset_time_help),
        "quiet_start" to (R.string.setting_quiet_start to R.string.setting_quiet_start_help),
        "quiet_end" to (R.string.setting_quiet_end to null),
        "shabbat_mode" to (R.string.setting_shabbat_mode to R.string.setting_shabbat_mode_help),
        "latitude" to (R.string.setting_latitude to R.string.setting_latitude_help),
        "longitude" to (R.string.setting_longitude to R.string.setting_longitude_help),
        "altitude" to (R.string.setting_altitude to null),
        "diaspora" to (R.string.setting_diaspora to null),
        "candle_lighting_min" to (R.string.setting_candle_lighting_min to null),
        "havdalah_min" to (R.string.setting_havdalah_min to R.string.setting_havdalah_min_help),
        "shabbat_before_min" to (R.string.setting_shabbat_before_min to null),
        "shabbat_after_min" to (R.string.setting_shabbat_after_min to null),
        "meeting_lead_min" to (R.string.setting_meeting_lead_min to R.string.setting_meeting_lead_min_help),
        "week_start_day" to (R.string.setting_week_start_day to null),
        "week_meetings_time" to (R.string.setting_week_meetings_time to R.string.setting_week_meetings_time_help),
        "meetings_eve_time" to (R.string.setting_meetings_eve_time to R.string.setting_meetings_eve_time_help),
        "nag_interval_min" to (R.string.setting_nag_interval_min to R.string.setting_nag_interval_min_help),
        "max_nags" to (R.string.setting_max_nags to null),
        "min_gap_min" to (R.string.setting_min_gap_min to R.string.setting_min_gap_min_help),
        "max_proactive_per_day" to (R.string.setting_max_proactive_per_day to null),
        "max_per_task_per_day" to (R.string.setting_max_per_task_per_day to R.string.setting_max_per_task_per_day_help),
        "followup_min_min" to (R.string.setting_followup_min_min to null),
        "followup_max_days" to (R.string.setting_followup_max_days to null),
        "reply_link_hours" to (R.string.setting_reply_link_hours to null),
        "pushover_urgent_after" to (R.string.setting_urgent_after to R.string.setting_urgent_after_help),
        "pushover_alarm_for" to (R.string.setting_alarm_for to R.string.setting_alarm_for_help),
        "pushover_alarm_after" to (R.string.setting_alarm_after to R.string.setting_alarm_after_help),
        "pushover_alarm_retry_min" to (R.string.setting_alarm_retry_min to R.string.setting_alarm_retry_min_help),
        "show_model" to (R.string.setting_show_model to R.string.setting_show_model_help),
    )

    /** (key, stored value) → the option's label. */
    internal val OPTIONS: Map<Pair<String, String>, Int> = mapOf(
        ("pushover_alarm_for" to "critical") to R.string.setting_alarm_for_critical,
        ("pushover_alarm_for" to "important") to R.string.setting_alarm_for_important,
        ("pushover_alarm_for" to "all") to R.string.setting_alarm_for_all,
        ("pushover_alarm_for" to "off") to R.string.setting_alarm_for_off,
    )

    internal val GROUPS: Map<String, Int> = mapOf(
        SettingSpecs.DAY to R.string.settings_group_day,
        SettingSpecs.QUIET to R.string.settings_group_quiet,
        SettingSpecs.SHABBAT to R.string.settings_group_shabbat,
        SettingSpecs.MEET to R.string.settings_group_meetings,
        SettingSpecs.NAG to R.string.settings_group_nag,
        SettingSpecs.LOUD to R.string.settings_group_loud,
        SettingSpecs.APP to R.string.settings_group_app,
    )

    fun label(res: Resources, spec: SettingSpec): String = TEXTS[spec.key]?.first?.let(res::getString) ?: spec.label

    fun help(res: Resources, spec: SettingSpec): String = TEXTS[spec.key]?.second?.let(res::getString) ?: spec.help

    fun options(res: Resources, spec: SettingSpec): List<Pair<String, String>> =
        spec.options.map { (value, label) -> value to (OPTIONS[spec.key to value]?.let(res::getString) ?: label) }

    fun group(res: Resources, group: String): String = GROUPS[group]?.let(res::getString) ?: group
}
