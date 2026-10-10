package com.newoether.agora.api.openai

import com.newoether.agora.api.*
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.util.Constants

/**
 * OpenCode Zen's anonymous free lane, served through the OpenAI-compatible contract
 * (`https://opencode.ai/zen/v1`).
 *
 * No account and no API key are involved: the credential is the literal [ANONYMOUS_KEY], which the
 * gateway accepts for its free tier. Two gates make the request read as an OpenCode CLI session,
 * and both are enforced upstream — a request failing either one is answered 403 `FreeTierError`
 * ("OpenCode's free tier can only be used from within OpenCode"):
 *
 *  1. a canonical session id (`ses_` + 12 hex + 14 Base62) in the correlation headers — see
 *     [OpenCodeZenIdentity];
 *  2. an agent-shaped stream: `stream: true` plus the core tool names in [FREE_LANE_GATE_TOOLS]
 *     declared in `tools`.
 *
 * The gate tool names are declared only to satisfy the second gate. They are never routed to Agora's
 * own tool executor: [freeLaneToolChoice] pins the model to `none` when the app itself declared no
 * tools, and the gate tools are appended after the real ones so a model that insists on calling
 * something still names a real Agora tool first. Any call the model emits for a gate name is dropped
 * by [isFreeLaneGateTool].
 *
 * Only a `-free` model is usable anonymously; a paid id on the same base URL answers 401. The
 * provider exposes every model its catalog lists and lets the gateway reject the rest, because
 * `/v1/models` publishes no per-model tier and the roster rotates constantly.
 */
class OpenCodeZenProvider(
    private val appVersion: String = "?",
) : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_OPENCODE_ZEN
    override val defaultBaseUrl: String = "https://opencode.ai/zen/v1"

    /**
     * A blank key is the normal configuration here, not a misconfiguration: the lane is anonymous
     * and [ANONYMOUS_KEY] is the credential it accepts. A user-supplied key still wins, so someone
     * with a real Zen key can use the paid ids on the same endpoint.
     */
    override fun resolveApiKey(apiKey: String): String =
        apiKey.ifBlank { ANONYMOUS_KEY }

    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest =
        freeLaneRequest(request, config)

    override fun getExtraHeaders(config: ProviderConfig): Map<String, String> =
        zenHeaders(config, appVersion)

    override suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        emit: suspend (StreamEvent) -> Unit
    ) {
        zenEvents(delta).forEach { emit(it) }
    }

    /**
     * The gate tools exist only to open the lane. A model that calls one anyway would otherwise
     * hand Agora a tool name it has no executor for; the call is dropped at the source instead.
     */
    override fun acceptsToolCall(toolName: String): Boolean = zenAcceptsToolCall(toolName)

    /** Model discovery uses the same anonymous credential the generation path does. */
    override fun modelFetchHeaders(apiKey: String): Map<String, String> =
        super.modelFetchHeaders(apiKey) + mapOf("x-opencode-client" to "cli")

    /**
     * Only models priced at zero stay in the catalog.
     *
     * Zen publishes no price in `/v1/models`, so the cost comes from the OpenCode model metadata;
     * [OpenCodeZenFreeModels] caches that lookup. A paid id would answer `401 AuthError` the first
     * time it was used, so it is kept out of the list rather than offered and then rejected.
     */
    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        super.fetchModels(apiKey, baseUrl).filter { id ->
            OpenCodeZenFreeModels.isFree(id)
        }

    internal companion object {
        /** The anonymous credential. Zen's free lane rejects any other value as unauthenticated. */
        const val ANONYMOUS_KEY = "public"

        /** Version advertised in the CLI User-Agent. */
        const val OPENCODE_CLI_VERSION = "1.18.31"

        /** Names the free lane expects on every request body; only the names matter. */
        val FREE_LANE_GATE_TOOLS: Set<String> = setOf("bash", "edit", "glob", "grep", "read")

        private const val GATE_TOOL_DESCRIPTION = "Reserved for the host runtime; do not call it."
    }
}

/** Whether [toolName] is one of the declarations the free lane requires but Agora cannot run. */
internal fun isFreeLaneGateTool(toolName: String): Boolean =
    toolName in OpenCodeZenProvider.FREE_LANE_GATE_TOOLS

