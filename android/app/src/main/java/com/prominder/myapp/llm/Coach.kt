package com.prominder.myapp.llm

import com.prominder.myapp.data.CANCELLED
import com.prominder.myapp.data.TaskKind
import com.prominder.myapp.data.AppSettings
import com.prominder.myapp.data.OPEN
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskLists
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.logic.ChipRules
import com.prominder.myapp.logic.Coaching
import com.prominder.myapp.logic.Focus
import com.prominder.myapp.logic.FocusSessions
import com.prominder.myapp.logic.Pressure
import com.prominder.myapp.logic.Schedule
import com.prominder.myapp.logic.Texts
import com.prominder.myapp.remind.Templates
import com.prominder.myapp.remind.Ticker
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** What the coach needs to read from the tick (Ticker.Run implements it inside its transaction or after it). */
interface TickReads {
    /** The open tasks, read once per step of the tick rather than by every message. */
    suspend fun openTasks(): List<TaskRow>
    suspend fun lastUserText(): String?
    suspend fun eventCounts(t: TaskRow, days: Long): Map<String, Int>
    suspend fun meetingsBetween(start: Instant, end: Instant): List<TaskRow>
    /** Ids of what was finished since [start] (steps and recurring tasks too). */
    suspend fun doneSince(start: Instant): List<Long>
    suspend fun focusToday(today: LocalDate): Pair<List<TaskRow>, Set<Long>>
    suspend fun staleTasks(): List<TaskRow>
}

/** A task check-in, written. [followups]: next check-in times the model chose (clamped by code). */
data class CheckinText(
    val text: String,
    val kind: String,
    val title: String,
    val chips: List<ChipRules.ChipJson>,
    val lang: String,
    val followups: Map<Long, Instant> = emptyMap(),
    /** The model that wrote it; null for a template. */
    val model: String? = null,
)

/**
 * Writes the proactive messages: task check-ins (grouped), the morning plan, mid-day check-ins, the evening review and
 * the re-entry message.
 *
 * Code decides WHEN (the tick), WHICH playbook and WHICH reply buttons. The words come from the LLM when it's
 * available and from the fixed templates (Templates, Phase C) when it isn't, or when [llm] is null: a reminder is never
 * lost because of the LLM. Meeting and hard-stop lines are always written by code; the LLM only adds text around them.
 */
class Coach(private val store: Store, private val llm: Llm?, private val prompts: Prompts?) {
    private val dao = store.dao

    // ------------------------------------------------------------ task check-ins

    /**
     * One message for everything due in this tick (meetings and fences first, as the planner ordered them). [avoided]:
     * the group is one avoided task and this is its reason message (one kind line, Too big · Unclear · Boring · Not now).
     * A message with only hard stops is written by code alone.
     */
    suspend fun checkin(
        group: List<TaskRow>, sentToday: Map<Long, Int>, avoided: Boolean, now: Instant, reads: TickReads, timeoutMs: Long,
    ): CheckinText {
        val s = store.settings()
        val zone = s.zone
        val local = now.atZone(zone)
        val today = Schedule.focusDay(local, s.dayResetTime)
        val meetings = group.filter { it.kind == TaskKind.MEETING && Times.parseOrNull(it.dueAt) != null }
        val fences = group.filter { it.kind == TaskKind.FENCE }
        val others = group.filter { it !in meetings && it !in fences }
        val firstTask = group.firstOrNull { it.kind == TaskKind.TASK }
        val lang = ChipRules.chipLang(reads.lastUserText(), firstTask?.title)
        val stages = group.filter { it.kind == TaskKind.TASK }.associate { it.id to Coaching.taskStage(it, reads.eventCounts(it, 7), today) }
        val chips = firstTask?.let {
            ChipRules.chipsJson(ChipRules.chipsFor(if (avoided) "avoided" else "checkin", stages[it.id]), lang, it.id)
        }.orEmpty()

        var body = ""
        var followups = emptyMap<Long, Instant>()
        var model: String? = null
        if ((meetings.isNotEmpty() || others.isNotEmpty()) && canWrite(timeoutMs)) {
            val models = mutableListOf<String>()
            val ctx = ToolContext("coach", now)
            val written = attempt("Check-in", timeoutMs) {
                val messages = checkinPrompt(group, sentToday, avoided, chips, stages, lang, local, today, s, reads)
                ToolLoop(llm!!, Tools(store)).run(messages, if (firstTask != null) ToolDefs.COACH_TOOLS else emptyList(), ctx, maxRounds = 3,
                    timeoutS = timeoutMs / 1000.0, models = models).text
            }
            if (!written.isNullOrBlank()) {
                body = written
                followups = if (avoided) emptyMap() else ctx.followups.toMap() // an avoided task's next look is code's (3 days)
                model = models.lastOrNull()
            }
        }
        if (model == null) {
            body = if (avoided && firstTask != null) Texts.text("avoid_ask", lang, "title" to firstTask.title)
            else others.joinToString("\n") { Templates.templateLine(it, now, zone) }
        }
        val head = (meetings.map { Templates.meetingLine(it, now, zone) } + fences.map { Templates.fenceLine(it, zone, lang) }).joinToString("\n")
        val text = Texts.clip(listOf(head, body.trim()).filter { it.isNotEmpty() }.joinToString("\n"), Templates.MAX_TEXT)
        return CheckinText(text, Templates.messageKind(group), Templates.pushTitle(group), chips, lang, followups, model)
    }

