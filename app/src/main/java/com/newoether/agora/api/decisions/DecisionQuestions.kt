package com.newoether.agora.api.decisions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The per-model question map, stored as the JSON the user edits.
 *
 * The three primitives differ only in their `criteria`, and `instructions` may be a string or an
 * arbitrary structure, so the document is validated structurally and carried through as raw
 * elements rather than mapped onto a Kotlin model that would have to guess at that variety.
 */
object DecisionQuestions {

    private val json = Json { ignoreUnknownKeys = true }

    private val TYPES = setOf(
        DecisionQuestionType.NOUL,
        DecisionQuestionType.CHOICE,
        DecisionQuestionType.SCORE,
    )

    /**
     * Parses and validates the stored questions.
     *
     * Returns null for blank input so the caller can distinguish "not configured" from
     * "configured but wrong" — a decision endpoint cannot answer anything without questions, and
     * sending an empty map is rejected upstream with a message that says nothing about the cause.
     */
    fun parse(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: throw DecisionQuestionsFormatException("the questions must be a JSON object")
        if (root.isEmpty()) {
            throw DecisionQuestionsFormatException("at least one question is required")
        }
        for ((name, value) in root) {
            val entry = value as? JsonObject
                ?: throw DecisionQuestionsFormatException("question '$name' must be an object")
            val type = entry["type"]?.jsonPrimitive?.content
                ?: throw DecisionQuestionsFormatException("question '$name' has no type")
            if (type !in TYPES) {
                throw DecisionQuestionsFormatException(
                    "question '$name' has type '$type'; expected noul, choice or score",
                )
            }
            if (entry["instructions"] == null && entry["criteria"] == null) {
                throw DecisionQuestionsFormatException(
                    "question '$name' needs instructions or criteria",
                )
            }
            validateCriteria(name, type, entry["criteria"])
        }
        return root
    }

    private fun validateCriteria(name: String, type: String, criteria: JsonElement?) {
        when (type) {
            // A noul's criteria are optional, but when present they describe exactly yes and no.
            DecisionQuestionType.NOUL -> {
                if (criteria == null) return
                criteria as? JsonObject
                    ?: throw DecisionQuestionsFormatException(
                        "question '$name' criteria must be an object with 'true' and 'false'",
                    )
            }
            // A choice's options are the keys of its criteria map, so it cannot be empty.
            DecisionQuestionType.CHOICE -> {
                val options = (criteria as? JsonObject)
                    ?: throw DecisionQuestionsFormatException(
                        "question '$name' needs criteria listing its options",
                    )
                if (options.isEmpty()) {
                    throw DecisionQuestionsFormatException("question '$name' has no options")
                }
            }
            // A score's criteria is an ordered array, lowest level first.
            DecisionQuestionType.SCORE -> {
                val levels = criteria?.takeIf { it !is JsonObject }
                if (levels == null || levels !is kotlinx.serialization.json.JsonArray) {
                    throw DecisionQuestionsFormatException(
                        "question '$name' needs criteria as an ordered array of levels",
                    )
                }
                if (levels.isEmpty()) {
                    throw DecisionQuestionsFormatException("question '$name' has no levels")
                }
            }
        }
    }

    /** A question map a user can start from when editing a model. */
    fun sample(): String = """
        {
          "answerable": {
            "type": "noul",
            "instructions": "Does the last message ask something that can be answered from the conversation?"
          }
        }
    """.trimIndent()
}

class DecisionQuestionsFormatException(message: String) : IllegalArgumentException(message)
