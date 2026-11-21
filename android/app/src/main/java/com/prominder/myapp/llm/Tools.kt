package com.prominder.myapp.llm

import com.prominder.myapp.data.EventKind
import com.prominder.myapp.data.TaskKind
import com.prominder.myapp.data.OPEN
import com.prominder.myapp.data.SettingException
import com.prominder.myapp.data.SettingSpecs
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.data.addPreference
import com.prominder.myapp.data.block
import com.prominder.myapp.data.closeNotesForTask
import com.prominder.myapp.data.createTask
import com.prominder.myapp.data.listTasks
import com.prominder.myapp.data.resetAvoidance
import com.prominder.myapp.data.setFollowup
import com.prominder.myapp.data.taskEvent
import com.prominder.myapp.data.unblock
import com.prominder.myapp.data.updateTask
import com.prominder.myapp.logic.Coaching
import com.prominder.myapp.logic.Contacts
import com.prominder.myapp.logic.Focus
import com.prominder.myapp.logic.Schedule
import com.prominder.myapp.remind.Ticker
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What one LLM turn did with its tools. */
class ToolContext(
    /** chat | coach (check-ins) | morning (the morning plan) */
    val mode: String,
    val now: Instant,
) {
    /** Task ids touched, in order (to link the turn to them). */
    val actedOn = mutableListOf<Long>()
    /** Coach mode: the next check-in times the model chose (applied after the message is sent). */
    val followups = mutableMapOf<Long, Instant>()
    var changed = false
    /** set_focus was called: Today's 3, in order. */
    var focus: List<Long>? = null
    /** Tasks completed or cancelled in this turn. */
    val closed = mutableSetOf<Long>()
    /** Tasks added in this turn: id → kind. */
    val created = mutableMapOf<Long, String>()

    fun touched(id: Long) {
        if (id !in actedOn) actedOn += id
    }
}

/**
 * The tools the LLM can call (definitions and behaviour), writing to the phone's database. Flat parameters and enums, for a mid-size open model.
 */
