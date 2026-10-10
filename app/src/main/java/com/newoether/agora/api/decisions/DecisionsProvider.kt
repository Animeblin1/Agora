package com.newoether.agora.api.decisions

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A provider for the System One decision endpoints — Jev, Mercury Decide and the OpenRouter
 * Decisions router.
 *
 * These endpoints answer typed questions over a shared state instead of writing text, so this
 * provider does not extend [com.newoether.agora.api.openai.BaseOpenAiProvider]: there is no
 * `messages` array, no tool calling and no streaming. The whole answer arrives as one document,
 * which is emitted as a single [StreamEvent.TextChunk] after being described in prose.
 *
 * The conversation is the state. A chat has no application state of its own to offer beyond what
 * was said, so the messages are serialized in the shape the questions expect.
 */
class DecisionsProvider(
    override val name: String,
    override val defaultBaseUrl: String,
    /** Path under the base URL. Hosts disagree: `/v1/systemone` vs `/v1/decisions`. */
    private val decisionsPath: String = DEFAULT_DECISIONS_PATH,
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): Flow<StreamEvent> = flow {
        val questions = try {
            DecisionQuestions.parse(config.decisionQuestions)
        } catch (e: DecisionQuestionsFormatException) {
            emit(StreamEvent.Error(GenerationError.Configuration("$name: ${e.message}")))
            return@flow
        }
        if (questions == null) {
            emit(
                StreamEvent.Error(
                    GenerationError.Configuration(
                        "$name: ${config.modelId} has no decision questions configured",
                    ),
                ),
            )
            return@flow
        }
        val body = buildJsonObject {
            put("model", config.modelId)
            put("state", stateOf(messages))
            put("questions", questions)
        }.toString()

        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            if (config.apiKey.isNotBlank()) put("Authorization", "Bearer ${config.apiKey}")
        }

        val url = endpointUrl(config)
        DebugLog.d("AgoraAPI", "[$name] request decisions model=${config.modelId} url=$url")
        val response = try {
            HttpClient.postTextResponse(url, body, headers)
        } catch (e: Exception) {
            DebugLog.w("AgoraAPI", "[$name] decisions request failed: ${e.localizedMessage}")
            emit(
                StreamEvent.Error(
                    GenerationError.Network(
                        statusCode = 0,
                        message = e.localizedMessage ?: "Request failed",
                    ),
                ),
            )
            return@flow
        }
        if (!response.isSuccessful) {
            emit(
                StreamEvent.Error(
                    GenerationError.Api(
                        code = response.code.toString(),
                        type = null,
                        message = response.body.take(300),
                    ),
                ),
            )
            return@flow
        }
        val parsed = runCatching { json.decodeFromString<DecisionsResponse>(response.body) }
            .getOrNull()
        if (parsed == null) {
            emit(
                StreamEvent.Error(
                    GenerationError.Api(
                        code = null,
                        type = "unparsable",
                        message = "The endpoint returned a body that is not a decisions response.",
                    ),
                ),
            )
            return@flow
        }
        parsed.usage?.toTokenUsage()?.let { emit(StreamEvent.UsageUpdate(it)) }
        emit(StreamEvent.TextChunk(parsed.render(questions.keys.toList())))
    }

    private fun endpointUrl(config: ProviderConfig): String {
        val base = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        return "${base.trimEnd('/')}/${decisionsPath.trimStart('/')}"
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> {
        if (apiKey.isBlank()) return emptyList()
        val base = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val url = "${base.trimEnd('/')}/${MODELS_PATH.trimStart('/')}"
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val body = HttpClient.fetchModels(url, headers) ?: return emptyList()
        // TypeSafe answers {"models":[{"name":...}]}; an OpenAI-shaped {"data":[{"id":...}]} is
        // accepted too so a gateway mirroring one of these hosts still lists what it serves.
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["models"]?.namesIn("name")
                ?: root["data"]?.namesIn("id")
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun JsonElement.namesIn(field: String): List<String> {
        val array = this as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.get(field)?.jsonPrimitive?.content }
    }

    companion object {
        const val DEFAULT_DECISIONS_PATH = "v1/systemone"
        const val INCEPTION_DECISIONS_PATH = "v1/decisions"
        const val OPENROUTER_DECISIONS_PATH = "api/alpha/decisions"
        private const val MODELS_PATH = "v1/models"

        const val TYPE_SAFE_URL = "https://api.typesafe.ai"
        const val INCEPTION_URL = "https://api.inceptionlabs.ai"
        /** Decisions lives outside the `/api/v1` prefix the chat endpoint uses. */
        const val OPENROUTER_URL = "https://openrouter.ai"

        const val DEFAULT_MODEL = "jev-latest"
        const val INCEPTION_MODEL = "mercury-decide"
        const val OPENROUTER_MODEL = "inception/mercury-decide"
    }
}

/** The conversation as the state a decision model judges. */
internal fun stateOf(messages: List<ChatMessage>): JsonObject = buildJsonObject {
    put(
        "messages",
        buildJsonArray {
            messages.forEach { message ->
                add(
                    buildJsonObject {
                        put(
                            "role",
                            when (message.participant) {
                                Participant.USER -> "user"
                                Participant.MODEL -> "assistant"
                                Participant.ERROR -> "error"
                            },
                        )
                        put("content", message.text)
                    },
                )
            }
        },
    )
}
