package com.prominder.myapp.auth

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cognito login over plain HTTPS: the same email-code login as the web app.
 *
 * 1. [sendCode] asks Cognito to email a code (choice-based sign-in, EMAIL_OTP). Sign-up is off in the
 *    user pool, so only an account that already exists (yours) can log in. Cognito answers with a
 *    login-attempt id that the next step needs; it's kept in memory (a new code is needed if Android
 *    closes the app in between).
 * 2. [verifyCode] trades the code for a [Session].
 * 3. [refresh] gets a new access token before the old one expires (they last an hour).
 *
 * [region] and [clientId] are public: the web app ships the same two values to every browser.
 */
class CognitoAuth(
    private val region: String,
    private val clientId: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val endpoint: String = "https://cognito-idp.$region.amazonaws.com/",
) {
    val isConfigured: Boolean get() = region.isNotBlank() && clientId.isNotBlank()

    private data class Pending(val email: String, val session: String)

    @Volatile
    private var pending: Pending? = null

    suspend fun sendCode(email: String) {
        val user = normalize(email)
        var r = call("InitiateAuth", buildJsonObject {
            put("ClientId", clientId)
            put("AuthFlow", "USER_AUTH")
            putJsonObject("AuthParameters") {
                put("USERNAME", user)
                put("PREFERRED_CHALLENGE", "EMAIL_OTP")
            }
        })
        if (r.string("ChallengeName") == "SELECT_CHALLENGE") {
            r = call("RespondToAuthChallenge", buildJsonObject {
                put("ClientId", clientId)
                put("ChallengeName", "SELECT_CHALLENGE")
                put("Session", r.string("Session").orEmpty())
                putJsonObject("ChallengeResponses") {
                    put("USERNAME", user)
                    put("ANSWER", "EMAIL_OTP")
                }
            })
        }
        if (r.string("ChallengeName") != "EMAIL_OTP") throw AuthException("Login by email code isn't turned on for this app.")
        pending = Pending(user, r.string("Session").orEmpty())
    }

    suspend fun verifyCode(email: String, code: String): Session {
        val user = normalize(email)
        val p = pending?.takeIf { it.email == user } ?: throw AuthException("Send a new code first.")
        val r = call("RespondToAuthChallenge", buildJsonObject {
            put("ClientId", clientId)
            put("ChallengeName", "EMAIL_OTP")
            put("Session", p.session)
            putJsonObject("ChallengeResponses") {
                put("USERNAME", user)
                put("EMAIL_OTP_CODE", code.filter { !it.isWhitespace() })
            }
        }, verifying = true)
        val result = r["AuthenticationResult"] as? JsonObject
        if (result == null) {
            // A wrong code can come back as the same challenge with a new attempt id: keep it for the retry.
            r.string("Session")?.let { pending = Pending(user, it) }
            throw AuthException("That code is wrong. Check the latest email.")
        }
        pending = null
        return toSession(result, user, refreshFallback = null)
    }

    /** Cognito doesn't send a new refresh token on refresh; the old one keeps working. Email comes back blank. */
    suspend fun refresh(refreshToken: String): Session {
        val r = call("InitiateAuth", buildJsonObject {
            put("ClientId", clientId)
            put("AuthFlow", "REFRESH_TOKEN_AUTH")
            putJsonObject("AuthParameters") { put("REFRESH_TOKEN", refreshToken) }
        }, refreshing = true)
        val result = r["AuthenticationResult"] as? JsonObject ?: throw unreadable()
        return toSession(result, email = "", refreshFallback = refreshToken)
    }

    /** Ends the login on Cognito too (the refresh token stops working). Best effort: logging out works offline. */
    suspend fun logout(refreshToken: String) {
        runCatching {
            call("RevokeToken", buildJsonObject {
                put("ClientId", clientId)
                put("Token", refreshToken)
            })
        }
    }

    private fun toSession(result: JsonObject, email: String, refreshFallback: String?): Session {
        val access = result.string("AccessToken") ?: throw unreadable()
        val refresh = result.string("RefreshToken") ?: refreshFallback ?: throw unreadable()
        val expiresIn = (result["ExpiresIn"])?.jsonPrimitive?.longOrNull ?: 3600
        val userId = runCatching {
            val payload = String(java.util.Base64.getUrlDecoder().decode(access.split(".")[1]))
            JSON.parseToJsonElement(payload).jsonObject.string("sub")
        }.getOrNull() ?: throw unreadable()
        return Session(access, refresh, nowSeconds() + expiresIn, userId, email)
    }

    private fun unreadable() = AuthException("The login server sent an answer the app doesn't understand.")

    private suspend fun call(target: String, body: JsonObject, verifying: Boolean = false, refreshing: Boolean = false): JsonObject =
        withContext(Dispatchers.IO) {
            if (!isConfigured) throw AuthException("This build has no login settings.")
            val request = Request.Builder()
                .url(endpoint)
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.$target")
                .post(body.toString().toRequestBody(AMZ_JSON))
                .build()
            val text = try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) throw errorFor(response.code, text, verifying, refreshing)
                    text
                }
            } catch (e: IOException) {
                throw networkError(e)
            }
            runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrElse { throw unreadable() }
        }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val AMZ_JSON = "application/x-amz-json-1.1".toMediaType()

        private fun normalize(email: String) = email.trim().lowercase()

        private fun JsonObject.string(name: String): String? =
            this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

        /**
         * Says what kind of connection failure it was, since "no internet", "address not found" and
         * "certificate refused" need different fixes. The original error stays attached as the cause.
         */
        internal fun networkError(e: IOException): AuthException {
            val message = when (e) {
                is SSLException ->
                    "The secure connection to the login server was refused (certificate check failed). " +
                        "If a filter or VPN is on (NetFree, Rimon, etc.), install its certificate on this device and try again."
                is UnknownHostException ->
                    "Can't find the login server. Check the internet connection, or whether a filter is blocking amazonaws.com."
                is SocketTimeoutException ->
                    "The login server didn't answer in time. Check the internet connection and try again."
                else -> "Can't reach the login server. Check the internet connection and try again."
            }
            val detail = listOfNotNull(e::class.java.simpleName, e.message).joinToString(": ")
            return AuthException("$message\n($detail)", AuthException.Kind.Network, e)
        }

        /** Turns Cognito's error answer ({"__type": "...Exception", "message": "..."}) into a sentence. */
        internal fun errorFor(status: Int, body: String, verifying: Boolean = false, refreshing: Boolean = false): AuthException {
            val obj = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val type = obj?.string("__type")?.substringAfterLast('#').orEmpty()
            val raw = obj?.string("message") ?: obj?.string("Message") ?: ""
            return when {
                status >= 500 || type == "InternalErrorException" ->
                    AuthException("The login server is having trouble right now. Try again in a minute.", AuthException.Kind.Network)
                type == "TooManyRequestsException" || type == "LimitExceededException" ->
                    AuthException("Too many tries. Wait a minute and try again.")
                refreshing && type == "NotAuthorizedException" ->
                    AuthException("Your login has ended. Log in again.", AuthException.Kind.SessionGone)
                type == "CodeMismatchException" || type == "ExpiredCodeException" ->
                    AuthException("That code is wrong or has expired. Check the latest email, or send a new code.")
                verifying && type == "NotAuthorizedException" ->
                    if ("session" in raw.lowercase()) AuthException("This login attempt has expired. Send a new code.")
                    else AuthException("That code is wrong or has expired. Check the latest email, or send a new code.")
                type == "UserNotFoundException" || (type == "NotAuthorizedException" && "disabled" !in raw.lowercase()) ->
                    AuthException("There's no account with this email. Use the email you log in to the web app with.")
                type == "InvalidParameterException" && ("email" in raw.lowercase() || "username" in raw.lowercase()) ->
                    AuthException("That doesn't look like an email address.")
                raw.isNotBlank() -> AuthException(raw)
                else -> AuthException("Login failed (error $status).")
            }
        }
    }
}
