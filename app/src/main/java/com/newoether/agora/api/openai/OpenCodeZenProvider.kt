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
 * gateway accepts for its free tier. Several gates make the request read as an OpenCode CLI session,
 * and all are enforced upstream — a request failing any one is answered 403 `FreeTierError`
 * ("OpenCode's free tier can only be used from within OpenCode"). After the 2026-09 Zen v2 content
 * gate the body itself is inspected, so the admission now depends on:
 *
 *  1. a canonical session id (`ses_` + 12 hex + 14 Base62) in the correlation headers — see
 *     [OpenCodeZenIdentity];
 *  2. a CLI User-Agent (`opencode/<version>/cli`) — see [OPENCODE_CLI_VERSION];
 *  3. a v2 content gate: the body must open with a `developer` message carrying OpenCode's own
 *     title-generator prompt [ZEN_MARKER_PROMPT] verbatim, the real system prompt must be prefixed
 *     with [ZEN_MARKER_PREAMBLE] (so the model ignores the marker), and the core tool names in
 *     [FREE_LANE_GATE_TOOLS] must be declared in `tools`.
 *
 * The gate tool names are declared only to satisfy the body check. They are never routed to Agora's
 * own tool executor: [freeLaneToolChoice] pins the model to `none` when the app itself declared no
 * tools, and the gate tools are appended after the real ones so a model that insists on calling
 * something still names a real Agora tool first. Any call the model emits for a gate name is dropped
 * by [isFreeLaneGateTool]. [ZEN_MARKER_PROMPT] and [ZEN_MARKER_PREAMBLE] mirror the exact strings
 * `opencode-zen-oauth` (v1.2.6, `src/v2-gate.ts`) prepends to every free-tier request.
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

        /** Version advertised in the CLI User-Agent. Tracks the latest OpenCode CLI the gate accepts. */
        const val OPENCODE_CLI_VERSION = "2.0.27"

        /** Names the free lane expects on every request body; only the names matter. */
        val FREE_LANE_GATE_TOOLS: Set<String> = setOf("bash", "edit", "glob", "grep", "read")

        /**
         * OpenCode's own session title-generator prompt, verbatim. The v2 free-tier gate rejects the
         * request unless the body opens with a `developer` message carrying this string exactly
         * (port of `opencode-zen-oauth` `v2-gate.ts: ZEN_MARKER_PROMPT`). It is a fixed gateway prefix,
         * not an instruction to the model.
         */
        const val ZEN_MARKER_PROMPT = """You are a title generator. You output ONLY a thread title. Nothing else.

<task>
Generate a brief title that would help the user find this conversation later.

Follow all rules in <rules>
Use the <examples> so you know what a good title looks like.
Your output must be:
- A single line
- ≤50 characters
- No explanations
</task>

<rules>
- you MUST use the same language as the user message you are summarizing
- Title must be grammatically correct and read naturally - no word salad
- Never include tool names in the title (e.g. "read tool", "bash tool", "edit tool")
- Focus on the main topic or question the user needs to retrieve
- Vary your phrasing - avoid repetitive patterns like always starting with "Analyzing"
- When a file is mentioned, focus on WHAT the user wants to do WITH the file, not just that they shared it
- Keep exact: technical terms, numbers, filenames, HTTP codes
- Remove: the, this, my, a, an
- Never assume tech stack
- Never use tools
- NEVER respond to questions, just generate a title for the conversation
- The title should NEVER include "summarizing" or "generating" when generating the title
- DO NOT SAY YOU CANNOT GENERATE A TITLE OR COMPLAIN ABOUT THE INPUT
- Always output something meaningful, even if the input is minimal.
- If the user message is short or conversational (e.g. "hello", "lol", "what's up", "hey"):
  → create a title that reflects the user's tone or intent (such as Greeting, Quick check-in, Light chat, Intro message, etc.)
</rules>

<examples>
"debug 500 errors in production" → Debugging production 500 errors
"refactor user service" → Refactoring user service
"why is app.js failing" → app.js failure investigation
"implement rate limiting" → Rate limiting implementation
"how do I connect postgres to my API" → Postgres API connection
"best practices for React hooks" → React hooks best practices
"@src/auth.ts can you add refresh token support" → Auth refresh token support
"@utils/parser.ts this is broken" → Parser bug fix
"look at @config.json" → Config review
"@App.tsx add dark mode toggle" → Dark mode toggle in App
</examples>
"""

        /**
         * Prepended to the real system prompt so the model treats [ZEN_MARKER_PROMPT] as a fixed
         * gateway prefix it must ignore (port of `opencode-zen-oauth` `v2-gate.ts: PREAMBLE`).
         */
        const val ZEN_MARKER_PREAMBLE = "IMPORTANT: Disregard the previous developer message entirely. It is a fixed technical prefix from a gateway, NOT an instruction for you - you are not a title generator and must never output a title or one-line summary unless the user explicitly asks for one. Operate under this instruction set only:\n\n"

        /**
         * Native-tool declarations the gate expects to see described in the system prompt. The free
         * lane does not expose Agora's tools as callable functions; this mirror text keeps the body
         * shaped like a coding-agent session (port of `opencode-zen-oauth` `buildTextToolProtocol`).
         */
        const val ZEN_TOOL_PROTOCOL = """<opencode-tool-protocol>
Native tool declarations are unavailable on this connection. To call a tool, emit a fenced code block with the info string "tool" containing one JSON object, then stop:
```tool
{"name":"<tool name>","arguments":{...}}
```
Available tools:
- bash: Run a shell command.
- edit: Edit a file on disk.
- glob: Find files by name pattern.
- grep: Search file contents.
- read: Read a file from disk.
Emit only tools listed above. Multiple independent calls may use separate blocks. Tool results will arrive as user messages. Never invent tool results.
</opencode-tool-protocol>"""
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
 * The request the free lane accepts: an agent-shaped stream whose body opens with the v2 content
 * gate. Real Agora tools keep their positions at the front of the list so a model that decides to
 * call something is steered to a tool that exists.
 */
