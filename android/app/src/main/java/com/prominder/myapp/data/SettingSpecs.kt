package com.prominder.myapp.data

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * The settings you can change (⚙): their keys, defaults, checks and stored text (the same as the web app's, removed
 * in b09e82e, so a value restored from the backup means the same here). Pushover's account, sounds and browser push
 * aren't here (the phone has its own notification channels); its three loudness settings are ("How loud").
 */
data class SettingSpec(
    val key: String,
    /** time | times | time_off | int | float | bool | weekday | timezone | choice */
    val kind: String,
    val default: String,
    val label: String,
    val group: String,
    val help: String = "",
    val min: Double? = null,
    val max: Double? = null,
    /** choice: (stored value, label) */
    val options: List<Pair<String, String>> = emptyList(),
)

class SettingException(message: String) : IllegalArgumentException(message)

object SettingSpecs {
    private const val DAY = "Daily messages"
    private const val QUIET = "Quiet hours"
    private const val SHABBAT = "Shabbat & holidays"
    private const val MEET = "Meetings"
    private const val NAG = "Check-ins & limits"
    private const val LOUD = "How loud"
    private const val APP = "App"

    val ALL: List<SettingSpec> = listOf(
        SettingSpec("timezone", "timezone", "Asia/Jerusalem", "Time zone", DAY),
        SettingSpec("morning_time", "time", "07:30", "Morning plan", DAY),
        SettingSpec("checkin_times", "times", "13:00, 17:00", "Mid-day check-ins", DAY, "Comma-separated times. Empty = none."),
        SettingSpec("evening_time", "time", "21:30", "Evening review", DAY),
        SettingSpec("day_reset_time", "time", "04:00", "New chat day starts at", DAY,
            "The assistant only sees today's chat, from this time."),

        SettingSpec("quiet_start", "time", "23:00", "Quiet from", QUIET, "No messages at all in between."),
        SettingSpec("quiet_end", "time", "07:00", "Quiet until", QUIET),

        SettingSpec("shabbat_mode", "bool", "true", "Don't disturb on Shabbat & holidays", SHABBAT,
            "Nothing is sent from candle lighting to havdalah (plus the margins below). Shabbat and yom tov only."),
        SettingSpec("latitude", "float", "32.0956", "Latitude", SHABBAT, "Your location, for sunset times.", -90.0, 90.0),
        SettingSpec("longitude", "float", "34.9566", "Longitude", SHABBAT, "Defaults are Rosh HaAyin.", -180.0, 180.0),
        SettingSpec("altitude", "float", "60", "Altitude (m)", SHABBAT, min = -500.0, max = 9000.0),
        SettingSpec("diaspora", "bool", "false", "Outside Israel (two-day holidays)", SHABBAT),
        SettingSpec("candle_lighting_min", "int", "20", "Candle lighting (min before sunset)", SHABBAT, min = 0.0, max = 90.0),
        SettingSpec("havdalah_min", "int", "0", "Havdalah (min after sunset)", SHABBAT,
            "0 = nightfall (three stars). 72 = Rabbeinu Tam.", 0.0, 120.0),
        SettingSpec("shabbat_before_min", "int", "60", "Quiet starts before candle lighting (min)", SHABBAT, min = 0.0, max = 600.0),
        SettingSpec("shabbat_after_min", "int", "60", "Quiet continues after havdalah (min)", SHABBAT, min = 0.0, max = 600.0),

        SettingSpec("meeting_lead_min", "int", "15", "Reminder before a meeting (min)", MEET, "Plus one at the start time.", 0.0, 1440.0),
        SettingSpec("week_start_day", "weekday", "sun", "First day of the week", MEET),
        SettingSpec("week_meetings_time", "time_off", "08:00", "This week's meetings at", MEET,
            "On the first day of the week. Off = don't send."),
        SettingSpec("meetings_eve_time", "time_off", "20:00", "Tomorrow's meetings at", MEET, "Every evening. Off = don't send."),

        SettingSpec("nag_interval_min", "int", "30", "Follow-up interval (min)", NAG,
            "Default time between check-ins; faster for important tasks.", 5.0, 1440.0),
        SettingSpec("max_nags", "int", "4", "Stop after this many check-ins", NAG, min = 1.0, max = 50.0),
        SettingSpec("min_gap_min", "int", "20", "Minimum gap between messages (min)", NAG, "Meetings are exempt.", 0.0, 1440.0),
        SettingSpec("max_proactive_per_day", "int", "20", "Messages per day (max)", NAG, min = 1.0, max = 200.0),
        SettingSpec("max_per_task_per_day", "int", "4", "Check-ins per task per day (max)", NAG,
            "Reminders you asked for don't count.", 1.0, 50.0),
        SettingSpec("followup_min_min", "int", "10", "Earliest follow-up the assistant may pick (min)", NAG, min = 1.0, max = 1440.0),
        SettingSpec("followup_max_days", "int", "7", "Latest follow-up the assistant may pick (days)", NAG, min = 1.0, max = 60.0),
        SettingSpec("reply_link_hours", "float", "3", "A reply refers to the last check-in for (hours)", NAG, min = 0.0, max = 48.0),

        // The notification levels (the keys are from the Pushover days): normal → urgent → alarm.
        SettingSpec("pushover_urgent_after", "int", "1", "Urgent after this many ignored check-ins", LOUD,
            "Check-ins about a task you keep ignoring get louder. Only reminders you asked for and near deadlines.", 0.0, 20.0),
        SettingSpec("pushover_alarm_for", "choice", "important", "Alarm for", LOUD,
            "An alarm rings on the alarm volume, full screen, and repeats until you answer it: ✓ Done, Later, Open, or anything you write or tap in the app.",
            options = listOf("critical" to "Critical tasks only", "important" to "Important and critical tasks",
                "all" to "Any task", "off" to "Never (urgent at most)")),
        SettingSpec("pushover_alarm_after", "int", "2", "Alarm after this many ignored check-ins", LOUD,
            "If check-ins stop sooner, the last one is the alarm.", 0.0, 50.0),
        SettingSpec("pushover_alarm_retry_min", "int", "2", "Alarm repeats every (min)", LOUD,
            "It rings for up to a minute each time, until you answer it, for up to an hour, and never into quiet hours or Shabbat.",
            1.0, 30.0),

        SettingSpec("show_model", "bool", "false", "Show which AI model replied", APP,
            "A small grey line under the title, for the latest message only. Empty when code wrote it."),
    )

