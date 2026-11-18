package com.prominder.myapp.data

import com.prominder.myapp.logic.Logic.ChipJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The reply buttons under a message, as stored (JSON text, the way the server's thread.py wrote them). */
object Chips {
    /** Python's json.dumps(…, ensure_ascii=False), key for key. */
    fun encode(chips: List<ChipJson>): String = chips.joinToString(", ", "[", "]") { c ->
        val fields = mutableListOf("\"id\": ${str(c.id)}", "\"label\": ${str(c.label)}")
        c.action?.let { fields += "\"action\": ${str(it)}" }
        c.minutes?.let { fields += "\"minutes\": $it" }
        c.taskId?.let { fields += "\"task_id\": $it" }
        c.taskIds?.let { ids -> fields += "\"task_ids\": " + ids.joinToString(", ", "[", "]") }
        c.exclude?.let { ids -> fields += "\"exclude\": " + ids.joinToString(", ", "[", "]") }
        c.lowEnergy?.let { fields += "\"low_energy\": $it" }
        fields.joinToString(", ", "{", "}")
    }

    /** The stored text back to chips; anything unreadable is left out. */
    fun decode(text: String?): List<ChipJson> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.string("id") ?: return@mapNotNull null
            ChipJson(
                id = id,
                label = o.string("label") ?: id,
                action = o.string("action"),
                minutes = (o["minutes"] as? JsonPrimitive)?.intOrNull,
                taskId = (o["task_id"] as? JsonPrimitive)?.longOrNull,
                taskIds = o.ids("task_ids"),
                lowEnergy = (o["low_energy"] as? JsonPrimitive)?.booleanOrNull,
                exclude = o.ids("exclude"),
            )
        }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.ids(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    private fun str(s: String) = JsonPrimitive(s).toString()
}
