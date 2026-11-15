package com.annoyingreminder.app.auth

import android.content.Context
import android.content.SharedPreferences
import com.annoyingreminder.app.diag.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Where the login is kept: the app's private storage (not backed up, see allowBackup="false"). */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session?)
}

class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    override fun load(): Session? =
        prefs.getString(KEY, null)?.let { runCatching { Json.decodeFromString<Session>(it) }.getOrNull() }

    override fun save(session: Session?) {
        prefs.edit().apply {
            if (session == null) remove(KEY) else putString(KEY, Json.encodeToString(Session.serializer(), session))
        }.apply()
    }

    private companion object { const val KEY = "session" }
}

/**
 * Who's logged in. The phone has to work offline, so a saved login counts as logged in even when
 * Supabase can't be reached; only Supabase saying "this login is gone" logs you out.
 */
class AuthRepository(
    private val api: SupabaseAuth,
    private val store: SessionStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _session = MutableStateFlow(store.load())
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val refreshLock = Mutex()

    val isConfigured: Boolean get() = api.isConfigured

    suspend fun sendCode(email: String) {
        api.sendCode(email)
        Diag.log("auth", "Login code sent")
    }

    suspend fun verifyCode(email: String, code: String) {
        val s = api.verifyCode(email, code)
        store.save(s)
        _session.value = s
        Diag.log("auth", "Logged in")
    }

    /**
     * An access token that's good for at least another minute, refreshing it first if needed.
     * Null when logged out. Throws [AuthException] (Network) when a refresh is needed and fails.
     */
    suspend fun accessToken(): String? = refreshLock.withLock {
        val current = _session.value ?: return null
        if (!current.expiresSoon(nowSeconds())) return current.accessToken
        try {
            val fresh = api.refresh(current.refreshToken)
            // Supabase doesn't always repeat the email; keep the one we know.
            val merged = fresh.copy(email = fresh.email.ifBlank { current.email })
            store.save(merged)
            _session.value = merged
            Diag.log("auth", "Login refreshed")
            merged.accessToken
        } catch (e: AuthException) {
            Diag.log("auth", "Refresh failed: ${e.message}")
            if (e.kind == AuthException.Kind.SessionGone) signOutLocally()
            throw e
        }
    }

    suspend fun logout() {
        val current = _session.value
        signOutLocally()
        if (current != null) api.logout(current.accessToken)
    }

    private fun signOutLocally() {
        store.save(null)
        _session.value = null
        Diag.log("auth", "Logged out")
    }
}
