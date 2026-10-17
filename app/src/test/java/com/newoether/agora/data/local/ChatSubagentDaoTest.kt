package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatSubagentDaoTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.chatDao()
    }
    @After fun tearDown() = database.close()

    private suspend fun seed(id: String, parent: String? = null) {
        dao.upsertConversation(ChatEntity(id, id, parentConversationId = parent, dataChangedAt = 5L))
    }
    private fun run(id: String) = RunEntity(
        id = "r-$id", conversationId = id, parentRunId = null,
        status = RunStatus.ACTIVE, activeSlot = 1, currentPass = 2,
        startedAt = 1L, lastCheckpointAt = 1L,
    )

    @Test fun authorizationRequiresExactActiveRootRunAndPass() = runTest {
        seed("parent")
        seed("child", "parent")
        dao.insertRun(run("parent"))
        dao.insertRun(run("child"))
        assertTrue(dao.isSubagentToolOwnerActive("parent", "r-parent", 2))
        assertFalse(dao.isSubagentToolOwnerActive("child", "r-child", 2))
        assertFalse(dao.isSubagentToolOwnerActive("parent", "r-parent", 1))
        assertFalse(dao.isSubagentToolOwnerActive("parent", "r-child", 2))
        assertFalse(dao.isSubagentToolOwnerActive("missing", "r-parent", 2))
        dao.markRunStopping("r-parent", 10L)
        assertFalse(dao.isSubagentToolOwnerActive("parent", "r-parent", 2))
        dao.terminalizeLiveRun("r-parent", RunStatus.STOPPED, RunEndReason.USER_STOPPED, 11L)
        assertFalse(dao.isSubagentToolOwnerActive("parent", "r-parent", 2))
    }

    @Test fun outboxAndCapsulesAreParentScopedAndRunningResultsCannotBePublished() = runTest {
        seed("parent")
        seed("other")
        seed("child", "parent")
        seed("foreign", "other")
        assertEquals(1, dao.setSubagentResult("child", "parent", "{}", "result", "completed", 10L))
        assertEquals(1, dao.setSubagentResult("foreign", "other", "{}", "other-result", "failed", 10L))
        val before = dao.getConversation("child")
        assertEquals(0, dao.setSubagentResult("child", "other", "{}", "bad", "failed", 20L))
        assertEquals(before, dao.getConversation("child"))
        assertEquals(listOf("child"), dao.pendingSubagentResults("parent").map { it.id })
        assertEquals("parent", dao.observeParentConversationId("child").first())
        assertEquals("completed", dao.observeSubagents("parent").first().single().status)
        dao.insertRun(run("child"))
        assertTrue(dao.pendingSubagentResults("parent").isEmpty())
        assertEquals("running", dao.observeSubagents("parent").first().single().status)
        assertEquals(0, dao.setSubagentResult("child", "parent", "{}", "bad", "failed", 20L))
        assertEquals(before, dao.getConversation("child"))
    }

    @Test fun exactReceiptsAreOneShotAndCannotConsumeReplacementOrOtherParent() = runTest {
        seed("parent")
        seed("child", "parent")
        dao.setSubagentResult("child", "parent", "{}", "one", "completed", 10L)
        assertEquals(0, dao.acknowledgeSubagentResult("child", "other", "one", 11L))
        assertEquals(0, dao.acknowledgeSubagentResult("child", "parent", "wrong", 11L))
        assertEquals(1, dao.acknowledgeSubagentResult("child", "parent", "one", 11L))
        assertEquals(0, dao.acknowledgeSubagentResult("child", "parent", "one", 12L))
        assertTrue(dao.pendingSubagentResults("parent").isEmpty())
        dao.setSubagentResult("child", "parent", "{}", "two", "stopped", 13L)
        assertEquals(0, dao.acknowledgeSubagentResult("child", "parent", "one", 14L))
        assertEquals("two", dao.pendingSubagentResults("parent").single().resultId)
        assertEquals("stopped", dao.observeSubagents("parent").first().single().status)
    }
}