    private suspend fun checkinPrompt(
        group: List<TaskRow>, sentToday: Map<Long, Int>, avoided: Boolean, chips: List<ChipRules.ChipJson>, stages: Map<Long, String>,
        lang: String, local: ZonedDateTime, today: LocalDate, s: AppSettings, reads: TickReads,
    ): MutableList<kotlinx.serialization.json.JsonObject> {
        val now = local.toInstant()
        val zone = local.zone
        val start = Schedule.dayStart(local, s.dayResetTime).toInstant()
        val ids = group.map { it.id }
        val facts = LlmContext.loadFacts(dao, group, now)
        val playbooks = if (avoided) listOf("avoiding")
        else Coaching.selectPlaybooks(null, stages.values, if (facts.stuckSince(ids, start)) setOf("stuck") else emptySet())
        val blocks = group.zip(LlmContext.taskContexts(group, local, facts, sentToday, today)).map { (t, block) ->
            when {
                t.kind == TaskKind.MEETING && t.dueAt != null -> block + "\n  (Shown above your text by the system: ${Templates.meetingLine(t, now, zone)})"
                t.kind == TaskKind.FENCE -> block + "\n  (A hard stop. Shown above your text by the system: ${Templates.fenceLine(t, zone, lang)})"
                t.remindRequested -> block + "\n  (The user asked to be reminded now.)"
                else -> block
            }
        }
        val todayMsgs = LlmContext.since(dao, start, 8)
        val endOfDay = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val openToday = TaskLists.today(reads.openTasks(), endOfDay)
        val user = listOf(
            "Due for a check-in now:\n" + blocks.joinToString("\n"),
            "Earlier messages about these tasks:\n" + LlmContext.linkedHistory(dao, ids, local),
            "Today's conversation so far (latest last):\n" + LlmContext.formatMessages(todayMsgs, local).ifEmpty { "(nothing yet)" },
            "Other open items today:\n" + LlmContext.formatOpenItems(openToday, local, ids.toSet()),
            if (avoided) AVOID_ASK else "Write the check-in message now.",
        ).joinToString("\n\n")
        val system = prompts!!.render("checkin", playbooks, chips.map { it.label }, preferences(), timeValues(local))
        return mutableListOf(message("system", system), message("user", user))
    }

    // ------------------------------------------------------------ daily messages

