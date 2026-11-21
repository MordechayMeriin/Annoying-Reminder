package com.prominder.myapp.sync

import androidx.test.core.app.ApplicationProvider
import com.prominder.myapp.data.AppDatabase
import com.prominder.myapp.data.OPEN
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskForm
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The backup against a fake backend. restore-sample.json is a real /sync/restore answer, and the backend's own tests
 * (backend/tests/test_sync.py) upload and restore the same file, so both sides are checked against one format.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SyncEngineTest {
    private val server = MockWebServer().apply { start() }
    private var now: Instant = Instant.parse("2026-11-17T10:00:00Z")
    private val db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val store = Store(db, clock = { now })
    private val api = SyncApi(server.url("/").toString(), OkHttpClient()) { "token" }
    private val engine = SyncEngine(db, api, "Galaxy S9", clock = { now })
    private val dao = db.dao()

    private val sample: String = javaClass.classLoader!!.getResource("restore-sample.json")!!.readText()

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun ok(body: String = """{"ok":true,"written":0,"deleted":0,"serverTime":"2026-11-17T10:00:00.000000Z"}""") =
        server.enqueue(MockResponse().setBody(body))

    private fun uploaded(): JsonObject = SyncApi.JSON.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun `the handover downloads everything, as the server stores it`() = runTest {
        server.enqueue(MockResponse().setBody(sample))
        val r = engine.restore(handover = true)
        assertEquals(3, r.tasks)
        assertEquals(2, r.messages)
        val req = server.takeRequest()
        assertEquals("/sync/restore", req.path)
        assertEquals("Bearer token", req.getHeader("Authorization"))
        assertEquals("""{"handover":true,"device":"Galaxy S9","paged":true,"cursor":null}""", req.body.readUtf8())

        val task = dao.task(1)!!
        assertEquals("Write report", task.title)
        assertEquals("2026-11-17T13:00:00.000000Z", task.dueAt)
        assertEquals("2026-11-17", task.focusDate)
        assertEquals(1L, dao.task(2)!!.parentId)
        assertEquals(0L, task.seq) // as it came from the backup: not sent back
        assertTrue(store.hasData())
        assertEquals("08:00", store.settings().raw("morning_time"))
        assertEquals(listOf(1L), store.focusIds(java.time.LocalDate.of(2026, 11, 17)))
        assertEquals(0, engine.pending())
        assertEquals("3", dao.meta(Store.META_TASK_COUNTER))
        assertTrue(dao.meta(Store.META_HANDED_OVER_AT)!!.endsWith("Z"))
    }

    @Test
    fun `a restored row goes back byte for byte`() = runTest {
        server.enqueue(MockResponse().setBody(sample))
        engine.restore(handover = false)
        val original = SyncApi.JSON.parseToJsonElement(sample).jsonObject["tables"]!!.jsonObject
        // Every table, re-encoded from the phone's rows, is exactly what the server sent.
        val t = Tables(
            tasks = dao.changedTasks(-1, 0), events = dao.changedEvents(-1, 0), messages = dao.changedMessages(-1, 0),
            sessions = dao.changedSessions(-1, 0), preferences = dao.changedPreferences(-1, 0),
            settings = dao.changedSettings(-1, 0), focus = dao.changedFocus(-1, 0),
        )
        val encoded = SyncApi.JSON.encodeToJsonElement(Tables.serializer(), t).jsonObject
        for (name in original.keys) {
            assertEquals(name, original[name], encoded[name])
        }
        assertFalse("seq is the phone's own" , "seq" in encoded["tasks"]!!.jsonArray[0].jsonObject)
    }

    @Test
    fun `a phone with data never restores over it`() = runTest {
        store.create(TaskForm("Local"))
        try {
            engine.restore(handover = true)
            fail("expected a SyncException")
        } catch (e: SyncException) {
            assertFalse(e.network)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an upload sends only what changed, once`() = runTest {
        server.enqueue(MockResponse().setBody(sample))
        engine.restore(handover = true)
        server.takeRequest()

        store.complete(1)          // the task, its step, an event and a chat line
        store.saveSetting("morning_time", null)
        assertEquals(5, engine.pending())
        ok()
        val summary = engine.upload()
        assertEquals(4, summary.rows)
        assertEquals(1, summary.deleted)
        val body = uploaded()
        assertEquals("Galaxy S9", body["device"]!!.jsonPrimitive.content)
        val tables = body["tables"]!!.jsonObject
        assertEquals(listOf(1L, 2L), tables["tasks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }.sorted())
        assertEquals("done", tables["tasks"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == "1" }.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(1, tables["events"]!!.jsonArray.size)
        assertEquals("[Marked done: Write report]", tables["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("morning_time"))), body["deletes"]!!.jsonObject["settings"])
        assertEquals(0, engine.pending())

        // Nothing new: no request at all.
        assertEquals(0, engine.upload().rows)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a failed upload sends the same rows next time`() = runTest {
        store.create(TaskForm("A"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"Waking up"}"""))
        try {
            engine.upload()
            fail("expected a SyncException")
        } catch (e: SyncException) {
            assertTrue(e.network)
            assertEquals("Waking up", e.message)
        }
        server.takeRequest()
        assertEquals(1, engine.pending())
        store.create(TaskForm("B"))
        ok()
        engine.upload()
        assertEquals(listOf("A", "B"), uploaded()["tables"]!!.jsonObject["tasks"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content })
        assertEquals(0, engine.pending())
    }

    @Test
    fun `a refused upload says why`() = runTest {
        store.create(TaskForm("A"))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"tasks: missing ['title']"}"""))
        try {
            engine.upload()
            fail("expected a SyncException")
        } catch (e: SyncException) {
            assertFalse(e.network)
            assertEquals("The server refused the data: tasks: missing ['title']", e.message)
        }
    }

    @Test
    fun `with nothing to send, it still tells the server once a day that the phone is alive`() = runTest {
        server.enqueue(MockResponse().setBody(sample))
        engine.restore(handover = true)
        server.takeRequest()
        now = now.plus(Duration.ofHours(19))
        assertEquals(0, engine.upload().rows)
        assertEquals(1, server.requestCount)
        now = now.plus(Duration.ofHours(2))
        ok()
        assertTrue(engine.upload().heartbeat)
        assertEquals(Tables(), SyncApi.JSON.decodeFromJsonElement(Tables.serializer(), uploaded()["tables"]!!))
    }

    @Test
    fun `a restore in several pages saves every page`() = runTest {
        val whole = SyncApi.JSON.parseToJsonElement(sample).jsonObject
        val tables = whole["tables"]!!.jsonObject
        fun page(keep: Set<String>, next: String?, counters: Boolean): String {
            val part = JsonObject(tables.mapValues { (name, rows) -> if (name in keep) rows else JsonArray(emptyList()) })
            val body = whole.toMutableMap().apply {
                put("tables", part)
                put("next", kotlinx.serialization.json.JsonPrimitive(next))
                if (!counters) put("counters", JsonObject(emptyMap()))
            }
            return JsonObject(body).toString()
        }
        server.enqueue(MockResponse().setBody(page(setOf("tasks", "events"), "c1", counters = false)))
        server.enqueue(MockResponse().setBody(page(tables.keys - setOf("tasks", "events"), null, counters = true)))
        val r = engine.restore(handover = true)
        assertEquals(3, r.tasks)
        assertEquals(2, r.messages)
        server.takeRequest()
        assertEquals("""{"handover":true,"device":"Galaxy S9","paged":true,"cursor":"c1"}""", server.takeRequest().body.readUtf8())
        assertEquals("3", dao.meta(Store.META_TASK_COUNTER))
        assertEquals(2, dao.messagesForTask(1, 10).size)   // the message ↔ task links come with the restore
    }

    @Test
    fun `a failed second page leaves the phone empty`() = runTest {
        server.enqueue(MockResponse().setBody(sample.replaceFirst("{", """{"next":"c1",""")))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"busy"}"""))
        try {
            engine.restore(handover = true)
            fail("expected a SyncException")
        } catch (e: SyncException) {
            assertTrue(e.network)
        }
        assertFalse(store.hasData())
    }

    @Test
    fun `a setting set, reset and set again goes as one row and no delete`() = runTest {
        store.saveSetting("morning_time", "07:30")
        store.saveSetting("morning_time", null)
        store.saveSetting("morning_time", "07:45")
        ok()
        val summary = engine.upload()
        assertEquals(1, summary.rows)
        assertEquals(0, summary.deleted)
        val body = uploaded()
        assertEquals("07:45", body["tables"]!!.jsonObject["settings"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), body["deletes"])
    }

    @Test
    fun `a change that wrote nothing doesn't use up a change number`() = runTest {
        store.create(TaskForm("A"))
        val seq = dao.meta(Store.META_SEQ)
        store.complete(999)                // no such task
        store.deletePreference(999)        // no such preference
        assertEquals(seq, dao.meta(Store.META_SEQ))
        ok()
        engine.upload()
        server.takeRequest()
        store.complete(999)
        assertEquals(0, engine.upload().rows)   // nothing new: no request
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a backup that fails part-way continues where it stopped`() = runTest {
        repeat(250) { store.create(TaskForm("t$it")) }   // 250 changes: two requests
        ok()
        server.enqueue(MockResponse().setResponseCode(504).setBody("""{"detail":"timed out"}"""))
        try {
            engine.upload()
            fail("expected a SyncException")
        } catch (e: SyncException) {
            assertTrue(e.network)
        }
        assertEquals(200, uploaded()["tables"]!!.jsonObject["tasks"]!!.jsonArray.size)
        server.takeRequest()
        assertEquals(50, engine.pending())   // the first request's 200 are kept as sent
        ok()
        engine.upload()
        assertEquals(50, uploaded()["tables"]!!.jsonObject["tasks"]!!.jsonArray.size)
        assertEquals(0, engine.pending())
    }

    @Test
    fun `large changes go in several requests, cut between change numbers`() {
        fun task(id: Long, seq: Long) = SyncEngine.Change.Row(seq, com.prominder.myapp.data.TaskRow(id, title = "t$id", createdAt = "2026-11-17T10:00:00.000000Z"))
        // seq 1..300 with one row each, then seq 301 with 450 rows (one change larger than a request).
        val changes = (1..300L).map { task(it, it) } + (301..750L).map { task(it, 301) } +
            listOf(SyncEngine.Change.Delete(5, "settings", kotlinx.serialization.json.JsonPrimitive("morning_time")))
        val batches = SyncEngine.batches(changes.shuffled(), after = 0, size = 200)
        assertEquals(listOf(200, 101, 200, 200, 50), batches.map { it.size })
        assertEquals(listOf(199L, 300L, 300L, 300L, 301L), batches.map { it.through })
        assertEquals(1, batches[0].deletes["settings"]!!.size)
        assertNull(batches.firstOrNull { it.size > 200 })
        assertEquals(750, batches.sumOf { it.tables.tasks.size })
        assertEquals(OPEN, batches[0].tables.tasks[0].status)
    }
}
