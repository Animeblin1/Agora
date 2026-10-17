package com.newoether.agora.tool

import com.newoether.agora.automation.SubagentManager
import com.newoether.agora.model.SubagentCompletion
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SubagentToolProviderTest {
    private val manager = mockk<SubagentManager>()
    private val provider = SubagentToolProvider(manager)
    private val context = GenerationContext(conversationId = "parent", subagentToolsEnabled = true,
        enabledModelIds = listOf("Provider:one", "Provider:two"))

    @Test fun definitionsExposeOnlyEnabledIdsAndNoToolsInChildrenOrCompact() {
        assertTrue(provider.definitions(context.copy(subagentToolsEnabled = false)).isEmpty())
        val definitions = provider.definitions(context)
        assertEquals(listOf("spawn_agent", "send_agent_message", "wait_agents", "list_agents", "stop_agent"),
            definitions.map { it.function.name })
        val description = definitions.first().function.description
        assertTrue(description.contains("Provider:one"))
        assertTrue(description.contains("Provider:two"))
        assertFalse(description.contains("price"))
    }

    @Test fun spawnAndWaitReturnExactConversationAndCompletionReceipts() = runTest {
        coEvery { manager.spawn("Worker", "Provider:one", "Task", context) } returns "child"
        val spawn = Json.parseToJsonElement(provider.execute("spawn_agent",
            """{"name":"Worker","model_id":"Provider:one","task":"Task"}""", context)).jsonObject
        assertEquals("child", spawn["agent_id"]?.jsonPrimitive?.content)
        coEvery { manager.wait(listOf("child"), 50L, context) } returns
            listOf("child" to SubagentCompletion("completion", "completed", "Answer"))
        val wait = Json.parseToJsonElement(provider.execute("wait_agents",
            """{"agent_ids":["child"],"timeout_ms":50}""", context)).jsonObject
        assertEquals("completion", wait["subagent_receipts"]?.jsonObject?.get("child")?.jsonPrimitive?.content)
        assertEquals("Answer", wait["results"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test fun disabledAndInvalidToolsNeverReachManagerAndCancellationPropagates() = runTest {
        assertTrue(provider.executeOneShotResult("spawn_agent", "{}", context).isError)
        assertTrue(provider.executeOneShotResult("list_agents", "{}",
            context.copy(subagentToolsEnabled = false)).isError)
        coVerify(exactly = 0) { manager.spawn(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.list(any()) }
        coEvery { manager.stop("child", context) } throws CancellationException("Stop")
        assertTrue(runCatching {
            provider.executeOneShotResult("stop_agent", """{"agent_id":"child"}""", context)
        }.exceptionOrNull() is CancellationException)
    }
}
