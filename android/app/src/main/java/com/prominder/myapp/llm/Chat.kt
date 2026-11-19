package com.prominder.myapp.llm

import com.prominder.myapp.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the chat shows about the turn in progress (the web app's status area and delivery line). */
data class ChatTurn(
    /** The text being sent; null when nothing is. */
    val sending: String? = null,
    /** Your saved message (✓ Sent), until the reply arrives. */
    val sentId: Long? = null,
    /** The tool steps of this turn ("📝 Writing a new task: Dentist"). */
    val steps: List<String> = emptyList(),
    /** The assistant is writing (the typing dots). */
    val typing: Boolean = false,
    /** Why there's no reply; [failedId] is the message it was for (Try again answers it). */
    val error: String? = null,
    val failedId: Long? = null,
    val failedText: String? = null,
) {
    val busy: Boolean get() = sending != null
}

/**
 * Runs chat turns for the screen, a shared text and a reply typed into a notification. It lives in the app, not the
 * screen, so a turn keeps going if you leave the chat; turns run one at a time, in order.
 */
class Chat(private val scope: CoroutineScope, private val agent: () -> Agent) {
    private val _turn = MutableStateFlow(ChatTurn())
    val turn: StateFlow<ChatTurn> = _turn.asStateFlow()
    private val lock = Mutex()

    /** Send from the screen, in the background. False when a turn is already running (one at a time). */
    fun send(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || _turn.value.busy) return false
        _turn.value = ChatTurn(sending = t)
        scope.launch { run(t, retryOf = null) }
        return true
    }

    /** Ask again for a reply to a message that didn't get one (without saving it twice). */
    fun retry(): Boolean {
        val cur = _turn.value
        val id = cur.failedId ?: return false
        val text = cur.failedText ?: return false
        if (cur.busy) return false
        _turn.value = ChatTurn(sending = text, sentId = id)
        scope.launch { run(text, retryOf = id) }
        return true
    }

    /** Send and wait for the reply (a notification reply, a share). Returns it, or null when there was none. */
    suspend fun sendAndWait(text: String): ChatResult? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return run(t, retryOf = null)
    }

    fun dismissError() = _turn.update { if (it.busy) it else ChatTurn() }

    private suspend fun run(text: String, retryOf: Long?): ChatResult? = lock.withLock {
        _turn.value = ChatTurn(sending = text, sentId = retryOf)
        var saved: Long? = retryOf
        try {
            val result = agent().handle(text, retryOf) { p ->
                when (p) {
                    is ChatProgress.Received -> {
                        saved = p.userMessageId
                        _turn.update { it.copy(sentId = p.userMessageId) }
                    }
                    ChatProgress.Thinking -> _turn.update { it.copy(typing = true) }
                    is ChatProgress.Tool -> _turn.update { it.copy(typing = false, steps = it.steps + toolLabel(p.status)) }
                }
            }
            _turn.value = ChatTurn()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmUnavailable) {
            Diag.log("llm", "Chat: no reply (${e.message?.lineSequence()?.firstOrNull()})")
            _turn.value = ChatTurn(error = e.message ?: "The assistant is unavailable right now.", failedId = saved, failedText = text)
            null
        } catch (e: Exception) {
            Diag.log("llm", "Chat turn failed: ${e::class.java.simpleName}: ${e.message}")
            _turn.value = ChatTurn(error = "Something went wrong. Try again.", failedId = saved, failedText = text)
            null
        }
    }

    companion object {
        /** The status line for a tool call (the web app's TOOL_LABEL). */
        fun toolLabel(t: ToolStatus): String {
            val (what, which) = when (t.name) {
                "add_task" -> (if (t.kind == "meeting") "📅 Saving a new meeting" else "📝 Writing a new task") to t.title
                "update_setting" -> "⚙️ Changing a setting" to null
                "remember_preference" -> "🧠 Remembering that" to null
                "list_tasks" -> "🔎 Checking your tasks" to null
                "update_task" -> "✏️ Updating task" to (t.taskTitle ?: t.title)
                "complete_task" -> "✅ Marking as done" to t.taskTitle
                "cancel_task" -> "🗑 Cancelling task" to t.taskTitle
                "schedule_followup" -> "⏰ Scheduling a follow-up" to t.taskTitle
                "add_subtask" -> "➕ Adding a step" to t.title
                "log_interaction" -> "🗒 Making a note" to t.taskTitle
                "mark_blocked" -> "⛔ Marking as blocked" to t.taskTitle
                "set_focus" -> "🎯 Setting Today's 3" to null
                else -> "⚙️ Working" to null
            }
            return if (which != null) "$what: $which" else what
        }
    }
}