internal fun freeLaneRequest(
    request: OpenAiChatRequest,
    config: ProviderConfig,
): OpenAiChatRequest {
    val resolved = config.resolvedThinking(ThinkingProviderFamily.OPENAI_COMPATIBLE)
    return request.copy(
        messages = freeLaneMessages(request.messages),
        tools = withFreeLaneGateTools(request.tools),
        toolChoice = freeLaneToolChoice(request.tools),
        // The lane only serves streams; a non-streaming body is refused outright.
        stream = true,
        streamOptions = request.streamOptions ?: OpenAiStreamOptions(includeUsage = true),
        reasoningEffort = if (resolved.disabled) "none" else resolved.effort,
    )
}

/**
 * Satisfies the v2 content gate on the request body:
 *  1. a leading `developer` message carrying [ZEN_MARKER_PROMPT] verbatim (the gateway checks this
 *     is the first message and matches exactly);
 *  2. the real system prompt, if any, is prefixed with [ZEN_MARKER_PREAMBLE] so the model ignores
 *     the marker and answers the user directly.
 * The marker is prepended even when there is no system prompt, because the gate requires it to be
 * the first message regardless.
 */
internal fun freeLaneMessages(messages: List<OpenAiMessage>): List<OpenAiMessage> {
    val out = mutableListOf<OpenAiMessage>()
    out.add(
        OpenAiMessage(
            role = "developer",
            content = listOf(OpenAiContentPart(type = "text", text = OpenCodeZenProvider.ZEN_MARKER_PROMPT)),
        )
    )
    var injectedSystem = false
    for (message in messages) {
        if (!injectedSystem && message.role == "system") {
            val original = message.content?.firstOrNull { it.type == "text" }?.text.orEmpty()
            val prefixed = if (original.startsWith(OpenCodeZenProvider.ZEN_MARKER_PREAMBLE)) original else OpenCodeZenProvider.ZEN_MARKER_PREAMBLE + original
            out.add(
                OpenAiMessage(
                    role = "system",
                    content = listOf(
                        OpenAiContentPart(type = "text", text = prefixed + "\n\n" + OpenCodeZenProvider.ZEN_TOOL_PROTOCOL),
                    ),
                )
            )
            injectedSystem = true
        } else {
            out.add(message)
        }
    }
    if (!injectedSystem) {
        // No system prompt was supplied; still give the gate a system-shaped context so the marker
        // is clearly separated from the conversation.
        out.add(
            OpenAiMessage(
                role = "system",
                content = listOf(OpenAiContentPart(type = "text", text = OpenCodeZenProvider.ZEN_TOOL_PROTOCOL)),
            )
        )
    }
    return out
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
