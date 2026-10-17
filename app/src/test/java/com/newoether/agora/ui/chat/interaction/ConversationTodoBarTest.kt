package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.Typography
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

    @Test
    fun typographyMatchesInteractionCapsuleAndThinkingDetail() {
        showTodo(reduced = true)
        val capsuleTitle = compose.onNodeWithText("Todo", useUnmergedTree = true).textStyle()
        assertTypography(Typography.titleSmall.copy(fontWeight = FontWeight.Bold), capsuleTitle)
        assertTypography(Typography.labelMedium,
            compose.onNodeWithText("0 / 1", useUnmergedTree = true).textStyle())

        compose.onNodeWithText("Todo").performClick()
        val titles = compose.onAllNodesWithText("Todo", useUnmergedTree = true)
        assertTypography(ChatType.detailTitle, titles[1].textStyle())
        val counts = compose.onAllNodesWithText("0 / 1", useUnmergedTree = true)
        assertTypography(Typography.labelMedium, counts[1].textStyle())
        assertTypography(Typography.bodyMedium,
            compose.onNodeWithText("Short task", useUnmergedTree = true).textStyle())
    }

    @Test
    fun blankBodyDragsSharedSheetWithMotion() = assertBlankBodyDrags(reduced = false)

    @Test
    fun blankBodyDragsSharedSheetWithReducedMotion() = assertBlankBodyDrags(reduced = true)

    private fun assertBlankBodyDrags(reduced: Boolean) {
        showTodo(reduced)
        compose.onNodeWithText("Todo").performClick()
        val list = compose.onNode(hasScrollAction())
        val partialHeight = list.fetchSemanticsNode().boundsInRoot.height
        val dialog = compose.onNode(isDialog())
        dialog.performTouchInput {
            swipe(Offset(centerX, height - 40f), Offset(centerX, height * 0.3f), 500)
        }
        val expandedHeight = list.fetchSemanticsNode().boundsInRoot.height
        assertTrue("Dragging blank space expands the shared sheet", expandedHeight > partialHeight * 1.5f)
        compose.onNodeWithText("Short task").assertExists()
        dialog.performTouchInput {
            swipe(Offset(centerX, height * 0.6f), Offset(centerX, height - 40f), 500)
        }
        assertEquals(partialHeight, list.fetchSemanticsNode().boundsInRoot.height, 2f)
        dialog.performTouchInput {
            swipe(Offset(centerX, height * 0.75f), Offset(centerX, height - 16f), 500)
        }
        compose.onNodeWithText("Short task").assertDoesNotExist()
        compose.onNodeWithText("Todo").assertExists()
    }

    private fun showTodo(reduced: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme(typography = Typography) {
                    Box(Modifier.fillMaxSize()) {
                        ConversationTodoBar("c", listOf(ConversationTodoItem("Short task")), {})
                    }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.textStyle(): TextStyle {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style
    }

    private fun assertTypography(expected: TextStyle, actual: TextStyle) {
        assertEquals(expected.fontFamily, actual.fontFamily)
        assertEquals(expected.fontSize, actual.fontSize)
        assertEquals(expected.lineHeight, actual.lineHeight)
        assertEquals(expected.fontWeight, actual.fontWeight)
    }
}
