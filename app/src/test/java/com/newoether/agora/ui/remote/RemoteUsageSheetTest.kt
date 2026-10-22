package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.remote.RemoteConnection
import com.newoether.agora.remote.RemoteConnectionStore
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.remote.applicationFixtureClient
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.mockk
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h800dp-mdpi")
class RemoteUsageSheetTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test fun headerReadsAndDisplaysUsageThroughTheRealViewModelAndClient() {
        readUsage(200, """{"limits":[{"id":"codex","name":"Codex","primary":{"usedPercent":49,"windowDurationMins":10080},"secondary":null}]}""", true)
    }

    @Test fun failedAccountReadSettlesAsUnavailableWithoutNativeMutations() {
        readUsage(503, """{"code":"usage_unavailable"}""", false)
    }

    private fun readUsage(status: Int, response: String, available: Boolean) {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val address = "http://127.0.0.1:${server.address.port}/"
        val connections = mockk<RemoteConnectionStore>()
        coEvery { connections.load() } returns listOf(RemoteConnection("Workstation", address, "a".repeat(64)))
        val settings = mockk<SettingsRepository>(relaxed = true)
        val owners = ViewModelStore()
        lateinit var vm: RemoteViewModel
        try {
            compose.runOnIdle {
                vm = ViewModelProvider(owners, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        RemoteViewModel(connections) { url, token -> applicationFixtureClient(url, token) } as T
                })[RemoteViewModel::class.java]
            }
            compose.waitUntil(5_000) { !vm.state.value.restoring }
            compose.runOnIdle {
                val restoredId = vm.state.value.devices.single().id
                vm.selectDevice(restoredId)
                assertEquals(restoredId, vm.state.value.deviceId)
                assertEquals(true, vm.state.value.capabilities.usage)
            }
            compose.setContent { MaterialTheme { RemoteScreen(vm, settings, true, {}, {}, { _, _ -> }) } }
            compose.onNodeWithContentDescription(text(R.string.remote_usage)).performClick()
            val expected = if (available) RuntimeEnvironment.getApplication()
                .getString(R.string.remote_usage_remaining, 51) else text(R.string.remote_usage_unavailable)
            // The sheet is its own dialog composition driven by the test clock, and its loading
            // spinner never idles. After the click no idle sync remains to grant a frame, so the
            // poll advances the clock manually until the fetched usage renders.
            compose.mainClock.autoAdvance = false
            var polls = 0
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeBy(50)
                // With the clock paused, a plain idle pump is safe (the spinner has no pending
                // frames) and drains the main queue the HTTP resumption lands its writes on.
                compose.waitForIdle()
                compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
                    .also { rendered ->
                        if (!rendered && ++polls % 40 == 0) {
                            println("UsageSheetDebug: polls=$polls requests=$requests")
                        }
                    }
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithText(expected).assertExists()
            if (available) compose.onNodeWithText(text(R.string.remote_usage_unavailable)).assertDoesNotExist()
            assertEquals(listOf("GET /v1/usage"), requests.toList())
        } finally {
            compose.runOnIdle { owners.clear() }
            server.stop(0)
        }
    }
}
