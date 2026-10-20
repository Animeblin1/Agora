package com.newoether.agora.ui.remote

import com.newoether.agora.R
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.remote.RemoteDevice
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.remote.RemoteFailure
import com.newoether.agora.remote.RemoteMessageGroup
import com.newoether.agora.remote.RemoteRuntime
import com.newoether.agora.remote.RemoteSession
import com.newoether.agora.remote.RemoteState
import org.junit.Assert.*
import org.junit.Test

class RemoteStatusPresentationTest {
    private val session = RemoteSession("session", "Task", "/workspace", 1)
    private fun state(status: RemoteDeviceStatus = RemoteDeviceStatus.CONNECTED) = RemoteState(
        restoring = false,
        deviceId = "device",
        devices = listOf(RemoteDevice("other", "Other", "http://other", RemoteDeviceStatus.ERROR),
            RemoteDevice("device", "Computer", "http://computer", status)),
        session = session,
    )

    @Test fun selectedGatewayStatusNeverClaimsDesktopOrConversationReadiness() {
        for (status in RemoteDeviceStatus.entries) {
            val expected = status.toMcpConnectionStatus()
            for (runtime in listOf(null, RemoteRuntime("notLoaded"), RemoteRuntime("idle"), RemoteRuntime("active"))) {
                val snapshot = state(status).copy(runtime = runtime, hydrationEnabled = runtime != null)
                assertEquals(expected, snapshot.remoteConnectionStatus())
                assertEquals(expected, snapshot.copy(failure = RemoteFailure.SERVICE).remoteConnectionStatus())
                assertEquals(expected, snapshot.copy(draftSessionId = session.id).remoteConnectionStatus())
            }
        }
        assertEquals(McpConnectionStatus.IDLE, state().copy(deviceId = "missing").remoteConnectionStatus())
        assertEquals(R.string.remote_online, remoteConnectionStatusLabel(McpConnectionStatus.CONNECTED))
        assertEquals(R.string.remote_connecting, remoteConnectionStatusLabel(McpConnectionStatus.CONNECTING))
        assertEquals(R.string.remote_offline, remoteConnectionStatusLabel(McpConnectionStatus.IDLE))
        assertEquals(R.string.remote_offline, remoteConnectionStatusLabel(McpConnectionStatus.ERROR))
    }

    @Test fun onlineNewDraftIsLocalWhileOfflineDraftOffersConnectionRetry() {
        val draft = state().copy(draftSessionId = session.id, drafts = mapOf("device/session" to "Unsent"))
        assertEquals(R.string.remote_new_chat_hint, draft.remoteEmptyConversationHint())
        assertFalse(draft.canRetryEmptyConnection())
        val offline = draft.copy(devices = state(RemoteDeviceStatus.IDLE).devices)
        assertEquals(R.string.remote_empty_offline_hint, offline.remoteEmptyConversationHint())
        assertTrue(offline.canRetryEmptyConnection())
        assertEquals("Unsent", offline.drafts[offline.owner])
        val failed = offline.copy(failure = RemoteFailure.NETWORK)
        assertEquals(R.string.remote_empty_retry_hint, failed.remoteEmptyConversationHint())
        assertTrue(failed.canRetryEmptyConnection())
    }

    @Test fun openingAndConnectingAreExplicitWithoutOfferingAnotherConcurrentRead() {
        for (snapshot in listOf(state().copy(loading = true), state(RemoteDeviceStatus.CONNECTING), state())) {
            assertEquals(R.string.remote_empty_connecting_hint, snapshot.remoteEmptyConversationHint())
            assertFalse(snapshot.canRetryEmptyConnection())
        }
        val reconnecting = state(RemoteDeviceStatus.CONNECTING).copy(failure = RemoteFailure.NETWORK)
        assertEquals(R.string.remote_empty_connecting_hint, reconnecting.remoteEmptyConversationHint())
        assertFalse(reconnecting.canRetryEmptyConnection())
        val confirmedEmpty = state().copy(hydrationEnabled = true, runtime = RemoteRuntime("notLoaded"))
        assertEquals(R.string.remote_empty_conversation_hint, confirmedEmpty.remoteEmptyConversationHint())
        assertFalse(confirmedEmpty.canRetryEmptyConnection())
    }

    @Test fun nativeReadFailureDoesNotFabricateAnOfflineGateway() {
        val failed = state().copy(failure = RemoteFailure.SERVICE)
        assertEquals(McpConnectionStatus.CONNECTED, failed.remoteConnectionStatus())
        assertEquals(R.string.remote_empty_retry_hint, failed.remoteEmptyConversationHint())
        assertTrue(failed.canRetryEmptyConnection())
        assertEquals(R.string.remote_empty_offline_hint,
            state(RemoteDeviceStatus.ERROR).remoteEmptyConversationHint())
    }

    @Test fun loadedHistoryAlwaysWinsOverEmptyGuidanceDuringFailureAndRecovery() {
        val message = ChatMessage(id = "answer", text = "Retained answer", participant = Participant.MODEL)
        val groups = listOf(RemoteMessageGroup(message, emptyList()))
        for (status in RemoteDeviceStatus.entries) {
            val snapshot = state(status).copy(messageGroups = groups, drafts = mapOf("device/session" to "Unsent"))
            for (candidate in listOf(snapshot, snapshot.copy(failure = RemoteFailure.NETWORK), snapshot.copy(loading = true))) {
                assertNull(candidate.remoteEmptyConversationHint())
                assertFalse(candidate.canRetryEmptyConnection())
                assertSame(groups, candidate.messageGroups)
                assertEquals("Unsent", candidate.drafts[candidate.owner])
            }
        }
        assertNull(state().copy(session = null).remoteEmptyConversationHint())
    }
}
