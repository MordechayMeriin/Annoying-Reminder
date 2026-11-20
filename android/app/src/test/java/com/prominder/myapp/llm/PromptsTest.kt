package com.prominder.myapp.llm

import com.prominder.myapp.logic.Coaching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompt rendering (Prompts.kt) on the real prompt files in backend/app/prompts/. */
class PromptsTest {
    private val prompts = testPrompts()

    @Test fun `every prompt and playbook the app uses exists`() {
        Prompts.NAMES.forEach { assertTrue(it, prompts.load(it).isNotBlank()) }
        Coaching.PLAYBOOKS.forEach { assertTrue(it, prompts.load("playbooks/$it").isNotBlank()) }
    }

    @Test fun `placeholders are filled, unknown braces stay`() {
        val p = Prompts { name -> mapOf("core" to "CORE", "x" to "A {core} B {chips} C {missing}\n\n\n\n{playbooks}{preferences}D")[name] }
        assertEquals("A CORE B Done · Later C {missing}\n\nD", p.render("x", chips = listOf("Done", "Later")))
        assertEquals("A CORE B (none) C {missing}\n\nD", p.render("x"))
    }

    @Test fun `the system prompt carries the core, playbooks, chips and preferences`() {
        val text = prompts.render("system", listOf("stuck"), listOf("▶ Start 5 min", "✓ Done"), listOf("Shorter messages"),
            mapOf("now" to "2026-11-09 10:00", "weekday" to "Monday", "tz" to "Asia/Jerusalem", "likely_topic" to "TOPIC"))
        assertTrue(text.contains(prompts.load("core")))
        assertTrue(text.contains(prompts.load("playbooks/stuck")))
        assertTrue(text.contains("▶ Start 5 min · ✓ Done"))
        assertTrue(text.contains("# What the user asked of you (always follow these)\n- Shorter messages"))
        assertTrue(text.contains("2026-11-09 10:00 (Monday)") && text.contains("TOPIC"))
        assertFalse(text.contains("{core}") || text.contains("{playbooks}") || text.contains("\n\n\n"))
    }
}