    val BY_KEY: Map<String, SettingSpec> = ALL.associateBy { it.key }

    private val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** config.Spec.canonical: checked, normalised text (what's stored). Throws [SettingException] with the reason. */
    fun canonical(spec: SettingSpec, raw: String): String {
        val text = raw.trim()
        return when (spec.kind) {
            "time" -> fmt(time(text))
            "times" -> text.split(",").filter { it.isNotBlank() }.map(::time).sorted().joinToString(", ") { fmt(it) }
            "time_off" -> if (text.lowercase() in setOf("off", "none", "no", "false", "0", "")) "off" else fmt(time(text))
            "weekday" -> text.lowercase().take(3).takeIf { it in WEEKDAYS }
                ?: throw SettingException("use sun, mon, tue, wed, thu, fri or sat")
            "bool" -> when (text.lowercase()) {
                "1", "true", "yes", "on" -> "true"
                "0", "false", "no", "off" -> "false"
                else -> throw SettingException("use true or false")
            }
            "choice" -> text.lowercase().takeIf { v -> spec.options.any { it.first == v } }
                ?: throw SettingException("choose one of: " + spec.options.joinToString(", ") { it.first })
            "timezone" -> runCatching { ZoneId.of(text).id }.getOrNull()?.takeIf { "/" in it || it == "UTC" }
                ?: throw SettingException("unknown time zone (e.g. Asia/Jerusalem)")
            "int" -> {
                val v = text.toLongOrNull() ?: throw SettingException("must be a whole number")
                range(spec, v.toDouble())
                v.toString()
            }
            "float" -> {
                val v = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw SettingException("must be a number")
                range(spec, v)
                general(v)
            }
            else -> throw SettingException("unknown kind ${spec.kind}")
        }
    }

    private fun range(spec: SettingSpec, v: Double) {
        spec.min?.let { if (v < it) throw SettingException("must be at least ${general(it)}") }
        spec.max?.let { if (v > it) throw SettingException("must be at most ${general(it)}") }
    }

    private fun time(text: String): LocalTime {
        val parts = text.trim().split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (parts.size != 2 || h == null || m == null || h !in 0..23 || m !in 0..59) throw SettingException("use HH:MM, e.g. 07:30")
        return LocalTime.of(h, m)
    }

    private fun fmt(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    /** A number as settings store it, shortest form: 32.0956 → "32.0956", 60.0 → "60". */
    fun general(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else java.math.BigDecimal(v).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()

    fun weekday(value: String): DayOfWeek = DayOfWeek.of(WEEKDAYS.indexOf(value.take(3).lowercase()).coerceAtLeast(0) + 1)
}

/** The current settings: your changes on top of the defaults. */
class AppSettings(stored: Map<String, String>) {
    /** Only valid stored values count (an invalid one is ignored: the default applies). */
    val overrides: Map<String, String> = stored.mapNotNull { (k, v) ->
        val spec = SettingSpecs.BY_KEY[k] ?: return@mapNotNull null
        runCatching { k to SettingSpecs.canonical(spec, v) }.getOrNull()
    }.toMap()

    fun raw(key: String): String = overrides[key] ?: SettingSpecs.canonical(SettingSpecs.BY_KEY.getValue(key), SettingSpecs.BY_KEY.getValue(key).default)

    // Read through the typed properties below: a key exists in one place, so a typo doesn't compile.
    private fun time(key: String): LocalTime = LocalTime.parse(raw(key))
    private fun int(key: String): Int = raw(key).toInt()

    val zone: ZoneId get() = ZoneId.of(raw("timezone"))
    val morningTime: LocalTime get() = time("morning_time")
    val dayResetTime: LocalTime get() = time("day_reset_time")
    val meetingLeadMin: Int get() = int("meeting_lead_min")
    val eveningTime: LocalTime get() = time("evening_time")
    val quietStart: LocalTime get() = time("quiet_start")
    val quietEnd: LocalTime get() = time("quiet_end")
    val checkinTimes: List<LocalTime> get() = raw("checkin_times").split(",").filter { it.isNotBlank() }.map { LocalTime.parse(it.trim()) }
    val weekStartDay: DayOfWeek get() = SettingSpecs.weekday(raw("week_start_day"))
    /** null = off */
    private fun timeOrOff(key: String): LocalTime? = raw(key).takeIf { it != "off" }?.let(LocalTime::parse)
    val weekMeetingsTime: LocalTime? get() = timeOrOff("week_meetings_time")
    val meetingsEveTime: LocalTime? get() = timeOrOff("meetings_eve_time")
    private fun bool(key: String): Boolean = raw(key) == "true"
    private fun double(key: String): Double = raw(key).toDouble()
    val nagIntervalMin: Int get() = int("nag_interval_min")
    val maxNags: Int get() = int("max_nags")
    val minGapMin: Int get() = int("min_gap_min")
    val maxProactivePerDay: Int get() = int("max_proactive_per_day")
    val maxPerTaskPerDay: Int get() = int("max_per_task_per_day")
    val followupMinMin: Int get() = int("followup_min_min")
    val followupMaxDays: Int get() = int("followup_max_days")
    /** How loud, by how many check-ins went unanswered (the keys are from the Pushover days). */
    val urgentAfter: Int get() = int("pushover_urgent_after")
    val alarmAfter: Int get() = int("pushover_alarm_after")
    val alarmFor: String get() = raw("pushover_alarm_for")
    /** An alarm rings again this often until it's answered. */
    val alarmRepeatMin: Int get() = int("pushover_alarm_retry_min")
    val shabbatMode: Boolean get() = bool("shabbat_mode")
    val diaspora: Boolean get() = bool("diaspora")
    val latitude: Double get() = double("latitude")
    val longitude: Double get() = double("longitude")
    val altitude: Double get() = double("altitude")
    val candleLightingMin: Int get() = int("candle_lighting_min")
    val havdalahMin: Int get() = int("havdalah_min")
    val shabbatBeforeMin: Int get() = int("shabbat_before_min")
    val shabbatAfterMin: Int get() = int("shabbat_after_min")
    val replyLinkHours: Double get() = double("reply_link_hours")
    val showModel: Boolean get() = bool("show_model")

    companion object {
        val DEFAULTS = AppSettings(emptyMap())
    }
}
