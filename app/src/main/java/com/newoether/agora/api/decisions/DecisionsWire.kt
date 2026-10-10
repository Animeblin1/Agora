package com.newoether.agora.api.decisions

import com.newoether.agora.model.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire types for the System One family of endpoints.
 *
 * Jev, Inception's Mercury Decide and the OpenRouter Decisions router all speak the same
 * contract: a shared `state`, a map of named typed questions, and one typed answer per name.
 * The path differs per host — `/v1/systemone` on TypeSafe, `/v1/decisions` on Inception,
 * `/api/alpha/decisions` on OpenRouter — so only the body is shared here.
 *
 * None of these endpoints generate prose and none of them stream: the reply is a single JSON
 * document whose answers are probabilities, not tokens of text.
 */

/**
 * One typed question.
 *
 * `instructions` and every criterion accept a string or an arbitrary JSON structure, so they are
 * carried as raw elements rather than mapped onto a Kotlin model.
 */
@Serializable
data class DecisionQuestion(
    val type: String,
    val instructions: JsonElement? = null,
    val criteria: JsonElement? = null,
)

/** The request body. [state] is a string, object or array; [questions] is keyed by caller-chosen names. */
@Serializable
data class DecisionsRequest(
    val model: String,
    val state: JsonElement,
    val questions: Map<String, DecisionQuestion>,
)

@Serializable
data class DecisionsResponse(
    val model: String? = null,
    val answers: Map<String, DecisionAnswer>? = null,
    val usage: DecisionsUsage? = null,
)

@Serializable
data class DecisionsUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    /** OpenRouter only: the request cost in USD. */
    val cost: Double? = null,
)

/**
 * One typed answer, discriminated by [type]: `noul` (yes/no probability), `choice` (one of a
 * labelled set) or `score` (a point on an ordered scale).
 *
 * Every field but [type] is optional because the published schema marks the distribution fields
 * that way; a caller must check [type] and then read the field it implies, treating anything
 * missing as an error rather than silently defaulting.
 */
@Serializable
data class DecisionAnswer(
    val type: String? = null,
    /** For `noul`: the probability the answer is yes, 0..1. This is also the confidence. */
    val noul: Double? = null,
    /** For `choice`: the highest-probability option. */
    val choice: String? = null,
    /** For `score`: the probability-weighted level, which can land between two levels. */
    val score: Double? = null,
    val confidence: Double? = null,
    val probabilities: Map<String, Double>? = null,
    /** For `score`: maps the stringified level index back to the criterion it came from. */
    val legend: Map<String, String>? = null,
)

/** Question type names as the wire spells them. */
object DecisionQuestionType {
    const val NOUL = "noul"
    const val CHOICE = "choice"
    const val SCORE = "score"
}

internal fun DecisionsUsage.toTokenUsage(): TokenUsage = TokenUsage(
    totalTokenCount = listOfNotNull(inputTokens, outputTokens).sum().coerceAtLeast(0),
    inputTokenCount = inputTokens?.coerceAtLeast(0),
    outputTokenCount = outputTokens?.coerceAtLeast(0),
)

/**
 * Renders one answer as text a chat can display.
 *
 * The model does not write prose, so there is nothing to stream: the whole value is known at once
 * and is described instead, keeping the probability and the confidence that justify it visible.
 */
internal fun DecisionAnswer.render(name: String): String = when (type) {
    DecisionQuestionType.NOUL -> {
        val yes = noul
        if (yes == null) "$name: no answer" else "$name: yes ${percent(yes)} (no ${percent(1.0 - yes)})"
    }
    DecisionQuestionType.CHOICE -> {
        val picked = choice
        if (picked == null) {
            "$name: no answer"
        } else {
            val spread = probabilities
                ?.filterKeys { it != picked }
                ?.filterValues { it > 0.0 }
                ?.toList()
                ?.sortedByDescending(Pair<String, Double>::second)
                ?.joinToString(", ") { (option, p) -> "$option ${percent(p)}" }
                .orEmpty()
            val certainty = confidence?.let { ", confidence ${percent(it)}" }.orEmpty()
            "$name: $picked$certainty" + if (spread.isBlank()) "" else " (others: $spread)"
        }
    }
    DecisionQuestionType.SCORE -> {
        val value = score
        val level = legend?.get(value?.toInt()?.toString())
        val label = level?.let { " — $it" }.orEmpty()
        val spread = probabilities
            ?.mapNotNull { (index, p) -> legend?.get(index)?.let { it to p } }
            ?.filter { (_, p) -> p > 0.0 }
            ?.joinToString(", ") { (text, p) -> "$text ${percent(p)}" }
            .orEmpty()
        val certainty = confidence?.let { ", confidence ${percent(it)}" }.orEmpty()
        if (value == null) {
            "$name: no answer"
        } else {
            "$name: ${trim(value)}$label$certainty" + if (spread.isBlank()) "" else " ($spread)"
        }
    }
    else -> "$name: unexpected answer type ${type ?: "unknown"}"
}

/** Renders a whole response, one line per question, in the order the questions were asked. */
internal fun DecisionsResponse.render(order: List<String>): String {
    val answers = answers ?: return "The endpoint returned no answers."
    val names = order.filter { it in answers } + answers.keys.filter { it !in order }
    return names.joinToString("\n") { name ->
        answers[name]?.render(name) ?: "$name: no answer"
    }
}

private fun percent(value: Double): String = "${(value * 100).toInt()}%"

/** Drops the trailing zeros a weighted score carries, so "1.0" reads as "1". */
private fun trim(value: Double): String {
    val rounded = Math.round(value * 100.0) / 100.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

/** The criterion of a question when it is plain text; null when it is structured or absent. */
internal fun JsonObject.textField(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull
