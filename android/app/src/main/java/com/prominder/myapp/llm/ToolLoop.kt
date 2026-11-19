package com.prominder.myapp.llm

import com.prominder.myapp.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Progress of a turn, for the chat's status lines: a model call starts, or a tool is about to run. */
sealed interface LoopEvent {
    data object Thinking : LoopEvent
    data class Tool(val name: String, val args: JsonObject) : LoopEvent
}

/** The model's final text ('' if none), and the models that answered, in order (the last one wrote the text). */
data class LoopResult(val text: String, val models: List<String>)

/**
 * The tool-calling loop shared by the chat agent and the coach, ported from backend/app/llm_loop.py. Robust for a
 * mid-size free model:
 * - tool calls run one after another (never relies on parallel calls);
 * - malformed arguments go back to the model as a clear error, with ONE retry per turn;
 * - a tool call written as text instead of tool_calls is parsed once;
 * - [LlmUnavailable] propagates, so the caller can fall back.
 */
class ToolLoop(private val llm: Llm, private val tools: Tools) {
    /**
     * Runs until the model answers in words. [onEvent] gets [LoopEvent.Thinking] before each model call and
     * [LoopEvent.Tool] before each tool runs; it must not throw. [models] collects who answered (even if a timeout
     * stops the loop later).
     */
    suspend fun run(
        messages: MutableList<JsonObject>, toolDefs: List<JsonObject>, ctx: ToolContext, maxRounds: Int = 5,
        temperature: Double = 0.3, timeoutS: Double = 25.0, models: MutableList<String> = mutableListOf(),
        onEvent: suspend (LoopEvent) -> Unit = {},
    ): LoopResult {
        val names = toolDefs.map(ToolDefs::name).toSet()
        var errors = 0
        var parsedTextCall = false
        var leftover = "" // text the model wrote next to a text-form tool call

        for (round in 0 until maxRounds) {
            onEvent(LoopEvent.Thinking)
            val reply = llm.complete(messages, toolDefs, temperature, timeoutS)
            reply.model?.let { models += it }
            var content = reply.content.trim()
            var calls = reply.toolCalls.map { Triple(it.id, it.name, it.arguments) }
            var rawCalls: JsonElement? = reply.raw["tool_calls"]

            if (calls.isEmpty()) {
                val parsed = if (parsedTextCall) null else ToolArgs.parseTextToolCall(content, names)
                if (parsed == null) return LoopResult(content.ifEmpty { leftover }, models)
                parsedTextCall = true
                leftover = if ("```" in content) ToolArgs.FENCE.replace(content, "").trim() else ""
                val id = "text_call_$round"
                val args = parsed.second.toString()
                calls = listOf(Triple(id, parsed.first, JsonPrimitive(args)))
                rawCalls = JsonArray(listOf(buildJsonObject {
                    put("id", id)
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", parsed.first)
                        put("arguments", args)
                    }
                }))
                content = ""
            }

            messages += buildJsonObject {
                put("role", "assistant")
                put("content", content)
                rawCalls?.let { put("tool_calls", it) }
            }
            for ((id, name, rawArgs) in calls) {
                val result: JsonElement = try {
                    val args = parseArgs(rawArgs)
                    if (args is JsonObject) onEvent(LoopEvent.Tool(name, args))
                    tools.execute(name, args, ctx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ToolArgError) {
                    errors++
                    Diag.log("llm", "Tool $name refused: ${e.message}")
                    error(if (errors == 1) "${e.message} Fix the arguments and try again." else e.message.orEmpty())
                } catch (e: Exception) { // a bug or a database problem: don't let the model loop on it
                    errors++
                    Diag.log("llm", "Tool $name failed: ${e::class.java.simpleName}: ${e.message}")
                    error("Internal error while running this tool.")
                }
                messages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", id ?: "call_$round")
                    put("content", result.toString())
                }
            }
            if (errors > 1) {
                Diag.log("llm", "Gave up after $errors tool errors")
                return LoopResult(leftover, models)
            }
        }
        return LoopResult(leftover, models)
    }

    private fun error(text: String): JsonElement = buildJsonObject { put("error", text) }

    /** Arguments as JSON text ("{…}") or already an object. Malformed JSON is the model's to fix. */
    private fun parseArgs(raw: JsonElement?): JsonElement {
        if (raw == null) return JsonObject(emptyMap())
        if (raw is JsonPrimitive && raw.isString) {
            val text = raw.content.ifBlank { "{}" }
            return try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw ToolArgError("The arguments aren't valid JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}.")
            }
        }
        return raw
    }
}
