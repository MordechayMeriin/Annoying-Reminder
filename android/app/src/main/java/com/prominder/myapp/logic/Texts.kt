package com.prominder.myapp.logic

import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.logic.Focus.Pick
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The fixed texts (no LLM), in English and Hebrew, and clipping a text to a length. Pure: no I/O. */
object Texts {
    /** At most [limit] characters, cut at the last space or line break when that keeps 2/3, ending with "…". */
    fun clip(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var cut = text.substring(0, limit - 1)
        if (!text[limit - 1].isWhitespace()) {
            val space = maxOf(cut.lastIndexOf(' '), cut.lastIndexOf('\n'))
            if (space >= limit * 2 / 3) cut = cut.substring(0, space)
        }
        return cut.trimEnd() + "…"
    }

    private val TEXTS: Map<String, Pair<String, String>> = mapOf(
        "fits" to ("Do this: {step} ({min} min)." to "עכשיו: {step} ({min} דק׳)."),
        "small" to ("Something small: {step} ({min} min)." to "משהו קטן: {step} ({min} דק׳)."),
        "short_start_before" to ("Before {at}: just 5 minutes of this: {step}." to "עד {at}: רק 5 דקות של זה: {step}."),
        "short_start" to ("Just 5 minutes of this: {step}." to "רק 5 דקות של זה: {step}."),
        "bonus" to ("All of Today's 3 are done. Bonus, only if you feel like it: {step} ({min} min)." to
            "כל שלוש המשימות של היום בוצעו. בונוס, רק אם בא לך: {step} ({min} דק׳)."),
        "all_done" to ("All of Today's 3 are done. Nothing else is open." to "כל שלוש המשימות של היום בוצעו. אין עוד משהו פתוח."),
        "nothing" to ("Nothing open right now. Tell me what's on your mind and I'll save it." to
            "אין כרגע משהו פתוח. ספר לי מה על הראש ואשמור את זה."),
        "session_end" to ("⏱ Time's up: {step}. Done, another round, or stop?" to "⏱ הזמן נגמר: {step}. בוצע, עוד סבב, או לעצור?"),
        "avoid_ask" to ("{title} keeps slipping, and that's fine. What's in the way?" to "{title} נדחית שוב ושוב, וזה בסדר. מה מפריע?"),
        "reentry" to ("Good to see you. Want me to pick one thing for today?" to "טוב לראות אותך. שאבחר לך דבר אחד להיום?"),
        "fence" to ("⛔ {at} — {title}. Time to stop." to "⛔ {at} — {title}. הגיע הזמן לעצור."),
        "wins_week" to ("✅ Last week you finished {n} things." to "✅ בשבוע שעבר סיימת {n} דברים."),
        "wins_week_1" to ("✅ Last week you finished one thing." to "✅ בשבוע שעבר סיימת דבר אחד."),
        "stale" to ("🧹 Open for 3+ weeks with no progress: {titles}. Drop them or keep?" to
            "🧹 פתוחות יותר מ-3 שבועות בלי התקדמות: {titles}. לוותר עליהן או להשאיר?"),
    )

    /** A fixed text in the user's language, with {name} values filled in. */
    fun text(key: String, lang: String, vararg values: Pair<String, Any>): String {
        var out = TEXTS.getValue(key).let { if (lang == "he") it.second else it.first }
        for ((k, v) in values) out = out.replace("{$k}", v.toString())
        return out
    }

    /** The task as one thing to do: its next action, with the title when they differ. */
    fun stepOf(task: TaskRow): String {
        val step = task.nextAction?.takeIf { it.isNotEmpty() } ?: task.title
        return if (step == task.title) step else "$step — ${task.title}"
    }

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** What now?'s reply. nextFixedLocal: the next meeting's local start (names the deadline of a short start). */
    fun whatNowText(pick: Pick?, lang: String, nextFixedLocal: ZonedDateTime? = null): String {
        pick ?: return text("nothing", lang)
        val task = pick.task ?: return text(pick.reason, lang)
        val step = "step" to stepOf(task)
        val min = "min" to pick.minutes
        if (pick.reason == "short_start" && nextFixedLocal != null) return text("short_start_before", lang, "at" to nextFixedLocal.format(HHMM), step, min)
        return text(pick.reason, lang, step, min)
    }

    fun sessionText(kind: String, task: TaskRow, minutes: Int, lang: String): String =
        text(kind, lang, "step" to (task.nextAction?.takeIf { it.isNotEmpty() } ?: task.title), "min" to minutes)
}
