package com.prominder.myapp.llm

import com.prominder.myapp.auth.BackendAuth
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * No usable answer from the model right now (the server's 503, the daily cap, no internet, a timeout). Not a bug:
 * the coach falls back to its templates, the chat says so. [limit]: the daily cap is used up.
 */
class LlmUnavailable(message: String, val limit: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/** One tool call the model asked for. [arguments] as the model sent them: usually JSON text, sometimes an object. */
data class ToolCall(val id: String?, val name: String, val arguments: JsonElement?)

/** The model's answer to one call. [raw] is the assistant message as received, to send back in the next round. */
data class LlmReply(val content: String, val toolCalls: List<ToolCall>, val model: String?, val raw: JsonObject)

/** One call to the model (backend/app/llm.py's chat_completion, through the Lambda). */
fun interface Llm {
    suspend fun complete(messages: List<JsonObject>, tools: List<JsonObject>, temperature: Double, timeoutS: Double): LlmReply
}

/** A chat message for the model. */
fun message(role: String, content: String): JsonObject = buildJsonObject {
    put("role", role)
    put("content", content)
}

/**
 * The model through the backend's POST /llm (backend/app/llm_proxy.py): the OpenRouter key, the fallback models,
 * the daily cap and the rate limit stay on the server, so nothing secret is in the app.
 */
class BackendLlm(
    private val apiUrl: String,
    private val http: OkHttpClient,
    private val token: () -> String?,
) : Llm {
    override suspend fun complete(messages: List<JsonObject>, tools: List<JsonObject>, temperature: Double, timeoutS: Double): LlmReply =
        withContext(Dispatchers.IO) {
            val t = token() ?: throw LlmUnavailable("You're logged out. Log in again.")
            val body = buildJsonObject {
                put("messages", JsonArray(messages))
                if (tools.isNotEmpty()) put("tools", JsonArray(tools))
                put("temperature", temperature)
                put("timeout_s", timeoutS)
            }.toString()
            val request = Request.Builder().url(apiUrl.trimEnd('/') + "/llm")
                .header("Authorization", "Bearer $t")
                .post(body.toRequestBody(JSON_TYPE))
                .build()
            // The server stops the model after timeoutS; a little more for the trip and a cold start.
            val client = http.newBuilder().callTimeout((timeoutS + 20).toLong(), TimeUnit.SECONDS).build()
            val (code, text) = try {
                client.newCall(request).execute().use { it.code to it.body.string() }
            } catch (e: IOException) {
                throw LlmUnavailable(BackendAuth.networkError(e).message.orEmpty(), cause = e)
            }
            if (code !in 200..299) throw errorFor(code, text)
            parse(text)
        }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_TYPE = "application/json".toMediaType()

        internal fun errorFor(code: Int, body: String): LlmUnavailable {
            val obj = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val detail = obj?.get("detail")?.let { (it as? JsonPrimitive)?.contentOrNull }
            val limit = obj?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull } == "limit"
            return when (code) {
                401 -> LlmUnavailable("The server didn't accept the login. Log out and in again.")
                404 -> LlmUnavailable("The server doesn't have the assistant yet. Deploy the backend (Actions → Backend).")
                else -> LlmUnavailable(detail ?: "The assistant is unavailable right now (HTTP $code).", limit = limit)
            }
        }

        /** {"message": {role, content, tool_calls?}, "model": "..."} → the reply. */
        internal fun parse(text: String): LlmReply {
            val obj = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw LlmUnavailable("The server sent an answer the app doesn't understand.")
            val msg = obj["message"] as? JsonObject ?: throw LlmUnavailable("The server sent an answer the app doesn't understand.")
            val model = (obj["model"] as? JsonPrimitive)?.contentOrNull
            return reply(msg, model)
        }

        /** An assistant message (OpenAI format) → the reply. */
        fun reply(msg: JsonObject, model: String?): LlmReply {
            val content = (msg["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val calls = (msg["tool_calls"] as? JsonArray).orEmpty().mapNotNull { c ->
                val call = c as? JsonObject ?: return@mapNotNull null
                val fn = call["function"] as? JsonObject ?: return@mapNotNull null
                ToolCall(
                    id = (call["id"] as? JsonPrimitive)?.contentOrNull,
                    name = (fn["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    arguments = fn["arguments"]?.takeUnless { it is JsonNull },
                )
            }
            return LlmReply(content, calls, model, msg)
        }

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