/**
 * Admission a call gets on the way to Agora's executor. Gate names are declared only to pass the
 * free-lane body check and have no executor behind them, so they are dropped here.
 */
internal fun zenAcceptsToolCall(toolName: String): Boolean = !isFreeLaneGateTool(toolName)

/**
 * The request the free lane accepts: an agent-shaped stream carrying every gate tool name.
 *
 * Real Agora tools keep their positions at the front of the list so a model that decides to call
 * something is steered to a tool that exists.
 */
internal fun freeLaneRequest(
    request: OpenAiChatRequest,
    config: ProviderConfig,
): OpenAiChatRequest {
    val resolved = config.resolvedThinking(ThinkingProviderFamily.OPENAI_COMPATIBLE)
    return request.copy(
        reasoningEffort = if (resolved.disabled) "none" else resolved.effort,
        tools = withFreeLaneGateTools(request.tools),
        toolChoice = freeLaneToolChoice(request.tools),
        // The lane only serves streams; a non-streaming body is refused outright.
        stream = true,
        streamOptions = request.streamOptions ?: OpenAiStreamOptions(includeUsage = true),
    )
}

/**
 * Appends a stub definition for every gate name the request does not already declare.
 *
 * The stubs are inert: [freeLaneToolChoice] pins the model to `none` whenever the app declared no
 * tools at all.
 */
internal fun withFreeLaneGateTools(tools: List<ToolDefinition>?): List<ToolDefinition> {
    val declared = tools.orEmpty()
    val missing = OpenCodeZenProvider.FREE_LANE_GATE_TOOLS -
        declared.mapTo(LinkedHashSet()) { it.function.name }
    if (missing.isEmpty()) return declared
    return declared + missing.map(::gateTool)
}

/**
 * `none` unless the app actually declared tools. Letting the model pick a gate stub would produce a
 * call Agora cannot execute; with `none` the stubs only satisfy the body shape.
 */
internal fun freeLaneToolChoice(tools: List<ToolDefinition>?): String? =
    if (tools.isNullOrEmpty()) "none" else null

private fun gateTool(toolName: String): ToolDefinition = ToolDefinition(
    function = ToolFunction(
        name = toolName,
        description = "Reserved for the host runtime; do not call it.",
        parameters = ToolParameters(
            type = "object",
            properties = emptyMap(),
            required = emptyList(),
        ),
    ),
)

/**
 * CLI-identical correlation headers. The upstream pairs the session id across three header names,
 * and a request whose session shape is not canonical is refused before the model is even resolved.
 */
internal fun zenHeaders(config: ProviderConfig, appVersion: String): Map<String, String> {
    val ids = OpenCodeZenIdentity.derive(config.sessionId)
    return buildMap {
        put("User-Agent", "opencode/${OpenCodeZenProvider.OPENCODE_CLI_VERSION} (Android; Agora/$appVersion)")
        put("x-opencode-client", "cli")
        put("x-opencode-session", ids.session)
        put("x-session-affinity", ids.session)
        put("X-Session-Id", ids.session)
        put("x-opencode-request", ids.request)
        put("x-opencode-project", ids.project)
    }
}

/**
 * Zen reports its own chain of thought in `reasoning_details` and repeats it in the bare
 * `reasoning` string, so whichever text actually arrived is emitted once instead of twice.
 */
internal fun zenEvents(delta: OpenAiDelta): List<StreamEvent> {
    val structured = delta.reasoningDetails.orEmpty()
        .filter { it.type == "reasoning.text" || it.type == "text" }
        .mapNotNull { it.text?.takeIf(String::isNotEmpty) }
    val thoughts = structured.ifEmpty {
        listOfNotNull(
            delta.reasoningContent?.takeIf(String::isNotEmpty)
                ?: delta.reasoning?.takeIf(String::isNotEmpty),
        )
    }
    val answer = listOfNotNull(delta.content?.takeIf(String::isNotEmpty))
    return thoughts.map { StreamEvent.ThoughtChunk(it) } +
        answer.map { StreamEvent.TextChunk(it) }
}
