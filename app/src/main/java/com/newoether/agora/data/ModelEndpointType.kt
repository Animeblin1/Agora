package com.newoether.agora.data

import com.newoether.agora.api.decisions.DecisionsProvider

/**
 * Which endpoint one model is served on.
 *
 * A provider defaults to one transport, but a single host often serves several: OpenRouter, for
 * instance, exposes chat completions and its Decisions router on the same base URL under
 * different paths, so the transport is a property of the model rather than of the provider.
 *
 * [CUSTOM] carries the path explicitly because a self-hosted relay can mount a compatible
 * endpoint anywhere; without a path there is nothing to guess from.
 */
enum class ModelEndpointType(val wireValue: String) {
    /** Ordinary OpenAI-compatible chat completions. */
    COMPLETIONS("completions"),

    /** OpenAI's Responses API: `input` items instead of a `messages` array. */
    RESPONSES("responses"),

    /**
     * A System One decision endpoint: typed questions in, probabilities out. It does not stream
     * and does not produce prose.
     */
    DECISIONS("decisions"),

    /** Any other path, supplied by the user. */
    CUSTOM("custom");

    companion object {
        val selectable: List<ModelEndpointType> = entries.toList()

        /**
         * Reads a stored value, including the `custom:<path>` spelling.
         *
         * A custom endpoint is only useful together with the path it lives at, so the two are
         * stored as one value rather than in a second map that could drift out of step with the
         * type it belongs to.
         */
        fun fromWireValue(value: String?): ModelEndpointType {
            val raw = value?.trim()?.lowercase().orEmpty()
            if (raw.startsWith(CUSTOM_PREFIX)) return CUSTOM
            return entries.firstOrNull { it.wireValue == raw } ?: COMPLETIONS
        }

        /** The stored value for a type, with the path folded in for [CUSTOM]. */
        fun toWireValue(type: ModelEndpointType, customPath: String? = null): String =
            if (type == CUSTOM) {
                CUSTOM_PREFIX + customPath?.trim()?.trimStart('/').orEmpty()
            } else {
                type.wireValue
            }

        /** The path carried by a stored `custom:` value. */
        fun customPathOf(value: String?): String {
            val raw = value?.trim().orEmpty()
            if (!raw.startsWith(CUSTOM_PREFIX, ignoreCase = true)) return ""
            return raw.substring(CUSTOM_PREFIX.length).trim().trimStart('/')
        }

        private const val CUSTOM_PREFIX = "custom:"
    }
}

/** The path a [type] appends to a provider base URL; null when the caller must decide. */
fun endpointPathFor(
    type: ModelEndpointType,
    baseUrl: String,
    customPath: String? = null,
): String = when (type) {
    ModelEndpointType.COMPLETIONS -> "chat/completions"
    ModelEndpointType.RESPONSES -> "responses"
    ModelEndpointType.DECISIONS -> DecisionsProvider.DEFAULT_DECISIONS_PATH
    ModelEndpointType.CUSTOM -> customPath?.trim()?.trimStart('/').orEmpty()
}

/**
 * The OpenRouter decisions route.
 *
 * OpenRouter keeps decisions outside the `/api/v1` prefix its chat endpoint uses, so the path is
 * not a suffix of the saved base URL and has to be named in full.
 */
fun openRouterDecisionsPath(): String = DecisionsProvider.OPENROUTER_DECISIONS_PATH
