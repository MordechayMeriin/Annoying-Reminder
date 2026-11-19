package com.prominder.myapp.auth

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Logs in to ProMinder's backend: trades the ID token Google gave the app ([GoogleSignIn]) for the
 * backend's own login token. The backend checks the token is Google's and the account is the owner's.
 */
class BackendAuth(
    private val apiUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    val isConfigured: Boolean get() = apiUrl.isNotBlank()

    suspend fun signIn(googleIdToken: String): Session = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("idToken", googleIdToken) }.toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder().url(apiUrl.trimEnd('/') + "/auth/google").post(body).build()
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body.string() }
        } catch (e: IOException) {
            throw networkError(e)
        }
        if (code !in 200..299) throw errorFor(code, text)
        val obj = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw unreadable()
        val token = obj.string("token") ?: throw unreadable()
        val expiresAt = obj["expiresAt"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() } ?: throw unreadable()
        Session(token, expiresAt, obj.string("email").orEmpty())
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_TYPE = "application/json".toMediaType()

        private fun JsonObject.string(name: String): String? =
            this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

        private fun unreadable() = AuthException("The server sent an answer the app doesn't understand.")

        /**
         * Says what kind of connection failure it was, since "no internet", "address not found", "certificate
         * refused" and "cut off by a filter" need different fixes. The original error stays attached as the cause.
         */
        internal fun networkError(e: IOException): AuthException {
            val message = when {
                e is SSLException && certificateProblem(e) ->
                    "The phone doesn't trust the server's certificate. If a filter or VPN is on (NetFree, Rimon, etc.), " +
                        "install its certificate on this phone and try again."
                e is SSLException ->
                    "The secure connection to the server was cut off. If a filter or VPN is on (NetFree, Rimon, etc.), " +
                        "it may be blocking this app: ask it to allow ProMinder."
                e is UnknownHostException ->
                    "Can't find the server. Check the internet connection, or whether a filter is blocking the app."
                e is SocketTimeoutException || e.message.orEmpty().contains("timeout", ignoreCase = true) ->
                    "The server didn't answer in time. Check the internet connection and try again."
                else -> "Can't reach the server. Check the internet connection and try again."
            }
            val detail = listOfNotNull(e::class.java.simpleName, e.message).joinToString(": ")
            return AuthException("$message\n($detail)", AuthException.Kind.Network, e)
        }

        private fun certificateProblem(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(10).any {
                it is CertificateException || it is CertPathValidatorException ||
                    it.message.orEmpty().contains("certif", ignoreCase = true) ||
                    it.message.orEmpty().contains("trust anchor", ignoreCase = true)
            }

        /** Turns the backend's error answer ({"detail": "..."}) into a sentence. */
        internal fun errorFor(status: Int, body: String): AuthException {
            val detail = runCatching { (JSON.parseToJsonElement(body) as? JsonObject)?.string("detail") }.getOrNull()
            return when (status) {
                403, 429 -> AuthException(detail ?: "The server refused the sign-in.")
                401 -> AuthException("Google's answer wasn't accepted. Try again.")
                502, 503, 504 -> AuthException(
                    detail ?: "The server ran into a problem (HTTP $status). Try again in a minute.",
                    AuthException.Kind.Network,
                )
                else -> AuthException("The server refused the sign-in (HTTP $status).")
            }
        }
    }
}
