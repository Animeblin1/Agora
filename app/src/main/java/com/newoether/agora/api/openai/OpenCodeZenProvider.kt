package com.newoether.agora.api.openai

import com.newoether.agora.api.*
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json

/**
 * OpenCode Zen's anonymous free lane, served through the OpenAI-compatible contract
 * (`https://opencode.ai/zen/v1`).
 *
 * No account and no API key are involved: the credential is the literal [ANONYMOUS_KEY], which the
 * gateway accepts for its free tier. Several gates make the request read as an OpenCode CLI session,
 * and all are enforced upstream — a request failing any one is answered 403 `FreeTierError`
 * ("OpenCode's free tier can only be used from within OpenCode"). The admission depends on:
 *
 *  1. a canonical session id (`ses_` + 12 hex + 14 Base62) mirrored across the correlation headers —
 *     see [OpenCodeZenIdentity];
 *  2. a CLI User-Agent (`opencode/latest/<version>/cli`) — see [OPENCODE_CLI_VERSION];
 *  3. a bare 40-character hex `x-opencode-project` id (the gateway's own `prj_` form is rejected);
 *  4. an agent-shaped streaming body whose `tools` declare the genuine OpenCode core tools
 *     ([FREE_LANE_GATE_TOOLS]: `shell/edit/glob/grep/read`) with their real parameter schemas, so the
 *     upstream model sees a coding-agent context.
 *
 * The body is otherwise sent verbatim: messages and tool_choice pass through unchanged, no `developer`
 * message or marker text is injected, and the model is free to call the tools. The gate tools are
 * declared only to shape the request; if the model emits one, Agora has no executor for it and drops
 * the call (see [zenAcceptsToolCall]). This mirrors the opencode2api gateway, which proxies the very
 * same `https://opencode.ai/zen/v1` endpoint on the anonymous `Bearer public` credential.
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
     * The gate tools exist only to shape the request. A model that calls one anyway would otherwise
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

        /** Version advertised in the CLI User-Agent. Tracks the latest OpenCode CLI the gate accepts. */
        const val OPENCODE_CLI_VERSION = "2.0.29"

        /** Genuine OpenCode core tools the free lane expects on every request body. */
        val FREE_LANE_GATE_TOOLS: Set<String> = setOf("shell", "edit", "glob", "grep", "read")

        /** Genuine definitions (real schemas) for the core tools, ported from opencode2api. */
        internal val GENUINE_GATE_TOOLS: Map<String, ToolDefinition> = mapOf(
            "shell" to gateDef("shell", SHELL_DESC, SHELL_SCHEMA),
            "edit" to gateDef("edit", EDIT_DESC, EDIT_SCHEMA),
            "glob" to gateDef("glob", GLOB_DESC, GLOB_SCHEMA),
            "grep" to gateDef("grep", GREP_DESC, GREP_SCHEMA),
            "read" to gateDef("read", READ_DESC, READ_SCHEMA),
        )

        private fun gateDef(name: String, description: String, schemaJson: String): ToolDefinition =
            ToolDefinition(
                function = ToolFunction(
                    name = name,
                    description = description,
                    parameters = ToolParameters(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList(),
                        schema = Json.parseToJsonElement(schemaJson).jsonObject,
                    ),
                ),
            )

        private const val EDIT_DESC =
            "Edit a file on disk. Use this tool to replace text within a file. Use the oldString to find the text to replace and newString as the replacement. If the oldString matches multiple locations, include more surrounding context in oldString to disambiguate. If there are multiple matches, set replaceAll to true to replace all of them. The edit will FAIL if oldString is not unique and replaceAll is not set. The edit will also FAIL if oldString doesn't match exactly, including whitespace. Do not use this tool to create new files, use the write tool instead."

        private const val GLOB_DESC =
            "Find files by name pattern. Searches the current working directory and its subdirectories."

        private const val GREP_DESC =
            "Search file contents by pattern. Returns matching lines with file paths and line numbers."

        private const val READ_DESC =
            "Read the contents of a file or directory. For a file, returns the content. For a directory, returns the listing. Use offset and limit to read specific portions for large files."

        private const val SHELL_DESC =
            "Execute a shell command and return its output. Commands run on Windows using powershell. Quote file paths containing spaces or special characters. Prefer dedicated tools over shell commands when possible. When output is large, the full result is saved to a file and a truncated preview is returned. Rely on automatic truncation unless filtering the output is more useful. Commands accept an optional timeout, background commands have no timeout by default. Background commands return immediately, and you will be notified when they complete."

        private const val EDIT_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"newString":{"type":"string"},"oldString":{"type":"string"},"path":{"type":"string"},"replaceAll":{"type":"boolean"}},"required":["path","oldString","newString"]}
        """

        private const val GLOB_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"hidden":{"type":"boolean"},"limit":{"type":"integer","exclusiveMinimum":0},"path":{"type":"string"},"pattern":{"type":"string"}},"required":["pattern"]}
        """

        private const val GREP_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"caseSensitive":{"type":"boolean"},"include":{"type":"string"},"limit":{"type":"integer","exclusiveMinimum":0},"literal":{"type":"boolean"},"path":{"type":"string"},"pattern":{"type":"string","minLength":1}},"required":["pattern"]}
        """

        private const val READ_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"limit":{"type":"integer","minimum":0},"offset":{"type":"integer","minimum":0},"path":{"type":"string"}},"required":["path"]}
        """

        private const val SHELL_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{"background":{"type":"boolean"},"command":{"type":"string"},"timeout":{"type":"integer","minimum":0},"workdir":{"type":"string"}},"required":["command"]}
        """
    }
}

/** Whether [toolName] is one of the core tools the free lane expects but Agora cannot run. */
internal fun isFreeLaneGateTool(toolName: String): Boolean =
    toolName in OpenCodeZenProvider.FREE_LANE_GATE_TOOLS

/**
 * Admission a call gets on the way to Agora's executor. Gate names are declared only to shape the
 * free-lane request and have no executor behind them, so they are dropped here.
 */
internal fun zenAcceptsToolCall(toolName: String): Boolean = !isFreeLaneGateTool(toolName)

/**
 * The request the free lane accepts: an agent-shaped stream with the genuine OpenCode core tools
 * declared. Messages and tool_choice pass through verbatim — no `developer` marker message is
 * injected — mirroring how opencode2api proxies the same endpoint.
 */
internal fun freeLaneRequest(
    request: OpenAiChatRequest,
    config: ProviderConfig,
): OpenAiChatRequest {
    val resolved = config.resolvedThinking(ThinkingProviderFamily.OPENAI_COMPATIBLE)
    return request.copy(
        messages = request.messages,
        tools = withFreeLaneGateTools(request.tools),
        toolChoice = request.toolChoice,
        // The lane only serves streams; a non-streaming body is refused outright.
        stream = true,
        streamOptions = request.streamOptions ?: OpenAiStreamOptions(includeUsage = true),
        reasoningEffort = if (resolved.disabled) "none" else resolved.effort,
    )
}

/**
 * Mirrors opencode2api's `ensureAnonymousTools`: a declared tool whose name is a core OpenCode tool
 * is replaced with its genuine definition, and any core tool the caller omitted is appended so the
 * body always exposes the full coding-agent tool set the lane expects.
 */
internal fun withFreeLaneGateTools(tools: List<ToolDefinition>?): List<ToolDefinition> {
    val declared = tools.orEmpty()
    val out = declared.map { def -> OpenCodeZenProvider.Companion.GENUINE_GATE_TOOLS[def.function.name] ?: def }
    val present = out.mapTo(LinkedHashSet()) { it.function.name }
    val missing = OpenCodeZenProvider.FREE_LANE_GATE_TOOLS - present
    return out + missing.mapNotNull { OpenCodeZenProvider.Companion.GENUINE_GATE_TOOLS[it] }
}

/**
 * CLI-identical correlation headers. Upstream pairs the session id across several header names, wants
 * the OpenCode User-Agent shape and a bare 40-hex `x-opencode-project`, and rejects the gateway's own
 * `x-opencode-request` header (403 `FreeTierError` since 2026-10-14). Dropping or changing any one of
 * them turns the request into a 403, so the client's header set is mirrored exactly.
 */
internal fun zenHeaders(config: ProviderConfig, appVersion: String): Map<String, String> {
    val ids = OpenCodeZenIdentity.derive(config.sessionId)
    return buildMap {
        put("User-Agent", "opencode/latest/${OpenCodeZenProvider.OPENCODE_CLI_VERSION}/cli")
        put("x-opencode-client", "cli")
        put("x-opencode-session", ids.session)
        put("x-opencode-session-id", ids.session)
        put("x-session-affinity", ids.session)
        put("X-Session-Id", ids.session)
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
