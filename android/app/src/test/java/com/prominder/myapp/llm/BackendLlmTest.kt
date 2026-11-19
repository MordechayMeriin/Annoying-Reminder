package com.prominder.myapp.llm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The model through the backend's POST /llm (backend/app/llm_proxy.py). */
class BackendLlmTest {
    private val server = MockWebServer().apply { start() }
    private var token: String? = "login"
    private val llm = BackendLlm(server.url("/").toString(), OkHttpClient()) { token }
    private val ask = listOf(message("user", "hi"))

    @After
    fun tearDown() = server.shutdown()

    private suspend fun fails(): LlmUnavailable {
        try {
            llm.complete(ask, emptyList(), 0.3, 5.0)
        } catch (e: LlmUnavailable) {
            return e
        }
        fail("no error")
        throw AssertionError()
    }

    @Test fun `one call sends the messages and tools with the login, and reads the answer`() = runTest {
        server.enqueue(MockResponse().setBody("""{"message": {"role": "assistant", "content": "Hello!"}, "model": "google/gemini-2.5-flash"}"""))
        val tools = ToolDefs.MORNING_TOOLS
        val r = llm.complete(ask, tools, 0.5, 20.0)
        assertEquals("Hello!", r.content)
        assertEquals("google/gemini-2.5-flash", r.model)
        assertTrue(r.toolCalls.isEmpty())
        val req = server.takeRequest()
        assertEquals("/llm", req.path)
        assertEquals("Bearer login", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(1, (body["messages"] as JsonArray).size)
        assertEquals(2, (body["tools"] as JsonArray).size)
        assertEquals("0.5", body["temperature"].toString())
        assertEquals("20.0", body["timeout_s"].toString())
    }

    @Test fun `tool calls are read, no tools are sent when there are none`() = runTest {
        server.enqueue(MockResponse().setBody("""{"message": {"role": "assistant", "content": "",
            "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "list_tasks", "arguments": "{\"scope\": \"open\"}"}}]}, "model": null}"""))
        val r = llm.complete(ask, emptyList(), 0.3, 5.0)
        assertEquals(listOf(ToolCall("c1", "list_tasks", JsonPrimitive("{\"scope\": \"open\"}"))), r.toolCalls)
        assertEquals(null, r.model)
        assertFalse("tools" in Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject)
    }

    @Test fun `the daily cap says so`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail": "Daily LLM limit reached. Reminders still work.", "code": "limit"}"""))
        val e = fails()
        assertTrue(e.limit)
        assertEquals("Daily LLM limit reached. Reminders still work.", e.message)
    }

    @Test fun `no answer from the model, an old backend, a refused login`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail": "The assistant is unavailable right now. Try again in a minute.", "code": "unavailable"}"""))
        assertEquals("The assistant is unavailable right now. Try again in a minute.", fails().message)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail": "Not Found"}"""))
        assertTrue(fails().message!!.contains("Deploy the backend"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(fails().message!!.contains("Log out and in again"))
    }

    @Test fun `no internet is unavailable, not a crash`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(fails().message!!.isNotEmpty())
    }

    /** A model call is silent for longer than OkHttp's 10-second read timeout: the call's own limit applies. */
    @Test fun `a slow answer isn't cut off by the app's short read timeout`() = runTest {
        val short = OkHttpClient.Builder().readTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
        val slow = BackendLlm(server.url("/").toString(), short) { "login" }
        server.enqueue(MockResponse().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS)
            .setBody("""{"message": {"role": "assistant", "content": "Thought about it."}, "model": "m"}"""))
        assertEquals("Thought about it.", slow.complete(ask, emptyList(), 0.3, 5.0).content)
    }

    @Test fun `too slow even for the call's limit says the assistant took too long`() = runTest {
        server.enqueue(MockResponse().setHeadersDelay(30, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
        val quick = BackendLlm(server.url("/").toString(), OkHttpClient()) { "login" }
        val e = try { quick.complete(ask, emptyList(), 0.3, -19.0); null } catch (e: LlmUnavailable) { e }
        assertEquals("The assistant took too long to answer. Try again.", e?.message)
    }

    @Test fun `logged out never calls`() = runTest {
        token = null
        assertTrue(fails().message!!.contains("logged out"))
        assertEquals(0, server.requestCount)
    }
}
