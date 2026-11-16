package com.prominder.myapp.auth

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AuthRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private var now = 1_000L
    private val api = CognitoAuth("us-west-2", "client", nowSeconds = { now }, endpoint = server.url("/").toString())

    private class MemoryStore(var saved: Session? = null) : SessionStore {
        override fun load() = saved
        override fun save(session: Session?) { saved = session }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a saved login counts as logged in, offline too`() {
        val store = MemoryStore(Session("a", "r", 5_000, "u", "me@example.com"))
        val repo = AuthRepository(api, store, nowSeconds = { now })
        assertEquals("me@example.com", repo.session.value?.email)
    }

    @Test
    fun `a fresh token is used as is`() = runTest {
        val repo = AuthRepository(api, MemoryStore(Session("a", "r", 5_000, "u", "e")), nowSeconds = { now })
        assertEquals("a", repo.accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an expiring token is refreshed and saved, keeping the email`() = runTest {
        val store = MemoryStore(Session("old", "r1", 1_030, "u", "me@example.com"))
        val repo = AuthRepository(api, store, nowSeconds = { now })
        val token = jwt("u")
        server.enqueue(MockResponse().setBody("""{"AuthenticationResult":{"AccessToken":"$token","ExpiresIn":3600}}"""))
        assertEquals(token, repo.accessToken())
        assertEquals(Session(token, "r1", 4_600, "u", "me@example.com"), store.saved)
    }

    @Test
    fun `refresh rejected by Cognito logs out`() = runTest {
        val store = MemoryStore(Session("old", "r1", 900, "u", "e"))
        val repo = AuthRepository(api, store, nowSeconds = { now })
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"__type":"NotAuthorizedException","message":"Refresh Token has been revoked"}"""))
        expectError { repo.accessToken() }
        assertNull(repo.session.value)
        assertNull(store.saved)
    }

    @Test
    fun `refresh failing for lack of internet keeps the login`() = runTest {
        val saved = Session("old", "r1", 900, "u", "e")
        val store = MemoryStore(saved)
        val repo = AuthRepository(api, store, nowSeconds = { now })
        server.shutdown()
        expectError { repo.accessToken() }
        assertEquals(saved, repo.session.value)
        assertEquals(saved, store.saved)
    }

    @Test
    fun `verify logs in and logout forgets`() = runTest {
        val store = MemoryStore()
        val repo = AuthRepository(api, store, nowSeconds = { now })
        server.enqueue(MockResponse().setBody("""{"ChallengeName":"EMAIL_OTP","Session":"s1"}"""))
        repo.sendCode("me@example.com")
        server.enqueue(MockResponse().setBody("""{"AuthenticationResult":{"AccessToken":"${jwt("u")}","RefreshToken":"r","ExpiresIn":3600}}"""))
        repo.verifyCode("me@example.com", "123456")
        assertEquals("u", repo.session.value?.userId)
        assertEquals("me@example.com", repo.session.value?.email)
        server.enqueue(MockResponse().setBody("{}"))
        repo.logout()
        server.takeRequest(); server.takeRequest()
        assertEquals("AWSCognitoIdentityProviderService.RevokeToken", server.takeRequest().getHeader("X-Amz-Target"))
        assertNull(repo.session.value)
        assertNull(store.saved)
    }

    @Test
    fun `logout works offline`() = runTest {
        val store = MemoryStore(Session("a", "r", 5_000, "u", "e"))
        val repo = AuthRepository(api, store, nowSeconds = { now })
        server.shutdown()
        repo.logout()
        assertNull(repo.session.value)
    }

    private fun jwt(sub: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString("""{"sub":"$sub"}""".toByteArray())}.sig"
    }

    private suspend fun expectError(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: AuthException) {
            return
        }
        fail("expected an AuthException")
    }
}
