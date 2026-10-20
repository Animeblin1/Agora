package com.newoether.agora.ui.remote

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import android.content.res.Configuration
import java.util.Locale
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.chat.ChatSwitchingOverlay
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.remote.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        devices = listOf(RemoteDevice("device", "Computer", "http://computer", RemoteDeviceStatus.CONNECTED,
            desktopStatus = RemoteDeviceStatus.CONNECTED)),
        session = RemoteSession("session", "", "", 0), draftSessionId = "session",
    ))
    private var active by mutableStateOf(true)
    private var opening by mutableStateOf(false)
    private var bottom by mutableStateOf(180.dp)
    private var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
    private var fontScale by mutableStateOf(1f)
    private var retries = 0
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    Box(Modifier.size(400.dp, 700.dp)) {
                        RemoteEmptyConversation(state, active, 80.dp, bottom, { retries++ }, opening)
                    }
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
        compose.onNodeWithTag("remote-welcome-mark").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.runOnIdle { opening = false; state = state.copy(loading = false, failure = RemoteFailure.SERVICE) }
        val failureBounds = content.getUnclippedBoundsInRoot()
        assertEquals((80f + 700f - 360f) / 2f, (failureBounds.top.value + failureBounds.bottom.value) / 2f, 0.5f)
        assertTrue("The full failure block stays above the IME/composer", failureBounds.bottom.value <= 340.5f)
    }

    @Test fun welcomeMarkAndBoldHeadlineFollowTheWebUiHierarchyWithoutBodyProse() {
        show()
        val mark = compose.onNodeWithTag("remote-welcome-mark")
            .assertWidthIsEqualTo(112.dp).assertHeightIsEqualTo(112.dp).getUnclippedBoundsInRoot()
        val headline = compose.onNodeWithTag("remote-welcome-headline")
            .assertTextEquals(text(R.string.remote_new_chat_hint)).getUnclippedBoundsInRoot()
        assertEquals(16f, headline.top.value - mark.bottom.value, 0.5f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("remote-welcome-headline")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val style = layouts.single().layoutInput.style
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(28.sp, style.fontSize)
        assertEquals(35.sp, style.lineHeight)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(1)
        compose.runOnIdle { state = state.copy(failure = RemoteFailure.SERVICE) }
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_empty_retry_hint))
        compose.onNodeWithTag("remote-welcome-mark").assertWidthIsEqualTo(112.dp)
        compose.onNodeWithText(text(R.string.retry)).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = true); opening = true }
        compose.onNodeWithTag("remote-welcome-mark").assertDoesNotExist()
        val openingLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("remote-welcome-headline")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(openingLayouts) }
        assertEquals(FontWeight.Normal, openingLayouts.single().layoutInput.style.fontWeight)
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
    }

    @Test fun largeTextKeepsFailureHeadlineAndRetryAboveTheComposerByShrinkingOnlyTheMark() {
        state = state.copy(failure = RemoteFailure.SERVICE)
        fontScale = 1.5f
        layoutDirection = LayoutDirection.Rtl
        bottom = 360.dp
        show()
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertIsDisplayed().performClick()
        val bounds = compose.onNodeWithTag("remote-empty-conversation").getUnclippedBoundsInRoot()
        assertTrue(bounds.top.value >= 79.5f)
        assertTrue(bounds.bottom.value <= 340.5f)
        assertEquals(1, retries)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("remote-welcome-headline")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(28.sp, layouts.single().layoutInput.style.fontSize)
    }

    @Test fun shortEnglishAndChineseHeadlinesUseTheSameExistingResourceKeys() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = mapOf(
            R.string.remote_new_chat_hint to ("Start a Conversation" to "开始对话"),
            R.string.remote_empty_connecting_hint to ("Connecting to Codex" to "正在连接 Codex"),
            R.string.remote_empty_offline_hint to ("Codex Is Offline" to "Codex 已离线"),
            R.string.remote_empty_retry_hint to ("Unable to Load Conversation" to "无法加载对话"),
            R.string.remote_empty_conversation_hint to ("No Messages Yet" to "暂无消息"),
        )
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
        expected.forEach { (key, pair) ->
            assertEquals(pair.first, english.getString(key))
            assertEquals(pair.second, chinese.getString(key))
        }
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
