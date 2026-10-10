package com.newoether.agora.ui.settings

import com.newoether.agora.api.decisions.DecisionQuestionType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One question as the editor shows it.
 *
 * The wire form is a JSON map with a type and either instructions or criteria, which is fine to
 * store but hostile to edit by hand. The editor works on plain fields and serializes on save, so
 * a user never has to write the shape — only what the model should decide.
 */
data class DecisionQuestionDraft(
    val name: String = "",
    val type: String = DecisionQuestionType.NOUL,
    val instructions: String = "",
    val options: List<String> = emptyList(),
) {
    /** True when the endpoint would reject it: no name, or nothing that says what to judge. */
    val isEmpty: Boolean
        get() = name.isBlank() && instructions.isBlank() && options.all { it.isBlank() }

    val filledOptions: List<String>
        get() = options.map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        /** The three types as the editor offers them, in a stable order. */
        val TYPES: List<String> = listOf(
            DecisionQuestionType.NOUL,
            DecisionQuestionType.CHOICE,
            DecisionQuestionType.SCORE,
        )
    }
}

private val json = Json { prettyPrint = true }

/**
 * Reads the stored map into drafts.
 *
 * A stored document can be richer than the editor draws — criteria may be structured, not just
 * text — so anything unreadable is kept verbatim in the instructions field rather than dropped,
 * and the draft round-trips back to the same JSON.
 */
fun decodeDecisionQuestions(raw: String?): List<DecisionQuestionDraft> {
    if (raw.isNullOrBlank()) return emptyList()
    val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: return emptyList()
    return root.map { (name, value) ->
        val entry = runCatching { value.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val type = entry["type"]?.jsonPrimitive?.content ?: DecisionQuestionType.NOUL
        val instructions = entry["instructions"]?.let { element ->
            element.takeIf { it !is JsonObject && it !is JsonArray }
                ?.jsonPrimitiveOrNull()
                ?: element.toString()
        }.orEmpty()
        val criteria = entry["criteria"]
        val options = when (criteria) {
            is JsonObject -> criteria.keys.toList()
            is JsonArray -> criteria.mapNotNull { it.jsonPrimitiveOrNull() }
            else -> emptyList()
        }
        DecisionQuestionDraft(
            name = name,
            type = type,
            instructions = instructions,
            options = options,
        )
    }
}

/** Serializes drafts back into the stored map, skipping the ones left blank. */
fun encodeDecisionQuestions(drafts: List<DecisionQuestionDraft>): String {
    val usedNames = mutableSetOf<String>()
    val entries = drafts.filter { !it.isEmpty }.map { draft ->
        val name = draft.name.trim().ifBlank { "question" }
        var unique = name
        var suffix = 2
        while (unique in usedNames) {
            unique = "${name}_$suffix"
            suffix++
        }
        usedNames += unique
        val body = buildJsonObject {
            put("type", draft.type)
            if (draft.instructions.isNotBlank()) put("instructions", draft.instructions.trim())
            val options = draft.filledOptions
            if (options.isNotEmpty()) {
                when (draft.type) {
                    // A choice's options are the keys; a score's are an ordered array.
                    DecisionQuestionType.CHOICE -> put(
                        "criteria",
                        buildJsonObject {
                            options.distinct().forEach { put(it, JsonPrimitive("")) }
                        },
                    )
                    DecisionQuestionType.SCORE -> put(
                        "criteria",
                        buildJsonArray { options.forEach { add(JsonPrimitive(it)) } },
                    )
                }
            }
        }
        unique to body
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject(entries.toMap()))
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): String? =
    runCatching { jsonPrimitive.content }.getOrNull()
