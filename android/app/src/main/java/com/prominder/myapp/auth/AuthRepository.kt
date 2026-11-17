package com.prominder.myapp.auth

import android.content.Context
import android.content.SharedPreferences
import com.prominder.myapp.diag.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Who's logged in. The login is checked by the server when you sign in; after that it's a saved token,
 * so the phone counts as logged in offline too, until the token expires (a year) or you log out.
 */
class AuthRepository(
    private val api: BackendAuth,
    private val store: SessionStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _session = MutableStateFlow(store.load()?.takeUnless { it.expired(nowSeconds()) })
    val session: StateFlow<Session?> = _session.asStateFlow()

    val isConfigured: Boolean get() = api.isConfigured

    /** Trades Google's ID token for the server's login and keeps it. Throws [AuthException]. */
    suspend fun signIn(googleIdToken: String) {
        val s = api.signIn(googleIdToken)
        store.save(s)
        _session.value = s
        Diag.log("auth", "Logged in with Google")
    }

    /** The login token for calls to the server; null when logged out. An expired login logs out. */
    fun token(): String? {
        val current = _session.value ?: return null
        if (current.expired(nowSeconds())) {
            Diag.log("auth", "Login expired")
            logout()
            return null
        }
        return current.token
    }

    fun logout() {
        store.save(null)
        _session.value = null
        Diag.log("auth", "Logged out")
    }
}
