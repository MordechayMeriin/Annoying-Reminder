package com.prominder.myapp.logic

import com.prominder.myapp.logic.Focus.Pick
import com.prominder.myapp.logic.FocusSessions.START_MIN_NOTIFICATION

/** The reply buttons ("chips"): which ones a message gets, their labels in English and Hebrew, and how they're stored. Pure: no I/O. */
object ChipRules {
    data class Chip(val id: String, val action: String? = null, val minutes: Int? = null)

    val CHIP_LABELS: Map<String, Pair<String, String>> = linkedMapOf(
        "start5" to ("▶ Start 5 min" to "▶ להתחיל 5 דק׳"),
        "done" to ("✓ Done" to "✓ בוצע"),
        "later" to ("Later" to "אחר כך"),
        "stuck" to ("I'm stuck" to "נתקעתי"),
        "start_first" to ("▶ Start the first one" to "▶ להתחיל בראשונה"),
        "change" to ("Change it" to "לשנות"),
        "another" to ("▶ Another round" to "▶ עוד סבב"),
        "stop" to ("Stop" to "לעצור"),
        "too_big" to ("Too big" to "גדול מדי"),
        "unclear" to ("Unclear" to "לא ברור"),
        "boring" to ("Boring" to "משעמם"),
        "not_now" to ("Not now" to "לא עכשיו"),
        "pick_one" to ("Pick one for me" to "תבחר לי משהו"),
        "move_overdue" to ("Move overdue to next week" to "להעביר את מה שבאיחור לשבוע הבא"),
        "drop" to ("Drop them" to "לוותר עליהן"),
        "keep" to ("Keep" to "להשאיר"),
        "start_next" to ("▶ Start next" to "▶ להתחיל את הבאה"),
        "what_now" to ("What now?" to "מה עכשיו?"),
        "start" to ("▶ Start" to "▶ להתחיל"),
        "low_energy" to ("Low energy" to "אין לי כוח"),
        "something_else" to ("Something else" to "משהו אחר"),
    )

    private val START5 = Chip("start5", "start", START_MIN_NOTIFICATION)
    private val DONE_CHIP = Chip("done", "done")
    private val WHAT_NOW_CHIP = Chip("what_now", "what_now")

    val CHIPS_BY_KIND: Map<String, List<Chip>> = mapOf(
        "checkin" to listOf(START5, DONE_CHIP, Chip("later", "later"), Chip("stuck")),
        "morning" to listOf(Chip("start_first", "start", START_MIN_NOTIFICATION), Chip("change")),
        "session_end" to listOf(DONE_CHIP, Chip("another", "another"), Chip("stop", "stop")),
        "avoided" to listOf(Chip("too_big"), Chip("unclear"), Chip("boring"), Chip("not_now", "not_now")),
        "reentry" to listOf(Chip("pick_one"), Chip("move_overdue", "move_overdue")),
        "sunday" to listOf(Chip("drop", "drop"), Chip("keep", "keep")),
        "meeting" to emptyList(),
        "midday" to listOf(Chip("start_next", "start", START_MIN_NOTIFICATION), WHAT_NOW_CHIP),
        "what_now" to listOf(Chip("start", "start", START_MIN_NOTIFICATION), Chip("low_energy", "what_now"),
            Chip("something_else", "what_now")),
        "chat_task" to listOf(START5, DONE_CHIP),
        "evening" to emptyList(),
    )

    private val NEEDS_TASK = setOf("start", "done", "later", "another", "stop", "not_now")
    private val NEEDS_TASKS = setOf("drop", "keep")
    private val HEBREW = Regex("[֐-׿]")

    /** 'he' when the user's latest chat message (or, failing that, e.g. the task title) has Hebrew letters. */
    fun chipLang(latestUserText: String?, fallbackText: String? = null): String {
        val text = if (!latestUserText.isNullOrBlank()) latestUserText else fallbackText
        return if (text != null && HEBREW.containsMatchIn(text)) "he" else "en"
    }

    fun chipLabel(chipId: String, lang: String): String = CHIP_LABELS.getValue(chipId).let { if (lang == "he") it.second else it.first }

    /** The reply buttons for a message, from its kind and its task's stage. A done task gets none. */
    fun chipsFor(kind: String, stage: String?): List<Chip> =
        if (stage == "done" || stage == "cancelled") emptyList() else CHIPS_BY_KIND[kind].orEmpty()

    /** One chip as stored on a message. Never changed once made (so the chat's lists of them can skip redrawing). */
    @androidx.compose.runtime.Immutable
    data class ChipJson(
        val id: String, val label: String, val action: String? = null, val minutes: Int? = null,
        val taskId: Long? = null, val taskIds: List<Long>? = null,
        /** What now?'s Low energy / Something else: how to ask again. */
        val lowEnergy: Boolean? = null, val exclude: List<Long>? = null,
    )

    /** Chips as stored: action chips bound to their task (left out without one); Drop them / Keep to [taskIds]. */
    fun chipsJson(chips: Iterable<Chip>, lang: String, taskId: Long?, taskIds: List<Long> = emptyList()): List<ChipJson> =
        chips.filter { (taskId != null || it.action !in NEEDS_TASK) && (taskIds.isNotEmpty() || it.action !in NEEDS_TASKS) }
            .map { c ->
                if (c.action == null) ChipJson(c.id, chipLabel(c.id, lang))
                else ChipJson(c.id, chipLabel(c.id, lang), c.action, c.minutes?.takeIf { it > 0 },
                    taskId.takeIf { c.action in NEEDS_TASK }, taskIds.takeIf { c.action in NEEDS_TASKS })
            }

    /** Which text chip a user message is (a tap sends its label), in either language. */
    fun chipIdForText(text: String?): String? {
        val t = (text ?: "").trim()
        return CHIP_LABELS.entries.firstOrNull { (_, l) -> t == l.first || t == l.second }?.key
    }
}
