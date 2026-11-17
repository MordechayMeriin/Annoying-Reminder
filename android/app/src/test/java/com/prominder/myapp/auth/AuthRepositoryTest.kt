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
    private val api = BackendAuth(server.url("/").toString())

    private class MemoryStore(var saved: Session? = null) : SessionStore {
        override fun load() = saved
        override fun save(session: Session?) { saved = session }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a saved login counts as logged in, offline too`() {
        val repo = AuthRepository(api, MemoryStore(Session("t", 5_000, "me@example.com")), nowSeconds = { now })
        assertEquals("me@example.com", repo.session.value?.email)
        assertEquals("t", repo.token())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an expired saved login doesn't count`() {
        val repo = AuthRepository(api, MemoryStore(Session("t", 900, "e")), nowSeconds = { now })
        assertNull(repo.session.value)
    }

    @Test
    fun `a login that expires while the app runs logs out`() {
        val store = MemoryStore(Session("t", 2_000, "e"))
        val repo = AuthRepository(api, store, nowSeconds = { now })
        now = 2_000
        assertNull(repo.token())
        assertNull(repo.session.value)
        assertNull(store.saved)
    }

    @Test
    fun `sign in keeps the server's login, logout forgets it`() = runTest {
        val store = MemoryStore()
        val repo = AuthRepository(api, store, nowSeconds = { now })
        server.enqueue(MockResponse().setBody("""{"token":"ours","expiresAt":99999,"email":"me@example.com"}"""))
        repo.signIn("google-id-token")
        assertEquals(Session("ours", 99_999, "me@example.com"), store.saved)
        assertEquals("ours", repo.token())
        repo.logout()
        assertNull(repo.session.value)
        assertNull(store.saved)
    }

    @Test
    fun `a refused sign in stays logged out`() = runTest {
        val store = MemoryStore()
        val repo = AuthRepository(api, store, nowSeconds = { now })
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Only the owner's Google account can use this app."}"""))
        try {
            repo.signIn("google-id-token")
            fail("expected an AuthException")
        } catch (e: AuthException) {
            assertEquals("Only the owner's Google account can use this app.", e.message)
        }
        assertNull(repo.session.value)
        assertNull(store.saved)
    }
}
