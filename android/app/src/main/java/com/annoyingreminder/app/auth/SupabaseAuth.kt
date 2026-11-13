package com.annoyingreminder.app.auth

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Supabase Auth over plain HTTPS: the same email-code login as the web app.
 *
 * 1. [sendCode] asks Supabase to email a code. `create_user: false`, so only an account that already
 *    exists (yours) can log in, exactly like the web app's `shouldCreateUser: false`.
 * 2. [verifyCode] trades the code for a [Session].
 * 3. [refresh] gets a new access token before the old one expires (they last an hour).
 *
 * [baseUrl] and [anonKey] are the public project URL and anon key, the same ones the web app uses.
 */
class SupabaseAuth(
    private val baseUrl: String,
    private val anonKey: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    suspend fun sendCode(email: String) {
        post("otp", buildJsonObject {
            put("email", email.trim())
            put("create_user", false)
        })
    }

    suspend fun verifyCode(email: String, code: String): Session =
        toSession(post("verify", buildJsonObject {
            put("type", "email")
            put("email", email.trim())
            put("token", code.filter { !it.isWhitespace() })
        }))

    suspend fun refresh(refreshToken: String): Session =
        toSession(post("token?grant_type=refresh_token", buildJsonObject { put("refresh_token", refreshToken) }))

    /** Ends the session on Supabase too. Best effort: logging out on the phone works offline. */
    suspend fun logout(accessToken: String) {
        runCatching { post("logout", JsonObject(emptyMap()), bearer = accessToken) }
    }

    private fun toSession(body: String): Session {
        val r = try {
            JSON.decodeFromString<TokenResponse>(body)
        } catch (e: Exception) {
            throw AuthException("Supabase sent an answer the app doesn't understand.")
        }
        return Session(
            accessToken = r.accessToken,
            refreshToken = r.refreshToken,
            expiresAt = r.expiresAt ?: (nowSeconds() + (r.expiresIn ?: 3600)),
            userId = r.user.id,
            email = r.user.email.orEmpty(),
        )
    }

    private suspend fun post(path: String, body: JsonObject, bearer: String = anonKey): String =
        withContext(Dispatchers.IO) {
            if (!isConfigured) throw AuthException("This build has no Supabase settings.")
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/auth/v1/$path")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $bearer")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) throw errorFor(response.code, text)
                    text
                }
            } catch (e: IOException) {
                throw AuthException("Can't reach Supabase. Check the internet connection and try again.", AuthException.Kind.Network)
            }
        }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("expires_at") val expiresAt: Long? = null,
        val user: User,
    )

    @Serializable
    private data class User(val id: String, val email: String? = null)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_TYPE = "application/json".toMediaType()

        /** Turns Supabase's error answer into a sentence for the login screen. */
        internal fun errorFor(status: Int, body: String): AuthException {
            val obj = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()
            fun field(name: String) = obj?.get(name)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val code = field("error_code") ?: field("error") ?: ""
            val raw = field("msg") ?: field("message") ?: field("error_description") ?: ""
            val text = "$code $raw".lowercase()
            return when {
                status == 429 || "rate_limit" in text || "rate limit" in text ->
                    AuthException("Too many tries. Wait a minute and try again.")
                "refresh_token" in text || "invalid_grant" in text ->
                    AuthException("Your login has ended. Log in again.", AuthException.Kind.SessionGone)
                "signup" in text || "user_not_found" in text ->
                    AuthException("There's no account with this email. Use the email you log in to the web app with.")
                "email" in text && ("format" in text || "validate" in text) ->
                    AuthException("That doesn't look like an email address.")
                "otp_expired" in text || "expired" in text || "invalid" in text ->
                    AuthException("That code is wrong or has expired. Check the latest email, or send a new code.")
                status == 401 || status == 403 ->
                    AuthException("Your login has ended. Log in again.", AuthException.Kind.SessionGone)
                status >= 500 ->
                    AuthException("Supabase is having trouble right now. Try again in a minute.", AuthException.Kind.Network)
                raw.isNotBlank() -> AuthException(raw)
                else -> AuthException("Login failed (error $status).")
            }
        }
    }
}
