package com.infinifolder.viewer

import org.infiniforge.fx.api.BuiltinFx
import org.infiniforge.fx.api.FxConnection
import org.infiniforge.fx.api.FxEffectCategory
import org.infiniforge.fx.api.FxEffectDescriptor
import org.infiniforge.fx.api.FxGraph
import org.infiniforge.fx.api.FxGraphValidator
import org.infiniforge.fx.api.FxNode
import org.infiniforge.fx.api.FxParameterValue

/** App-owned, ordered image-effect chain. Generator stages blend over the current image. */
internal class FxEffectPipeline {
    private data class Stage(
        var effectId: String,
        val parameters: MutableMap<String, FxParameterValue> = mutableMapOf(),
    )

    private val selectableEffects = BuiltinFx.descriptors.filter { descriptor ->
        descriptor.category != FxEffectCategory.COMPOSITOR
    }
    private val selectableIds = selectableEffects.mapTo(HashSet()) { it.id }
    private var lightningStyle = LightningSpectrumStyle()
    private val stages = mutableListOf(
        Stage("distort:rotozoom"),
        Stage("display:crt"),
        Stage("distort:water-refraction"),
        lightningStage(),
    )
    private var graphRevision = 0L

    @Synchronized
    fun availableFilters(): List<FxEffectDescriptor> = selectableEffects

    @Synchronized
    fun currentEffects(): List<String> = stages.map { it.effectId }

    @Synchronized
    fun replaceStage(index: Int, effectId: String) {
        require(index in stages.indices) { "Effect-chain item $index does not exist" }
        require(effectId in selectableIds) { "Effect $effectId is not available as an image-chain effect" }
        stages[index] = createStage(effectId)
        graphRevision++
    }

    @Synchronized
    fun addStage(effectId: String): Int {
        require(effectId in selectableIds) { "Effect $effectId is not available as an image-chain effect" }
        check(stages.size < MAX_STAGES) { "The image-effect chain is limited to $MAX_STAGES items" }
        stages += createStage(effectId)
        graphRevision++
        return stages.lastIndex
    }

    @Synchronized
    fun removeStage(index: Int): String {
        require(index in stages.indices) { "Effect-chain item $index does not exist" }
        graphRevision++
        return stages.removeAt(index).effectId
    }

    @Synchronized
    fun moveStage(fromIndex: Int, toIndex: Int) {
        require(fromIndex in stages.indices && toIndex in stages.indices) { "Effect-chain position is out of range" }
        if (fromIndex == toIndex) return
        val stage = stages.removeAt(fromIndex)
        stages.add(toIndex, stage)
        graphRevision++
    }

    @Synchronized
    fun stageParameters(index: Int): Map<String, FxParameterValue> {
        require(index in stages.indices) { "Effect-chain item $index does not exist" }
        return parameterValues(stages[index])
    }

    @Synchronized
    fun updateStageParameter(index: Int, name: String, value: FxParameterValue) {
        require(index in stages.indices) { "Effect-chain item $index does not exist" }
        val stage = stages[index]
        val descriptor = BuiltinFx.byId.getValue(stage.effectId)
        val validated = FxGraphValidator.validateParameters(descriptor, parameterValues(stage) + (name to value))
        stage.parameters[name] = validated.getValue(name)
        if (stage.effectId == "generator:lightning-spectrum") {
            lightningStyle = validated.toLightningSpectrumStyle()
        }
        graphRevision++
    }

    @Synchronized
    fun lightningSpectrumStyle(): LightningSpectrumStyle {
        val stage = stages.firstOrNull { it.effectId == "generator:lightning-spectrum" }
        return stage?.let(::parameterValues)?.toLightningSpectrumStyle() ?: lightningStyle
    }

    @Synchronized
    fun updateLightningSpectrumStyle(value: LightningSpectrumStyle) {
        lightningStyle = value
        stages.filter { it.effectId == "generator:lightning-spectrum" }.forEach { stage ->
            stage.parameters.putAll(value.toParameters())
        }
        graphRevision++
    }

    @Synchronized
    fun graph(): FxGraph {
        val nodes = mutableListOf<FxNode>()
        val connections = mutableListOf<FxConnection>()
        var current = FxGraph.SOURCE_NODE_ID
        stages.forEachIndexed { index, stage ->
            val effectId = stage.effectId
            val descriptor = BuiltinFx.byId.getValue(effectId)
            if (descriptor.category == FxEffectCategory.GENERATOR) {
                val generator = "stage_${index}_generator"
                val blend = "stage_${index}_blend"
                nodes += FxNode(generator, effectId, parameterValues(stage))
                nodes += FxNode(blend, "composite:blend")
                connections += FxConnection(current, blend, "base")
                connections += FxConnection(generator, blend, "overlay")
                current = blend
            } else {
                val node = "stage_$index"
                nodes += FxNode(node, effectId, parameterValues(stage))
                connections += FxConnection(current, node, "image")
                current = node
            }
        }
        return FxGraph(
            name = "infinimedia-fx-$graphRevision",
            nodes = nodes,
            connections = connections,
            outputNodeId = current,
        )
    }

    private fun createStage(effectId: String) =
        if (effectId == "generator:lightning-spectrum") lightningStage() else Stage(effectId)

    private fun lightningStage() = Stage(
        effectId = "generator:lightning-spectrum",
        parameters = lightningStyle.toParameters().toMutableMap(),
    )

    private fun parameterValues(stage: Stage): Map<String, FxParameterValue> {
        val descriptor = BuiltinFx.byId.getValue(stage.effectId)
        return descriptor.parameters.associate { parameter ->
            parameter.name to (stage.parameters[parameter.name] ?: parameter.defaultValue)
        }
    }

    private fun LightningSpectrumStyle.toParameters() = mapOf(
        "gain" to FxParameterValue.FloatValue(gain),
        "maxHeight" to FxParameterValue.FloatValue(maxHeight),
        "widthScale" to FxParameterValue.FloatValue(widthScale),
        "branchCount" to FxParameterValue.IntValue(branchCount),
        "animationSpeed" to FxParameterValue.FloatValue(animationSpeed),
        "glow" to FxParameterValue.FloatValue(glow),
        "idleHeight" to FxParameterValue.FloatValue(idleHeight),
    )

    private fun Map<String, FxParameterValue>.toLightningSpectrumStyle() = LightningSpectrumStyle(
        gain = (getValue("gain") as FxParameterValue.FloatValue).value,
        maxHeight = (getValue("maxHeight") as FxParameterValue.FloatValue).value,
        widthScale = (getValue("widthScale") as FxParameterValue.FloatValue).value,
        branchCount = (getValue("branchCount") as FxParameterValue.IntValue).value,
        animationSpeed = (getValue("animationSpeed") as FxParameterValue.FloatValue).value,
        glow = (getValue("glow") as FxParameterValue.FloatValue).value,
        idleHeight = (getValue("idleHeight") as FxParameterValue.FloatValue).value,
    )

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
