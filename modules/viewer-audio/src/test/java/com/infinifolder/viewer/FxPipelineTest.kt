package com.infinifolder.viewer

import org.infiniforge.fx.api.BuiltinFx
import org.infiniforge.fx.api.FxEffectCategory
import org.infiniforge.fx.api.FxGraph
import org.infiniforge.fx.api.FxGraphValidator
import org.infiniforge.fx.api.FxParameterValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxPipelineTest {
    @Test fun editorCanReplaceAddReorderAndRemoveAnyStage() {
        val pipeline = FxEffectPipeline()
        assertEquals(
            listOf(
                "distort:rotozoom",
                "display:crt",
                "distort:water-refraction",
                "generator:lightning-spectrum",
            ),
            pipeline.currentEffects(),
        )
        assertTrue(pipeline.availableFilters().any { it.id == "distort:kaleidoscope" })
        assertTrue(pipeline.availableFilters().any { it.id == "generator:lightning-spectrum" })

        pipeline.replaceStage(1, "display:lcd")
        pipeline.moveStage(2, 0)
        pipeline.addStage("distort:kaleidoscope")
        assertEquals(
            listOf(
                "distort:water-refraction",
                "distort:rotozoom",
                "display:lcd",
                "generator:lightning-spectrum",
                "distort:kaleidoscope",
            ),
            pipeline.currentEffects(),
        )
        assertEquals("display:lcd", pipeline.removeStage(2))
        assertEquals(
            listOf(
                "distort:water-refraction",
                "distort:rotozoom",
                "generator:lightning-spectrum",
                "distort:kaleidoscope",
            ),
            pipeline.currentEffects(),
        )
        pipeline.addStage("generator:fire")
        assertValidGraph(pipeline.graph())
    }

    @Test fun kaleidoscopeConsumesThePreviousChainStageOutput() {
        val pipeline = FxEffectPipeline()
        pipeline.replaceStage(1, "distort:kaleidoscope")
        val graph = pipeline.graph()

        assertEquals(
            FxGraph.SOURCE_NODE_ID,
            graph.connections.single { it.toNodeId == "stage_0" && it.inputPort == "image" }.fromNodeId,
        )
        assertEquals(
            "stage_0",
            graph.connections.single { it.toNodeId == "stage_1" && it.inputPort == "image" }.fromNodeId,
        )
        assertEquals("distort:kaleidoscope", graph.nodes.single { it.id == "stage_1" }.effectId)
        assertValidGraph(graph)
    }

    @Test fun lightningIsASelectableChainStageAndBlendsAtItsPosition() {
        val pipeline = FxEffectPipeline()
        val graph = pipeline.graph()
        val lightning = graph.nodes.single { it.effectId == "generator:lightning-spectrum" }
        val blendEdge = graph.connections.single { it.fromNodeId == lightning.id && it.inputPort == "overlay" }
        val blend = graph.nodes.single { it.id == blendEdge.toNodeId }

        assertEquals("stage_3_generator", lightning.id)
        assertEquals("stage_3_blend", blend.id)
        assertEquals("composite:blend", blend.effectId)
        assertEquals(
            "stage_2",
            graph.connections.single { it.toNodeId == blend.id && it.inputPort == "base" }.fromNodeId,
        )
        assertEquals(blend.id, graph.outputNodeId)

        pipeline.moveStage(3, 1)
        val moved = pipeline.graph()
        val movedGenerator = moved.nodes.single { it.effectId == "generator:lightning-spectrum" }
        assertEquals("stage_1_generator", movedGenerator.id)
        assertEquals(
            "stage_0",
            moved.connections.single { it.toNodeId == "stage_1_blend" && it.inputPort == "base" }.fromNodeId,
        )

        pipeline.removeStage(1)
        assertTrue(pipeline.graph().nodes.none { it.effectId == "generator:lightning-spectrum" })
        assertValidGraph(pipeline.graph())
    }

    @Test fun emptyChainPassesArtworkThroughWithoutAHiddenLightningGenerator() {
        val pipeline = FxEffectPipeline()
        while (pipeline.currentEffects().isNotEmpty()) pipeline.removeStage(0)
        val graph = pipeline.graph()
        assertEquals(FxGraph.SOURCE_NODE_ID, graph.outputNodeId)
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.connections.isEmpty())
        assertEquals(null, graph.lightningOnlyFallback())
        FxGraphValidator.validate(graph, BuiltinFx.byId)
    }

    @Test fun chainRejectsInvalidIdsPositionsAndMoreThanMaximumStages() {
        val pipeline = FxEffectPipeline()
        try { pipeline.replaceStage(0, "composite:blend"); throw AssertionError("compositor should not be a chain item") }
        catch (_: IllegalArgumentException) { }
        try { pipeline.removeStage(8); throw AssertionError("out-of-range removal should fail") }
        catch (_: IllegalArgumentException) { }
        pipeline.replaceStage(0, "generator:lightning-spectrum")
        while (pipeline.currentEffects().size < FxEffectPipeline.MAX_STAGES) pipeline.addStage("core:passthrough")
        assertValidGraph(pipeline.graph())
        try { pipeline.addStage("core:passthrough"); throw AssertionError("chain cap should be enforced") }
        catch (_: IllegalStateException) { }
    }

    @Test fun lightningSpectrumKeepsExpectedDefaultsAndZeroFreeIdleBaseline() {
        val node = FxEffectPipeline().graph().nodes.single { it.effectId == "generator:lightning-spectrum" }
        assertEquals(1f, (node.parameters.getValue("widthScale") as FxParameterValue.FloatValue).value)
        assertEquals(1f, (node.parameters.getValue("maxHeight") as FxParameterValue.FloatValue).value)
        assertEquals(1f, (node.parameters.getValue("gain") as FxParameterValue.FloatValue).value)
        assertEquals(20f, (node.parameters.getValue("animationSpeed") as FxParameterValue.FloatValue).value)
        assertEquals(6, (node.parameters.getValue("branchCount") as FxParameterValue.IntValue).value)
        assertEquals(.14f, (node.parameters.getValue("idleHeight") as FxParameterValue.FloatValue).value)
    }

    @Test fun lightningOnlyFallbackIsValidAndUsesTheChainGeneratorsStyle() {
        val graph = FxEffectPipeline().graph()
        val fallback = requireNotNull(graph.lightningOnlyFallback())
        FxGraphValidator.validate(fallback, BuiltinFx.byId)
        assertEquals("generator:lightning-spectrum", fallback.nodes.single().effectId)
        assertEquals("lightning_fallback", fallback.outputNodeId)
        assertEquals(
            graph.nodes.single { it.effectId == "generator:lightning-spectrum" }.parameters,
            fallback.nodes.single().parameters,
        )
    }

    @Test fun lightningEditorParametersReachItsChainGeneratorNode() {
        val pipeline = FxEffectPipeline()
        pipeline.updateLightningSpectrumStyle(
            LightningSpectrumStyle(gain = 3.4f, maxHeight = .72f, widthScale = .76f, branchCount = 7, animationSpeed = 30f, glow = 1.2f),
        )
        val node = pipeline.graph().nodes.single { it.effectId == "generator:lightning-spectrum" }
        assertEquals(3.4f, (node.parameters.getValue("gain") as FxParameterValue.FloatValue).value)
        assertEquals(.72f, (node.parameters.getValue("maxHeight") as FxParameterValue.FloatValue).value)
        assertEquals(.76f, (node.parameters.getValue("widthScale") as FxParameterValue.FloatValue).value)
        assertEquals(7, (node.parameters.getValue("branchCount") as FxParameterValue.IntValue).value)
        assertEquals(30f, (node.parameters.getValue("animationSpeed") as FxParameterValue.FloatValue).value)
    }

    @Test fun effectParametersAreAppliedPerStageAndFollowItemsWhenMoved() {
        val pipeline = FxEffectPipeline()
        pipeline.updateStageParameter(0, "zoom", FxParameterValue.FloatValue(1.7f))
        pipeline.updateStageParameter(1, "mask", FxParameterValue.EnumValue("aperture"))

        var graph = pipeline.graph()
        assertEquals(
            1.7f,
            (graph.nodes.single { it.id == "stage_0" }.parameters.getValue("zoom") as FxParameterValue.FloatValue).value,
        )
        assertEquals(
            "aperture",
            (graph.nodes.single { it.id == "stage_1" }.parameters.getValue("mask") as FxParameterValue.EnumValue).value,
        )

        pipeline.moveStage(1, 0)
        graph = pipeline.graph()
        assertEquals(
            "aperture",
            (graph.nodes.single { it.id == "stage_0" }.parameters.getValue("mask") as FxParameterValue.EnumValue).value,
        )
        assertEquals(
            1.7f,
            (graph.nodes.single { it.id == "stage_1" }.parameters.getValue("zoom") as FxParameterValue.FloatValue).value,
        )
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
        graph.nodes.filter { BuiltinFx.byId.getValue(it.effectId).category == FxEffectCategory.GENERATOR }.forEach { generator ->
            val overlay = graph.connections.single { it.fromNodeId == generator.id && it.inputPort == "overlay" }
            assertEquals("composite:blend", graph.nodes.single { it.id == overlay.toNodeId }.effectId)
        }
    }
}
