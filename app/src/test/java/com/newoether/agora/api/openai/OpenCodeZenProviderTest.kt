package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiDelta
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiReasoningDetail
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.util.Constants
import com.newoether.agora.api.openai.OpenCodeZenFreeModels.Verdict
import com.newoether.agora.api.openai.OpenCodeZenFreeModels.Verdict.DEPRECATED
import com.newoether.agora.api.openai.OpenCodeZenFreeModels.Verdict.FREE
import com.newoether.agora.api.openai.OpenCodeZenFreeModels.Verdict.PAID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenCodeZenProviderTest {

    private val provider = OpenCodeZenProvider(appVersion = "1.0")

    private fun request(
        tools: List<ToolDefinition>? = null,
        stream: Boolean = true,
    ): OpenAiChatRequest = OpenAiChatRequest(
        model = "mimo-v2.6-flash-free",
        messages = listOf(OpenAiMessage(role = "user")),
        stream = stream,
        tools = tools,
    )

    private fun tool(name: String) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = "d",
            parameters = ToolParameters(type = "object", properties = emptyMap()),
        ),
    )

    private fun config(sessionId: String? = null): ProviderConfig = ProviderConfig(
        apiKey = "",
        modelId = "mimo-v2.6-flash-free",
        sessionId = sessionId,
    )

    @Test
    fun theEndpointIsTheZenGatewayAndTheCredentialIsPublic() {
        assertEquals("https://opencode.ai/zen/v1", provider.defaultBaseUrl)
        assertEquals(Constants.PROVIDER_OPENCODE_ZEN, provider.name)
        assertEquals("public", provider.resolveApiKey(""))
        assertEquals("public", provider.resolveApiKey("   "))
        // A real Zen key still wins, so the paid ids on the same endpoint stay reachable.
        assertEquals("sk-zen", provider.resolveApiKey("sk-zen"))
    }

    @Test
    fun aBlankKeySendsTheAnonymousCredentialOnBothPaths() {
        assertEquals("Bearer public", provider.modelFetchHeaders("")["Authorization"])
        assertEquals("Bearer public", provider.modelFetchHeaders("  ")["Authorization"])
        assertEquals("Bearer sk-zen", provider.modelFetchHeaders("sk-zen")["Authorization"])
        assertEquals("cli", provider.modelFetchHeaders("")["x-opencode-client"])
    }

    @Test
    fun everyGateToolIsDeclaredWithGenuineSchemasEvenWithoutAnyRealTool() {
        val customized = freeLaneRequest(request(tools = null), config())
        val names = customized.tools.orEmpty().map { it.function.name }
        assertEquals(OpenCodeZenProvider.FREE_LANE_GATE_TOOLS.toList(), names)
        // The gate tools carry genuine schemas, not empty stubs.
        customized.tools.orEmpty().forEach { def ->
            assertTrue(def.function.name, def.function.parameters.schema != null)
        }
        // Tool choice passes through unchanged: no `none` is forced when the app declared no tools.
        assertNull(customized.toolChoice)
        assertTrue(customized.stream)
    }

    @Test
    fun gateToolsSerializeExactlyLikeTheReferenceSnapshot() {
        val snapshot = requireNotNull(javaClass.getResourceAsStream("/opencode/core-tools.json"))
            .bufferedReader().use { it.readText() }
        val expected = Json.parseToJsonElement(snapshot).jsonObject
        val actual = freeLaneRequest(request(), config()).tools.orEmpty()
        assertEquals(expected.keys, actual.map { it.function.name }.toSet())
        actual.forEach { tool ->
            val reference = requireNotNull(expected[tool.function.name]).jsonObject
            assertEquals(reference["description"]?.jsonPrimitive?.content, tool.function.description)
            // Exercise the real serializer, not only the in-memory schema field: parameter
            // descriptions and all JSON Schema keywords must survive onto the wire.
            val wire = Json.parseToJsonElement(
                Json.encodeToString(ToolDefinition.serializer(), tool),
            ).jsonObject
            val function = requireNotNull(wire["function"]).jsonObject
            assertEquals(reference["parameters"], function["parameters"])
        }
    }

    @Test
    fun realToolsComeFirstAndMissingGateToolsAreAppended() {
        val customized = freeLaneRequest(
            request(tools = listOf(tool("file_read"), tool("read"))),
            config(),
        )
        val names = customized.tools.orEmpty().map { it.function.name }
        assertEquals(listOf("file_read", "read"), names.take(2))
        assertTrue(names.containsAll(OpenCodeZenProvider.FREE_LANE_GATE_TOOLS))
        // No duplicate `read`: the caller's declaration is kept, not duplicated.
        assertEquals(names.toSet().size, names.size)
        // Real tools exist, so the model keeps its own choice.
        assertNull(customized.toolChoice)
    }

    @Test
    fun aRealToolSharingAGateNameKeepsItsBodyAndIsNotDuplicated() {
        val customized = freeLaneRequest(
            request(tools = listOf(tool("shell"))),
            config(),
        )
        val names = customized.tools.orEmpty().map { it.function.name }
        // The caller's `shell` is one entry; the genuine rest are appended once.
        assertEquals(listOf("shell") + (OpenCodeZenProvider.FREE_LANE_GATE_TOOLS - "shell").toList(), names)
        assertEquals(names.toSet().size, names.size)
    }

    @Test
    fun messagesPassThroughVerbatimWithNoDeveloperMarker() {
        val customized = freeLaneRequest(
            request(tools = null, ).copy(messages = listOf(OpenAiMessage(role = "user", content = listOf(OpenAiContentPart(type = "text", text = "hi"))))),
            config(),
        )
        // No `developer` role is ever injected into the body.
        assertFalse(customized.messages.any { it.role == "developer" })
        assertEquals(1, customized.messages.size)
        assertEquals("user", customized.messages.first().role)
    }

    @Test
    fun aProvidedToolChoiceIsPreserved() {
        val customized = freeLaneRequest(
            request(tools = listOf(tool("shell"))).copy(toolChoice = "auto"),
            config(),
        )
        assertEquals("auto", customized.toolChoice)
    }

    @Test
    fun aNonStreamingBodyIsForcedOntoTheStreamingLane() {
        val customized = freeLaneRequest(request(stream = false), config())
        assertTrue(customized.stream)
        assertTrue(requireNotNull(customized.streamOptions).includeUsage)
    }

    @Test
    fun headersCarryACanonicalSessionAcrossAllNames() {
        val headers = zenHeaders(config(sessionId = "conversation-1"), "1.0")
        val session = requireNotNull(headers["x-opencode-session"])
        assertTrue(session, session.matches(Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")))
        assertEquals(session, headers["x-opencode-session-id"])
        assertEquals(session, headers["x-session-affinity"])
        assertEquals(session, headers["X-Session-Id"])
        assertEquals("cli", headers["x-opencode-client"])
        // OpenCode CLI User-Agent shape (no OS/arch/runtime suffix).
        assertEquals("opencode/latest/2.0.29/cli", headers["User-Agent"])
        // The gateway's own correlation header is dropped (rejected with 403 by the lane).
        assertFalse(headers.containsKey("x-opencode-request"))
        // Bare 40-hex project id, not the gateway's `prj_` form.
        assertTrue(requireNotNull(headers["x-opencode-project"]), headers["x-opencode-project"]!!.matches(Regex("^[0-9a-f]{40}$")))
    }

    @Test
    fun theSameConversationKeepsOneStableSession() {
        val first = zenHeaders(config(sessionId = "conversation-1"), "1.0")
        val second = zenHeaders(config(sessionId = "conversation-1"), "1.0")
        // Upstream prompt-cache affinity depends on the session being stable across turns.
        assertEquals(first["x-opencode-session"], second["x-opencode-session"])
        assertFalse(
            first["x-opencode-session"] ==
                zenHeaders(config(sessionId = "conversation-2"), "1.0")["x-opencode-session"],
        )
    }

    @Test
    fun gateToolNamesAreRefusedByTheExecutor() {
        OpenCodeZenProvider.FREE_LANE_GATE_TOOLS.forEach { name ->
            assertTrue(name, isFreeLaneGateTool(name))
            assertFalse("$name must never execute", zenAcceptsToolCall(name))
        }
        assertTrue(zenAcceptsToolCall("file_read"))
        assertTrue(zenAcceptsToolCall("web_search"))
    }

    @Test
    fun aZeroCostModelIsFreeEvenWithoutAFreeHintInItsId() {
        // big-pickle costs nothing upstream but carries no "-free" hint in its id, so only the
        // metadata cost — not the name — can let it stay in the catalog.
        val metadata = """
            {"opencode":{"models":{
                "big-pickle":{"cost":{"input":0,"output":0}},
                "claude-opus-5":{"cost":{"input":5,"output":25}},
                "mimo-v2.6-flash-free":{"cost":{"input":0,"output":0}}
            }}}
        """.trimIndent()
        val verdicts = OpenCodeZenFreeModels.parse(metadata)
        assertEquals(FREE, verdicts["big-pickle"])
        assertEquals(FREE, verdicts["mimo-v2.6-flash-free"])
        assertEquals(PAID, verdicts["claude-opus-5"])
    }

    @Test
    fun theMetadataIsReadFromTheOpenCodeSectionOnly() {
        // Hundreds of vendors are listed; a zero-cost model from another one is not a Zen model.
        val metadata = """
            {"somevendor":{"models":{"free-thing":{"cost":{"input":0,"output":0}}}},
             "opencode":{"models":{"zen-zero":{"cost":{"input":0,"output":0}}}}}
        """.trimIndent()
        assertEquals(mapOf("zen-zero" to FREE), OpenCodeZenFreeModels.parse(metadata))
    }

    @Test
    fun aPriceThatIsNotZeroInEitherDirectionIsPaid() {
        val metadata = """
            {"opencode":{"models":{
                "input-only":{"cost":{"input":0,"output":1}},
                "output-only":{"cost":{"input":1,"output":0}}
            }}}
        """.trimIndent()
        val verdicts = OpenCodeZenFreeModels.parse(metadata)
        assertEquals(PAID, verdicts["input-only"])
        assertEquals(PAID, verdicts["output-only"])
    }

    @Test
    fun aDeprecatedModelIsRefusedEvenAtZeroCost() {
        // grok-code costs nothing upstream but the lane answers 401 "not supported"; deprecation is
        // the only published signal that separates it from a zero-cost model that does answer.
        val metadata = """
            {"opencode":{"models":{
                "groq-code":{"cost":{"input":0,"output":0},"status":"deprecated"},
                "retired":{"cost":{"input":0,"output":0},"status":"retired"},
                "flagged":{"cost":{"input":0,"output":0},"deprecated":true},
                "dated":{"cost":{"input":0,"output":0},"retirement_date":"2026-01-01"},
                "big-pickle":{"cost":{"input":0,"output":0}}
            }}}
        """.trimIndent()
        val verdicts = OpenCodeZenFreeModels.parse(metadata)
        assertEquals(DEPRECATED, verdicts["groq-code"])
        assertEquals(DEPRECATED, verdicts["retired"])
        assertEquals(DEPRECATED, verdicts["flagged"])
        assertEquals(DEPRECATED, verdicts["dated"])
        assertEquals(FREE, verdicts["big-pickle"])
    }

    @Test
    fun anEntryWithoutACostIsNotAssumedFree() {
        val metadata = """{"opencode":{"models":{"mystery":{"limit":{"context":8000}}}}}"""
        assertEquals(emptyMap<String, Verdict>(), OpenCodeZenFreeModels.parse(metadata))
    }

    @Test
    fun verifiedIdsCoverTheCatalogWhenTheMetadataCannotSpeak() {
        // A dead metadata host must cost breadth, not the whole provider.
        val fallback = OpenCodeZenFreeModels.verifiedFallback()
        assertTrue(fallback.contains("big-pickle"))
        assertTrue(fallback.contains("mimo-v2.6-flash-free"))
        assertFalse(fallback.contains("claude-opus-5"))
    }

    @Test
    fun aVerifiedIdOutranksDeprecationButNeverAPrice() {
        // muse-spark-1.2-contributor-free is flagged deprecated upstream yet still answers; the
        // verified roster is confirmed against the lane itself, so it wins over the flag.
        val metadata = """
            {"opencode":{"models":{
                "muse-spark-1.2-contributor-free":{"cost":{"input":0,"output":0},"status":"deprecated"},
                "big-pickle":{"cost":{"input":5,"output":25}}
            }}}
        """.trimIndent()
        val verdicts = OpenCodeZenFreeModels.parse(metadata)
        assertEquals(DEPRECATED, verdicts["muse-spark-1.2-contributor-free"])
        assertEquals(PAID, verdicts["big-pickle"])
        assertTrue(
            OpenCodeZenFreeModels.verifiedFallback()
                .contains("muse-spark-1.2-contributor-free"),
        )
    }

    @Test
    fun unreadableMetadataYieldsNothingRatherThanEverything() {
        // A truncated or unexpected document must not promote the whole paid catalog to "free".
        assertEquals(emptyMap<String, Verdict>(), OpenCodeZenFreeModels.parse("not json"))
        assertEquals(emptyMap<String, Verdict>(), OpenCodeZenFreeModels.parse("{}"))
    }

    @Test
    fun anonymousGenerationMovesToTheInferenceBase() {
        // Proven by A/B probe: the genuine free-lane body is 403 on /zen/v1 and streams 200
        // on /inference/v1, so a blank key with no custom base must leave the Zen base.
        assertEquals(
            "https://opencode.ai/inference/v1",
            provider.chatEndpointBaseUrl(config()),
        )
        assertEquals(
            "https://opencode.ai/inference/v1",
            provider.chatEndpointBaseUrl(config().copy(baseUrl = "https://opencode.ai/zen/v1")),
        )
        // A real Zen key keeps the paid lane: its ids live on the Zen base.
        assertEquals(
            "https://opencode.ai/zen/v1",
            provider.chatEndpointBaseUrl(config().copy(apiKey = "sk-zen")),
        )
        // An explicitly configured base (a local proxy, for instance) is never hijacked,
        // keyed or not.
        assertEquals(
            "http://localhost:8080",
            provider.chatEndpointBaseUrl(config().copy(baseUrl = "http://localhost:8080/")),
        )
        assertEquals(
            "http://localhost:8080",
            provider.chatEndpointBaseUrl(
                config().copy(apiKey = "sk-zen", baseUrl = "http://localhost:8080/"),
            ),
        )
    }

    @Test
    fun reasoningArrivesOnceWhetherStructuredOrBare() {
        // Zen repeats the same text in reasoning_details and in the bare `reasoning` string.
        assertEquals(
            listOf(StreamEvent.ThoughtChunk("think"), StreamEvent.TextChunk("answer")),
            zenEvents(
                OpenAiDelta(
                    reasoningDetails = listOf(
                        OpenAiReasoningDetail(type = "reasoning.text", text = "think"),
                    ),
                    reasoning = "think",
                    content = "answer",
                ),
            ),
        )
        assertEquals(
            listOf(StreamEvent.ThoughtChunk("think"), StreamEvent.TextChunk("answer")),
            zenEvents(OpenAiDelta(reasoning = "think", content = "answer")),
        )
        assertEquals(
            listOf(StreamEvent.ThoughtChunk("think")),
            zenEvents(OpenAiDelta(reasoningContent = "think")),
        )
        assertEquals(emptyList<StreamEvent>(), zenEvents(OpenAiDelta()))
    }
}
