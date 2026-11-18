package com.prominder.myapp.sync

import com.prominder.myapp.auth.AuthException
import com.prominder.myapp.auth.BackendAuth
import com.prominder.myapp.data.EventRow
import com.prominder.myapp.data.FocusRow
import com.prominder.myapp.data.MessageRow
import com.prominder.myapp.data.PreferenceRow
import com.prominder.myapp.data.SessionRow
import com.prominder.myapp.data.SettingRow
import com.prominder.myapp.data.TaskRow
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Every table the phone keeps, as the server sends and receives them (backend/app/sync.py). */
@Serializable
data class Tables(
    val tasks: List<TaskRow> = emptyList(),
    val events: List<EventRow> = emptyList(),
    val messages: List<MessageRow> = emptyList(),
    val sessions: List<SessionRow> = emptyList(),
    val preferences: List<PreferenceRow> = emptyList(),
    val settings: List<SettingRow> = emptyList(),
    val focus: List<FocusRow> = emptyList(),
) {
    val size: Int get() = tasks.size + events.size + messages.size + sessions.size + preferences.size + settings.size + focus.size
}

@Serializable
data class SyncStatus(
    val handedOverAt: String? = null,
    val lastUploadAt: String? = null,
    val lastRestoreAt: String? = null,
    val device: String? = null,
)

@Serializable
data class RestoreResult(
    val tables: Tables,
    val counters: Map<String, Long> = emptyMap(),
    val status: SyncStatus = SyncStatus(),
    val serverTime: String,
)

@Serializable
data class UploadBody(
    val device: String,
    val tables: Tables,
    /** table → keys (an id, a setting's key, a date, an event's [task_id, created_at, rid]) */
    val deletes: Map<String, List<JsonElement>> = emptyMap(),
)

@Serializable
data class UploadResult(val ok: Boolean = false, val written: Int = 0, val deleted: Int = 0, val serverTime: String? = null)

/** Thrown when sync fails; [message] is written for you. Network ones are worth retrying later. */
class SyncException(message: String, val network: Boolean, cause: Throwable? = null) : Exception(message, cause)

/** The backup's three endpoints on the backend. */
open class SyncApi(
    private val apiUrl: String,
    private val http: OkHttpClient,
    private val token: () -> String?,
) {
    open suspend fun status(): SyncStatus = call("GET", "/sync/status", null)

    open suspend fun restore(handover: Boolean, device: String): RestoreResult =
        call("POST", "/sync/restore", JSON.encodeToString(Restore(handover, device)))

    open suspend fun upload(body: UploadBody): UploadResult = call("POST", "/sync/upload", JSON.encodeToString(body))

    /** The backend's prompt files (Phase D): newer than the app's own when a prompt changed after the release. */
    open suspend fun prompts(): com.prominder.myapp.llm.PromptSet = call("GET", "/prompts", null)

    @Serializable
    private data class Restore(val handover: Boolean, val device: String)

    private suspend inline fun <reified T> call(method: String, path: String, body: String?): T = withContext(Dispatchers.IO) {
        val t = token() ?: throw SyncException("You're logged out. Log in again.", network = false)
        val request = Request.Builder().url(apiUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $t")
            .method(method, body?.toRequestBody(JSON_TYPE))
            .build()
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body.string() }
        } catch (e: IOException) {
            val reason: AuthException = BackendAuth.networkError(e)
            throw SyncException(reason.message.orEmpty(), network = true, cause = e)
        }
        if (code !in 200..299) throw errorFor(code, text)
        try {
            JSON.decodeFromString<T>(text)
        } catch (e: Exception) {
            throw SyncException("The server sent an answer the app doesn't understand.", network = false, cause = e)
        }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
        private val JSON_TYPE = "application/json".toMediaType()

        internal fun errorFor(code: Int, body: String): SyncException {
            val detail = runCatching {
                (JSON.parseToJsonElement(body) as? JsonObject)?.get("detail")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return when (code) {
                401 -> SyncException("The server didn't accept the login. Log out and in again.", network = false)
                422 -> SyncException("The server refused the data: ${detail ?: "HTTP 422"}", network = false)
                // Render answers 502-504 while it wakes up; a 500 is a bug or a refusal on the server (see its log).
                in 502..504 -> SyncException(detail ?: "The server is starting up. Try again in a minute. (HTTP $code)", network = true)
                in 500..599 -> SyncException(detail ?: "The server ran into an error (HTTP $code). Try again later; " +
                    "if it keeps happening, share Diagnostics.", network = true)
                else -> SyncException(detail ?: "The server refused (HTTP $code).", network = code == 429 || code == 408)
            }
        }
    }
}
