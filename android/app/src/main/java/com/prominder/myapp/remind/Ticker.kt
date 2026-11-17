package com.prominder.myapp.remind

import com.prominder.myapp.data.AppSettings
import com.prominder.myapp.data.DONE
import com.prominder.myapp.data.OPEN
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskLists
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.logic.Logic
import com.prominder.myapp.logic.Logic.ALARM
import com.prominder.myapp.logic.Logic.ContactState
import com.prominder.myapp.logic.Logic.NORMAL
import com.prominder.myapp.logic.Logic.URGENT
import com.prominder.myapp.logic.RestCalendar
import com.prominder.myapp.logic.RestConfig
import com.prominder.myapp.logic.avoidAsked
import com.prominder.myapp.logic.avoidReset
import com.prominder.myapp.logic.created
import com.prominder.myapp.logic.due
import com.prominder.myapp.logic.lastNotified
import com.prominder.myapp.logic.remind
import com.prominder.myapp.logic.blocked
import com.prominder.myapp.logic.parked
import com.prominder.myapp.logic.staleOffered
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A notification to show, decided by the tick. */
data class Notice(
    val messageId: Long,
    val title: String,
    val text: String,
    /** Logic.NORMAL | URGENT | ALARM */
    val level: Int,
    /** ALARM: how long it may ring (never into quiet hours or Shabbat). */
    val alarmSeconds: Long = 0,
    /** A check-in about exactly one task: ✓ Done and Later on the notification. */
    val taskId: Long? = null,
    /** The buttons' language (en | he), as the chips'. */
    val lang: String = "en",
)

/** What one tick did: the notifications to show, the log lines, and when the next one should run. */
data class TickReport(val notices: List<Notice>, val lines: List<String>, val nextWake: Instant)

/**
 * The tick, ported from backend/app/scheduler.py: everything the server's /tick did every 5 minutes, now run by
 * the phone's alarm exactly when something is due (see Alarms), with the same rules:
 *
 * 1. Due tasks → guardrails (Logic.planContacts) → postpone what may not be sent now → ONE combined message (as
 *    loud as its most pressing task) → next check-in times. Avoided tasks get one reason message instead;
 *    stale fences are skipped; a lapse stops task check-ins.
 * 2. Daily jobs: morning plan (Today's 3 is picked by code), mid-day check-ins, evening review, re-entry after a
 *    lapse, the week's and tomorrow's meetings. Nothing on Shabbat and holidays.
 * 3. Focus sessions: one nobody answered is recorded as paused (the end check-in itself comes with Phase E).
 *
 * The words are the server's templates (Templates); the LLM comes back in Phase D. Everything happens in one
 * transaction, so a tick is all or nothing, and the returned [TickReport.lines] explain every message and every
 * postponement for the Diagnostics log.
 */
class Ticker(private val store: Store) {
    private val dao = store.dao

    suspend fun tick(): TickReport = tick(dailyJobs = true)

    /** The task check-ins only (the Python tests' tick(), which ran the daily jobs separately). */
    internal suspend fun tickTasksOnly(): TickReport = tick(dailyJobs = false)

    private suspend fun tick(dailyJobs: Boolean): TickReport = LOCK.withLock {
        store.write {
            val run = Run(store.settings(), store.now())
            run.go(dailyJobs)
            TickReport(run.notices, run.lines, nextWake(run.s, store.now()))
        }
    }

    /** When the next tick should run, without running one (after every change). */
    suspend fun nextWake(): Instant = nextWake(store.settings(), store.now())

