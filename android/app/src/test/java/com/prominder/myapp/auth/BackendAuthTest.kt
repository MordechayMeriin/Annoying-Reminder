package com.prominder.myapp.auth

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackendAuthTest {
    private val server = MockWebServer().apply { start() }
    private val auth = BackendAuth(server.url("/").toString())

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `sign in sends Google's token and returns the server's login`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"ours","expiresAt":1234567890,"email":"me@example.com"}"""))
        val session = auth.signIn("google-id-token")
        assertEquals(Session("ours", 1_234_567_890, "me@example.com"), session)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/auth/google", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("google-id-token", body["idToken"]!!.jsonPrimitive.content)
    }

    @Test
    fun `someone else's account shows the server's explanation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Only the owner's Google account can use this app. Choose me@example.com instead."}"""))
        val e = expectError { auth.signIn("t") }
        assertEquals("Only the owner's Google account can use this app. Choose me@example.com instead.", e.message)
    }

    @Test
    fun `a sleeping server is a network problem, not a refusal`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        val e = expectError { auth.signIn("t") }
        assertEquals(AuthException.Kind.Network, e.kind)
    }

    @Test
    fun `an unreadable answer says so`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"ours"}"""))
        val e = expectError { auth.signIn("t") }
        assertEquals("The server sent an answer the app doesn't understand.", e.message)
    }

    @Test
    fun `no server is a network error`() = runTest {
        server.shutdown()
        val e = expectError { auth.signIn("t") }
        assertEquals(AuthException.Kind.Network, e.kind)
    }

    @Test
    fun `a connection cut off by a filter is told apart from a certificate problem`() {
        val cut = BackendAuth.networkError(SSLHandshakeException("connection closed")).message!!
        assertTrue(cut, cut.contains("cut off"))
        assertTrue(cut, cut.contains("(SSLHandshakeException: connection closed)"))

        val untrusted = SSLHandshakeException("handshake failed").apply {
            initCause(CertPathValidatorException("Trust anchor for certification path not found."))
        }
        assertTrue(BackendAuth.networkError(untrusted).message!!.contains("certificate"))
    }

    @Test
    fun `other network errors get their own advice`() {
        assertTrue(BackendAuth.networkError(UnknownHostException("x")).message!!.contains("Can't find the server"))
        assertTrue(BackendAuth.networkError(SocketTimeoutException("timeout")).message!!.contains("wake"))
        assertTrue(BackendAuth.networkError(IOException("boom")).message!!.contains("Can't reach the server"))
    }

    private suspend fun expectError(block: suspend () -> Unit): AuthException {
        try {
            block()
        } catch (e: AuthException) {
            return e
        }
        fail("expected an AuthException")
        throw AssertionError()
    }
}