    /**
     * The morning plan, mid-day check-in or evening review; null when there's nothing worth saying (an empty mid-day
     * check-in). [sunday]: the first morning of the week also carries last week's wins and the cleanup.
     */
    suspend fun daily(job: String, sunday: Boolean, now: Instant, reads: TickReads, timeoutMs: Long): Ticker.Daily? {
        val s = store.settings()
        val zone = s.zone
        val local = now.atZone(zone)
        val today = Schedule.focusDay(local, s.dayResetTime)
        val open = reads.openTasks()
        val endOfDay = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val todayList = TaskLists.today(open, endOfDay)
        val lang = ChipRules.chipLang(reads.lastUserText(), todayList.ifEmpty { open }.take(5).joinToString(" ") { it.title })
        if (job == "morning") return morning(sunday, now, local, today, open, endOfDay, lang, reads, timeoutMs)

        val (focus, focusDone) = reads.focusToday(today)
        val firstOpen = focus.firstOrNull { it.id !in focusDone && it.status == OPEN }
        if (job == "checkin" && todayList.isEmpty() && firstOpen == null) return null
        val chips = ChipRules.chipsJson(ChipRules.chipsFor(Templates.JOB_CHIPS.getValue(job), null), lang, firstOpen?.id)
        val start = Schedule.dayStart(local, s.dayResetTime).toInstant()
        val sessions = dao.sessionsSince(Times.format(start))
        val sessionStats = sessions.size to sessions.sumOf { FocusSessions.actualMinutes(it, now) }
        val doneIds = reads.doneSince(start)

        if (canWrite(timeoutMs)) {
            val models = mutableListOf<String>()
            val written = attempt(JOB_NAMES.getValue(job), timeoutMs) {
                val snapshot = snapshot(job, local, today, open, todayList, doneIds, sessionStats, focus to focusDone, emptyList(), emptyList())
                val messages = listOf(
                    message("system", prompts!!.render(JOB_PROMPTS.getValue(job), chips = chips.map { it.label }, preferences = preferences(),
                        values = timeValues(local))),
                    message("user", snapshot + "\n\nToday's conversation so far:\n" + todayConversation(local, s, 12)),
                )
                llm!!.complete(messages, emptyList(), 0.5, timeoutMs / 1000.0).also { r -> r.model?.let { models += it } }.content.trim()
            }
            if (!written.isNullOrEmpty()) return Ticker.Daily(Texts.clip(written, Templates.MAX_TEXT), chips, model = models.lastOrNull())
        }
        if (job == "checkin" && firstOpen != null) {
            val step = firstOpen.nextAction?.takeIf { it.isNotEmpty() } ?: firstOpen.title
            return Ticker.Daily("Next from Today's 3: ▶ $step (${firstOpen.title})", chips)
        }
        if (job == "evening") return Ticker.Daily(Templates.eveningTemplate(doneIds.size, sessionStats, todayList.size), chips)
        if (todayList.isEmpty()) return Ticker.Daily("Nothing open for today.", chips)
        return Ticker.Daily("${todayList.size} open item(s) for today. Open the app to see them.", chips)
    }

    /**
     * The morning plan proposes Today's 3 and saves it (silence accepts it). The LLM picks with set_focus; if it
     * doesn't (or can't), the code's pick stands ([Ticker.Daily.setFocus], saved when the message goes out).
     */
    private suspend fun morning(
        sunday: Boolean, now: Instant, local: ZonedDateTime, today: LocalDate, open: List<TaskRow>, endOfDay: Instant, lang: String,
        reads: TickReads, timeoutMs: Long,
    ): Ticker.Daily {
        val s = store.settings()
        val zone = local.zone
        val suggestedIds = Focus.pickFocus(open, local)
        val byId = open.associateBy { it.id }
        val suggested = suggestedIds.mapNotNull { byId[it] }
        val meetings = Schedule.meetingOccurrences(reads.meetingsBetween(now, endOfDay), now, endOfDay, zone)
        val stale = if (sunday) reads.staleTasks() else emptyList()

        var text = ""
        var model: String? = null
        var picked: List<Long>? = null
        if (suggestedIds.isNotEmpty() && canWrite(timeoutMs)) {
            val models = mutableListOf<String>()
            val ctx = ToolContext("morning", now)
            // The chips name "the first one": as suggested, until the LLM changes the pick.
            val preview = ChipRules.chipsJson(ChipRules.chipsFor("morning", null), lang, suggestedIds[0])
            val written = attempt("Morning plan", timeoutMs * 2) {
                val start = Schedule.dayStart(local, s.dayResetTime).toInstant()
                val doneIds = reads.doneSince(start)
                val sessions = dao.sessionsSince(Times.format(start))
                val snapshot = snapshot("morning", local, today, open, TaskLists.today(open, endOfDay), doneIds,
                    sessions.size to sessions.sumOf { FocusSessions.actualMinutes(it, now) }, emptyList<TaskRow>() to emptySet(), suggested, meetings)
                val messages = mutableListOf(
                    message("system", prompts!!.render("daily_morning", Coaching.selectPlaybooks("morning", signals = if (sunday) setOf("sunday") else emptySet()),
                        preview.map { it.label }, preferences(), timeValues(local))),
                    message("user", snapshot + "\n\nToday's conversation so far:\n" + todayConversation(local, s, 12)),
                )
                ToolLoop(llm!!, Tools(store)).run(messages, ToolDefs.MORNING_TOOLS, ctx, maxRounds = 3, temperature = 0.5,
                    timeoutS = timeoutMs / 1000.0, models = models).text
            }
            picked = ctx.focus
            if (!written.isNullOrBlank()) {
                text = Texts.clip(written.trim(), Templates.MAX_TEXT)
                model = models.lastOrNull()
            }
        }
        val focus: List<TaskRow> = picked?.let { ids -> dao.tasks(ids).associateBy { it.id }.let { m -> ids.mapNotNull { m[it] } } } ?: suggested
        val ids = focus.map { it.id }
        if (model == null) text = Templates.morningTemplate(focus, meetings, zone)
        var chips = if (ids.isNotEmpty()) ChipRules.chipsJson(ChipRules.chipsFor("morning", null), lang, ids[0]) else emptyList()
        var staleIds = emptyList<Long>()
        if (sunday) {
            val wins = reads.doneSince(now.minus(Duration.ofDays(Pressure.WEEK_WINS_DAYS))).size
            val offered = stale.filter { it.id !in ids } // not "do it today" and "drop it?" at once
            val extra = mutableListOf<String>()
            if (wins > 0) extra += Texts.text(if (wins == 1) "wins_week_1" else "wins_week", lang, "n" to wins)
            if (offered.isNotEmpty()) extra += Texts.text("stale", lang, "titles" to offered.joinToString(" · ") { it.title })
            if (extra.isNotEmpty()) text += "\n\n" + extra.joinToString("\n")
            if (offered.isNotEmpty()) {
                chips = chips + ChipRules.chipsJson(ChipRules.chipsFor("sunday", null), lang, null, offered.map { it.id })
                staleIds = offered.map { it.id }
            }
        }
        return Ticker.Daily(text, chips, ids, model = model, setFocus = if (picked == null && ids.isNotEmpty()) ids else null, staleIds = staleIds)
    }

