package com.infinifolder.viewer

import org.infiniforge.fx.api.BuiltinFx
import org.infiniforge.fx.api.FxConnection
import org.infiniforge.fx.api.FxEffectCategory
import org.infiniforge.fx.api.FxEffectDescriptor
import org.infiniforge.fx.api.FxGraph
import org.infiniforge.fx.api.FxNode
import org.infiniforge.fx.api.FxParameterValue

/** App-owned, ordered image-effect chain with an always-on spectrum overlay. */
internal class FxEffectPipeline {
    private val selectableEffects = BuiltinFx.descriptors.filter { descriptor ->
        descriptor.category != FxEffectCategory.COMPOSITOR && descriptor.id != "generator:lightning-spectrum"
    }
    private val selectableIds = selectableEffects.mapTo(HashSet()) { it.id }
    private val stages = mutableListOf("distort:rotozoom", "display:crt", "distort:water-refraction")
    private var graphRevision = 0L
    private var lightningStyle = LightningSpectrumStyle()

    @Synchronized
    fun availableFilters(): List<FxEffectDescriptor> = selectableEffects

    @Synchronized
    fun currentEffects(): List<String> = stages.toList()

    @Synchronized
    fun replaceStage(index: Int, effectId: String) {
        require(index in stages.indices) { "Effect-chain item $index does not exist" }
        require(effectId in selectableIds) { "Effect $effectId is not available as an image-chain effect" }
        stages[index] = effectId
        graphRevision++
    }

    @Synchronized
    fun addStage(effectId: String): Int {
        require(effectId in selectableIds) { "Effect $effectId is not available as an image-chain effect" }
        check(stages.size < MAX_STAGES) { "The image-effect chain is limited to $MAX_STAGES items" }
        stages += effectId
        graphRevision++
        return stages.lastIndex
    }

    @Synchronized
    fun removeStage(index: Int): String {
        require(index in stages.indices) { "Effect-chain item $index does not exist" }
        graphRevision++
        return stages.removeAt(index)
    }

    @Synchronized
    fun moveStage(fromIndex: Int, toIndex: Int) {
        require(fromIndex in stages.indices && toIndex in stages.indices) { "Effect-chain position is out of range" }
        if (fromIndex == toIndex) return
        val effect = stages.removeAt(fromIndex)
        stages.add(toIndex, effect)
        graphRevision++
    }

    @Synchronized
    fun lightningSpectrumStyle(): LightningSpectrumStyle = lightningStyle

    @Synchronized
    fun updateLightningSpectrumStyle(value: LightningSpectrumStyle) {
        lightningStyle = value
        graphRevision++
    }

    @Synchronized
    fun graph(): FxGraph {
        val nodes = mutableListOf<FxNode>()
        val connections = mutableListOf<FxConnection>()
        var current = FxGraph.SOURCE_NODE_ID
        stages.forEachIndexed { index, effectId ->
            if (effectId == "generator:fire") {
                val generator = "stage_${index}_generator"
                val blend = "stage_${index}_blend"
                nodes += FxNode(generator, effectId)
                nodes += FxNode(blend, "composite:blend")
                connections += FxConnection(current, blend, "base")
                connections += FxConnection(generator, blend, "overlay")
                current = blend
            } else {
                val node = "stage_$index"
                nodes += FxNode(node, effectId)
                connections += FxConnection(current, node, "image")
                current = node
            }
        }
        val spectrum = "lightning_spectrum"
        val blend = "lightning_blend"
        nodes += FxNode(
            spectrum,
            "generator:lightning-spectrum",
            parameters = mapOf(
                "gain" to FxParameterValue.FloatValue(lightningStyle.gain),
                "maxHeight" to FxParameterValue.FloatValue(lightningStyle.maxHeight),
                "widthScale" to FxParameterValue.FloatValue(lightningStyle.widthScale),
                "branchCount" to FxParameterValue.IntValue(lightningStyle.branchCount),
                "animationSpeed" to FxParameterValue.FloatValue(lightningStyle.animationSpeed),
                "glow" to FxParameterValue.FloatValue(lightningStyle.glow),
                "idleHeight" to FxParameterValue.FloatValue(lightningStyle.idleHeight),
            ),
        )
        nodes += FxNode(blend, "composite:blend")
        connections += FxConnection(current, blend, "base")
        connections += FxConnection(spectrum, blend, "overlay")
        return FxGraph(
            name = "infinimedia-fx-$graphRevision",
            nodes = nodes,
            connections = connections,
            outputNodeId = blend,
        )
    }

    companion object {
        const val MAX_STAGES = 12
    }
}

internal data class LightningSpectrumStyle(
    val gain: Float = 1f,
    val maxHeight: Float = 1f,
    val widthScale: Float = 1f,
    val branchCount: Int = 6,
    val animationSpeed: Float = 20f,
    val glow: Float = .9f,
    val idleHeight: Float = .14f,
)

/** A narrow recovery graph keeps the spectrum available if an image-effect graph fails. */
internal fun FxGraph.lightningOnlyFallback(): FxGraph? {
    val lightning = nodes.firstOrNull { it.effectId == "generator:lightning-spectrum" } ?: return null
    return FxGraph(
        name = "infinimedia-lightning-fallback",
        nodes = listOf(lightning.copy(id = "lightning_fallback")),
        connections = emptyList(),
        outputNodeId = "lightning_fallback",
    )
}
