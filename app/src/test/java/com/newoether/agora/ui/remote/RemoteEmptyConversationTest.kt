package com.newoether.agora.ui.remote

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.chat.ChatSwitchingOverlay
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.remote.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h700dp-mdpi")
class RemoteEmptyConversationTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(RemoteState(
        restoring = false, deviceId = "device",
        devices = listOf(RemoteDevice("device", "Computer", "http://computer", RemoteDeviceStatus.CONNECTED)),
        session = RemoteSession("session", "", "", 0), draftSessionId = "session",
    ))
    private var active by mutableStateOf(true)
    private var opening by mutableStateOf(false)
    private var bottom by mutableStateOf(180.dp)
    private var retries = 0
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 700.dp)) {
                    RemoteEmptyConversation(state, active, 80.dp, bottom, { retries++ }, opening)
                }
            }
        }
    }

    @Test fun onlineNewDraftConnectingOfflineAndConfirmedEmptyUseDistinctLocalizedGuidance() {
        show()
        compose.onNodeWithText(text(R.string.remote_new_chat_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        assertEquals(0, retries)
        compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) }) }
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.IDLE) }) }
        compose.onNodeWithText(text(R.string.remote_empty_offline_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertIsEnabled().performClick()
        assertEquals(1, retries)
        compose.runOnIdle {
            state = state.copy(draftSessionId = null, hydrationEnabled = true,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTED) })
        }
        compose.onNodeWithText(text(R.string.remote_empty_conversation_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
    }

    @Test fun failureIsNeutralAndRetryIsAnExplicitActionDisabledOnAnInactivePage() {
        state = state.copy(failure = RemoteFailure.SERVICE)
        show()
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertIsEnabled().performClick()
        assertEquals(1, retries)
        compose.runOnIdle { active = false }
        compose.onNodeWithText(text(R.string.retry)).assertIsNotEnabled()
        assertEquals(1, retries)
        compose.runOnIdle { state = state.copy(loading = true) }
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
    }

    @Test fun loadedContentSuppressesEveryEmptyPresentationWithoutReplacingTheHistory() {
        show()
        val message = ChatMessage(id = "answer", text = "Retained", participant = Participant.MODEL)
        val groups = listOf(RemoteMessageGroup(message, emptyList()))
        compose.runOnIdle { state = state.copy(messageGroups = groups, failure = RemoteFailure.NETWORK) }
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        assertEquals(groups, state.messageGroups)
        assertEquals(0, retries)
    }

    @Test fun guidanceFollowsTheOriginalTopAndComposerAvailableRangeIncludingImeChanges() {
        show()
        val content = compose.onNodeWithTag("remote-empty-conversation")
        val firstBounds = content.getUnclippedBoundsInRoot()
        assertEquals((80f + 700f - 180f) / 2f, (firstBounds.top.value + firstBounds.bottom.value) / 2f, 0.5f)
        compose.runOnIdle { bottom = 360.dp }
        val imeBounds = content.getUnclippedBoundsInRoot()
        assertEquals((80f + 700f - 360f) / 2f, (imeBounds.top.value + imeBounds.bottom.value) / 2f, 0.5f)
        compose.runOnIdle { opening = true; state = state.copy(loading = true) }
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertIsDisplayed()
        assertEquals((80f + 700f - 360f) / 2f + 40f,
            content.getUnclippedBoundsInRoot().top.value, 0.5f)
    }

    @Test fun passiveOpeningHintCannotStealTheOriginalLoadingCoversGestureBarrier() {
        state = state.copy(draftSessionId = null, loading = true)
        var clicks = 0
        var drags = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 700.dp)) {
                    Box(Modifier.fillMaxSize().testTag("underlying-content")
                        .clickable { clicks++ }.pointerInput(Unit) {
                            detectVerticalDragGestures { _, _ -> drags++ }
                        })
                    ChatSwitchingOverlay(true, false, 80.dp, 180.dp)
                    RemoteEmptyConversation(state, true, 80.dp, 180.dp, { retries++ }, opening = true)
                }
            }
        }
        val hint = compose.onNodeWithTag("remote-empty-conversation")
        val hintCenter = hint.fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("underlying-content").performTouchInput {
            click(hintCenter)
            swipe(hintCenter, androidx.compose.ui.geometry.Offset(hintCenter.x, hintCenter.y - 120f))
        }
        assertEquals(0, clicks)
        assertEquals(0, drags)
        assertEquals(0, retries)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
    }
}