object ToolDefs {
    private const val ISO = "Local ISO datetime, e.g. 2026-11-09T14:00"
    private val ID = obj("type" to "integer")
    private val NEXT_ACTION = obj("type" to "string", "description" to
        "REQUIRED for tasks: the very first physical step, verb first, 15 minutes or less, in the user's language (e.g. 'Find last " +
        "year's PDF in email'). Infer it yourself; never ask. Over 45 minutes in total: the first 15-minute chunk.")
    private val ESTIMATE = obj("type" to "integer", "description" to "Your guess of the whole task's length in minutes. Infer it; never ask.")

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            is String -> put(k, v)
            is JsonElement -> put(k, v)
            else -> error("unsupported")
        }
    }

    private fun str(description: String? = null, enum: List<String>? = null) = buildJsonObject {
        put("type", "string")
        enum?.let { e -> putJsonArray("enum") { e.forEach { add(JsonPrimitive(it)) } } }
        description?.let { put("description", it) }
    }

    private fun int(description: String? = null, enum: List<Int>? = null) = buildJsonObject {
        put("type", "integer")
        enum?.let { e -> putJsonArray("enum") { e.forEach { add(JsonPrimitive(it)) } } }
        description?.let { put("description", it) }
    }

    private fun fn(name: String, description: String, props: List<Pair<String, JsonObject>>, required: List<String>): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    private val SCHEDULE_FOLLOWUP_PROPS = listOf(
        "task_id" to ID,
        "minutes_from_now" to int("When to check in next, in minutes. Use this OR at_local_iso."),
        "at_local_iso" to str(ISO),
        "reason" to str("Short reason, e.g. 'after the user's meeting'"),
    )

    private val PRIORITY = int(enum = listOf(1, 2, 3))
    private val KIND = str(enum = listOf("task", "meeting", "fence"))

    val SET_FOCUS = fn("set_focus", "Set Today's 3: up to three task ids, most important first. Replaces today's earlier pick. No meetings.",
        listOf("task_ids" to buildJsonObject {
            put("type", "array")
            putJsonObject("items") { put("type", "integer") }
            put("description", "1 to 3 task ids, first = do first")
        }), listOf("task_ids"))

    val LIST_TASKS = fn("list_tasks", "List tasks. Use it to find the right id before changing a task.",
        listOf("scope" to str(enum = listOf("today", "overdue", "upcoming", "open", "done_today", "blocked"))), listOf("scope"))

    val CHAT_TOOLS: List<JsonObject> = listOf(
        fn("add_task", "Save a new task, meeting, deadline or hard stop (kind=fence: a fixed time the user must stop what they're doing, " +
            "e.g. picking up the kids at 17:00).", listOf(
            "title" to str("Short title in the user's language"),
            "kind" to KIND,
            "due_at" to str("Deadline / meeting start / the moment to stop (fence). $ISO"),
            "remind_at" to str("Optional explicit reminder time. $ISO"),
            "priority" to PRIORITY,
            "recurrence" to str(enum = listOf("daily", "weekdays", "weekly", "monthly")),
            "description" to str("What the task is about and anything useful the user said"),
            "next_action" to NEXT_ACTION,
            "estimate_min" to ESTIMATE,
        ), listOf("title")),
        LIST_TASKS,
        fn("update_task", "Change a task. Rewrite `description` whenever the user tells you something new about the task (keep every " +
            "fact that still matters). unblock=true clears a blocker.", listOf(
            "id" to ID,
            "title" to str(),
            "description" to str("The full new description (max ~800 chars)"),
            "due_at" to str(ISO),
            "remind_at" to str(ISO),
            "priority" to PRIORITY,
            "recurrence" to str(enum = listOf("daily", "weekdays", "weekly", "monthly", "none")),
            "kind" to KIND,
            "next_action" to str("A new first step (e.g. the user corrected yours)"),
            "estimate_min" to ESTIMATE,
            "resume_note" to str("Where the user stopped, in their words ('page 3', 'row 40'); shown next time they start. Cleared " +
                "automatically when the task is done."),
            "unblock" to obj("type" to "boolean"),
        ), listOf("id")),
        fn("complete_task", "Mark a task as done.", listOf("id" to ID), listOf("id")),
        fn("cancel_task", "Cancel a task that is no longer relevant.", listOf("id" to ID), listOf("id")),
        fn("schedule_followup", "Set when to check in with the user about a task next (also: snooze / 'remind me later'). " +
            "requested_by=user when the user asked for that time.",
            SCHEDULE_FOLLOWUP_PROPS + ("requested_by" to str(enum = listOf("user", "assistant"))), listOf("task_id", "reason", "requested_by")),
        fn("add_subtask", "Add a small step under a task (to break a big task down).", listOf(
            "task_id" to ID,
            "title" to str("A concrete step, 10-30 minutes"),
            "due_at" to str("Optional. $ISO"),
        ), listOf("task_id", "title")),
        fn("log_interaction", "Record how the user relates to a task right now: postponed ('later'), committed ('I'll do it at 5'), " +
            "progress (did part of it), stuck (doesn't know how to continue).", listOf(
            "task_id" to ID,
            "kind" to str(enum = listOf("postponed", "committed", "progress", "stuck")),
            "note" to str("Short, in the user's words"),
            "avoid_reason" to str("Optional: why they keep putting this task off, when they say so or it's clear from what they wrote",
                Coaching.AVOID_REASONS),
        ), listOf("task_id", "kind", "note")),
        fn("mark_blocked", "The task can't move until something external happens (waiting for someone/something).", listOf(
            "task_id" to ID,
            "reason" to str(),
            "followup_minutes" to int("When to check whether it's unblocked"),
        ), listOf("task_id", "reason", "followup_minutes")),
        SET_FOCUS,
        fn("update_setting", "Change how the app behaves when the user asks ('don't message me after 22:00'). Applies at once; no " +
            "confirmation needed. Times are HH:MM, lists of times are comma-separated, switches are true/false, value 'default' resets " +
            "the setting.", listOf(
            "key" to str(enum = SettingSpecs.ALL.map { it.key }),
            "value" to str("The new value as text, e.g. '22:00', '13:00, 17:00', 'false', '45'"),
        ), listOf("key", "value")),
        fn("remember_preference", "Remember how the user wants you to talk or behave, when it isn't a setting (tone, length, topics to " +
            "avoid): 'shorter messages please'. It's added to all your instructions.", listOf(
            "text" to str("One short line, as an instruction to yourself, in English"),
        ), listOf("text")),
    )

    /** The morning plan picks Today's 3. */
    val MORNING_TOOLS: List<JsonObject> = listOf(SET_FOCUS, LIST_TASKS)

    val COACH_TOOLS: List<JsonObject> = listOf(
        fn("schedule_followup", "Optional: choose when to check in about this task next, if a different time than usual makes sense " +
            "(e.g. after a meeting, tomorrow morning).", SCHEDULE_FOLLOWUP_PROPS, listOf("task_id", "reason")),
    )

    fun name(tool: JsonObject): String = ((tool["function"] as JsonObject)["name"] as JsonPrimitive).content
    fun parameters(tool: JsonObject): JsonObject = (tool["function"] as JsonObject)["parameters"] as JsonObject

    val SCHEMAS: Map<String, JsonObject> = CHAT_TOOLS.associate { name(it) to parameters(it) }

    /** Tools allowed per mode (chat: all of CHAT_TOOLS). */
    val MODE_TOOLS = mapOf("coach" to setOf("schedule_followup"), "morning" to setOf("set_focus", "list_tasks"))
}