    private inner class Run(val s: AppSettings, val now: Instant) {
        val zone = s.zone
        val local: ZonedDateTime = now.atZone(zone)
        val notices = mutableListOf<Notice>()
        val lines = mutableListOf<String>()
        val rest: RestConfig? = restConfig(s)
        val g = guardrails(s, rest)

        fun log(line: String) {
            lines += line
        }

        suspend fun go(dailyJobs: Boolean) {
            closeFinishedMeetings()
            closeSilentSessions()
            contactTasks()
            if (dailyJobs) for (job in dueDailyJobs()) runDailyJob(job)
        }

        // ------------------------------------------------------------ 1. task check-ins

        suspend fun contactTasks() {
            val lapsed = isLapsed()
            val today = Logic.focusDay(local, s.dayResetTime)
            val due = dao.openTasks()
                .filter { t -> t.remind?.let { it <= now } == true && Logic.mayCheckIn(t, now, today, lapsed) }
                .sortedWith(compareBy({ it.remindAt }, { it.id })).take(20).toMutableList()
            for (fence in due.filter { it.kind == "fence" && Logic.fenceIsStale(it, now) }) {
                fenceFired(fence)
                due.remove(fence)
                log("Skipped hard stop #${fence.id} \"${fence.title}\": its time passed while nothing could be sent")
            }
            if (due.isEmpty()) return
            val state = contactState().copy(focusHoldUntil = Logic.focusHoldUntil(dao.latestSession()))
            val sendable = mutableListOf<TaskRow>()
            for (t in due) {
                val holds = Logic.contactReasons(t, state, local, g)
                if (holds.isEmpty()) {
                    sendable += t
                } else {
                    val until = holds.maxOf { it.until }
                    defer(t, until)
                    log("Held #${t.id} \"${t.title}\" until ${fmt(until)}: " + holds.joinToString("; ") { it.reason })
                }
            }
            val plan = Logic.planContacts(sendable, state, local, g)
            val (ids, avoided) = withoutAvoided(plan.send)
            if (ids.isEmpty()) return
            val byId = plan.send.associateBy { it.id }
            send(ids.map { byId.getValue(it) }, state, avoided)
        }

        /** Avoided tasks get no ordinary check-ins: the others, or ONE avoided task's reason message. */
        suspend fun withoutAvoided(send: List<TaskRow>): Pair<List<Long>, Boolean> {
            val normal = mutableListOf<Long>()
            val asks = mutableListOf<Long>()
            for (t in send) {
                when (Logic.avoidMove(t, if (t.kind == "task") eventCounts(t, 7) else null, now)) {
                    null -> normal += t.id
                    "ask" -> asks += t.id
                    "wait" -> {
                        val until = t.avoidAsked!!.plus(Duration.ofDays(Logic.AVOID_RETRY_DAYS))
                        defer(t, until)
                        log("Held #${t.id} \"${t.title}\" until ${fmt(until)}: avoided, the reason message went out")
                    }
                    else -> {
                        park(t)
                        log("Parked #${t.id} \"${t.title}\" for ${Logic.PARK_DAYS} days: two reason messages went unanswered")
                    }
                }
            }
            return if (normal.isNotEmpty()) normal to false else asks.take(1) to asks.isNotEmpty()
        }

        suspend fun send(group: List<TaskRow>, state: ContactState, avoided: Boolean) {
            val meetings = group.filter { it.kind == "meeting" && it.due != null }
            val fences = group.filter { it.kind == "fence" }
            val others = group.filter { it !in meetings && it !in fences }
            val firstTask = group.firstOrNull { it.kind == "task" }
            val lang = Logic.chipLang(lastUserText(), firstTask?.title)
            val body = if (avoided && firstTask != null) Logic.text("avoid_ask", lang, "title" to firstTask.title)
            else others.joinToString("\n") { Templates.templateLine(it, now, zone) }
            val head = (meetings.map { Templates.meetingLine(it, now, zone) } + fences.map { Templates.fenceLine(it, zone, lang) }).joinToString("\n")
            val text = Logic.clip(listOf(head, body.trim()).filter { it.isNotEmpty() }.joinToString("\n"), Templates.MAX_TEXT)
            val today = Logic.focusDay(local, s.dayResetTime)
            val chips = firstTask?.let {
                val stage = Logic.taskStage(it, eventCounts(it, 7), today)
                Logic.chipsJson(Logic.chipsFor(if (avoided) "avoided" else "checkin", stage), lang, it.id)
            }.orEmpty()
            val kind = Templates.messageKind(group)
            val msg = store.addMessage("assistant", text, kind, group.map { it.id }, Templates.encodeChips(chips))

            val lastReply = lastUserReplyAt()
            val (level, alarmS) = if (avoided) NORMAL to 0L else messageLevel(group, lastReply)
            notices += Notice(msg.id, Templates.pushTitle(group), text, level, alarmS,
                if (Templates.hasTaskButtons(group)) group[0].id else null, lang)
            log("Sent message #${msg.id} ($kind, ${LEVELS[level]}) about " + group.joinToString { "#${it.id} \"${it.title}\"" } +
                if (avoided) " (avoided: the reason message)" else "")
            for (t in group) afterContact(t, alarmed = level == ALARM, lastReply = lastReply, avoided = avoided)
        }

        /** (level, alarm seconds) for one message: as loud as its most pressing task. */
        fun messageLevel(group: List<TaskRow>, lastReply: Instant?): Pair<Int, Long> {
            val policy = alertPolicy(s)
            val level = group.maxOf { Logic.alertLevel(it, ignoredStreak(it, lastReply), policy, now) }
            if (level < ALARM) return level to 0L
            val period = rest?.let { RestCalendar.nextPeriod(now, it) }
            val window = Logic.alarmWindowS(local, s.quietStart, period?.start)
            if (window < ALARM_RETRY_MIN * 60) return URGENT to 0L // quiet hours or Shabbat start too soon for an alarm
            return ALARM to window
        }

        /** Check-ins about this task in a row with no reply since. */
        fun ignoredStreak(t: TaskRow, lastReply: Instant?): Int {
            val notified = t.lastNotified ?: return 0
            return if (lastReply != null && lastReply > notified) 0 else t.nagCount
        }

        suspend fun afterContact(task: TaskRow, alarmed: Boolean, lastReply: Instant?, avoided: Boolean) {
            val t = dao.task(task.id) ?: return
            if (t.kind == "fence") {
                fenceFired(t)
                return
            }
            val notified = t.lastNotified
            if (notified != null && !(lastReply != null && lastReply > notified)) store.addEvent(t.id, "ignored")
            if (avoided) {
                // One more look in 3 days: a last try, or (after that one) the park. Nothing in between.
                store.putTask(t.copy(avoidAsks = t.avoidAsks + 1, avoidAskedAt = Times.format(now),
                    remindAt = Times.format(now.plus(Duration.ofDays(Logic.AVOID_RETRY_DAYS))), remindRequested = false,
                    lastNotifiedAt = Times.format(now)))
                return
            }
            val nagCount = t.nagCount + 1
            val nextAt: Instant? = if (t.kind == "meeting" && t.due != null) {
                // Meetings: one reminder before, one at the start time, then stop.
                t.due!!.takeIf { now < it.minus(Duration.ofMinutes(2)) }
            } else {
                // Tasks that may escalate follow up within the day; every other task gets one check-in a day.
                val esc = Logic.escalates(t, now)
                Logic.nextReminderAfterNag(now, nagCount, if (esc) t.priority else 1, if (esc) s.nagIntervalMin else 24 * 60, s.maxNags)
            }
            store.putTask(t.copy(nagCount = nagCount, lastNotifiedAt = Times.format(now), remindAt = nextAt?.let(Times::format),
                remindRequested = t.kind == "meeting", escalated = t.escalated || alarmed))
        }

        // ------------------------------------------------------------ 2. daily jobs

        /** Which daily jobs run now (claimed, so each runs once a day). Nothing on Shabbat / holidays. */
        suspend fun dueDailyJobs(): List<String> {
            if (RestCalendar.restUntil(now, rest) != null) return emptyList()
            val today = local.toLocalDate()
            val jobs = mutableListOf<String>()
            if (Logic.jobIsDue(local, s.morningTime) && claim("morning", today.toString())) jobs += "morning"
            for (t in s.checkinTimes) {
                if (Logic.jobIsDue(local, t, 1.0) && claim("checkin@${t.format(HM)}", today.toString())) jobs += "checkin"
            }
            if (Logic.jobIsDue(local, s.eveningTime, 2.0) && claim("evening", today.toString())) jobs += "evening"
            val week = s.weekMeetingsTime
            if (week != null && local.dayOfWeek == s.weekStartDay && !Logic.weekMeetingsMerged(s.morningTime, week) &&
                Logic.jobIsDue(local, week) && claim("week_meetings", today.toString())) jobs += "week_meetings"
            val eve = s.meetingsEveTime
            if (eve != null && Logic.jobIsDue(local, eve, 2.0) && claim("meetings_eve", today.toString())) jobs += "meetings_eve"
            return jobs
        }

        suspend fun claim(job: String, date: String): Boolean {
            val key = "job.$job"
            if (dao.meta(key) == date) return false
            dao.setMeta(com.prominder.myapp.data.MetaRow(key, date))
            return true
        }

        suspend fun runDailyJob(job: String) {
            if (job == "week_meetings" || job == "meetings_eve") return meetingDigest(job)
            val first = job == "morning" && local.dayOfWeek == s.weekStartDay
            val merged = first && Logic.weekMeetingsMerged(s.morningTime, s.weekMeetingsTime)
            val away = isLapsed()
            if (job == "checkin" && activeRecently()) return log("Mid-day check-in skipped: you were active in the last hour")
            if (away && job != "morning") return log("${JOB_NAMES[job]} skipped: away for 48 hours")
            var daily: Daily? = when {
                away && reentryDue() -> reentry()
                away -> null.also { log("Morning plan skipped: away, and the re-entry message was already sent") }
                else -> composeDaily(job, first)
            }
            if (merged) {
                val digest = digestFor("week_meetings")
                if (digest != null) {
                    daily = daily?.copy(text = daily.text + "\n\n" + digest.first)
                        ?: Daily(digest.first, taskIds = digest.third, kind = "meeting")
                }
            }
            daily ?: return
            val kind = daily.kind ?: job
            val msg = store.addMessage("assistant", daily.text, kind, daily.taskIds, Templates.encodeChips(daily.chips))
            notices += Notice(msg.id, Templates.JOB_TITLES.getValue(kind), daily.text, NORMAL)
            log("Sent ${JOB_NAMES[kind] ?: kind} (message #${msg.id})")
        }

        suspend fun composeDaily(job: String, sunday: Boolean): Daily? {
            val today = Logic.focusDay(local, s.dayResetTime)
            val open = dao.openTasks()
            val endOfDay = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            val todayList = TaskLists.today(open, endOfDay)
            val lang = Logic.chipLang(lastUserText(), (todayList.ifEmpty { open }).take(5).joinToString(" ") { it.title })
            when (job) {
                "morning" -> {
                    val suggested = Logic.pickFocus(open, local)
                    val focus = if (suggested.isNotEmpty()) store.setFocusIn(suggested, today) else emptyList()
                    val ids = focus.map { it.id }
                    val meetings = Logic.meetingOccurrences(meetingsBetween(now, endOfDay), now, endOfDay, zone)
                    var text = Templates.morningTemplate(focus, meetings, zone)
                    var chips = if (ids.isNotEmpty()) Logic.chipsJson(Logic.chipsFor("morning", null), lang, ids[0]) else emptyList()
                    if (sunday) {
                        val wins = doneSince(now.minus(Duration.ofDays(Logic.WEEK_WINS_DAYS))).size
                        val stale = staleTasks().filter { it.id !in ids }
                        val extra = mutableListOf<String>()
                        if (wins > 0) extra += Logic.text(if (wins == 1) "wins_week_1" else "wins_week", lang, "n" to wins)
                        if (stale.isNotEmpty()) extra += Logic.text("stale", lang, "titles" to stale.joinToString(" · ") { it.title })
                        if (extra.isNotEmpty()) text += "\n\n" + extra.joinToString("\n")
                        if (stale.isNotEmpty()) {
                            chips = chips + Logic.chipsJson(Logic.chipsFor("sunday", null), lang, null, stale.map { it.id })
                            stale.forEach { store.putTask(it.copy(staleOfferedAt = Times.format(now))) }
                        }
                    }
                    return Daily(text, chips, ids)
                }
                else -> {
                    val (focus, focusDone) = focusToday(today)
                    val firstOpen = focus.firstOrNull { it.id !in focusDone && it.status == OPEN }
                    if (job == "checkin" && todayList.isEmpty() && firstOpen == null) return null.also { log("Mid-day check-in skipped: nothing open") }
                    val chips = Logic.chipsJson(Logic.chipsFor(Templates.JOB_CHIPS.getValue(job), null), lang, firstOpen?.id)
                    if (job == "checkin" && firstOpen != null) {
                        val step = firstOpen.nextAction?.takeIf { it.isNotEmpty() } ?: firstOpen.title
                        return Daily("Next from Today's 3: ▶ $step (${firstOpen.title})", chips)
                    }
                    if (job == "evening") {
                        val start = Logic.dayStart(local, s.dayResetTime).toInstant()
                        val sessions = dao.sessionsSince(Times.format(start))
                        val text = Templates.eveningTemplate(doneSince(start).size, sessions.size to sessions.sumOf { Logic.actualMinutes(it, now) }, todayList.size)
                        return Daily(text, chips)
                    }
                    if (todayList.isEmpty()) return Daily("Nothing open for today.", chips)
                    return Daily("${todayList.size} open item(s) for today. Open the app to see them.", chips)
                }
            }
        }

        suspend fun reentry(): Daily {
            val open = dao.openTasks().filter { it.kind == "task" && it.parentId == null }.let(TaskLists::all)
            val lang = Logic.chipLang(lastUserText(), open.take(5).joinToString(" ") { it.title })
            val overdue = open.any { t -> t.due?.let { it < now } == true }
            val chips = Logic.chipsJson(Logic.chipsFor("reentry", null), lang, null).filter { overdue || it.id != "move_overdue" }
            return Daily(Logic.text("reentry", lang), chips, kind = "reentry")
        }

        suspend fun meetingDigest(job: String) {
            val digest = digestFor(job) ?: return log("${JOB_NAMES[job]}: no meetings, nothing sent")
            val msg = store.addMessage("assistant", digest.first, "meeting", digest.third)
            notices += Notice(msg.id, digest.second, digest.first, NORMAL)
            log("Sent ${JOB_NAMES[job]} (message #${msg.id})")
        }

        suspend fun digestFor(job: String): Triple<String, String, List<Long>>? {
            val midnight = local.toLocalDate().atStartOfDay(zone)
            val (start, end) = if (job == "week_meetings") now to midnight.plusDays(7).toInstant()
            else midnight.plusDays(1).toInstant() to midnight.plusDays(2).toInstant()
            return Templates.meetingDigest(job, Logic.meetingOccurrences(meetingsBetween(start, end), start, end, zone), local)
        }

        // ------------------------------------------------------------ reading the data

        suspend fun isLapsed(): Boolean {
            val last = lastUserReplyAt() ?: return false
            val rests = rest?.let { cfg ->
                RestCalendar.periodsBetween(last.atZone(zone).toLocalDate().minusDays(4), local.toLocalDate().plusDays(1), cfg)
                    .map { it.start to it.end }
            }.orEmpty()
            return Logic.lapsed(last, now, rests)
        }

        suspend fun reentryDue(): Boolean =
            Logic.reentryDue(lastUserReplyAt(), Times.parseOrNull(dao.latestOfKinds(listOf("reentry"))?.createdAt), now)

        suspend fun activeRecently(): Boolean {
            val times = listOfNotNull(lastUserReplyAt(), Logic.sessionLastActive(dao.latestSession(), now))
            return Logic.userActiveRecently(times.maxOrNull(), now)
        }

        /** thread.contact_state: what was sent since the day reset. */
        suspend fun contactState(): ContactState {
            val start = Logic.dayStart(local, s.dayResetTime).toInstant()
            var lastAt: Instant? = null
            var sent = 0
            val byTask = mutableMapOf<Long, Int>()
            for (m in dao.messagesSince(Times.format(start))) {
                if (m.kind !in PROACTIVE_KINDS) continue
                lastAt = lastAt ?: Times.parse(m.createdAt)
                sent++
                if (m.kind == "nudge") m.taskIds.forEach { byTask[it] = (byTask[it] ?: 0) + 1 }
            }
            if (lastAt == null) lastAt = Times.parseOrNull(dao.latestOfKinds(PROACTIVE_KINDS)?.createdAt)
            return ContactState(lastAt, sent, byTask)
        }

        suspend fun lastUserReplyAt(): Instant? = Times.parseOrNull(dao.latestOfKindsBy(ACTIVITY_KINDS, "user")?.createdAt)

        suspend fun lastUserText(): String? = dao.latestOfKindsBy(listOf("chat"), "user")?.content

        /** Events per kind in the last [days], counted from the task's last fresh start. */
        suspend fun eventCounts(t: TaskRow, days: Long): Map<String, Int> {
            val cutoff = now.minus(Duration.ofDays(days))
            val reset = t.avoidReset
            val start = if (reset != null && reset > cutoff) reset else cutoff
            val startText = Times.format(start)
            return dao.eventsSince(t.id, startText).filter { it.createdAt > startText }.groupingBy { it.kind }.eachCount()
        }

        suspend fun meetingsBetween(start: Instant, end: Instant): List<TaskRow> =
            dao.openTasks().filter { t ->
                val due = t.due
                t.kind == "meeting" && due != null && due < end && (due >= start || t.recurrence != null)
            }.sortedWith(compareBy({ it.dueAt }, { it.id }))

        /** tasks.done_since: what was finished since [start] (steps and recurring tasks count), oldest first. */
        suspend fun doneSince(start: Instant): List<Long> {
            val ids = dao.eventsOfKindSince("done", Times.format(start)).map { it.taskId }.distinct()
            return dao.tasks(ids).filter { it.status != "cancelled" }.map { it.id }
        }

        suspend fun focusToday(today: java.time.LocalDate): Pair<List<TaskRow>, Set<Long>> {
            val rows = TaskLists.focus(dao.tasks(store.focusIds(today)), today.toString())
            val since = Times.format(today.atStartOfDay(zone).toInstant())
            val doneEvents = rows.flatMap { t -> dao.eventsSince(t.id, since).filter { it.kind == "done" } }
            return rows to TaskLists.doneToday(rows, doneEvents)
        }

        /** Sunday cleanup candidates: open 21+ days, nothing moving, not offered in 4 weeks (tasks.stale_tasks). */
        suspend fun staleTasks(): List<TaskRow> {
            val old = now.minus(Duration.ofDays(Logic.STALE_DAYS))
            val cooldown = now.minus(Duration.ofDays(Logic.STALE_COOLDOWN_DAYS))
            val candidates = dao.openTasks().filter { t ->
                t.kind == "task" && t.parentId == null && t.recurrence == null && (t.created ?: now) < old &&
                    (t.due?.let { it < now } ?: true) && (t.blocked?.let { it <= now } ?: true) &&
                    (t.parked?.let { it <= now } ?: true) && (t.staleOffered?.let { it < cooldown } ?: true)
            }.sortedWith(compareBy({ it.createdAt }, { it.id }))
            val oldText = Times.format(old)
            return candidates.filter { t -> dao.eventsSince(t.id, oldText).none { it.kind in PROGRESS_KINDS && it.createdAt > oldText } }
                .take(Logic.STALE_MAX)
        }

        // ------------------------------------------------------------ writing

        /** Postpone because of a guardrail (only if it's still due). */
        suspend fun defer(t: TaskRow, until: Instant) {
            val cur = dao.task(t.id) ?: return
            if (cur.status == OPEN && cur.remind?.let { it <= now } == true) store.putTask(cur.copy(remindAt = Times.format(until)))
        }

        /** A hard stop went out (or its time passed): a recurring one moves on, a one-off closes. */
        suspend fun fenceFired(t: TaskRow) {
            val cur = dao.task(t.id) ?: return
            if (cur.status != OPEN || cur.kind != "fence") return
            val due = cur.due
            if (cur.recurrence != null && due != null) {
                val next = Times.format(com.prominder.myapp.data.DayMath.nextFutureOccurrence(due, cur.recurrence, zone, now))
                store.putTask(cur.copy(dueAt = next, remindAt = next, remindRequested = true, nagCount = 0, lastNotifiedAt = Times.format(now)))
            } else {
                store.putTask(cur.copy(status = DONE, completedAt = Times.format(now), remindAt = null, lastNotifiedAt = Times.format(now)))
            }
        }

        /** Parked silently after two unanswered reason messages (actions.park with silent=True). */
        suspend fun park(t: TaskRow) {
            store.closeSessions(t.id, "stopped")
            val until = now.plus(Duration.ofDays(Logic.PARK_DAYS))
            store.putTask(t.copy(parkedUntil = Times.format(until), remindAt = Times.format(until), remindRequested = false, nagCount = 0,
                escalated = false, focusDate = null, focusRank = null, avoidResetAt = Times.format(now), avoidAsks = 0, avoidAskedAt = null))
            store.addEvent(t.id, "parked", "until ${until.atZone(zone).toLocalDate()}")
        }

        /** Meetings that ended long ago don't need to stay open. */
        suspend fun closeFinishedMeetings() {
            for (t in dao.openTasks()) {
                val due = t.due ?: continue
                if (t.kind == "meeting" && t.recurrence == null && due < now.minus(Duration.ofHours(2))) {
                    store.putTask(t.copy(status = DONE, completedAt = Times.format(now), remindAt = null))
                }
            }
        }

        /** A session nobody answered is recorded as paused at its planned end (scheduler.plan_sessions). */
        suspend fun closeSilentSessions() {
            for (sess in dao.allOpenSessions()) {
                if (Logic.sessionSilent(sess, now)) {
                    store.putSession(sess.copy(endedAt = Times.format(Logic.sessionEndAt(sess)), outcome = "paused"))
                    store.addEvent(sess.taskId, "session_ended", "paused")
                    log("Focus session #${sess.id} on task #${sess.taskId}: no answer, recorded as paused")
                }
            }
        }

        fun fmt(i: Instant): String = i.atZone(zone).format(LOG_TIME)
    }

