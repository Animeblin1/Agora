package com.newoether.agora.api.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeZenIdentityTest {

    private val canonical = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")

    @Test
    fun anySeedHashesIntoTheCanonicalSessionShape() {
        // The free lane 403s any other shape, so this is what keeps a request admissible.
        listOf("conversation-1", "", "ses_not_canonical", "a\u00e9\u4e2d").forEach { seed ->
            val session = OpenCodeZenIdentity.sessionIdFor(seed)
            assertTrue("seed '$seed' -> $session", canonical.matches(session))
        }
    }

    @Test
    fun theSameConversationKeepsTheSameSession() {
        // Upstream prompt-cache affinity depends on the session being stable across turns.
        assertEquals(
            OpenCodeZenIdentity.sessionIdFor("conversation-1"),
            OpenCodeZenIdentity.sessionIdFor("conversation-1"),
        )
        assertFalse(
            OpenCodeZenIdentity.sessionIdFor("conversation-1") ==
                OpenCodeZenIdentity.sessionIdFor("conversation-2"),
        )
    }

    @Test
    fun aRealOpenCodeSessionPassesThroughUnchanged() {
        val official = "ses_68f2a1b3c4d5aB3cD5eF7gH9iJ"
        assertEquals(official, OpenCodeZenIdentity.sessionIdFor(official))
    }

    @Test
    fun requestIdIsUniquePerRequest() {
        val first = OpenCodeZenIdentity.derive("conversation-1")
        val second = OpenCodeZenIdentity.derive("conversation-1")
        assertEquals(first.session, second.session)
        assertEquals(first.project, second.project)
        assertFalse(first.request == second.request)
    }
}
