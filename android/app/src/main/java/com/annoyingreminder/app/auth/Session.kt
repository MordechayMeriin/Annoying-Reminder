package com.annoyingreminder.app.auth

import kotlinx.serialization.Serializable

/** A Supabase login: what the app keeps so you stay logged in. */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** When [accessToken] stops working, in seconds since 1970 (UTC). */
    val expiresAt: Long,
    val userId: String,
    val email: String,
) {
    /** True when the access token has expired or will within [marginSeconds]. */
    fun expiresSoon(nowSeconds: Long, marginSeconds: Long = 60): Boolean = expiresAt - marginSeconds <= nowSeconds
}

/** Something went wrong logging in; [message] is written for you, not for a developer. */
class AuthException(message: String, val kind: Kind = Kind.Other) : Exception(message) {
    enum class Kind {
        /** No connection or Supabase didn't answer: try again later, don't log out. */
        Network,
        /** Supabase says the login (refresh token) is no longer valid: log in again. */
        SessionGone,
        Other,
    }
}
