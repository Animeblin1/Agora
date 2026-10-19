package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ShellConfirmationController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChatUserInteractionBarHostTest {
    @get:Rule val compose = createComposeRule()

    private val questions = MutableStateFlow(emptyList<AskUserController.Request>())
    private val shell = MutableStateFlow<ShellConfirmationController.PendingShellCommand?>(null)
    private val askUser = mockk<AskUserController>(relaxed = true)
    private val confirmations = mockk<ShellConfirmationController>(relaxed = true)
    private val viewModel = mockk<ChatViewModel>(relaxed = true)
    private var owner by mutableStateOf("c")
    private var reveal: Any? = null
    private var waiting = false
    private val events = mutableListOf<Any?>()

    @Test
    fun everyNewQuestionInAnExistingSetRevealsButEditsRemovalsAndOtherOwnersDoNot() {
        mount()
        assertNull(reveal)
        assertEquals(listOf<Any?>(null), events)
        val first = question(1, setId = 20)
        update { questions.value = listOf(first) }
        val firstReveal = reveal
        assertNotNull(firstReveal)
        val count = events.size
        update { questions.value = listOf(first.copy(question = "Updated wording")) }
        assertEquals(firstReveal, reveal)
        update { questions.value = listOf(first, question(90, conversation = "other")) }
        assertEquals(count, events.size)
        update { questions.value = listOf(first, question(2, setId = 20)) }
        val nextReveal = reveal
        assertNotEquals(firstReveal, nextReveal)
        assertEquals(count + 1, events.size)
        update { questions.value = listOf(first) }
        assertEquals(nextReveal, reveal)
        update { questions.value = emptyList() }
        assertEquals(nextReveal, reveal)
        assertTrue(!waiting)
    }

    @Test
    fun equalNumericShellAndQuestionIdsRevealSeparatelyAndIgnoreForeignShells() {
        questions.value = listOf(question(7))
        mount()
        val firstReveal = reveal
        update { shell.value = command(7, "other") }
        assertEquals(firstReveal, reveal)
        update { shell.value = command(7) }
        val shellReveal = reveal
        assertNotEquals(firstReveal, shellReveal)
        val pending = shell.value!!
        update { shell.value = pending.copy(summary = "different display only") }
        assertEquals(shellReveal, reveal)
        update { shell.value = null }
        assertEquals(shellReveal, reveal)
        update { questions.value = listOf(question(7), question(8)) }
        assertNotEquals(shellReveal, reveal)
    }

    @Test
    fun minimizedArrivalStaysFoldedAndEveryExplicitExpansionGetsAnotherToken() {
        questions.value = listOf(question(1))
        mount()
        val initialReveal = reveal
        compose.onNodeWithContentDescription(text(R.string.interaction_minimize)).performClick()
        compose.waitForIdle()
        assertEquals(initialReveal, reveal)
        update { questions.value = listOf(question(1), question(2)) }
        val arrivalReveal = reveal
        assertNotEquals(initialReveal, arrivalReveal)
        compose.onNodeWithContentDescription(text(R.string.interaction_expand)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.interaction_expand)).performClick()
        compose.waitForIdle()
        val expandedReveal = reveal
        assertNotEquals(arrivalReveal, expandedReveal)
        compose.onNodeWithText("Question 1?").assertExists()
        compose.onNodeWithText("1 / 2").assertExists()
        compose.onNodeWithContentDescription(text(R.string.interaction_minimize)).performClick()
        compose.waitForIdle()
        assertEquals(expandedReveal, reveal)
        compose.onNodeWithContentDescription(text(R.string.interaction_expand)).performClick()
        compose.waitForIdle()
        assertNotEquals(expandedReveal, reveal)
        verify(exactly = 0) { askUser.submitAll(any()) }
        verify(exactly = 0) { askUser.dismiss(any()) }
        verify(exactly = 0) { confirmations.resolve(any(), any(), any()) }
    }

    @Test
    fun navigationDoesNotRevealPendingRequestsButSameOwnerArrivalsStillDo() {
        questions.value = listOf(question(1), question(2, conversation = "d"))
        mount()
        val cReveal = reveal
        update { owner = "d" }
        val dReveal = reveal
        assertNull(dReveal)
        assertNotEquals(cReveal, dReveal)
        val count = events.size
        update { questions.value = questions.value + question(3) }
        assertEquals(dReveal, reveal)
        assertEquals(count, events.size)
        update { questions.value = questions.value + question(4, conversation = "d") }
        assertNotEquals(dReveal, reveal)
        val newRequestReveal = reveal
        assertEquals("d", (newRequestReveal as Pair<*, *>).first)
        update { owner = "c" }
        assertNull("Returning to an existing pending question is navigation", reveal)
        update { owner = "d" }
        assertNull("Returning to the other pending request does not replay its event", reveal)
        update { questions.value = questions.value + question(5, conversation = "d") }
        assertNotNull(reveal)
        assertNotEquals("A genuine new arrival gets a fresh serial", newRequestReveal, reveal)
        update { owner = "empty" }
        assertNull(reveal)
        assertTrue(!waiting)
    }

    private fun mount() {
        every { viewModel.askUser } returns askUser
        every { viewModel.shellConfirmation } returns confirmations
        every { askUser.requests } returns questions
        every { confirmations.pendingShellCommand } returns shell
        compose.setContent {
            MaterialTheme {
                ChatUserInteractionBarHost(viewModel, owner, false) { present, key, interaction ->
                    SideEffect {
                        waiting = present
                        reveal = key
                        if (events.isEmpty() || events.last() != key) events += key
                    }
                    Box(Modifier.width(400.dp)) {
                        interaction(Modifier, {}, {}, true, { _, _ -> })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun update(change: () -> Unit) {
        compose.runOnIdle(change)
        compose.waitForIdle()
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun question(id: Long, conversation: String = "c", setId: Long? = null) =
        AskUserController.Request(
            id = id, conversationId = conversation, question = "Question $id?", options = emptyList(),
            allowMultiple = false, blocking = false, setId = setId,
        )

    private fun command(id: Long, conversation: String = "c") =
        ShellConfirmationController.PendingShellCommand(
            id = id, conversationId = conversation, server = "test-server", summary = "echo test-$id",
            deferred = CompletableDeferred(),
        )
}