    /**
     * The next moment something could happen: a task's reminder, a daily job, the end of Shabbat (a job whose
     * window reaches past it runs then), a session's end. At least a minute away; at most [MAX_SLEEP].
     */
    private suspend fun nextWake(s: AppSettings, now: Instant): Instant {
        val zone = s.zone
        val local = now.atZone(zone)
        val candidates = mutableListOf<Instant>()
        dao.openTasks().mapNotNullTo(candidates) { it.remind }
        val times = listOfNotNull(s.morningTime, s.eveningTime, s.weekMeetingsTime, s.meetingsEveTime) + s.checkinTimes
        times.mapTo(candidates) { Logic.nextTimeAt(local, it).toInstant() }
        // On Shabbat everything waits for its end: wake up then (a job whose window reaches past it runs right away).
        val restEnd = RestCalendar.restUntil(now, restConfig(s))
        restEnd?.let { candidates += it }
        for (sess in dao.allOpenSessions()) {
            candidates += Logic.sessionEndAt(sess)
            candidates += (sess.endSent ?: Logic.sessionEndAt(sess)).plus(Duration.ofMinutes(Logic.SESSION_SILENCE_MIN))
        }
        val soonest = maxOf(now.plus(Duration.ofMinutes(1)), restEnd ?: now)
        val latest = maxOf(now.plus(MAX_SLEEP), soonest)
        return candidates.minOrNull()?.coerceIn(soonest, latest) ?: latest
    }

