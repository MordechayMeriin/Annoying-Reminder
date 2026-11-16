package com.prominder.myapp.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CognitoAuthTest {
    private val server = MockWebServer().apply { start() }
    private val auth = CognitoAuth("us-west-2", "client-1", nowSeconds = { 1_000 }, endpoint = server.url("/").toString())

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `send code asks for an email code`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ChallengeName":"EMAIL_OTP","Session":"s1"}"""))
        auth.sendCode("  Me@Example.com ")
        val req = server.takeRequest()
        assertEquals("AWSCognitoIdentityProviderService.InitiateAuth", req.getHeader("X-Amz-Target"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/x-amz-json-1.1"))
        val body = json(req)
        assertEquals("USER_AUTH", body["AuthFlow"]!!.jsonPrimitive.content)
        assertEquals("client-1", body["ClientId"]!!.jsonPrimitive.content)
        val params = body["AuthParameters"]!!.jsonObject
        assertEquals("me@example.com", params["USERNAME"]!!.jsonPrimitive.content)
        assertEquals("EMAIL_OTP", params["PREFERRED_CHALLENGE"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a choice of challenges picks the email code`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ChallengeName":"SELECT_CHALLENGE","Session":"s0"}"""))
        server.enqueue(MockResponse().setBody("""{"ChallengeName":"EMAIL_OTP","Session":"s1"}"""))
        auth.sendCode("me@example.com")
        server.takeRequest()
        val answer = json(server.takeRequest())
        assertEquals("EMAIL_OTP", answer["ChallengeResponses"]!!.jsonObject["ANSWER"]!!.jsonPrimitive.content)
        assertEquals("s0", answer["Session"]!!.jsonPrimitive.content)
    }

    @Test
    fun `verify code returns the session`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ChallengeName":"EMAIL_OTP","Session":"s1"}"""))
        auth.sendCode("me@example.com")
        server.enqueue(MockResponse().setBody("""{"AuthenticationResult":{"AccessToken":"${jwt("user-1")}","RefreshToken":"ref","ExpiresIn":3600}}"""))
        val s = auth.verifyCode("ME@example.com", " 123 456 ")
        server.takeRequest()
        val body = json(server.takeRequest())
        assertEquals("s1", body["Session"]!!.jsonPrimitive.content)
        assertEquals("123456", body["ChallengeResponses"]!!.jsonObject["EMAIL_OTP_CODE"]!!.jsonPrimitive.content)
        assertEquals(Session(jwt("user-1"), "ref", 4_600, "user-1", "me@example.com"), s)
    }

    @Test
    fun `verify without a code sent asks for one`() = runTest {
        assertMessage("Send a new code") { auth.verifyCode("me@example.com", "1") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refresh keeps the refresh token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"AuthenticationResult":{"AccessToken":"${jwt("u")}","ExpiresIn":3600}}"""))
        val s = auth.refresh("r")
        val body = json(server.takeRequest())
        assertEquals("REFRESH_TOKEN_AUTH", body["AuthFlow"]!!.jsonPrimitive.content)
        assertEquals("r", body["AuthParameters"]!!.jsonObject["REFRESH_TOKEN"]!!.jsonPrimitive.content)
        assertEquals(Session(jwt("u"), "r", 4_600, "u", ""), s)
    }

    @Test
    fun `wrong code`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ChallengeName":"EMAIL_OTP","Session":"s1"}"""))
        auth.sendCode("me@example.com")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"__type":"CodeMismatchException","message":"Invalid code received for user"}"""))
        assertMessage("wrong or has expired") { auth.verifyCode("me@example.com", "1") }
    }

    @Test
    fun `an expired login attempt asks for a new code`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ChallengeName":"EMAIL_OTP","Session":"s1"}"""))
        auth.sendCode("me@example.com")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"__type":"NotAuthorizedException","message":"Invalid session for the user, session is expired."}"""))
        assertMessage("Send a new code") { auth.verifyCode("me@example.com", "1") }
    }

    @Test
    fun `too many tries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"__type":"LimitExceededException","message":"Attempt limit exceeded"}"""))
        assertMessage("Too many tries") { auth.sendCode("x@example.com") }
    }

    @Test
    fun `a dead refresh token means log in again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"__type":"NotAuthorizedException","message":"Refresh Token has been revoked"}"""))
        assertEquals(AuthException.Kind.SessionGone, expectAuthError { auth.refresh("old") }.kind)
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
    fun `a refused certificate says so, so a filter's missing certificate is easy to spot`() {
        val e = CognitoAuth.networkError(javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found."))
        assertEquals(AuthException.Kind.Network, e.kind)
        assertTrue(e.message!!.contains("certificate"))
        assertTrue(e.message!!.contains("SSLHandshakeException"))
    }

    @Test
    fun `an unknown address says the server can't be found`() {
        val e = CognitoAuth.networkError(java.net.UnknownHostException("cognito-idp.us-west-2.amazonaws.com"))
        assertEquals(AuthException.Kind.Network, e.kind)
        assertTrue(e.message!!.contains("Can't find"))
    }

    @Test
    fun `an unconfigured build says so`() = runTest {
        val e = expectAuthError { CognitoAuth("", "").sendCode("x@example.com") }
        assertTrue(e.message!!.contains("no login settings"))
    }

    private fun json(req: RecordedRequest) = Json.parseToJsonElement(req.body.readUtf8()).jsonObject

    private fun jwt(sub: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString("""{"sub":"$sub"}""".toByteArray())}.sig"
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
}