/** Runs tool calls against the phone's database. */
class Tools(private val store: Store) {
    private val dao = store.dao

    /** Validate and run one tool call. Throws [ToolArgError] for problems the model can fix. */
    suspend fun execute(name: String, rawArgs: JsonElement?, ctx: ToolContext): JsonElement {
        val allowed = ToolDefs.MODE_TOOLS[ctx.mode]
        if (allowed != null && name !in allowed) throw ToolArgError("Tool '$name' is not available here.")
        val schema = if (ctx.mode == "coach") ToolDefs.parameters(ToolDefs.COACH_TOOLS[0]) else ToolDefs.SCHEMAS[name]
        val a = ToolArgs.validate(name, rawArgs, schema)
        return when (name) {
            "add_task" -> addTask(a, ctx)
            "list_tasks" -> JsonArray(store.listTasks(a.str("scope")!!).take(30).map { brief(it) })
            "update_task" -> updateTask(a, ctx)
            "complete_task" -> completeTask(a, ctx)
            "cancel_task" -> cancelTask(a, ctx)
            "schedule_followup" -> scheduleFollowup(a, ctx)
            "add_subtask" -> addSubtask(a, ctx)
            "log_interaction" -> logInteraction(a, ctx)
            "mark_blocked" -> markBlocked(a, ctx)
            "set_focus" -> setFocus(a, ctx)
            "update_setting" -> updateSetting(a, ctx)
            "remember_preference" -> rememberPreference(a)
            else -> throw ToolArgError("Unknown tool '$name'.")
        }
    }

    private fun Map<String, Any>.str(key: String) = this[key] as String?
    private fun Map<String, Any>.long(key: String) = this[key] as Long?
    private fun Map<String, Any>.int(key: String) = (this[key] as Long?)?.toInt()

    private suspend fun zone(): ZoneId = store.settings().zone

    private suspend fun dt(value: String?, key: String): Instant? {
        value ?: return null
        return try {
            Schedule.parseLocalDateTime(value, zone())
        } catch (e: Exception) {
            throw ToolArgError("'$key' must be a local ISO datetime like 2026-11-09T14:00, got '$value'.")
        }
    }

    private suspend fun task(id: Long): TaskRow = dao.task(id) ?: throw ToolArgError("No task with id $id. Use list_tasks to find the right id.")

    private fun estimate(a: Map<String, Any>): Int? {
        val est = a.int("estimate_min")
        if (est != null && est !in 1..10000) throw ToolArgError("'estimate_min' must be between 1 and 10000 minutes.")
        return est
    }

    /** The task as the model sees it, times in local ISO. */
    suspend fun brief(t: TaskRow): JsonObject {
        val zone = zone()
        fun local(text: String?) = Times.parseOrNull(text)?.atZone(zone)?.format(LOCAL_ISO)
        return buildJsonObject {
            put("id", t.id)
            t.parentId?.let { put("parent_id", it) }
            put("title", t.title)
            put("kind", t.kind)
            put("priority", t.priority)
            put("status", t.status)
            local(t.dueAt)?.let { put("due_at", it) }
            local(t.remindAt)?.let { put("remind_at", it) }
            t.recurrence?.let { put("recurrence", it) }
            t.blockedReason?.let { put("blocked_reason", it) }
            local(t.blockedUntil)?.let { put("blocked_until", it) }
            t.nextAction?.let { put("next_action", it) }
            t.estimateMin?.let { put("estimate_min", it) }
            t.focusDate?.let { put("focus_date", it) }
            t.resumeNote?.let { put("resume_note", it) }
        }
    }

