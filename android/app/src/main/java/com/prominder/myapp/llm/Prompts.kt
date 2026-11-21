package com.prominder.myapp.llm

import android.content.Context
import com.prominder.myapp.diag.Diag
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The prompts: the Markdown files in backend/app/prompts/ (the Lambda serves them too), shipped
 * inside the app (assets/prompts/, copied at build time) so the assistant works offline-first. A newer set downloaded
 * from the backend at the daily backup ([PromptFiles]) takes precedence, so a prompt can change without a new app.
 *
 * Placeholders are written {like_this}. Only names that are passed are replaced, so other braces are safe. Always
 * available: {core}, {playbooks} (the situational playbooks code chose), {chips} (the reply buttons this message
 * gets), {preferences} (what you asked of the assistant in the chat).
 */
class Prompts(private val source: (String) -> String?) {
    fun load(name: String): String = source(name)?.trim() ?: throw IllegalStateException("Prompt file missing: $name")

    fun playbooksText(names: Iterable<String>): String = names.joinToString("\n\n") { load("playbooks/$it") }

    /**
     * One prompt with its placeholders filled in. [chips]: the labels of this message's reply buttons;
     * [preferences]: the lines you asked of the assistant ("shorter messages please").
     */
    fun render(
        name: String,
        playbooks: Iterable<String> = emptyList(),
        chips: List<String> = emptyList(),
        preferences: List<String> = emptyList(),
        values: Map<String, String> = emptyMap(),
    ): String {
        val all = HashMap(values)
        all.putIfAbsent("core", load("core"))
        all["playbooks"] = playbooksText(playbooks)
        all.putIfAbsent("chips", chipsText(chips))
        all.putIfAbsent("preferences", preferencesText(preferences))
        val out = PLACEHOLDER.replace(load(name)) { m -> all[m.groupValues[1]] ?: m.value }
        return BLANK_LINES.replace(out, "\n\n") // an empty {playbooks} leaves no gap
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{(\w+)\}""")
        private val BLANK_LINES = Regex("\n{3,}")

        /** Every file the app needs. */
        val NAMES = listOf("system", "core", "checkin", "daily_morning", "daily_checkin", "daily_evening", "daily_reentry")

        fun chipsText(labels: List<String>): String = if (labels.isEmpty()) "(none)" else labels.joinToString(" · ")

        /** The block for {preferences}, empty when there are none. */
        fun preferencesText(lines: List<String>): String =
            if (lines.isEmpty()) "" else "# What the user asked of you (always follow these)\n" + lines.joinToString("\n") { "- $it" }
    }
}

/** The prompt set as the backend's GET /prompts sends it. */
@Serializable
data class PromptSet(val version: String = "", val prompts: Map<String, String> = emptyMap())

/**
 * Where the prompts come from on the phone: the downloaded set (a file in the app's storage) first, then the copies
 * shipped in the app. A downloaded set that lacks a file the app needs is ignored as a whole, so a half-broken
 * download can't leave the assistant without its instructions.
 */
class PromptFiles(private val context: Context) {
    private val file = File(context.filesDir, "prompts.json")

    @Volatile private var downloaded: PromptSet? = null
    @Volatile private var loaded = false

    val prompts: Prompts = Prompts(::read)

    fun version(): String? = current()?.version

    /** Keep a set downloaded from the backend. False when it's incomplete (and so not kept). */
    fun save(set: PromptSet): Boolean {
        val missing = Prompts.NAMES.filter { set.prompts[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            Diag.log("llm", "Downloaded prompts ignored: missing ${missing.joinToString()}")
            return false
        }
        file.writeText(JSON.encodeToString(PromptSet.serializer(), set))
        downloaded = set
        loaded = true
        return true
    }

    private fun current(): PromptSet? {
        if (!loaded) {
            downloaded = runCatching { JSON.decodeFromString(PromptSet.serializer(), file.readText()) }.getOrNull()
            loaded = true
        }
        return downloaded
    }

    private fun read(name: String): String? =
        current()?.prompts?.get(name) ?: runCatching { context.assets.open("prompts/$name.md").use { it.readBytes().decodeToString() } }.getOrNull()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
