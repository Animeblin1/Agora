package com.newoether.agora.ui.settings

import com.newoether.agora.api.decisions.DecisionQuestionType
import com.newoether.agora.api.decisions.DecisionQuestions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionQuestionEditorModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parsed(raw: String): JsonObject =
        requireNotNull(DecisionQuestions.parse(raw)) { "the editor produced invalid JSON: $raw" }

    @Test
    fun anEmptyOrUnreadableDocumentOpensWithNoQuestions() {
        assertEquals(emptyList<DecisionQuestionDraft>(), decodeDecisionQuestions(null))
        assertEquals(emptyList<DecisionQuestionDraft>(), decodeDecisionQuestions("  "))
        assertEquals(emptyList<DecisionQuestionDraft>(), decodeDecisionQuestions("not json"))
    }

    @Test
    fun aStoredDocumentOpensAsEditableFields() {
        val drafts = decodeDecisionQuestions(
            """
            {
              "urgent": {"type":"noul","instructions":"Is this urgent?"},
              "team": {"type":"choice","instructions":"Which team?","criteria":{"billing":"","sales":""}},
              "anger": {"type":"score","instructions":"How angry?","criteria":["calm","upset"]}
            }
            """.trimIndent(),
        )
        assertEquals(3, drafts.size)
        val urgent = drafts.first { it.name == "urgent" }
        assertEquals(DecisionQuestionType.NOUL, urgent.type)
        assertEquals("Is this urgent?", urgent.instructions)
        val team = drafts.first { it.name == "team" }
        assertEquals(DecisionQuestionType.CHOICE, team.type)
        assertEquals(listOf("billing", "sales"), team.options)
        val anger = drafts.first { it.name == "anger" }
        assertEquals(DecisionQuestionType.SCORE, anger.type)
        assertEquals(listOf("calm", "upset"), anger.options)
    }

    @Test
    fun aNoulNeedsNoOptionsAndRoundTrips() {
        val raw = encodeDecisionQuestions(
            listOf(
                DecisionQuestionDraft(
                    name = "urgent",
                    type = DecisionQuestionType.NOUL,
                    instructions = "Is this urgent?",
                ),
            ),
        )
        val entry = parsed(raw)["urgent"]!!.jsonObject
        assertEquals(DecisionQuestionType.NOUL, entry["type"]!!.jsonPrimitive.content)
        assertEquals("Is this urgent?", entry["instructions"]!!.jsonPrimitive.content)
        assertNull("a noul answers yes or no, so it carries no criteria", entry["criteria"])
        // What came out is what the endpoint accepts and what the editor reads back.
        assertEquals(
            DecisionQuestionDraft(
                name = "urgent",
                type = DecisionQuestionType.NOUL,
                instructions = "Is this urgent?",
            ),
            decodeDecisionQuestions(raw).single(),
        )
    }

    @Test
    fun aChoiceSerializesItsOptionsAsAMap() {
        val raw = encodeDecisionQuestions(
            listOf(
                DecisionQuestionDraft(
                    name = "team",
                    type = DecisionQuestionType.CHOICE,
                    instructions = "Which team?",
                    options = listOf("billing", "sales"),
                ),
            ),
        )
        val criteria = parsed(raw)["team"]!!.jsonObject["criteria"]!!.jsonObject
        assertEquals(setOf("billing", "sales"), criteria.keys)
    }

    @Test
    fun aScoreSerializesItsLevelsAsAnOrderedArray() {
        val raw = encodeDecisionQuestions(
            listOf(
                DecisionQuestionDraft(
                    name = "anger",
                    type = DecisionQuestionType.SCORE,
                    instructions = "How angry?",
                    options = listOf("calm", "upset"),
                ),
            ),
        )
        val criteria = parsed(raw)["anger"]!!.jsonObject["criteria"]!!
        assertTrue(criteria.toString().contains("calm"))
        assertTrue(criteria.toString().indexOf("calm") < criteria.toString().indexOf("upset"))
    }

    @Test
    fun blankEntriesAreLeftOutRatherThanSent() {
        val raw = encodeDecisionQuestions(
            listOf(DecisionQuestionDraft(), DecisionQuestionDraft(name = "  ")),
        )
        assertTrue(
            "nothing to ask means nothing to send",
            Json.parseToJsonElement(raw).jsonObject.isEmpty(),
        )
    }

    @Test
    fun aQuestionWithNoNameStillGoesOut() {
        val raw = encodeDecisionQuestions(
            listOf(DecisionQuestionDraft(instructions = "Is this urgent?")),
        )
        assertTrue(parsed(raw).isNotEmpty())
    }

    @Test
    fun aRepeatedNameIsKeptDistinctInsteadOfOverwritten() {
        val raw = encodeDecisionQuestions(
            listOf(
                DecisionQuestionDraft(name = "q", instructions = "first"),
                DecisionQuestionDraft(name = "q", instructions = "second"),
            ),
        )
        val root = parsed(raw)
        assertEquals(2, root.size)
        val instructions = root.values.map { it.jsonObject["instructions"]!!.jsonPrimitive.content }
        assertTrue(instructions.containsAll(listOf("first", "second")))
    }

    @Test
    fun blankOptionsAreNotSentAsOptions() {
        val raw = encodeDecisionQuestions(
            listOf(
                DecisionQuestionDraft(
                    name = "team",
                    type = DecisionQuestionType.CHOICE,
                    instructions = "Which team?",
                    options = listOf("billing", "  ", ""),
                ),
            ),
        )
        val criteria = parsed(raw)["team"]!!.jsonObject["criteria"]!!.jsonObject
        assertEquals(setOf("billing"), criteria.keys)
    }

    @Test
    fun theSampleDocumentOpensAndSaves() {
        val drafts = decodeDecisionQuestions(DecisionQuestions.sample())
        assertEquals(1, drafts.size)
        assertNull(
            "the sample is valid, so saving it back must not fail validation",
            runCatching { parsed(encodeDecisionQuestions(drafts)) }.exceptionOrNull(),
        )
    }
}
