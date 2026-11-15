package com.annoyingreminder.app.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SupabaseAuthTest {
    private val server = MockWebServer().apply { start() }
    private val auth = SupabaseAuth(server.url("/").toString(), "anon-key", nowSeconds = { 1_000 })

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `send code never creates an account`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        auth.sendCode("  me@example.com ")
        val req = server.takeRequest()
        assertEquals("/auth/v1/otp", req.path)
        assertEquals("anon-key", req.getHeader("apikey"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("me@example.com", body["email"]!!.jsonPrimitive.content)
        assertFalse(body["create_user"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `verify code returns the session`() = runTest {
        server.enqueue(MockResponse().setBody(TOKEN_JSON))
        val s = auth.verifyCode("me@example.com", " 123 456 ")
        val req = server.takeRequest()
        assertEquals("/auth/v1/verify", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("123456", body["token"]!!.jsonPrimitive.content)
        assertEquals("email", body["type"]!!.jsonPrimitive.content)
        assertEquals(Session("acc", "ref", 4_600, "user-1", "me@example.com"), s)
    }

    @Test
    fun `expires_in is used when expires_at is missing`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u"}}"""))
        val s = auth.refresh("r")
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals(4_600, s.expiresAt)
        assertEquals("", s.email)
    }

    @Test
    fun `unknown email gets a clear message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"code":422,"error_code":"otp_disabled","msg":"Signups not allowed for otp"}"""))
        assertMessage("no account") { auth.sendCode("x@example.com") }
    }

    @Test
    fun `wrong or expired code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"error_code":"otp_expired","msg":"Token has expired or is invalid"}"""))
        assertMessage("wrong or has expired") { auth.verifyCode("x@example.com", "1") }
    }

    @Test
    fun `too many tries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":429,"error_code":"over_email_send_rate_limit","msg":"email rate limit exceeded"}"""))
        assertMessage("Too many tries") { auth.sendCode("x@example.com") }
    }

    @Test
    fun `a dead refresh token means log in again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token: Refresh Token Not Found"}"""))
        val e = expectAuthError { auth.refresh("old") }
        assertEquals(AuthException.Kind.SessionGone, e.kind)
    }

    @Test
    fun `server trouble is a network problem, not a logout`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("oops"))
        assertEquals(AuthException.Kind.Network, expectAuthError { auth.refresh("r") }.kind)
    }

    @Test
    fun `no connection is a network problem`() = runTest {
        server.shutdown()
        assertEquals(AuthException.Kind.Network, expectAuthError { auth.sendCode("x@example.com") }.kind)
    }

    @Test
    fun `an unconfigured build says so`() = runTest {
        val e = expectAuthError { SupabaseAuth("", "").sendCode("x@example.com") }
        assertTrue(e.message!!.contains("no Supabase settings"))
    }

    private suspend fun assertMessage(part: String, block: suspend () -> Unit) {
        val e = expectAuthError(block)
        assertTrue("'${e.message}' should mention '$part'", e.message!!.contains(part))
    }

    private suspend fun expectAuthError(block: suspend () -> Unit): AuthException {
        try {
            block()
        } catch (e: AuthException) {
            return e
        }
        fail("expected an AuthException")
        throw AssertionError()
    }

    private companion object {
        const val TOKEN_JSON = """{"access_token":"acc","token_type":"bearer","expires_in":3600,"expires_at":4600,
            "refresh_token":"ref","user":{"id":"user-1","email":"me@example.com","aud":"authenticated"}}"""
    }
}
