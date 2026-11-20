package com.prominder.myapp.logic

import com.kosherjava.zmanim.hebrewcalendar.JewishCalendar
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Shabbat and holidays as don't-disturb time (pure, no I/O). The Hebrew calendar (which days are Shabbat and yom
 * tov, in Israel or outside) comes from KosherJava Zmanim, and the sun times from the hdate library's formula (see
 * [sunMinutesUtc]): RestCalendarTest checks them against a table of hdate's answers.
 *
 * A rest period runs from candle lighting before the first day of Shabbat / yom tov until havdalah after the
 * last one, so a holiday next to Shabbat is one period. It's widened by a margin on each side. Only Shabbat and
 * yom tov count: not chol hamoed, fast days, Chanukah or Purim.
 */
data class RestConfig(
    val zone: ZoneId,
    val latitude: Double,
    val longitude: Double,
    /** Kept for the setting; hdate's formula ignores it. */
    val altitude: Double = 0.0,
    val diaspora: Boolean = false,
    /** candle lighting = sunset - this */
    val candleLightingMin: Int = 20,
    /** 0 = nightfall (three stars: the sun 8.5° below the horizon); otherwise sunset + this */
    val havdalahMin: Int = 0,
    /** quiet starts this long before candle lighting … */
    val beforeMin: Int = 60,
    /** … and ends this long after havdalah */
    val afterMin: Int = 60,
)

data class RestPeriod(
    /** including the margin before */
    val start: Instant,
    /** including the margin after */
    val end: Instant,
    /** "שבת", "יום הכפורים", "שבת · א' ראש השנה · ב' ראש השנה" … */
    val name: String,
)

object RestCalendar {
    private const val SHABBAT = "שבת"

    /** (is a rest day, names) for a civil date. Cached: the answer never changes. */
    private val days = ConcurrentHashMap<Pair<LocalDate, Boolean>, Pair<Boolean, List<String>>>()
    private val periods = ConcurrentHashMap<Pair<LocalDate, RestConfig>, RestPeriod>()

    fun day(d: LocalDate, diaspora: Boolean): Pair<Boolean, List<String>> = days.getOrPut(d to diaspora) {
        val jc = JewishCalendar(d).apply { inIsrael = !diaspora }
        val shabbat = d.dayOfWeek == DayOfWeek.SATURDAY
        val yomTov = jc.isYomTovAssurBemelacha
        val names = (if (shabbat) listOf(SHABBAT) else emptyList()) + (if (yomTov) holidayNames(jc, diaspora) else emptyList())
        (shabbat || yomTov) to names
    }

    /** hdate's Hebrew names, so the Diagnostics log reads as before. */
    private fun holidayNames(jc: JewishCalendar, diaspora: Boolean): List<String> {
        val day = jc.jewishDayOfMonth
        return when (jc.yomTovIndex) {
            JewishCalendar.ROSH_HASHANA -> listOf(if (day == 1) "א' ראש השנה" else "ב' ראש השנה")
            JewishCalendar.YOM_KIPPUR -> listOf("יום הכפורים")
            JewishCalendar.SUCCOS -> listOf(if (day == 15) "סוכות" else "שני של סוכות")
            JewishCalendar.SHEMINI_ATZERES -> if (diaspora) listOf("שמיני עצרת") else listOf("שמיני עצרת", "שמחת תורה")
            JewishCalendar.SIMCHAS_TORAH -> listOf("שמחת תורה")
            JewishCalendar.PESACH -> listOf(
                when (day) {
                    15 -> "פסח"
                    16 -> "שני של פסח"
                    21 -> "שביעי פסח"
                    else -> "אחרון של פסח"
                },
            )
            JewishCalendar.SHAVUOS -> listOf(if (day == 6) "שבועות" else "שני של שבועות")
            else -> listOf("יום טוב")
        }
    }

