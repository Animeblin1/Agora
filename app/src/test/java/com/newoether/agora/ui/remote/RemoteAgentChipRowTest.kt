package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.newoether.agora.remote.RemoteDeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en")
class RemoteAgentChipRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statusChangesRetainOneInformationalChipAndItsGeometry() {
        var status by mutableStateOf(RemoteDeviceStatus.CONNECTED)
        compose.setContent { MaterialTheme { RemoteAgentChipRow(status) } }
        compose.onNodeWithContentDescription("Codex Desktop, Online").assertExists().assertHasNoClickAction()
        val initial = compose.onNodeWithText("Codex Desktop").getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { status = RemoteDeviceStatus.ERROR }
        compose.mainClock.advanceTimeByFrame()
        repeat(4) {
            compose.mainClock.advanceTimeBy(50)
            assertEquals(initial, compose.onNodeWithText("Codex Desktop").getUnclippedBoundsInRoot())
        }
        compose.onNodeWithContentDescription("Codex Desktop, Offline").assertExists()
        compose.onNodeWithText("Claude Code").assertDoesNotExist()
        compose.runOnIdle { status = RemoteDeviceStatus.CONNECTING }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithContentDescription("Codex Desktop, Connecting").assertExists()
        assertEquals(initial, compose.onNodeWithText("Codex Desktop").getUnclippedBoundsInRoot())
    }
}
