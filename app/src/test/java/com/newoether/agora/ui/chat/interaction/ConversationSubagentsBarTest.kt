package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.model.SubagentSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationSubagentsBarTest {
    @get:Rule val compose = createComposeRule()

    private fun agent(id: String, title: String = id, status: String = "idle") =
        SubagentSummary(id, title, "Provider:model", "parent", status)

    @Test fun ownerChangeFadesInertOutgoingRowAndSkipsIntermediateOwner() {
        var owner by mutableStateOf("a")
        var items by mutableStateOf(listOf(agent("a", "Worker A")))
        var height = 0f
        var selected: String? = null
        compose.setContent {
            MaterialTheme { ConversationSubagentsBar(owner, items, { selected = it }, { height = it }) }
        }
        compose.runOnIdle { }
        val fullHeight = height
        assertTrue(fullHeight > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner = "b"; items = listOf(agent("b", "Worker B")) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("Worker A").assertExists().assertIsNotEnabled().performClick()
        assertNull(selected)
        compose.onNodeWithText("Worker B").assertDoesNotExist()
        assertTrue("Outgoing lift follows fade: $height", height > 0f && height < fullHeight)
        compose.runOnIdle { owner = "c"; items = listOf(agent("c", "Worker C")) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Worker A").assertDoesNotExist()
        compose.onNodeWithText("Worker B").assertDoesNotExist()
        compose.onNodeWithText("Worker C").assertExists().performClick()
        assertEquals("c", selected)
        assertEquals(fullHeight, height, 0.1f)
        compose.runOnIdle { items = listOf(agent("c", "Updated worker", "completed")) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Updated worker").assertExists()
        assertEquals("Status/title updates do not replay entrance", fullHeight, height, 0.1f)
    }

    @Test fun clearingRetainsOnlyDisabledPaintUntilLiftReachesZero() {
        var items by mutableStateOf(listOf(agent("child", "Worker")))
        var height = 0f
        compose.setContent { MaterialTheme { ConversationSubagentsBar("parent", items, {}, { height = it }) } }
        compose.runOnIdle { }
        val fullHeight = height
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { items = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("Worker").assertExists().assertIsNotEnabled()
        assertTrue(height > 0f && height < fullHeight)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Worker").assertDoesNotExist()
        assertEquals(0f, height)
    }

    @Test fun reducedMotionSnapsLiftButKeepsFadeAndDisposalReleasesMeasurement() {
        val agents = listOf(agent("child", "Worker"))
        var items by mutableStateOf(agents)
        var visible by mutableStateOf(true)
        var height = 0f
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                MaterialTheme {
                    if (visible) ConversationSubagentsBar("parent", items, {}, { height = it })
                }
            }
        }
        compose.runOnIdle { }
        assertTrue(height > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { items = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("Worker").assertExists().assertIsNotEnabled()
        assertEquals(0f, height)
        compose.runOnIdle { items = agents }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Worker").assertExists()
        assertTrue(height > 0f)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { visible = false }
        compose.onNodeWithText("Worker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0f, height) }
    }

    @Test fun capsulesNavigateScrollAndReleaseTheirMeasuredLift() {
        val agents = (0..8).map { index ->
            SubagentSummary("child-$index", "Worker $index", "Provider:model", "parent",
                listOf("running", "completed", "failed", "stopped", "idle")[index % 5])
        }
        var items by mutableStateOf(agents)
        var owner by mutableStateOf<String?>("parent")
        var selected: String? = null
        var height = 0f
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ConversationSubagentsBar(owner, items, { selected = it }, { height = it })
                }
            }
        }
        compose.onNodeWithText("Worker 0").assertExists().performClick()
        assertEquals("child-0", selected)
        assertTrue(height > 0f)
        compose.onNode(hasScrollAction()).performScrollToIndex(8)
        compose.onNodeWithText("Worker 8").assertExists().performClick()
        assertEquals("child-8", selected)
        compose.runOnIdle { items = emptyList() }
        compose.onNodeWithText("Worker 8").assertDoesNotExist()
        assertEquals(0f, height)
        compose.runOnIdle { items = agents; owner = null }
        compose.onNodeWithText("Worker 0").assertDoesNotExist()
        assertEquals(0f, height)
    }
}
