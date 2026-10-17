package com.newoether.agora.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.*
import com.newoether.agora.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationSubagentsTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private lateinit var repository: ConversationRepository
    private val identity = RunEffectIdentity("parent", 1L, "r-parent", 0, "effect")

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.chatDao()
        repository = ConversationRepository(dao, database, scheduleMaintenance = {})
    }
    @After fun tearDown() = database.close()

    private fun run(owner: String, id: String = "r-$owner") = RunEntity(
        id = id, conversationId = owner, parentRunId = null,
        status = RunStatus.ACTIVE, activeSlot = 1, currentPass = 0,
        startedAt = 1L, lastCheckpointAt = 1L,
    )
    private suspend fun seed() {
        dao.upsertConversation(ChatEntity("parent", "Parent", draftText = "keep"))
        dao.insertRun(run("parent"))
        dao.upsertConversation(ChatEntity("other", "Other"))
    }
    private suspend fun child(id: String = "child") {
        repository.createSubagent(identity, ChatEntity(id, id, parentConversationId = "parent"), "{}")
        repository.recordSubagentCompletion(id, "parent", SubagentCompletion("result-$id", "completed", "Answer"))
    }
    private fun input(owner: String, runId: String, source: MessageSource? = null) = listOf(
        MessageEntity(id = "u-$runId", conversationId = owner, text = "Input", participant = Participant.USER,
            timestamp = 1L, runId = runId, runSequence = 0L, sourceJson = MessageSource.encode(source)),
        MessageEntity(id = "m-$runId", conversationId = owner, parentId = "u-$runId", text = "",
            participant = Participant.MODEL, status = MessageStatus.SENDING,
            timestamp = 2L, runId = runId, runSequence = 1L),
    )
    private suspend fun admit(owner: String, id: String, source: MessageSource? = null) =
        repository.createRunWithMessages(run(owner, id), input(owner, id, source), emptyMap(), null,
            at = 20L, touchConversationOnAdmission = false)

    @Test fun childCreationIsIsolatedAndStalePassOrNestedParentCannotCreate() = runTest {
        seed()
        child()
        assertTrue(dao.getMessageContextTopology("child").isEmpty())
        assertEquals("{}", dao.getConversationSettingsTransfer("child")?.settingsJson)
        assertTrue(runCatching {
            repository.createSubagent(identity.copy(pass = 1), ChatEntity("bad", "Bad", parentConversationId = "parent"), null)
        }.isFailure)
        dao.insertRun(run("child"))
        assertTrue(runCatching {
            repository.createSubagent(identity.copy(conversationId = "child", runId = "r-child"),
                ChatEntity("nested", "Nested", parentConversationId = "child"), null)
        }.isFailure)
        assertNull(dao.getConversation("bad"))
        assertNull(dao.getConversation("nested"))
        assertEquals("keep", dao.getConversation("parent")?.draftText)
    }

    @Test fun receiptAndAcceptedInputCommitTogetherAndFailuresRollbackBoth() = runTest {
        seed()
        child()
        val source = MessageSource(MessageSource.Kind.SUBAGENT, subagentResults = mapOf("child" to "result-child"))
        // Existing live parent Run rejects admission after the receipt update; Room rolls it back.
        assertTrue(runCatching { admit("parent", "blocked", source) }.isFailure)
        assertNull(dao.getConversation("child")?.subagentDeliveredResultId)
        assertNull(dao.getRun("blocked"))
        assertNull(dao.getMessage("u-blocked"))
        dao.terminalizeLiveRun("r-parent", RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, 10L)
        admit("parent", "accepted", source)
        assertEquals("result-child", dao.getConversation("child")?.subagentDeliveredResultId)
        assertEquals(source, MessageSource.decode(dao.getMessage("u-accepted")?.sourceJson))
        assertTrue(repository.pendingSubagentResults("parent").isEmpty())
        dao.terminalizeLiveRun("accepted", RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, 30L)
        assertTrue(runCatching { admit("parent", "duplicate", source) }.isFailure)
        assertNull(dao.getRun("duplicate"))
    }

    @Test fun staleBatchOfWaitReceiptsRollsBackPreviouslyAcknowledgedChildren() = runTest {
        seed()
        child("a")
        child("b")
        assertTrue(runCatching {
            repository.consumeSubagentCompletions(identity, linkedMapOf("a" to "result-a", "b" to "stale"))
        }.isFailure)
        assertNull(dao.getConversation("a")?.subagentDeliveredResultId)
        repository.consumeSubagentCompletions(identity, mapOf("a" to "result-a", "b" to "result-b"))
        assertTrue(repository.pendingSubagentResults("parent").isEmpty())
        assertTrue(runCatching { repository.consumeSubagentCompletions(identity.copy(pass = 1), emptyMap()) }.isFailure)
    }

    @Test fun terminalOutboxFollowsOnlyCommittedRunAndStopPersistsStoppedResult() = runTest {
        seed()
        child()
        admit("child", "child-run")
        val message = ChatMessage(id = "m-child-run", text = "Finished", participant = Participant.MODEL,
            status = MessageStatus.SUCCESS, runId = "child-run", runSequence = 1L)
        assertFalse(repository.finishGeneration(message, "child", "stale", RunStatus.COMPLETED,
            RunEndReason.MODEL_COMPLETED))
        assertEquals("result-child", dao.getConversation("child")?.subagentResultId)
        assertTrue(repository.finishStoppedGeneration(listOf(message), "child-run"))
        val completion = SubagentCompletion.decode(dao.getConversation("child")?.subagentResultJson)
        assertEquals("stopped", completion?.status)
        assertEquals("m-child-run", completion?.id)
        assertNull(dao.getLiveRun("child"))
        val terminal = dao.getConversation("child")
        assertFalse(repository.finishGeneration(message, "child", "child-run", RunStatus.COMPLETED,
            RunEndReason.MODEL_COMPLETED))
        assertEquals(terminal, dao.getConversation("child"))
    }

    @Test fun waitToolRoundAcknowledgesOnlyWhenProtocolRowsCommitAndRejectsStalePass() = runTest {
        seed()
        child()
        fun round(prefix: String, result: String): List<MessageEntity> {
            val call = MessageSegment(type = "tool", toolName = "wait_agents", toolArgs = "{}", toolCallId = prefix)
            return listOf(
                MessageEntity(id = "$prefix-tool", conversationId = "parent", text = "", participant = Participant.MODEL,
                    timestamp = 2L, runId = "r-parent", runSequence = 0L, toolCallJson = Json.encodeToString(listOf(call))),
                MessageEntity(id = "$prefix-result", conversationId = "parent", parentId = "$prefix-tool", text = "",
                    participant = Participant.USER, timestamp = 3L, runId = "r-parent", runSequence = 1L,
                    toolCallJson = Json.encodeToString(listOf(call.copy(toolResult = result)))),
            )
        }
        val payload = """{"subagent_receipts":{"child":"result-child"},"results":[]}"""
        val stale = round("stale", payload)
        assertTrue(runCatching { repository.appendToolRoundToRun(stale, 1) }.isFailure)
        assertNull(dao.getConversation("child")?.subagentDeliveredResultId)
        val invalid = round("invalid", """{"subagent_receipts":{"child":"replaced"}}""")
        assertTrue(runCatching { repository.appendToolRoundToRun(invalid, 0) }.isFailure)
        assertNull(dao.getMessage("invalid-tool"))
        assertNull(dao.getConversation("child")?.subagentDeliveredResultId)
        val accepted = round("accepted", payload)
        assertTrue(repository.appendToolRoundToRun(accepted, 0).inserted)
        assertEquals("result-child", dao.getConversation("child")?.subagentDeliveredResultId)
        assertFalse(repository.appendToolRoundToRun(accepted, 0).inserted)
        assertTrue(repository.pendingSubagentResults("parent").isEmpty())
    }

    @Test fun deletingParentRemovesChildrenGraphsButPreservesOtherOwners() = runTest {
        seed()
        child()
        admit("child", "child-run")
        admit("other", "other-run")
        val other = dao.getConversation("other")
        val otherMessage = dao.getMessage("u-other-run")
        val prepared = mutableListOf<String>()
        repository.prepareConversationDeletion = { prepared += it }
        repository.deleteConversation("parent")
        assertEquals(listOf("parent"), prepared)
        assertNull(dao.getConversation("parent"))
        assertNull(dao.getConversation("child"))
        assertNull(dao.getMessage("u-child-run"))
        assertNull(dao.getRun("child-run"))
        assertNull(dao.getConversationSettingsTransfer("child"))
        assertEquals(other, dao.getConversation("other"))
        assertEquals(otherMessage, dao.getMessage("u-other-run"))
    }
}
