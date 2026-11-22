package com.infinifolder.viewer

import org.infiniforge.fx.api.BuiltinFx
import org.infiniforge.fx.api.FxGraph
import org.infiniforge.fx.api.FxGraphValidator
import org.infiniforge.fx.api.FxParameterValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FxPipelineTest {
    @Test fun editorCanReplaceAddReorderAndRemoveAnyStage() {
        val pipeline = FxEffectPipeline()
        assertEquals(listOf("distort:rotozoom", "display:crt", "distort:water-refraction"), pipeline.currentEffects())
        assertTrue(pipeline.availableFilters().any { it.id == "distort:kaleidoscope" })

        pipeline.replaceStage(1, "display:lcd")
        pipeline.moveStage(2, 0)
        pipeline.addStage("distort:kaleidoscope")
        assertEquals(listOf("distort:water-refraction", "distort:rotozoom", "display:lcd", "distort:kaleidoscope"), pipeline.currentEffects())
        assertEquals("display:lcd", pipeline.removeStage(2))
        assertEquals(listOf("distort:water-refraction", "distort:rotozoom", "distort:kaleidoscope"), pipeline.currentEffects())
        pipeline.addStage("generator:fire")
        assertValidGraph(pipeline.graph())
    }

    @Test fun lightningRemainsASeparateOverlayAboveTheImageChain() {
        val pipeline = FxEffectPipeline()
        assertFalse(pipeline.availableFilters().any { it.id == "generator:lightning-spectrum" })
        val graph = pipeline.graph()
        val outputBlend = graph.nodes.single { it.id == graph.outputNodeId }
        assertEquals("composite:blend", outputBlend.effectId)
        assertEquals(
            "lightning_spectrum",
            graph.connections.single { it.toNodeId == outputBlend.id && it.inputPort == "overlay" }.fromNodeId,
        )
        assertEquals(
            "stage_2",
            graph.connections.single { it.toNodeId == outputBlend.id && it.inputPort == "base" }.fromNodeId,
        )
        assertValidGraph(graph)
    }

    @Test fun emptyChainPassesArtworkThroughToLightningOverlay() {
        val pipeline = FxEffectPipeline()
        while (pipeline.currentEffects().isNotEmpty()) pipeline.removeStage(0)
        val graph = pipeline.graph()
        assertEquals(FxGraph.SOURCE_NODE_ID, graph.connections.first { it.toNodeId == "lightning_blend" }.fromNodeId)
        assertEquals("generator:lightning-spectrum", graph.nodes.single { it.id == "lightning_spectrum" }.effectId)
        assertValidGraph(graph)
    }

    @Test fun chainRejectsInvalidIdsPositionsAndMoreThanMaximumStages() {
        val pipeline = FxEffectPipeline()
        try { pipeline.replaceStage(0, "generator:lightning-spectrum"); throw AssertionError("overlay should not be a chain filter") }
        catch (_: IllegalArgumentException) { }
        try { pipeline.removeStage(8); throw AssertionError("out-of-range removal should fail") }
        catch (_: IllegalArgumentException) { }
        while (pipeline.currentEffects().size < FxEffectPipeline.MAX_STAGES) pipeline.addStage("core:passthrough")
        assertValidGraph(pipeline.graph())
        try { pipeline.addStage("core:passthrough"); throw AssertionError("chain cap should be enforced") }
        catch (_: IllegalStateException) { }
    }

    @Test fun lightningSpectrumKeepsExpectedDefaultsAndZeroFreeIdleBaseline() {
        val node = FxEffectPipeline().graph().nodes.single { it.id == "lightning_spectrum" }
        assertEquals(1f, (node.parameters.getValue("widthScale") as FxParameterValue.FloatValue).value)
        assertEquals(1f, (node.parameters.getValue("maxHeight") as FxParameterValue.FloatValue).value)
        assertEquals(1f, (node.parameters.getValue("gain") as FxParameterValue.FloatValue).value)
        assertEquals(20f, (node.parameters.getValue("animationSpeed") as FxParameterValue.FloatValue).value)
        assertEquals(6, (node.parameters.getValue("branchCount") as FxParameterValue.IntValue).value)
        assertEquals(.14f, (node.parameters.getValue("idleHeight") as FxParameterValue.FloatValue).value)
    }

    @Test fun lightningOnlyFallbackIsAValidGeneratorGraphAndKeepsStyle() {
        val graph = FxEffectPipeline().graph()
        val fallback = requireNotNull(graph.lightningOnlyFallback())
        FxGraphValidator.validate(fallback, BuiltinFx.byId)
        assertEquals("generator:lightning-spectrum", fallback.nodes.single().effectId)
        assertEquals("lightning_fallback", fallback.outputNodeId)
        assertEquals(graph.nodes.single { it.id == "lightning_spectrum" }.parameters, fallback.nodes.single().parameters)
    }

    @Test fun lightningEditorParametersReachTheGeneratorNode() {
        val pipeline = FxEffectPipeline()
        pipeline.updateLightningSpectrumStyle(LightningSpectrumStyle(gain = 3.4f, maxHeight = .72f, widthScale = .76f, branchCount = 7, animationSpeed = 30f, glow = 1.2f))
        val node = pipeline.graph().nodes.single { it.id == "lightning_spectrum" }
        assertEquals(3.4f, (node.parameters.getValue("gain") as FxParameterValue.FloatValue).value)
        assertEquals(.72f, (node.parameters.getValue("maxHeight") as FxParameterValue.FloatValue).value)
        assertEquals(.76f, (node.parameters.getValue("widthScale") as FxParameterValue.FloatValue).value)
        assertEquals(7, (node.parameters.getValue("branchCount") as FxParameterValue.IntValue).value)
        assertEquals(30f, (node.parameters.getValue("animationSpeed") as FxParameterValue.FloatValue).value)
    }

    @Test fun existingAnalyzerLevelsMapToNormalizedFxMetadata() {
        val metadata = FxAudioMetadataAdapter.snapshot(
            bands = FloatArray(16) { it / 15f },
            vu = floatArrayOf(.3f, .4f, .8f, .6f),
            timestampNanos = 123L,
            bpm = 127f,
            onset = org.infiniforge.fx.api.FxAudioEvent(id = 3L, timestampNanos = 120L, strength = .7f),
        )
        assertEquals(123L, metadata.timestampNanos)
        assertEquals(127f, metadata.bpm!!, .0001f)
        assertEquals(3L, metadata.onsetEvents.single().id)
        assertEquals(kotlin.math.sqrt(.125f), metadata.rms, .0001f)
        assertEquals(.8f, metadata.peak, .0001f)
        assertEquals((0f + 1f / 15f + 2f / 15f + 3f / 15f) / 4f, metadata.lowEnergy, .0001f)
        assertEquals(((4f + 5f + 6f + 7f + 8f + 9f) / 15f) / 6f, metadata.midEnergy, .0001f)
        assertEquals(12.5f / 15f, metadata.highEnergy, .0001f)
    }

    private fun assertValidGraph(graph: FxGraph) {
        FxGraphValidator.validate(graph, BuiltinFx.byId)
        assertTrue(graph.nodes.any { it.id == "lightning_spectrum" })
        assertTrue(graph.connections.any { it.fromNodeId == "lightning_spectrum" && it.inputPort == "overlay" })
    }
}
