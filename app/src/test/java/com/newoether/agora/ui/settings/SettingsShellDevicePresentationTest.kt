package com.newoether.agora.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsShellDevicePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mockk<SettingsRepository>()
    private val viewModel = mockk<ChatViewModel>()
    private val host = ShellDeviceConfig(id = "host", name = "Build Host", serverUrl = "https://build.example.test")
    private lateinit var strings: Map<Int, String>
    private lateinit var drawingView: View
    private var primaryContainer = Color.Unspecified
    private var onSurfaceVariant = Color.Unspecified

    private fun showList(devices: List<ShellDeviceConfig> = listOf(host)) {
        every { viewModel.settings } returns settings
        every { viewModel.isSandboxFlavor } returns false
        every { settings.shellEnabled } returns MutableStateFlow(true)
        every { settings.shellConfirmEnabled } returns MutableStateFlow(false)
        every { settings.shellDevices } returns MutableStateFlow(devices)
        every { settings.sandboxEnabled } returns MutableStateFlow(false)
        every { settings.sandboxSharedStorageEnabled } returns MutableStateFlow(false)
        every { settings.showDocumentationFab } returns MutableStateFlow(false)
        every { settings.removeShellDevice(any()) } returns Job().apply { complete() }
        compose.setContent {
            drawingView = LocalView.current
            val context = LocalContext.current
            strings = listOf(R.string.task_section_details, R.string.delete, R.string.cancel,
                R.string.shell_delete_confirm_title, R.string.edit).associateWith(context::getString)
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    primaryContainer = MaterialTheme.colorScheme.primaryContainer
                    Box(Modifier.size(400.dp, 900.dp)) { SettingsShellPage(viewModel) {} }
                }
            }
        }
    }
    private fun row() = compose.onNodeWithTag("shell-device-row:${host.id}")
    private fun details() = compose.onNodeWithTag("shell-device-details:${host.id}")
    private fun editorName() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("shell-field-name")))
    private fun menu(label: Int) = compose.onNode(hasText(strings.getValue(label)) and hasClickAction())

    @Test
    fun wholeCardOpensDetailsDeleteMenuAndDetailsActionDoesNotOpenTheEditorUntilSelected() {
        showList()
        row().performScrollTo().performClick()
        menu(R.string.task_section_details).assertIsDisplayed()
        menu(R.string.delete).assertIsDisplayed()
        editorName().assertDoesNotExist()
        menu(R.string.task_section_details).performClick()
        compose.waitForIdle()
        editorName().assertExists()
        menu(R.string.delete).assertDoesNotExist()
    }

    @Test
    fun trailingDetailsOpensTheEditorDirectlyWithNoPencilOrMenu() {
        showList()
        details().assertContentDescriptionEquals(strings.getValue(R.string.task_section_details))
        compose.onNodeWithContentDescription(strings.getValue(R.string.edit)).assertDoesNotExist()
        details().performScrollTo().performClick()
        compose.waitForIdle()
        editorName().assertExists()
        menu(R.string.delete).assertDoesNotExist()
    }

    @Test
    fun menuDeleteOnlyRemovesTheCapturedDeviceAfterExplicitConfirmation() {
        showList()
        row().performScrollTo().performClick()
        menu(R.string.delete).performClick()
        compose.onNodeWithText(strings.getValue(R.string.shell_delete_confirm_title)).assertExists()
        verify(exactly = 0) { settings.removeShellDevice(any()) }
        menu(R.string.cancel).performClick()
        verify(exactly = 0) { settings.removeShellDevice(any()) }
        row().performClick()
        menu(R.string.delete).performClick()
        menu(R.string.delete).performClick()
        verify(exactly = 1) { settings.removeShellDevice(host.id) }
        editorName().assertDoesNotExist()
    }

    @Test
    fun conchAndSshBadgesUseTheRestoredPrimaryContainerFillAndLabelSmall() {
        val ssh = host.copy(id = "ssh", name = "SSH Host", type = "ssh", sshHost = "ssh.example.test")
        showList(listOf(host, ssh))
        for ((device, text) in listOf(host to "Conch", ssh to "SSH")) {
            val badge = compose.onNodeWithTag("shell-device-type:${device.id}", useUnmergedTree = true)
            badge.performScrollTo()
            val image = draw(badge)
            try {
                assertColor(primaryContainer, Color(image.getPixel(image.width / 2, 1)))
            } finally {
                image.recycle()
            }
            val label = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("shell-device-type:${device.id}")), useUnmergedTree = true)
            assertEquals(11.sp, layout(label).layoutInput.style.fontSize)
        }
    }

    // API 35 patterned ripple pixels require hardware. A real pointer press must reach
    // the platform drawable, and cancellation/release must clear its pressed state.
    @Test
    fun cardPointerPressReleaseAndCancellationReachTheApi35PlatformRipple() {
        showList()
        row().performScrollTo()
        row().performTouchInput {
            down(center)
            // The scroll container delays clickable's press indication while detecting a drag.
            advanceEventTime(160)
        }
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        lateinit var ripple: RippleDrawable
        try {
            compose.runOnIdle { ripple = pressedRipple() }
            editorName().assertDoesNotExist()
            menu(R.string.task_section_details).assertDoesNotExist()
        } finally {
            row().performTouchInput { cancel() }
        }
        compose.runOnIdle { assertFalse(android.R.attr.state_pressed in ripple.state) }
        menu(R.string.task_section_details).assertDoesNotExist()
        row().performTouchInput {
            down(center)
            // The scroll container delays clickable's press indication while detecting a drag.
            advanceEventTime(160)
        }
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        try {
            compose.runOnIdle { assertSame(ripple, pressedRipple()) }
        } finally {
            row().performTouchInput { up() }
        }
        compose.runOnIdle { assertFalse(android.R.attr.state_pressed in ripple.state) }
        menu(R.string.task_section_details).assertExists()
    }

    @Test
    fun shellUrlValueAndPlaceholderRenderAtTheSameSmallerFontAsTheMcpField() {
        var reference by mutableStateOf("")
        var hint = ""
        var label = ""
        compose.setContent {
            drawingView = LocalView.current
            val context = LocalContext.current
            hint = context.getString(R.string.shell_device_url_hint)
            label = context.getString(R.string.shell_device_url)
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.size(400.dp, 950.dp)) {
                        Box(Modifier.weight(1f)) {
                            SettingsShellDeviceEditor(
                                initial = host.copy(serverUrl = ""), isNew = false,
                                viewModel = viewModel, existingDevices = listOf(host),
                                showDocFab = false, isCurrent = { true }, onBack = {},
                            )
                        }
                        McpLabeledField(
                            label = "Reference URL", value = reference, onValueChange = { reference = it },
                            placeholder = hint, modifier = Modifier.testTag("mcp-reference"),
                        )
                    }
                }
            }
        }
        val shellField = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("shell-field-url")))
        shellField.performScrollTo()
        val shellHint = compose.onNode(hasText(hint) and hasAnyAncestor(hasTestTag("shell-field-url")), useUnmergedTree = true)
        val referenceHint = compose.onNode(hasText(hint) and hasAnyAncestor(hasTestTag("mcp-reference")), useUnmergedTree = true)
        val hintStyle = layout(shellHint).layoutInput.style
        val referenceStyle = layout(referenceHint).layoutInput.style
        assertEquals(14.sp, hintStyle.fontSize)
        assertEquals(referenceStyle.fontSize, hintStyle.fontSize)
        assertEquals(referenceStyle.lineHeight, hintStyle.lineHeight)
        assertEquals(onSurfaceVariant, hintStyle.color)
        val labelStyle = layout(compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("shell-field-url")), useUnmergedTree = true)).layoutInput.style
        assertEquals(16.sp, labelStyle.fontSize)
        assertEquals(FontWeight.Medium, labelStyle.fontWeight)
        val url = "https://typed.example.test"
        shellField.performTextReplacement(url)
        compose.runOnIdle { reference = url }
        val referenceField = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("mcp-reference")))
        val valueStyle = layout(shellField).layoutInput.style
        assertEquals(14.sp, valueStyle.fontSize)
        assertEquals(layout(referenceField).layoutInput.style.fontSize, valueStyle.fontSize)
        assertEquals(hintStyle.fontSize, valueStyle.fontSize)
        assertEquals(onSurfaceVariant, valueStyle.color)
    }

    private fun draw(node: SemanticsNodeInteraction): Bitmap {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val image = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = Canvas(image)
            canvas.translate(-bounds.left, -bounds.top)
            drawingView.draw(canvas)
        }
        return image
    }

    private fun pressedRipple(): RippleDrawable {
        fun ripples(view: View): List<RippleDrawable> = buildList {
            (view.background as? RippleDrawable)?.let { add(it) }
            if (view is ViewGroup) repeat(view.childCount) { addAll(ripples(view.getChildAt(it))) }
        }
        val pressed = ripples(drawingView.rootView).filter { android.R.attr.state_pressed in it.state }
        assertEquals("A real card press reaches one platform ripple", 1, pressed.size)
        return pressed.single()
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
    private fun assertColor(expected: Color, actual: Color) {
        assertTrue(abs(expected.red - actual.red) <= 1f / 255f)
        assertTrue(abs(expected.green - actual.green) <= 1f / 255f)
        assertTrue(abs(expected.blue - actual.blue) <= 1f / 255f)
        assertEquals(expected.alpha, actual.alpha, 1f / 255f)
    }
}
