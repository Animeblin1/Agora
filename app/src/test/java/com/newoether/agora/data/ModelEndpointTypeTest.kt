package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEndpointTypeTest {

    @Test
    fun theFourTransportsAreSelectable() {
        assertEquals(
            listOf(
                ModelEndpointType.COMPLETIONS,
                ModelEndpointType.RESPONSES,
                ModelEndpointType.DECISIONS,
                ModelEndpointType.CUSTOM,
            ),
            ModelEndpointType.selectable,
        )
    }

    @Test
    fun anUnknownValueFallsBackToCompletions() {
        assertEquals(ModelEndpointType.COMPLETIONS, ModelEndpointType.fromWireValue(null))
        assertEquals(ModelEndpointType.COMPLETIONS, ModelEndpointType.fromWireValue(""))
        assertEquals(ModelEndpointType.COMPLETIONS, ModelEndpointType.fromWireValue("nonsense"))
    }

    @Test
    fun aStoredValueIsReadCaseInsensitivelyAndTrimmed() {
        assertEquals(ModelEndpointType.RESPONSES, ModelEndpointType.fromWireValue(" Responses "))
        assertEquals(ModelEndpointType.DECISIONS, ModelEndpointType.fromWireValue("DECISIONS"))
    }

    @Test
    fun aCustomEndpointKeepsThePathItWasSavedWith() {
        val wire = ModelEndpointType.toWireValue(ModelEndpointType.CUSTOM, "v1/judge")
        assertEquals(ModelEndpointType.CUSTOM, ModelEndpointType.fromWireValue(wire))
        assertEquals("v1/judge", ModelEndpointType.customPathOf(wire))
        // A leading slash is noise: the path is joined onto a base URL that already has one.
        assertEquals("v1/judge", ModelEndpointType.customPathOf("custom:/v1/judge"))
    }

    @Test
    fun aCustomEndpointWithoutAPathStaysCustom() {
        val wire = ModelEndpointType.toWireValue(ModelEndpointType.CUSTOM, null)
        assertEquals(ModelEndpointType.CUSTOM, ModelEndpointType.fromWireValue(wire))
        assertEquals("", ModelEndpointType.customPathOf(wire))
    }

    @Test
    fun aPlainTypeCarriesNoPath() {
        assertEquals("", ModelEndpointType.customPathOf(ModelEndpointType.COMPLETIONS.wireValue))
        assertEquals("", ModelEndpointType.customPathOf(null))
        assertEquals("completions", ModelEndpointType.toWireValue(ModelEndpointType.COMPLETIONS))
    }

    @Test
    fun everyPathIsRelativeToTheBaseUrlItJoins() {
        val paths = ModelEndpointType.selectable.map { type ->
            endpointPathFor(type, "https://api.test", "v1/custom")
        }
        assertTrue(paths.none { it.startsWith("/") })
        assertEquals("chat/completions", endpointPathFor(ModelEndpointType.COMPLETIONS, "https://api.test"))
        assertEquals("responses", endpointPathFor(ModelEndpointType.RESPONSES, "https://api.test"))
        assertEquals("v1/systemone", endpointPathFor(ModelEndpointType.DECISIONS, "https://api.test"))
        assertEquals("v1/custom", endpointPathFor(ModelEndpointType.CUSTOM, "https://api.test", " /v1/custom "))
    }

    @Test
    fun openRouterDecisionsLiveOutsideTheApiV1Prefix() {
        val path = openRouterDecisionsPath()
        assertTrue(path.startsWith("api/alpha/"))
        assertFalse(path.startsWith("api/v1"))
    }
}