    private val com.prominder.myapp.data.SessionRow.endSent: Instant? get() = Times.parseOrNull(endSentAt)

    data class Daily(
        val text: String,
        val chips: List<Logic.ChipJson> = emptyList(),
        val taskIds: List<Long> = emptyList(),
        val kind: String? = null,
    )

    companion object {
        /** One tick at a time (the alarm and the safety-net job can fire together). */
        private val LOCK = Mutex()

        /** The alarm sleeps at most this long; the 15-minute safety net runs anyway. */
        val MAX_SLEEP: Duration = Duration.ofHours(6)

        /** An alarm that couldn't ring even this long (quiet hours or Shabbat about to start) is urgent instead. */
        const val ALARM_RETRY_MIN = 2

        /** thread.PROACTIVE_KINDS: messages the assistant sent on its own (they count toward the daily cap). */
        val PROACTIVE_KINDS = listOf("nudge", "reminder", "meeting", "morning", "checkin", "evening", "session", "reentry")

        /** thread.ACTIVITY_KINDS: typed messages and taps. */
        val ACTIVITY_KINDS = listOf("chat", "event")

        /** tasks.PROGRESS_KINDS */
        val PROGRESS_KINDS = setOf("progress", "done", "committed", "session_started", "session_ended")

        val LEVELS = mapOf(NORMAL to "normal", URGENT to "urgent", ALARM to "alarm")
        val JOB_NAMES = mapOf(
            "morning" to "Morning plan", "checkin" to "Mid-day check-in", "evening" to "Evening review", "reentry" to "Re-entry message",
            "meeting" to "This week's meetings (with the morning plan)", "week_meetings" to "This week's meetings",
            "meetings_eve" to "Tomorrow's meetings",
        )
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        private val LOG_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

        fun restConfig(s: AppSettings): RestConfig? = if (!s.bool("shabbat_mode")) null else RestConfig(
            zone = s.zone, latitude = s.double("latitude"), longitude = s.double("longitude"), altitude = s.double("altitude"),
            diaspora = s.bool("diaspora"), candleLightingMin = s.int("candle_lighting_min"), havdalahMin = s.int("havdalah_min"),
            beforeMin = s.int("shabbat_before_min"), afterMin = s.int("shabbat_after_min"),
        )

        fun guardrails(s: AppSettings, rest: RestConfig? = restConfig(s)) = Logic.Guardrails(
            quietStart = s.quietStart, quietEnd = s.quietEnd, dayReset = s.dayResetTime, minGapMin = s.int("min_gap_min"),
            maxPerDay = s.int("max_proactive_per_day"), maxPerTaskPerDay = s.int("max_per_task_per_day"),
            followupMinMin = s.int("followup_min_min"), followupMaxDays = s.int("followup_max_days"),
            restUntil = rest?.let { cfg -> { now: ZonedDateTime -> RestCalendar.restUntil(now.toInstant(), cfg) } },
        )

        fun alertPolicy(s: AppSettings) = Logic.AlertPolicy(
            urgentAfter = s.int("pushover_urgent_after"), alarmFor = s.raw("pushover_alarm_for"),
            alarmAfter = s.int("pushover_alarm_after"), maxNags = s.maxNags,
        )
    }
}
