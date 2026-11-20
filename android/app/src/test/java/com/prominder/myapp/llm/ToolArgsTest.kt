package com.prominder.myapp.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool argument validation and text tool-call parsing. The cases come from test_tools.py (the Python server's tests, removed in b09e82e). */
class ToolArgsTest {
    private val names = setOf("add_task", "schedule_followup", "log_interaction")

    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun validate(name: String, args: String) = ToolArgs.validate(name, json(args), ToolDefs.SCHEMAS[name])
    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test fun `validate coerces forgiving inputs`() {
        val args = validate("schedule_followup",
            """{"task_id": "12", "minutes_from_now": 30.0, "reason": " later ", "requested_by": "user", "at_local_iso": "", "extra": 1}""")
        assertEquals(mapOf("task_id" to 12L, "minutes_from_now" to 30L, "reason" to "later", "requested_by" to "user"), args)
    }

    @Test fun `booleans and enums`() {
        assertEquals(true, validate("update_task", """{"id": 1, "unblock": "true"}""")["unblock"])
        assertTrue(assertThrows(ToolArgError::class.java) { validate("log_interaction", """{"task_id": 1, "kind": "angry", "note": "x"}""") }
            .message!!.contains("one of"))
        assertTrue(assertThrows(ToolArgError::class.java) { validate("complete_task", """{"id": "abc"}""") }.message!!.contains("integer"))
        assertTrue(assertThrows(ToolArgError::class.java) { validate("add_subtask", """{"task_id": 3}""") }.message!!.contains("Missing"))
        assertTrue(assertThrows(ToolArgError::class.java) { validate("delete_everything", "{}") }.message!!.contains("Unknown tool"))
    }

    @Test fun `numbers in enums, whole numbers, booleans as ids`() {
        assertEquals(2L, validate("add_task", """{"title": "x", "priority": "2"}""")["priority"])
        assertThrows(ToolArgError::class.java) { validate("add_task", """{"title": "x", "priority": 5}""") }
        assertThrows(ToolArgError::class.java) { validate("complete_task", """{"id": 1.5}""") }
        assertThrows(ToolArgError::class.java) { validate("complete_task", """{"id": true}""") }
        assertThrows(ToolArgError::class.java) { validate("complete_task", "[1]") }
    }

    @Test fun `a list of ids may come as text`() {
        assertEquals(listOf(3L, 5L, 7L), validate("set_focus", """{"task_ids": "3, 5, 7"}""")["task_ids"])
        assertEquals(listOf(3L, 5L), validate("set_focus", """{"task_ids": "[3,5]"}""")["task_ids"])
        assertEquals(listOf(4L), validate("set_focus", """{"task_ids": 4}""")["task_ids"])
    }

    @Test fun `JSON tool call variants`() {
        assertEquals("add_task" to obj("title" to "x"), ToolArgs.parseTextToolCall("""{"name": "add_task", "arguments": {"title": "x"}}""", names))
        assertEquals("add_task" to obj("title" to "y"),
            ToolArgs.parseTextToolCall("```json\n{\"tool_call\": {\"name\": \"add_task\", \"parameters\": {\"title\": \"y\"}}}\n```", names))
        assertEquals("add_task" to obj("title" to "z"),
            ToolArgs.parseTextToolCall("""{"name": "add_task", "arguments": "{\"title\": \"z\"}"}""", names))
    }

    @Test fun `Python-style tool call`() {
        val text = "Sure.\n```tool_code\nprint(default_api.schedule_followup(task_id=4, minutes_from_now=90, reason=\"after lunch\"))\n```"
        val (name, args) = ToolArgs.parseTextToolCall(text, names)!!
        assertEquals("schedule_followup", name)
        assertEquals(json("""{"task_id": 4, "minutes_from_now": 90, "reason": "after lunch"}"""), args)
        assertEquals(json("""{"title": "it's", "x": [1, 2], "y": true}"""),
            ToolArgs.parseTextToolCall("add_task(title='it\\'s', x=[1, 2], y=True)", names)!!.second)
    }

    @Test fun `plain text and unknown tools are ignored`() {
        assertNull(ToolArgs.parseTextToolCall("How is the report going? {just braces}", names))
        assertNull(ToolArgs.parseTextToolCall("""{"name": "rm_rf", "arguments": {}}""", names))
        assertNull(ToolArgs.parseTextToolCall("", names))
        assertNull(ToolArgs.parseTextToolCall("add_task(1, 2)", names))
    }

    @Test fun `every chat tool has a schema the server's tests know`() {
        assertEquals(
            listOf("add_task", "list_tasks", "update_task", "complete_task", "cancel_task", "schedule_followup", "add_subtask",
                "log_interaction", "mark_blocked", "set_focus", "update_setting", "remember_preference"),
            ToolDefs.CHAT_TOOLS.map(ToolDefs::name),
        )
        assertEquals(listOf("set_focus", "list_tasks"), ToolDefs.MORNING_TOOLS.map(ToolDefs::name))
        assertEquals(listOf("schedule_followup"), ToolDefs.COACH_TOOLS.map(ToolDefs::name))
    }
}
