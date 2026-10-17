package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.newoether.agora.model.ConversationTodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConversationTodoBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun capsuleIsCenteredAndReadOnlySheetClosesOnClearOrOwnerChange() {
        var owner by mutableStateOf("c")
        val tasks = listOf(ConversationTodoItem("Literal **task**", "completed"), ConversationTodoItem("Next"))
        var items by mutableStateOf(tasks)
        var height = 0f
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ConversationTodoBar(owner, items, onHeightChanged = { height = it })
                }
            }
        }
        val title = compose.onNodeWithText("Todo")
        title.assertExists()
        assertTrue(height > 0f)
        val capsule = title.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, capsule.center.x, 1f)
        title.performClick()
        compose.onNodeWithText("Literal **task**").assertExists().assertHasNoClickAction()
        compose.runOnIdle { items = emptyList() }
        compose.onNodeWithText("Literal **task**").assertDoesNotExist()
        assertEquals(0f, height)
        compose.runOnIdle { items = tasks }
        compose.onNodeWithText("Literal **task**").assertDoesNotExist()
        compose.onNodeWithText("Todo").performClick()
        compose.onNodeWithText("Literal **task**").assertExists()
        compose.runOnIdle { owner = "other" }
        compose.onNodeWithText("Literal **task**").assertDoesNotExist()
    }
}
