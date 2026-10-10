package com.newoether.agora.api.decisions

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionsProviderTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val messages = listOf(
        ChatMessage(text = "My payouts have been failing for three days.", participant = Participant.USER),
        ChatMessage(text = "I can check that for you.", participant = Participant.MODEL),
    )

    @Test
    fun aNoulIsRenderedWithItsProbability() {
        val answer = DecisionAnswer(type = "noul", noul = 0.95)
        assertEquals("refund: yes 95% (no 5%)", answer.render("refund"))
    }

    @Test
    fun aChoiceCarriesTheWinnerAndTheSpread() {
        val answer = DecisionAnswer(
            type = "choice",
            choice = "billing",
            confidence = 0.81,
            probabilities = mapOf("billing" to 0.88, "technical" to 0.12, "sales" to 0.0),
        )
        val rendered = answer.render("team")
        assertTrue(rendered.startsWith("team: billing"))
        assertTrue(rendered.contains("confidence 81%"))
        assertTrue("the losing options stay visible", rendered.contains("technical 12%"))
        assertTrue("a zero-probability option is noise", !rendered.contains("sales"))
    }

    @Test
    fun aScoreNamesTheLevelItLandedOn() {
        val answer = DecisionAnswer(
            type = "score",
            score = 1.05,
            confidence = 0.92,
            legend = mapOf("0" to "Calm", "1" to "Frustrated", "2" to "Very angry"),
            probabilities = mapOf("0" to 0.0, "1" to 0.95, "2" to 0.05),
        )
        val rendered = answer.render("frustration")
        assertTrue(rendered.startsWith("frustration: 1.05"))
        assertTrue(rendered.contains("Frustrated"))
        assertTrue(rendered.contains("confidence 92%"))
    }

    @Test
    fun anAnswerWithoutItsValueIsNotRenderedAsOne() {
        assertEquals("x: no answer", DecisionAnswer(type = "noul").render("x"))
        assertEquals("x: no answer", DecisionAnswer(type = "choice").render("x"))
        assertEquals("x: no answer", DecisionAnswer(type = "score").render("x"))
        assertEquals(
            "x: unexpected answer type refusal",
            DecisionAnswer(type = "refusal").render("x"),
        )
    }

    @Test
    fun answersComeBackInTheOrderTheyWereAsked() {
        val response = json.decodeFromString<DecisionsResponse>(
            """
            {"model":"jev-1.13.0","answers":{
                "b":{"type":"noul","noul":0.2},
                "a":{"type":"noul","noul":0.9}
            }}
            """.trimIndent(),
        )
        val rendered = response.render(listOf("a", "b"))
        assertTrue(rendered.indexOf("a:") < rendered.indexOf("b:"))
        // A name the caller never asked for still has to be shown somewhere.
        assertTrue(rendered.contains("a:"))
        assertTrue(rendered.contains("b:"))
    }

    @Test
    fun usageIsReportedAsTokens() {
        val usage = DecisionsUsage(inputTokens = 296, outputTokens = 20)
        assertEquals(316, usage.toTokenUsage().totalTokenCount)
        assertEquals(296, usage.toTokenUsage().inputTokenCount)
    }

    @Test
    fun aModelWithoutQuestionsIsRefusedBeforeAnyRequest() = runBlocking {
        val config = ProviderConfig(apiKey = "key", modelId = "jev-latest", decisionQuestions = null)
        val events = DecisionsProvider("Jev", "https://api.typesafe.ai")
            .generateResponse(messages, config)
            .toList()
        assertEquals(1, events.size)
        val error = (events.first() as StreamEvent.Error).error
        assertTrue(error is GenerationError.Configuration)
    }

    @Test
    fun aValidQuestionMapIsAccepted() {
        val parsed = DecisionQuestions.parse(
            """
            {"ok":{"type":"noul","instructions":"Is this urgent?"}}
            """.trimIndent(),
        )
        assertTrue(parsed is JsonObject)
        assertNull("blank input means not configured, not invalid", DecisionQuestions.parse("  "))
        assertNull(DecisionQuestions.parse(null))
    }

    @Test
    fun aQuestionWithoutAKnownTypeIsRejected() {
        assertFormatError("unknown type") {
            DecisionQuestions.parse("""{"q":{"type":"maybe","instructions":"?"}}""")
        }
        assertFormatError("missing type") {
            DecisionQuestions.parse("""{"q":{"instructions":"?"}}""")
        }
    }

    @Test
    fun aQuestionWithNeitherInstructionsNorCriteriaIsRejected() {
        assertFormatError { DecisionQuestions.parse("""{"q":{"type":"noul"}}""") }
    }

    @Test
    fun aChoiceMustListItsOptionsAndAScoreItsLevels() {
        assertFormatError("empty choice") {
            DecisionQuestions.parse("""{"q":{"type":"choice","criteria":{}}}""")
        }
        assertFormatError("choice criteria must be an object") {
            DecisionQuestions.parse("""{"q":{"type":"choice","criteria":["a"]}}""")
        }
        assertFormatError("empty score") {
            DecisionQuestions.parse("""{"q":{"type":"score","criteria":[]}}""")
        }
        assertFormatError("score criteria must be an array") {
            DecisionQuestions.parse("""{"q":{"type":"score","criteria":{"0":"low"}}}""")
        }
        // Valid counterparts.
        DecisionQuestions.parse("""{"q":{"type":"choice","criteria":{"a":null,"b":"bee"}}}""")
        DecisionQuestions.parse("""{"q":{"type":"score","criteria":["low","high"]}}""")
    }

    @Test
    fun aNoulAcceptsOptionalCriteriaButOnlyAsAnObject() {
        DecisionQuestions.parse(
            """{"q":{"type":"noul","instructions":"?","criteria":{"true":"yes","false":"no"}}}""",
        )
        assertFormatError {
            DecisionQuestions.parse("""{"q":{"type":"noul","criteria":["yes","no"]}}""")
        }
    }

    @Test
    fun aQuestionMapThatIsNotAnObjectIsRejected() {
        assertFormatError { DecisionQuestions.parse("""["q"]""") }
        assertFormatError { DecisionQuestions.parse("not json") }
        assertFormatError("an empty map answers nothing") { DecisionQuestions.parse("{}") }
    }

    private fun assertFormatError(what: String = "input", body: () -> JsonObject?) {
        try {
            body()
            throw AssertionError("expected a format error for $what")
        } catch (e: DecisionQuestionsFormatException) {
            assertTrue("the message names the problem", e.message.orEmpty().isNotBlank())
        }
    }
}