    /**
     * The morning message after a lapse: no plan, no counts, no overdue list. Pick one for me · Move overdue to next
     * week (the second only if something is overdue). The LLM writes it; the template never fails.
     */
    suspend fun reentry(now: Instant, reads: TickReads, timeoutMs: Long): Ticker.Daily {
        val s = store.settings()
        val local = now.atZone(s.zone)
        val open = TaskLists.all(reads.openTasks().filter { it.kind == TaskKind.TASK && it.parentId == null })
        val lang = ChipRules.chipLang(reads.lastUserText(), open.take(5).joinToString(" ") { it.title })
        val overdue = open.any { t -> Times.parseOrNull(t.dueAt)?.let { it < now } == true }
        val chips = ChipRules.chipsJson(ChipRules.chipsFor("reentry", null), lang, null).filter { overdue || it.id != "move_overdue" }
        if (canWrite(timeoutMs)) {
            val models = mutableListOf<String>()
            val written = attempt("Re-entry message", timeoutMs) {
                val messages = listOf(
                    message("system", prompts!!.render("daily_reentry", Coaching.selectPlaybooks("reentry"), chips.map { it.label }, preferences(),
                        timeValues(local))),
                    message("user", "The user has been away for a few days. Some of their open tasks, only so you know their language " +
                        "(don't list, name or count them):\n" + open.take(5).joinToString("\n") { "- ${it.title}" }.ifEmpty { "(none)" }),
                )
                llm!!.complete(messages, emptyList(), 0.5, timeoutMs / 1000.0).also { r -> r.model?.let { models += it } }.content.trim()
            }
            if (!written.isNullOrEmpty()) return Ticker.Daily(Texts.clip(written, 300), chips, kind = "reentry", model = models.lastOrNull())
        }
        return Ticker.Daily(Texts.text("reentry", lang), chips, kind = "reentry")
    }

    // ------------------------------------------------------------ the facts for a daily message

