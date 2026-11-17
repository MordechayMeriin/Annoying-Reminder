package com.prominder.myapp.auth

import kotlinx.serialization.Serializable

/**
 * A login: the backend's own token, handed out after Google sign-in (POST /auth/google).
 * A login saved by an older version (Cognito) has other fields, doesn't load, and so asks you to sign in again.
 */
@Serializable
data class Session(
    val token: String,
    /** When [token] stops working, in seconds since 1970 (UTC): a year after signing in. */
    val expiresAt: Long,
    val email: String,
) {
    fun expired(nowSeconds: Long): Boolean = expiresAt <= nowSeconds
}

/** Something went wrong signing in; [message] is written for you, not for a developer. */
class AuthException(message: String, val kind: Kind = Kind.Other, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** No connection, or the server didn't answer: try again later. */
        Network,
        Other,
    }
}
