package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiDelta
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiReasoningDetail
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun everyGateToolIsDeclaredEvenWithoutAnyRealTool() {
        val customized = freeLaneRequest(request(tools = null), config())
        val names = customized.tools.orEmpty().map { it.function.name }
        assertEquals(OpenCodeZenProvider.FREE_LANE_GATE_TOOLS.toList(), names)
        // Without real tools the model must not be allowed to call a stub.
        assertEquals("none", customized.toolChoice)
        assertTrue(customized.stream)
    }

    @Test
    fun realToolsComeFirstAndMissingGateToolsAreAppended() {
        val customized = freeLaneRequest(
            request(tools = listOf(tool("file_read"), tool("read"))),
            config(),
        )
        val names = customized.tools.orEmpty().map { it.function.name }
        assertEquals(listOf("file_read"), names.take(1))
        assertTrue(names.containsAll(OpenCodeZenProvider.FREE_LANE_GATE_TOOLS))
        assertEquals(names.toSet().size, names.size)
        // Real tools exist, so the model keeps its own choice.
        assertNull(customized.toolChoice)
    }

    @Test
    fun aNonStreamingBodyIsForcedOntoTheStreamingLane() {
        val customized = freeLaneRequest(request(stream = false), config())
        assertTrue(customized.stream)
        assertTrue(requireNotNull(customized.streamOptions).includeUsage)
    }

    @Test
    fun headersCarryACanonicalSessionAcrossAllThreeNames() {
        val headers = zenHeaders(config(sessionId = "conversation-1"), "1.0")
        val session = requireNotNull(headers["x-opencode-session"])
        assertTrue(session, session.matches(Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")))
        assertEquals(session, headers["x-session-affinity"])
        assertEquals(session, headers["X-Session-Id"])
        assertEquals("cli", headers["x-opencode-client"])
        assertTrue(requireNotNull(headers["User-Agent"]).startsWith("opencode/"))
        assertTrue(requireNotNull(headers["x-opencode-request"]).startsWith("req_"))
        assertTrue(requireNotNull(headers["x-opencode-project"]).startsWith("prj_"))
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