    private fun JsonObject.with(key: String, value: String): JsonObject = JsonObject(this + (key to JsonPrimitive(value)))

    private suspend fun addTask(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        val kind = a.str("kind") ?: "task"
        val t = store.createTask(
            title = a.str("title")!!, kind = kind, priority = a.int("priority") ?: 1, description = a.str("description"),
            dueAt = dt(a.str("due_at"), "due_at"), remindAt = dt(a.str("remind_at"), "remind_at"), recurrence = a.str("recurrence"),
            nextAction = a.str("next_action"), estimateMin = estimate(a),
        )
        ctx.created[t.id] = t.kind
        ctx.touched(t.id)
        ctx.changed = true
        var out = brief(t)
        if (kind == TaskKind.FENCE) {
            out = out.with("note", if (t.dueAt == null) "Saved without a time, so it can't interrupt. Call update_task with due_at: the moment to stop."
            else "Saved as a hard stop: the app interrupts at that time, even during a focus session.")
        }
        if (kind == TaskKind.TASK && t.nextAction.isNullOrEmpty()) {
            // Saved anyway (never lose what the user said), but every task needs its first step.
            out = out.with("note", "Saved without a next_action. Now call update_task with next_action: the very first physical step, verb " +
                "first, 15 minutes or less. Infer it; don't ask the user.")
        }
        return out
    }

    private suspend fun updateTask(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        val id = a.long("id")!!
        task(id)
        val fields = LinkedHashMap<String, Any?>()
        for ((k, v) in a) {
            when (k) {
                "id", "unblock" -> Unit
                "due_at", "remind_at" -> fields[k] = dt(v as String, k)
                "recurrence" -> fields[k] = (v as String).takeUnless { it == "none" }
                "priority", "estimate_min" -> fields[k] = (v as Long).toInt()
                else -> fields[k] = v
            }
        }
        estimate(a)
        var t = if (fields.isNotEmpty()) store.updateTask(id, fields) else dao.task(id)
        if (!a.str("resume_note").isNullOrEmpty()) store.closeNotesForTask(id) // saved from the chat: no "where did you stop?"
        if (a["unblock"] == true) t = store.unblock(id)
        ctx.touched(id)
        ctx.changed = true
        return brief(t!!)
    }

    private suspend fun completeTask(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        val id = a.long("id")!!
        if (task(id).kind == TaskKind.FENCE) throw ToolArgError("A hard stop isn't completed: it fires at its time. Use cancel_task to remove it.")
        ctx.touched(id)
        ctx.closed += id
        ctx.changed = true
        return brief(store.complete(id, chatEvent = false)!!)
    }

    private suspend fun cancelTask(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        val id = a.long("id")!!
        task(id)
        ctx.touched(id)
        ctx.closed += id
        ctx.changed = true
        return brief(store.cancel(id, chatEvent = false)!!)
    }

    private suspend fun setFocus(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        @Suppress("UNCHECKED_CAST")
        val ids = (a["task_ids"] as List<Long>).distinct()
        if (ids.isEmpty()) throw ToolArgError("Give 1 to 3 task ids.")
        if (ids.size > Focus.FOCUS_MAX) throw ToolArgError("At most ${Focus.FOCUS_MAX} tasks: pick the most important ones.")
        val found = dao.tasks(ids).associateBy { it.id }
        val bad = ids.filter { i -> found[i].let { it == null || it.status != OPEN || it.kind != TaskKind.TASK || it.parentId != null } }
        if (bad.isNotEmpty()) {
            throw ToolArgError("Not open top-level tasks (meetings can't be focus tasks): ${bad.joinToString(", ", "[", "]")}. " +
                "Use list_tasks to find the right ids.")
        }
        for (i in ids) if (Focus.isParked(found.getValue(i), ctx.now)) store.resetAvoidance(i) // chosen for today: the park is over
        val s = store.settings()
        val rows = store.setFocus(ids, Schedule.focusDay(ctx.now.atZone(s.zone), s.dayResetTime))
        ctx.focus = rows.map { it.id }
        rows.forEach { ctx.touched(it.id) }
        ctx.changed = true
        return buildJsonObject { put("focus", JsonArray(rows.map { brief(it) })) }
    }

