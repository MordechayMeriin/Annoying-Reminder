package com.prominder.myapp.llm

import androidx.test.core.app.ApplicationProvider
import com.prominder.myapp.data.AppDatabase
import com.prominder.myapp.data.Store
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat controller: the delivery line, the steps, no reply → Try again without saving your message twice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChatTest {
    private val db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val store = Store(db, clock = { Instant.parse("2026-11-09T08:00:00Z") })
    private var llm: Llm = ScriptedLlm(emptyList())
    private val chat = Chat(CoroutineScope(Dispatchers.Default)) { Agent(store, llm, testPrompts()) }

    @After
    fun tearDown() = db.close()

    private fun waitIdle() = runBlocking { withTimeout(10_000) { while (chat.turn.value.busy) kotlinx.coroutines.delay(10) } }

    @Test fun `a turn, then no reply, then try again`() {
        llm = ScriptedLlm(listOf(calls("add_task", "title" to "Gym"), says("Saved: gym.")))
        assertTrue(chat.send("gym at 6"))
        waitIdle()
        assertEquals(ChatTurn(), chat.turn.value)

        llm = ScriptedLlm(listOf(Fails(LlmUnavailable("The assistant is unavailable right now."))))
        assertTrue(chat.send("dentist tomorrow"))
        waitIdle()
        val failed = chat.turn.value
        assertEquals("The assistant is unavailable right now.", failed.error)
        assertEquals("dentist tomorrow", failed.failedText)

        llm = ScriptedLlm(listOf(says("Saved: dentist.")))
        assertTrue(chat.retry())
        waitIdle()
        assertNull(chat.turn.value.error)
        val texts = runBlocking { db.dao().messagesSince("") }.sortedBy { it.id }.map { it.content }
        assertEquals(listOf("gym at 6", "Saved: gym.", "dentist tomorrow", "Saved: dentist."), texts)
    }

    @Test fun `empty text isn't sent`() {
        assertFalse(chat.send("   "))
        assertFalse(chat.retry())
    }
}
