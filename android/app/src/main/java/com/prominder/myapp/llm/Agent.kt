package com.prominder.myapp.llm

import com.prominder.myapp.data.Role
import com.prominder.myapp.data.Chips
import com.prominder.myapp.data.MessageRow
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.data.linkMessage
import com.prominder.myapp.data.listTasks
import com.prominder.myapp.data.resetAvoidance
import com.prominder.myapp.data.taskEvent
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.logic.ChipRules
import com.prominder.myapp.logic.Coaching
import com.prominder.myapp.logic.Schedule
import com.prominder.myapp.remind.Ticker
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A status for the chat while a turn runs ("📝 Writing a new task: Dentist"). */
data class ToolStatus(val name: String, val title: String? = null, val kind: String? = null, val taskTitle: String? = null)

/** What the chat shows while the assistant works. */
sealed interface ChatProgress {
    /** Your message is saved (✓ Sent). */
    data class Received(val userMessageId: Long) : ChatProgress
    data object Thinking : ChatProgress
    data class Tool(val status: ToolStatus) : ChatProgress
}

data class ChatResult(val reply: String, val changed: Boolean, val userMessageId: Long, val replyMessageId: Long, val taskIds: List<Long>)

/**
 * The chat agent: your messages → replies and task changes (tool calling).
 *
 * The model sees the system prompt (with the situational playbooks code chose), a "likely topic" block, and today's
 * messages only (the chat resets daily at the day-reset time; task descriptions and histories carry the long-term
 * memory). Which task a turn is about is the model's call: code only offers candidates, and the turn is linked to the
 * tasks the model actually acted on.
 */
class Agent(private val store: Store, private val llm: Llm, private val prompts: Prompts) {
    private val dao = store.dao
    private val tools = Tools(store)

    private class Turn(
        val history: List<MessageRow>,
        val userMsg: MessageRow,
        val checkinIds: List<Long>,
        val system: String,
        val titles: Map<Long, String>,
        /** Open tasks at the start of the turn: id → kind. */
        val openKinds: Map<Long, String>,
    )

