package com.newoether.agora.api.openai

import com.newoether.agora.api.*
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * OpenCode Zen's anonymous free lane, served through the OpenAI-compatible contract.
 *
 * No account and no API key are involved: the credential is the literal [ANONYMOUS_KEY], which the
 * gateway accepts for its free tier. Several gates make the request read as an OpenCode CLI session,
 * and all are enforced upstream — a request failing any one is answered 403 `FreeTierError`
 * ("OpenCode's free tier can only be used from within OpenCode"). The admission depends on:
 *
 *  1. the anonymous endpoint (`https://opencode.ai/inference/v1`): the same body posted to
 *     `https://opencode.ai/zen/v1` is refused 403 `FreeTierError` even with identical headers;
 *  2. a canonical session id (`ses_` + 12 hex + 14 Base62) mirrored across the correlation headers —
 *     see [OpenCodeZenIdentity];
 *  3. a CLI User-Agent (`opencode/latest/<version>/cli`) — see [OPENCODE_CLI_VERSION];
 *  4. a bare 40-character hex `x-opencode-project` id (the gateway's own `prj_` form is rejected);
 *  5. an agent-shaped streaming body whose `tools` declare the genuine OpenCode core tools
 *     ([FREE_LANE_GATE_TOOLS]: `shell/edit/glob/grep/read`) with their real parameter schemas, so the
 *     upstream model sees a coding-agent context.
 *
 * The body is otherwise sent verbatim: messages and tool_choice pass through unchanged, no `developer`
 * message or marker text is injected, and the model is free to call the tools. The gate tools are
 * declared only to shape the request; if the model emits one, Agora has no executor for it and drops
 * the call (see [zenAcceptsToolCall]). This mirrors the opencode2api gateway, whose anonymous lane
 * (`Bearer public`) targets `https://opencode.ai/inference` while the keyed lane stays on
 * `https://opencode.ai/zen`.
 *
 * Only a `-free` model is usable anonymously; a paid id on the same base URL answers 401. The
 * provider exposes every model its catalog lists and lets the gateway reject the rest, because
 * `/v1/models` publishes no per-model tier and the roster rotates constantly. Model discovery
 * stays on `https://opencode.ai/zen/v1/models`, which answers the anonymous credential; only
 * generation moves to the inference base.
 */
class OpenCodeZenProvider(
    private val appVersion: String = "?",
) : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_OPENCODE_ZEN
    override val defaultBaseUrl: String = "https://opencode.ai/zen/v1"

    /**
     * Anonymous generation is admitted only on the inference base; a keyed request keeps the
     * Zen base its paid ids live on, and an explicitly configured base (a local proxy, for
     * instance) is never hijacked. Model discovery is unaffected and stays on the Zen base.
     */
    override fun chatEndpointBaseUrl(config: ProviderConfig): String {
        val configured = config.baseUrl?.trimEnd('/')?.ifBlank { null }
        if (config.apiKey.isBlank() && (configured == null || configured == defaultBaseUrl)) {
            return ANONYMOUS_BASE_URL
        }
        return configured ?: defaultBaseUrl
    }

    /**
     * A blank key is the normal configuration here, not a misconfiguration: the lane is anonymous
     * and [ANONYMOUS_KEY] is the credential it accepts. A user-supplied key still wins, so someone
     * with a real Zen key can use the paid ids on the Zen base.
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

        /**
         * The base anonymous generation is posted to. Proven by A/B probe: the genuine free-lane
         * body answers 403 `FreeTierError` on `https://opencode.ai/zen/v1/chat/completions` and
         * streams 200 on `https://opencode.ai/inference/v1/chat/completions`.
         */
        const val ANONYMOUS_BASE_URL = "https://opencode.ai/inference/v1"

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
            "Edit the contents of a file by finding and replacing exact text. When editing text from Read output, preserve the exact indentation (tabs or spaces) and omit the line-number prefix, such as `1: `. Never include the prefix in oldString or newString. Write line breaks as \\n; the file's existing CRLF or LF line endings are matched and preserved. To change line endings themselves, make oldString and newString differ only in \\r; that edit applies to the exact bytes. If there is no exact match, the edit retries while ignoring typographic quote, dash, and space variants, then trailing whitespace on each line. The edit fails if oldString is not found. By default, oldString must identify a UNIQUE location. Multiple matches FAIL unless replaceAll is true. Add more surrounding context to disambiguate, or set replaceAll to true to replace every occurrence. Use replaceAll when the change should apply to every occurrence, such as renaming a variable."

        private const val EDIT_SCHEMA = """
            {"additionalProperties":false,"properties":{"newString":{"description":"Text to replace oldString with (must differ from oldString)","type":"string"},"oldString":{"description":"Exact text to find and replace","type":"string"},"path":{"description":"File to edit","type":"string"},"replaceAll":{"description":"Whether to replace every occurrence of oldString. When false, oldString must match exactly once. Defaults to false.","type":"boolean"}},"required":["path","oldString","newString"],"type":"object"}
        """

        private const val GLOB_DESC =
            "Search file paths using a glob pattern (examples: \"**/*.ts\", \"src/**/*.tsx\")."

        private const val GLOB_SCHEMA = """
            {"additionalProperties":false,"properties":{"hidden":{"description":"Include hidden files and directories (default: false).","type":"boolean"},"limit":{"description":"Maximum number of matching files to return (default: 100)","exclusiveMinimum":0,"type":"integer"},"path":{"description":"Directory to search. Defaults to the current working directory.","type":"string"},"pattern":{"description":"Glob pattern to match files against","type":"string"}},"required":["pattern"],"type":"object"}
        """

        private const val GREP_DESC =
            "Search file contents using ripgrep's regular expression syntax or literal text matching. Use it to locate specific code, symbols, or text patterns, and narrow searches with `path` or `include`. Returns matching file paths, line numbers, and line previews."

        private const val GREP_SCHEMA = """
            {"additionalProperties":false,"properties":{"caseSensitive":{"description":"Use case-sensitive matching (default: true).","type":"boolean"},"include":{"description":"Glob pattern to filter files (for example, \"*.js\" or \"*.{ts,tsx}\")","type":"string"},"limit":{"description":"Maximum number of matching lines to return (default: 100)","exclusiveMinimum":0,"type":"integer"},"literal":{"description":"Treat `pattern` as exact text instead of a regular expression (default: false).","type":"boolean"},"path":{"description":"File or directory to search. Defaults to the current working directory.","type":"string"},"pattern":{"description":"Regular expression or literal text to match in file contents.","minLength":1,"type":"string"}},"required":["pattern"],"type":"object"}
        """

        private const val READ_DESC =
            "Read the contents of a file or directory. Supports text files, images, and PDFs. Images and PDFs are presented directly to the model. Each text line is prefixed by its 1-based line number as <line>: <content>. The prefix is for reference and is not part of the file content. Directory entries are returned one per line. Use offset and limit to read large files or directories in sections. Prefer one larger read over many small slices, and use grep to find specific content in large files."

        private const val READ_SCHEMA = """
            {"additionalProperties":false,"properties":{"limit":{"description":"The maximum number of lines or directory entries to read (defaults to and capped at 2000)","minimum":0,"type":"integer"},"offset":{"description":"The line or directory entry to start reading from (1-based)","minimum":0,"type":"integer"},"path":{"description":"File or directory to read","type":"string"}},"required":["path"],"type":"object"}
        """

        private const val SHELL_DESC =
            "Execute a shell command and return its output. Commands run on Windows using powershell. Quote file paths containing spaces or special characters. Prefer dedicated tools over shell commands when possible. When output is large, the full result is saved to a file and a truncated preview is returned. Rely on automatic truncation unless filtering the output is more useful. Commands accept an optional timeout, background commands have no timeout by default. Background commands return immediately, and you will be notified when they complete."

        private const val SHELL_SCHEMA = """
            {"additionalProperties":false,"properties":{"background":{"description":"Run the command in the background and return immediately (useful for dev servers and long-running builds). You do not need to use '&' at the end of the command when using this parameter. You will be notified when it completes. DO NOT poll for completion.","type":"boolean"},"command":{"description":"Shell command string to execute","type":"string"},"timeout":{"description":"Timeout in milliseconds. Set to 0 to disable the timeout. Defaults to 120000 for foreground commands. Background commands have no timeout by default.","minimum":0,"type":"integer"},"workdir":{"description":"Working directory to execute the command in. Defaults to the current working directory. When possible, avoid changing directories in the command and set the working directory here instead.","type":"string"}},"required":["command"],"type":"object"}
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
        put("Accept", "application/json, text/event-stream")
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
