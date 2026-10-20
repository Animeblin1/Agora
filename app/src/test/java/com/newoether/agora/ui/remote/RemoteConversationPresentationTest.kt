package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.remote.*
import com.newoether.agora.viewmodel.AnimatedScrollRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h800dp-mdpi")
class RemoteConversationPresentationTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(RemoteState(
        restoring = false, deviceId = "device",
        devices = listOf(RemoteDevice("device", "Computer", "http://computer", RemoteDeviceStatus.CONNECTED,
            desktopStatus = RemoteDeviceStatus.CONNECTED)),
        session = RemoteSession("draft", "", "", 0), draftSessionId = "draft",
        drafts = mapOf("device/draft" to "Unsent draft"),
    ))
    private val vmState = MutableStateFlow(state)
    private val vm = mockk<RemoteViewModel>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show() {
        every { vm.state } answers { vmState.apply { value = state } }
        every { vm.animatedScrollRequest } returns MutableStateFlow<AnimatedScrollRequest?>(null)
        every { settings.blurEffectsEnabled } returns MutableStateFlow(false)
        every { settings.amoledEnabled } returns MutableStateFlow(false)
        every { settings.parseInlineDollarMath } returns MutableStateFlow(false)
        every { settings.stickToBottom } returns MutableStateFlow(true)
        every { settings.autoWrapCodeBlocks } returns MutableStateFlow(true)
        every { settings.toolCallDisplayMode } returns MutableStateFlow(ToolCallDisplayModes.DEFAULT)
        every { settings.thinkingSegmentDisplayMode } returns MutableStateFlow(ThinkingSegmentDisplayModes.DEFAULT)
        every { settings.autoExpandActiveGroup } returns MutableStateFlow(true)
        compose.setContent {
            MaterialTheme {
                RemoteConversation(state, vm, settings, true, {}, {}, { _, _ -> }, { _, _, _ -> })
            }
        }
    }

    @Test fun draftStaysEditableThroughFailureAndSafeRetryWithoutASecondSubmissionPath() {
        show()
        compose.onNodeWithText(text(R.string.remote_new_chat_hint)).assertIsDisplayed()
        compose.onNodeWithTag("remote-welcome-mark").assertIsDisplayed()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_new_chat_hint))
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft").performTextReplacement("Unsent draft continued")
        compose.runOnIdle {
            state = state.copy(failure = RemoteFailure.NETWORK,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) })
        }
        compose.onNodeWithText(text(R.string.remote_offline), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft continued")
        compose.onNodeWithText(text(R.string.retry)).performClick()
        verify(exactly = 1) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
        verify(exactly = 0) { vm.newSession() }
        compose.runOnIdle {
            state = state.copy(failure = null,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) })
        }
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft continued").performTextReplacement("Unsent draft continued more")
        compose.waitForIdle()
        verify { vm.editDraft("device/draft", "Unsent draft continued more") }
    }

    @Test fun existingOpeningShowsConnectionGuidanceAlongsideTheCanonicalLoadingIndicator() {
        state = state.copy(draftSessionId = null, loading = true)
        show()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )).assertExists()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, failure = RemoteFailure.SERVICE) }
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_online), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.retry)).assertIsEnabled().performClick()
        verify(exactly = 1) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
    }

    @Test fun admittedHistoryRemainsRenderedAfterConnectionFailureWithoutAnEmptyView() {
        val message = ChatMessage(id = "answer", text = "Retained answer", participant = Participant.USER)
        every { vm.observeMessage(any(), "answer") } returns flowOf(message)
        every { vm.cachedMessage(any(), "answer") } returns message
        state = state.copy(draftSessionId = null, hydrationEnabled = true,
            runtime = RemoteRuntime("idle"), messageGroups = listOf(RemoteMessageGroup(message, emptyList())))
        show()
        compose.onNodeWithText("Retained answer", substring = true).assertExists()
        compose.runOnIdle {
            state = state.copy(hydrationEnabled = false, runtime = null, failure = RemoteFailure.NETWORK,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) })
        }
        compose.onNodeWithText("Retained answer", substring = true).assertExists()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        verify(exactly = 0) { vm.retryConnection() }
    }
}