    /**
     * One chat message. [LlmUnavailable] propagates (your message is kept). [onProgress] gets [ChatProgress.Received]
     * once the message is saved, then Thinking before each model call and a Tool status before each tool runs.
     * [retryOf]: answer this already saved message again (Try again after no reply), instead of saving [text].
     */
    suspend fun handle(text: String, retryOf: Long? = null, onProgress: (ChatProgress) -> Unit = {}): ChatResult {
        val titles = HashMap<Long, String>()
        fun progress(event: ChatProgress) {
            try {
                onProgress(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // status updates are cosmetic
                Diag.log("llm", "Progress callback failed: $e")
            }
        }

        val now = store.now()
        val turn = startTurn(text, now, retryOf) { progress(ChatProgress.Received(it.id)) }
        titles.putAll(turn.titles)
        val messages = mutableListOf(message("system", turn.system))
        messages += normalizeRoles(turn.history.map { it.role to it.content } + ("user" to text))

        val ctx = ToolContext("chat", now)
        val models = mutableListOf<String>()
        var reply: String
        val linked: List<Long>
        try {
            reply = withTimeoutOrNull(CHAT_TURN_TIMEOUT_MS) {
                ToolLoop(llm, tools).run(messages, ToolDefs.CHAT_TOOLS, ctx, maxRounds = MAX_TOOL_ROUNDS, timeoutS = LLM_TIMEOUT_S,
                    models = models) { e ->
                    progress(if (e is LoopEvent.Tool) ChatProgress.Tool(toolStatus(e.name, e.args, titles)) else ChatProgress.Thinking)
                }.text
            } ?: TIMEOUT_REPLY.also { Diag.log("llm", "Chat turn stopped after ${CHAT_TURN_TIMEOUT_MS / 1000} s") }
        } finally {
            linked = Coaching.linkTaskIds(ctx.actedOn, turn.checkinIds)
            withContext(NonCancellable) { store.linkMessage(turn.userMsg.id, linked) }
        }
        if (reply.isEmpty()) reply = if (ctx.changed) "Done." else "Sorry, I couldn't process that. Try again?"
        val chips = replyChips(linked, ctx, turn.openKinds, text, titles)
        // The model that answered last, also under "Done." and the timeout reply: it's the one that was working.
        val replyMsg = store.addMessageNow("assistant", reply, "chat", linked, Chips.encode(chips), models.lastOrNull())
        return ChatResult(reply, ctx.changed, turn.userMsg.id, replyMsg.id, linked)
    }

    /** Saves your message (then calls [onSaved]) and builds the prompt. */
    private suspend fun startTurn(text: String, now: Instant, retryOf: Long?, onSaved: (MessageRow) -> Unit): Turn {
        val s = store.settings()
        val local = now.atZone(s.zone)
        val start = Schedule.dayStart(local, s.dayResetTime).toInstant()

        val earlier = retryOf?.let { dao.message(it) }
        val history = LlmContext.since(dao, start, 41).filter { earlier == null || it.id < earlier.id }.takeLast(40)
        val userMsg = earlier ?: store.addMessageNow("user", text, "chat")
        onSaved(userMsg)
        val today = Schedule.focusDay(local, s.dayResetTime)

        val last = dao.latestOfKinds(Ticker.PROACTIVE_KINDS)
        val lastAt = Times.parseOrNull(last?.createdAt)
        val linkHours = s.replyLinkHours
        val recent = last?.takeIf { lastAt != null && Duration.between(lastAt, now).seconds <= linkHours * 3600 }
        val open = store.listTasks("open")
        var candidateIds = Coaching.selectCandidateTasks(text, now, lastAt, last?.taskIds.orEmpty(), recentTaskIds(start), open, linkHours)
        val reason = avoidReasonTap(text, history, last)
        if (reason != null) {
            // A reason button: recorded here, and the task gets a fresh start (no more reason messages, no park).
            store.taskEvent(reason.second, "avoided", text.trim(), reason.first)
            store.resetAvoidance(reason.second)
            candidateIds = listOf(reason.second) + candidateIds.filter { it != reason.second }.take(2)
        }
        val byId = open.associateBy { it.id }.toMutableMap()
        dao.tasks(candidateIds.filter { it !in byId }).forEach { byId[it.id] = it }
        val candidates = candidateIds.mapNotNull { byId[it] }

        // Which playbooks (code decides): the likely tasks' stages, plus simple signals from this turn.
        val facts = LlmContext.loadFacts(dao, candidates, now)
        val lastReply = history.indexOfLast { it.role == Role.ASSISTANT }
        val signals = Coaching.chatSignals(text, history.drop(lastReply + 1).map { it.kind to it.content }, facts.stuckSince(candidateIds, start),
            local, open.count { it.focusDate == today.toString() })
        val stages = candidates.map { Coaching.taskStage(it, facts.counts[it.id], today) }
        val lang = ChipRules.chipLang(text)
        val system = prompts.render(
            "system",
            playbooks = Coaching.selectPlaybooks(null, stages, signals),
            chips = ChipRules.chipsFor("chat_task", null).map { ChipRules.chipLabel(it.id, lang) },
            preferences = dao.preferences().map { it.text },
            values = mapOf(
                "now" to local.format(NOW), "weekday" to weekday(local), "tz" to s.zone.id,
                "likely_topic" to likelyTopic(candidates, recent, local, facts, today),
            ),
        )
        val checkinIds = if (reason != null) listOf(reason.second) else recent?.taskIds.orEmpty()
        return Turn(history, userMsg, checkinIds, system, byId.mapValues { it.value.title }, open.associate { it.id to it.kind })
    }

    /** Tasks linked to the last few messages of today, most recent first. */
    private suspend fun recentTaskIds(start: Instant, lastN: Int = 6): List<Long> {
        val out = mutableListOf<Long>()
        for (m in LlmContext.since(dao, start, lastN).asReversed()) for (i in m.taskIds) if (i !in out) out += i
        return out
    }

    private suspend fun likelyTopic(candidates: List<TaskRow>, checkin: MessageRow?, local: ZonedDateTime, facts: Facts, today: LocalDate): String {
        val parts = mutableListOf<String>()
        if (checkin != null) parts += "Your last check-in (${LlmContext.fmtWhen(checkin.createdAt, local)}): \"${checkin.content.take(400)}\""
        val blocks = LlmContext.taskContexts(candidates, local, facts, today = today)
        if (blocks.isNotEmpty()) {
            parts += "Tasks it may be about (a guess from recent messages, not certain):\n" + blocks.joinToString("\n")
            parts += "Earlier messages about these:\n" + LlmContext.linkedHistory(dao, candidates.map { it.id }, local, 6)
            parts += "The user's message may be about one of these, a different task, a new task, or nothing task-related. Decide from " +
                "what they wrote. If it's ambiguous and it matters, ask one short question."
        } else {
            parts += "No obvious task. It may be a new task, a question, or about any task (use list_tasks)."
        }
        return parts.joinToString("\n\n")
    }

    /** A tool call → its status line. Never throws. */
    private suspend fun toolStatus(name: String, args: JsonObject, known: MutableMap<Long, String>): ToolStatus {
        val title = (args["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
        val kind = if (name == "add_task" && (args["kind"] as? JsonPrimitive)?.content == "meeting") "meeting" else null
        val idValue = (if ("id" in args) args["id"] else args["task_id"]) as? JsonPrimitive
        val taskId = idValue?.takeIf { !it.isString }?.content?.toLongOrNull()
        val taskTitle = taskId?.let { id ->
            known[id] ?: runCatching { dao.task(id)?.title }.getOrNull()?.also { known[id] = it }
        }
        return ToolStatus(name, title, kind, taskTitle)
    }

    /** ▶ Start 5 min · ✓ Done under a reply about exactly one open task (none once it's done). */
    private fun replyChips(linked: List<Long>, ctx: ToolContext, openKinds: Map<Long, String>, text: String, titles: Map<Long, String>): List<ChipRules.ChipJson> {
        if (linked.size != 1 || linked[0] in ctx.closed) return emptyList()
        val id = linked[0]
        if ((openKinds[id] ?: ctx.created[id]) != "task") return emptyList()
        val lang = ChipRules.chipLang(text, titles[id])
        return ChipRules.chipsJson(ChipRules.chipsFor("chat_task", null), lang, id)
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 6
        /** One model call (the Lambda allows up to LLM_TIMEOUT_S, 25 s by default). */
        const val LLM_TIMEOUT_S = 25.0
        /** A whole turn (several model calls and tools) stops after this, so "typing…" never hangs. */
        const val CHAT_TURN_TIMEOUT_MS = 90_000L
        const val TIMEOUT_REPLY = "That took too long, so I stopped. Anything I already changed is saved. Try again?"

        private val NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun weekday(local: ZonedDateTime): String = local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

        /**
         * History → alternating user/assistant turns (some models' templates reject two of the same in a row, e.g. two
         * proactive messages). Same-role neighbours are merged; events are the user's side; the first turn is the user's.
         */
        fun normalizeRoles(history: List<Pair<String, String>>): List<JsonObject> {
            val out = mutableListOf<Pair<String, StringBuilder>>()
            for ((r, content) in history) {
                val role = if (r == "user") "user" else "assistant"
                if (out.isNotEmpty() && out.last().first == role) out.last().second.append("\n\n").append(content)
                else out += role to StringBuilder(content)
            }
            if (out.isNotEmpty() && out[0].first == "assistant") out.add(0, "user" to StringBuilder("(start of today's chat)"))
            return out.map { message(it.first, it.second.toString()) }
        }

        /**
         * (reason, task id) when the message is a tap on Too big / Unclear / Boring under an avoided task's reason message:
         * the latest assistant message (today's, else the last proactive one) carries that button and is about that task.
         */
        fun avoidReasonTap(text: String, history: List<MessageRow>, lastProactive: MessageRow?): Pair<String, Long>? {
            val chip = ChipRules.chipIdForText(text) ?: return null
            if (chip !in Coaching.AVOID_CHIPS) return null
            val asked = history.lastOrNull { it.role == Role.ASSISTANT } ?: lastProactive ?: return null
            if (asked.taskIds.isNotEmpty() && Chips.decode(asked.chips).any { it.id == chip }) return chip to asked.taskIds[0]
            return null
        }
    }
}
