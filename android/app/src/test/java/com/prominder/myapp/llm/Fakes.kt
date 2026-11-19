package com.prominder.myapp.llm

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A model step for [ScriptedLlm]: the assistant's words, a tool call, or a failure. */
sealed interface Step
data class Says(val text: String) : Step
data class Calls(val tool: String, val args: JsonObject) : Step
data class Fails(val error: Exception) : Step

fun says(text: String) = Says(text)
fun calls(tool: String, vararg args: Pair<String, Any>) = Calls(tool, buildJsonObject {
    for ((k, v) in args) when (v) {
        is String -> put(k, v)
        is Int -> put(k, v)
        is Long -> put(k, v)
        is Boolean -> put(k, v)
        is List<*> -> put(k, buildJsonArray { v.forEach { add(kotlinx.serialization.json.JsonPrimitive(it as Number)) } })
        else -> error("unsupported: $v")
    }
})

/**
 * Stands in for the model (backend/tests/fakes.py's ScriptedLLM): answers with the given steps in order (then with
 * nothing), records every request, and reports [model] as the model that answered.
 */
class ScriptedLlm(steps: List<Step>, private val model: String = "test/model") : Llm {
    private val steps = ArrayDeque(steps)
    data class Request(val messages: List<JsonObject>, val tools: List<JsonObject>, val temperature: Double)
    val requests = mutableListOf<Request>()

    override suspend fun complete(messages: List<JsonObject>, tools: List<JsonObject>, temperature: Double, timeoutS: Double): LlmReply {
        requests += Request(messages.toList(), tools, temperature)
        val raw = when (val step = steps.removeFirstOrNull()) {
            null -> buildJsonObject { put("role", "assistant"); put("content", "") }
            is Fails -> throw step.error
            is Says -> buildJsonObject { put("role", "assistant"); put("content", step.text) }
            is Calls -> buildJsonObject {
                put("role", "assistant")
                put("content", "")
                put("tool_calls", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "call_${requests.size}")
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", step.tool)
                            put("arguments", step.args.toString())
                        }
                    })
                })
            }
        }
        return BackendLlm.reply(raw, model)
    }

    /** The content of message [i] of request [n]. */
    fun content(n: Int, i: Int): String = (requests[n].messages[i]["content"] as kotlinx.serialization.json.JsonPrimitive).content
}

/** The real prompt files, from backend/app/prompts (the app ships the same ones). */
fun testPrompts(): Prompts {
    var dir = File(System.getProperty("user.dir")!!).absoluteFile
    while (!File(dir, "backend/app/prompts").isDirectory) dir = dir.parentFile ?: error("backend/app/prompts not found")
    val prompts = File(dir, "backend/app/prompts")
    return Prompts { name -> File(prompts, "$name.md").takeIf { it.isFile }?.readText() }
}