    private suspend fun snapshot(
        job: String, local: ZonedDateTime, today: LocalDate, open: List<TaskRow>, todayList: List<TaskRow>, doneIds: List<Long>,
        sessions: Pair<Int, Int>, focus: Pair<List<TaskRow>, Set<Long>>, suggested: List<TaskRow>, meetings: List<Pair<Instant, TaskRow>>,
    ): String {
        val zone = local.zone
        val now = local.toInstant()
        val parts = mutableListOf<String>()
        if (todayList.isNotEmpty()) {
            val shown = todayList.take(12)
            val counts = LlmContext.eventCounts(dao, shown, now, 7)
            parts += "Open for today (incl. overdue):\n" + shown.joinToString("\n") { LlmContext.formatTask(it, local, counts = counts[it.id].orEmpty(), today = today) }
        }
        val done = dao.tasks(doneIds).filter { it.status != CANCELLED }.associateBy { it.id }.let { m -> doneIds.mapNotNull { m[it] } }
        parts += "Done today (steps count too):\n" +
            done.joinToString("\n") { "- ${it.title}" + if (it.parentId != null) " (a step)" else "" }.ifEmpty { "(nothing yet)" }
        if (sessions.first > 0) parts += "Focus sessions today: ${sessions.first} (${sessions.second} min)"
        val endOfDay = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val upcoming = TaskLists.upcoming(open, endOfDay)
        if (upcoming.isNotEmpty()) parts += "Next 7 days:\n" + LlmContext.formatOpenItems(upcoming, local, emptySet())
        val undated = TaskLists.all(open).filter { it.dueAt == null && it.parentId == null && it.kind == TaskKind.TASK }
        if (undated.isNotEmpty()) parts += "Open without a date:\n" + LlmContext.formatOpenItems(undated, local, emptySet())
        if (job == "morning") {
            parts += "Suggested Today's 3 (the code's pick: overdue, then due today, then priority):\n" + focusLines(suggested, emptySet()).ifEmpty { "(no open tasks)" }
            parts += "Meetings today:\n" + meetings.joinToString("\n") { (w, m) -> "${w.atZone(zone).format(HHMM)} ${m.title}" }.ifEmpty { "(none)" }
        } else if (focus.first.isNotEmpty()) {
            parts += "Today's 3 ([x] = done):\n" + focusLines(focus.first, focus.second)
        }
        return parts.joinToString("\n\n")
    }

    private fun focusLines(focus: List<TaskRow>, done: Set<Long>): String = focus.mapIndexed { i, t ->
        "${i + 1}. [${if (t.id in done) "x" else " "}] #${t.id} ${t.title}" + (t.nextAction?.takeIf { it.isNotEmpty() }?.let { " → $it" } ?: "")
    }.joinToString("\n")

    private suspend fun todayConversation(local: ZonedDateTime, s: AppSettings, limit: Int): String =
        LlmContext.formatMessages(LlmContext.since(dao, Schedule.dayStart(local, s.dayResetTime).toInstant(), limit), local).ifEmpty { "(nothing yet)" }

    private suspend fun preferences(): List<String> = dao.preferences().map { it.text }

    private fun timeValues(local: ZonedDateTime) = mapOf("now" to local.format(NOW), "weekday" to Agent.weekday(local))

    // ------------------------------------------------------------ the LLM, or not

    private fun canWrite(timeoutMs: Long) = llm != null && prompts != null && timeoutMs >= MIN_TIMEOUT_MS

    /** The LLM's text, or null when it couldn't help (unavailable, too slow, a bug): then the template is used. */
    private suspend fun attempt(what: String, timeoutMs: Long, block: suspend () -> String): String? = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        Diag.log("llm", "$what: no answer within ${timeoutMs / 1000} s, template used")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmUnavailable) {
        Diag.log("llm", "$what: template used (${e.message?.lineSequence()?.firstOrNull()})")
        null
    } catch (e: Exception) {
        Diag.log("llm", "$what failed, template used: ${e::class.java.simpleName}: ${e.message}")
        null
    }

    companion object {
        /** Below this, there's no point asking the model: the template goes out. */
        const val MIN_TIMEOUT_MS = 3_000L

        /** The reason message about an avoided task: sent once, instead of more check-ins. */
        const val AVOID_ASK = "This task keeps getting postponed or ignored, so this is NOT an ordinary check-in. Write one short, kind line: " +
            "say plainly that it keeps slipping and that's fine, and ask what's in the way. Don't name a next action, don't push, don't " +
            "count how often. The reply buttons carry the answers."

        val JOB_PROMPTS = mapOf("morning" to "daily_morning", "checkin" to "daily_checkin", "evening" to "daily_evening")
        private val JOB_NAMES = mapOf("checkin" to "Mid-day check-in", "evening" to "Evening review")
        private val NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    }
}