    private suspend fun scheduleFollowup(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        val t = task(a.long("task_id")!!)
        if (t.kind == TaskKind.MEETING || t.kind == TaskKind.FENCE) {
            throw ToolArgError("Meetings and hard stops are scheduled automatically; use update_task to change the time.")
        }
        if (t.status != OPEN) throw ToolArgError("Task ${t.id} is ${t.status}.")
        val (at, clamped) = resolveFollowup(ctx.now, a.long("minutes_from_now"), a.str("at_local_iso"))
        if (ctx.mode == "coach") {
            ctx.followups[t.id] = at // applied after the message is sent
        } else {
            val requested = a.str("requested_by") == "user"
            store.setFollowup(t.id, at, requested)
            if (requested) store.taskEvent(t.id, "postponed", a.str("reason"))
            ctx.changed = true
        }
        ctx.touched(t.id)
        return buildJsonObject {
            put("task_id", t.id)
            put("next_checkin", at.atZone(zone()).format(LOCAL_ISO))
            if (clamped) put("note", "Time adjusted to the allowed range (10 minutes to 7 days).")
        }
    }

    /** The arguments → a clamped time, and whether it was clamped. */
    suspend fun resolveFollowup(now: Instant, minutes: Long?, atIso: String?): Pair<Instant, Boolean> {
        if ((minutes == null) == (atIso == null)) throw ToolArgError("Give exactly one of minutes_from_now or at_local_iso.")
        val wanted = if (minutes != null) now.plus(Duration.ofMinutes(minutes)) else dt(atIso, "at_local_iso")!!
        val at = Contacts.clampFollowup(now, wanted, Ticker.guardrails(store.settings()))
        return at to (at != wanted)
    }

    private suspend fun addSubtask(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        val parent = task(a.long("task_id")!!)
        val t = store.createTask(a.str("title")!!, priority = parent.priority, dueAt = dt(a.str("due_at"), "due_at"), parentId = parent.id)
        ctx.touched(parent.id)
        ctx.changed = true
        return brief(t)
    }

    private suspend fun logInteraction(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        val id = a.long("task_id")!!
        task(id)
        val reason = a.str("avoid_reason")
        val kind = a.str("kind")!!
        store.taskEvent(id, kind, a.str("note"), reason)
        if (reason != null || kind == EventKind.PROGRESS || kind == EventKind.COMMITTED) store.resetAvoidance(id) // moving again, or we know why not
        ctx.touched(id)
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun markBlocked(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        val id = a.long("task_id")!!
        task(id)
        val (until, _) = resolveFollowup(ctx.now, a.long("followup_minutes"), null)
        val t = store.block(id, a.str("reason")!!, until)
        ctx.touched(id)
        ctx.changed = true
        return t?.let { brief(it) } ?: buildJsonObject { put("ok", false) }
    }

    private suspend fun updateSetting(a: Map<String, Any>, ctx: ToolContext): JsonElement {
        val key = a.str("key")!!
        val value = a.str("value")!!
        val spec = SettingSpecs.BY_KEY.getValue(key)
        val old = store.settings().raw(key)
        val reset = value.trim().lowercase() == "default"
        try {
            store.saveSetting(key, if (reset) null else value)
        } catch (e: SettingException) {
            throw ToolArgError("'$key' (${spec.label}): ${e.message}.")
        }
        ctx.changed = true
        return buildJsonObject {
            put("setting", spec.label)
            put("key", key)
            put("new", store.settings().raw(key))
            put("old", old)
            put("note", "Applied. Tell the user in one line, with the old value, and that saying 'undo' reverts it.")
        }
    }

    private suspend fun rememberPreference(a: Map<String, Any>): JsonElement {
        val row = try {
            store.addPreference(a.str("text")!!)
        } catch (e: IllegalArgumentException) {
            throw ToolArgError(e.message.orEmpty())
        }
        return buildJsonObject {
            put("remembered", row.text)
            put("note", "It now applies to every message. The user can delete it under ⚙ Settings.")
        }
    }

    companion object {
        /** A local time to the minute, with its offset: 2026-11-09T14:00+02:00 (how the model sees times). */
        val LOCAL_ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmxxx")
    }
}