    /**
     * Minutes after 00:00 UTC on [d] when the sun is [deg] degrees from the zenith while setting (90.833 = sunset,
     * 98.5 = three stars), or null where it never gets there. hdate's own formula (NOAA's low-accuracy solar
     * equations, whole minutes cut off), so every time matches hdate's to the minute. KosherJava's
     * more precise astronomy differs by 1-3 minutes, which the 60-minute margins swallow, but the tests are the spec.
     */
    fun sunMinutesUtc(d: LocalDate, deg: Double, latitude: Double, longitude: Double): Int? {
        val dayOfYear = (d.dayOfYear - 1).toDouble()
        val gama = 2.0 * PI * ((dayOfYear - 1) / 365.0)
        val eqtime = 229.18 * (0.000075 + 0.001868 * cos(gama) - 0.032077 * sin(gama) - 0.014615 * cos(2.0 * gama) -
            0.040849 * sin(2.0 * gama))
        val decl = 0.006918 - 0.399912 * cos(gama) + 0.070257 * sin(gama) - 0.006758 * cos(2.0 * gama) +
            0.000907 * sin(2.0 * gama) - 0.002697 * cos(3.0 * gama) + 0.00148 * sin(3.0 * gama)
        val lat = Math.toRadians(latitude)
        val x = cos(Math.toRadians(deg)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (x < -1.0 || x > 1.0) return null
        val hourAngle = 720.0 * acos(x) / PI
        return (720.0 - 4.0 * longitude + hourAngle - eqtime).toInt()
    }

    private fun utcMinute(d: LocalDate, minutes: Int): Instant = d.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(minutes * 60L)

    private fun sunset(d: LocalDate, cfg: RestConfig): Instant? =
        sunMinutesUtc(d, 90.833, cfg.latitude, cfg.longitude)?.let { utcMinute(d, it) }

    private fun nightfall(d: LocalDate, cfg: RestConfig): Instant? =
        if (cfg.havdalahMin == 0) sunMinutesUtc(d, 98.5, cfg.latitude, cfg.longitude)?.let { utcMinute(d, it) }
        else sunset(d, cfg)?.plus(Duration.ofMinutes(cfg.havdalahMin.toLong()))

    /** The rest period whose first rest day is [first]. */
    private fun periodFrom(first: LocalDate, cfg: RestConfig): RestPeriod = periods.getOrPut(first to cfg) {
        var last = first
        val names = day(first, cfg.diaspora).second.toMutableList()
        while (day(last.plusDays(1), cfg.diaspora).first) {
            last = last.plusDays(1)
            names += day(last, cfg.diaspora).second.filter { it !in names }
        }
        // Fallbacks only matter at extreme latitudes, where the sun doesn't set.
        val candles = sunset(first.minusDays(1), cfg)?.minus(Duration.ofMinutes(cfg.candleLightingMin.toLong()))
            ?: first.atStartOfDay(cfg.zone).minusHours(6).toInstant()
        val havdalah = nightfall(last, cfg) ?: ZonedDateTime.of(last.atTime(20, 0), cfg.zone).toInstant()
        val sorted = (if (SHABBAT in names) listOf(SHABBAT) else emptyList()) + names.filter { it != SHABBAT }
        RestPeriod(candles.minus(Duration.ofMinutes(cfg.beforeMin.toLong())), havdalah.plus(Duration.ofMinutes(cfg.afterMin.toLong())),
            sorted.joinToString(" · "))
    }

    /** Rest periods whose first rest day is in [start, end]. */
    fun periodsBetween(start: LocalDate, end: LocalDate, cfg: RestConfig): List<RestPeriod> {
        val out = mutableListOf<RestPeriod>()
        var d = start
        while (d <= end) {
            if (day(d, cfg.diaspora).first && !day(d.minusDays(1), cfg.diaspora).first) out += periodFrom(d, cfg)
            d = d.plusDays(1)
        }
        return out
    }

    /** The rest period [now] falls in (margins included), or null. */
    fun currentPeriod(now: Instant, cfg: RestConfig): RestPeriod? {
        val today = now.atZone(cfg.zone).toLocalDate()
        return periodsBetween(today.minusDays(4), today.plusDays(1), cfg).firstOrNull { it.start <= now && now < it.end }
    }

    /** The current rest period, or the next one (within ~5 weeks; there's always a Shabbat). */
    fun nextPeriod(now: Instant, cfg: RestConfig): RestPeriod? {
        val today = now.atZone(cfg.zone).toLocalDate()
        return periodsBetween(today.minusDays(4), today.plusDays(35), cfg).firstOrNull { now < it.end }
    }

    /** When the current rest period ends (margin included), or null if it isn't one now / Shabbat mode is off. */
    fun restUntil(now: Instant, cfg: RestConfig?): Instant? = cfg?.let { currentPeriod(now, it)?.end }
}
