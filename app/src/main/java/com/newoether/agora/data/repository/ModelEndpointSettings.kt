package com.newoether.agora.data.repository

import com.newoether.agora.data.ModelEndpointType

/**
 * Per-model endpoint settings, exposed as extensions so the repository itself stays within the
 * source-size budget: these read nothing private beyond the [hot] flows already declared there.
 */

/**
 * Applies an endpoint choice to one model.
 *
 * The type and its questions are saved together because they are one decision: a decisions model
 * with no questions cannot answer anything, and a chat model carrying stale questions would send
 * them to an endpoint that does not understand them.
 */
fun setModelEndpoint(
    settings: SettingsRepository,
    model: String,
    type: ModelEndpointType?,
    questions: String? = null,
) {
    settings.scope.launch { settings.settingsManager.updateModelEndpoint(model, type, questions) }
}

/** Reads the endpoint type of one model, falling back to the provider's own transport. */
fun SettingsRepository.endpointTypeFor(model: String): ModelEndpointType =
    ModelEndpointType.fromWireValue(modelEndpointTypes.value[model])

/**
 * The questions to persist for a chosen endpoint type.
 *
 * Only a decisions endpoint has anywhere to send them, so moving a model back to chat drops the
 * stored blob instead of leaving it behind to be sent somewhere that cannot read it.
 */
fun endpointQuestionsToPersist(type: ModelEndpointType, questions: String): String? =
    questions.takeIf { type == ModelEndpointType.DECISIONS }

/**
 * The questions a request should carry for [model].
 *
 * Read from the live map rather than a snapshot so a change in settings reaches the next request
 * without restarting anything, and only for a decisions endpoint — a chat transport would receive
 * a field it does not know.
 */
fun endpointQuestionsFor(settings: SettingsRepository, model: String): String? {
    val type = ModelEndpointType.fromWireValue(settings.modelEndpointTypes.value[model])
    if (type != ModelEndpointType.DECISIONS) return null
    return settings.modelDecisionQuestions.value[model]?.takeIf { it.isNotBlank() }
}
