package com.prominder.myapp.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Bad arguments: the message goes back to the model so it can fix them, once. */
class ToolArgError(message: String) : Exception(message)

/**
 * Checking and recovering tool calls, ported from backend/app/tools.py: forgiving validation (numbers as strings,
 * empty optional strings, unknown keys), and a parser for tool calls the model wrote as text instead of tool_calls.
 * Validated arguments are plain Kotlin values: Long, Boolean, String, or a List of those.
 */
object ToolArgs {
    /** Coerce and check arguments against the (flat) schema. Throws [ToolArgError]. */
    fun validate(name: String, args: JsonElement?, schema: JsonObject?): Map<String, Any> {
        schema ?: throw ToolArgError("Unknown tool '$name'.")
        val obj = args as? JsonObject ?: throw ToolArgError("Arguments must be a JSON object.")
        val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in obj) {
            val spec = props[key] as? JsonObject ?: continue // unknown keys are ignored
            if (value is JsonNull) continue
            if (value is JsonPrimitive && value.isString && value.content.isBlank()) continue // empty optionals too
            out[key] = coerce(key, value, spec)
        }
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val missing = required.filter { it !in out }
        if (missing.isNotEmpty()) throw ToolArgError("Missing required argument(s): ${missing.joinToString(", ")}.")
        return out
    }

    private fun coerce(key: String, value: JsonElement, spec: JsonObject): Any {
        val out: Any = when ((spec["type"] as? JsonPrimitive)?.contentOrNull) {
            "integer" -> {
                val p = value as? JsonPrimitive ?: throw ToolArgError("'$key' must be an integer, got ${repr(value)}.")
                if (!p.isString && p.booleanOrNull != null) throw ToolArgError("'$key' must be an integer.")
                val d = p.content.trim().toDoubleOrNull() ?: throw ToolArgError("'$key' must be an integer, got ${repr(value)}.")
                if (d != Math.floor(d) || d.isInfinite()) throw ToolArgError("'$key' must be a whole number.")
                d.toLong()
            }
            "boolean" -> {
                val p = value as? JsonPrimitive ?: throw ToolArgError("'$key' must be true or false.")
                when {
                    !p.isString && p.booleanOrNull != null -> p.booleanOrNull!!
                    p.isString && p.content.lowercase() in setOf("true", "false") -> p.content.lowercase() == "true"
                    else -> throw ToolArgError("'$key' must be true or false.")
                }
            }
            "string" -> {
                val p = value as? JsonPrimitive ?: throw ToolArgError("'$key' must be a string.")
                p.content.trim()
            }
            "array" -> {
                val items: List<JsonElement> = when {
                    value is JsonArray -> value
                    value is JsonPrimitive && value.isString ->
                        value.content.split(Regex("[\\s,\\[\\]]+")).filter { it.isNotEmpty() }.map { JsonPrimitive(it) }
                    else -> listOf(value)
                }
                val itemSpec = spec["items"] as? JsonObject ?: JsonObject(emptyMap())
                items.map { coerce(key, it, itemSpec) }
            }
            else -> (value as? JsonPrimitive)?.content ?: value.toString()
        }
        val enum = spec["enum"] as? JsonArray
        if (enum != null && enum.none { matches(it, out) }) {
            throw ToolArgError("'$key' must be one of ${enum.joinToString(", ", "[", "]") { pyRepr(it) }}, got ${pyRepr(out)}.")
        }
        return out
    }

    private fun matches(option: JsonElement, value: Any): Boolean {
        val p = option as? JsonPrimitive ?: return false
        return when (value) {
            is Long -> !p.isString && p.doubleOrNull == value.toDouble()
            is String -> p.isString && p.content == value
            else -> false
        }
    }

    private fun repr(value: JsonElement): String = if (value is JsonPrimitive && value.isString) "'${value.content}'" else value.toString()

    private fun pyRepr(value: Any): String = when (value) {
        is JsonPrimitive -> if (value.isString) "'${value.content}'" else value.content
        is String -> "'$value'"
        else -> value.toString()
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    // ------------------------------------------------------------ tool calls written as text

    val FENCE = Regex("```(?:json|tool_code|python|tool_call)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /**
     * If the model wrote a tool call as text instead of tool_calls, recover it (once per turn). Accepts JSON
     * ({"name":…, "arguments":{…}} and common variants) and Python-style calls (`add_task(title="x")`, as Gemma
     * writes in tool_code blocks).
     */
    fun parseTextToolCall(content: String?, names: Set<String>): Pair<String, JsonObject>? {
        if (content.isNullOrEmpty() || names.isEmpty()) return null
        val candidates = FENCE.findAll(content).map { it.groupValues[1] }.toList() + content
        for (raw in candidates) {
            val text = raw.trim()
            (fromJson(text, names) ?: fromPython(text, names))?.let { return it }
        }
        return null
    }

    private fun fromJson(text: String, names: Set<String>): Pair<String, JsonObject>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        var obj: JsonElement = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull() ?: return null
        repeat(2) { // unwrap {"tool_call": {...}} / {"function": {...}}
            val o = obj as? JsonObject
            if (o != null && o.size == 1 && o.keys.first() in setOf("tool_call", "function", "call") && o.values.first() is JsonObject) {
                obj = o.values.first()
            }
        }
        val o = obj as? JsonObject ?: return null
        val name = listOf("name", "tool", "function").firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } }
        var args: JsonElement = o["arguments"] ?: o["parameters"] ?: o["args"] ?: JsonObject(emptyMap())
        if (args is JsonPrimitive && args.isString) args = runCatching { Json.parseToJsonElement(args.content) }.getOrNull() ?: return null
        return if (name != null && name in names && args is JsonObject) name to args.jsonObject else null
    }

    private fun fromPython(text: String, names: Set<String>): Pair<String, JsonObject>? {
        val pattern = Regex("\\b(" + names.sorted().joinToString("|") { Regex.escape(it) } + ")\\s*\\(")
        val m = pattern.find(text) ?: return null
        return runCatching { m.groupValues[1] to PyCall(text, m.range.last + 1).kwargs() }.getOrNull()
    }

    /** Reads `key=literal, …)` from just after the opening parenthesis: strings, numbers, True/False/None, lists. */
    private class PyCall(val s: String, var i: Int) {
        fun kwargs(): JsonObject {
            val out = LinkedHashMap<String, JsonElement>()
            skip()
            if (peek() == ')') return JsonObject(out)
            while (true) {
                skip()
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val key = s.substring(start, i)
                require(key.isNotEmpty() && !key[0].isDigit())
                skip()
                require(next() == '=')
                out[key] = literal()
                skip()
                when (next()) {
                    ',' -> { skip(); if (peek() == ')') { i++; return JsonObject(out) } }
                    ')' -> return JsonObject(out)
                    else -> throw IllegalArgumentException()
                }
            }
        }

        fun literal(): JsonElement {
            skip()
            val c = peek()
            return when {
                c == '"' || c == '\'' -> JsonPrimitive(string())
                c == '[' -> list()
                s.startsWith("True", i) -> { i += 4; JsonPrimitive(true) }
                s.startsWith("False", i) -> { i += 5; JsonPrimitive(false) }
                s.startsWith("None", i) -> { i += 4; JsonNull }
                else -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    val num = s.substring(start, i)
                    num.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(num.toDouble())
                }
            }
        }

        fun list(): JsonArray {
            i++
            val items = mutableListOf<JsonElement>()
            skip()
            if (peek() == ']') { i++; return JsonArray(items) }
            while (true) {
                items += literal()
                skip()
                when (next()) {
                    ',' -> { skip(); if (peek() == ']') { i++; return JsonArray(items) } }
                    ']' -> return JsonArray(items)
                    else -> throw IllegalArgumentException()
                }
            }
        }

        fun string(): String {
            val quote = next()
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == quote -> return sb.toString()
                    c == '\\' -> when (val e = next()) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char = if (i < s.length) s[i] else throw IllegalArgumentException()
        fun next(): Char = peek().also { i++ }
    }

}
