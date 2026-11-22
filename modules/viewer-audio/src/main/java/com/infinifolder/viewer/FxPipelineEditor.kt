package com.infinifolder.viewer

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.SeekBar
import com.infinifolder.theme.ThemeManager
import java.util.Locale

/** App-owned effect-chain picker and lightning-visualizer controls. */
internal class FxPipelineEditor(
    private val context: Context,
    private val pipeline: FxEffectPipeline,
    private val onGraphChanged: () -> Unit,
) {
    fun show() {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val effects = pipeline.availableFilters().sortedBy { it.title }
        val effectNames = effects.map { it.title }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        val chainContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val availableContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val lightningContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(context).apply {
            text = "Choose the ordered image-effect chain. Changes apply immediately and stay selected when the track changes. The lightning spectrum remains as a separate overlay."
            textSize = 13f
        }, matchWrap().apply { bottomMargin = dp(12) })
        val chainTitle = sectionTitle("Effect chain")
        content.addView(chainTitle, matchWrap())
        content.addView(chainContainer, matchWrap())
        content.addView(sectionTitle("Available filters and effects"), matchWrap().apply {
            topMargin = dp(12)
        })
        content.addView(availableContainer, matchWrap())
        content.addView(sectionTitle("Lightning overlay"), matchWrap().apply {
            topMargin = dp(14)
        })
        content.addView(lightningContainer, matchWrap())

        fun commitGraph() {
            onGraphChanged()
        }

        fun renderChain() {
            chainContainer.removeAllViews()
            val selectedEffects = pipeline.currentEffects()
            chainTitle.text = "Effect chain (${selectedEffects.size}/${FxEffectPipeline.MAX_STAGES})"
            if (selectedEffects.isEmpty()) {
                chainContainer.addView(TextView(context).apply {
                    text = "No image filters. Album artwork passes through to the lightning overlay."
                    textSize = 12f
                }, matchWrap().apply { bottomMargin = dp(8) })
            }
            selectedEffects.forEachIndexed { index, effectId ->
                val descriptor = effects.firstOrNull { it.id == effectId }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = context.getDrawable(android.R.drawable.dialog_holo_light_frame)
                }
                row.addView(TextView(context).apply {
                    text = "${index + 1}. ${descriptor?.title ?: effectId}"
                    textSize = 14f
                }, matchWrap())
                val spinner = Spinner(context).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, effectNames)
                    val selectedIndex = effects.indexOfFirst { it.id == effectId }.coerceAtLeast(0)
                    setSelection(selectedIndex, false)
                }
                var previousId = effectId
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val replacement = effects.getOrNull(position)?.id ?: return
                        if (replacement == previousId) return
                        previousId = replacement
                        pipeline.replaceStage(index, replacement)
                        commitGraph()
                        renderChain()
                    }
                }
                row.addView(spinner, matchWrap())
                descriptor?.let {
                    row.addView(TextView(context).apply {
                        text = it.description
                        textSize = 11f
                    }, matchWrap())
                }
                val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(actionButton("↑", enabled = index > 0) {
                    pipeline.moveStage(index, index - 1)
                    commitGraph()
                    renderChain()
                }, weighted())
                actions.addView(actionButton("↓", enabled = index < selectedEffects.lastIndex) {
                    pipeline.moveStage(index, index + 1)
                    commitGraph()
                    renderChain()
                }, weighted())
                actions.addView(actionButton("Remove") {
                    pipeline.removeStage(index)
                    commitGraph()
                    renderChain()
                }, weighted())
                row.addView(actions, matchWrap())
                chainContainer.addView(row, matchWrap().apply { bottomMargin = dp(6) })
            }
        }

        fun renderAvailable() {
            availableContainer.removeAllViews()
            effects.forEach { descriptor ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                details.addView(TextView(context).apply {
                    text = descriptor.title
                    textSize = 13f
                }, matchWrap())
                details.addView(TextView(context).apply {
                    text = "${descriptor.category.name.lowercase(Locale.US)} · ${descriptor.description}"
                    textSize = 10f
                }, matchWrap())
                row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(actionButton("Add", enabled = pipeline.currentEffects().size < FxEffectPipeline.MAX_STAGES) {
                    pipeline.addStage(descriptor.id)
                    commitGraph()
                    renderChain()
                    renderAvailable()
                }, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
                availableContainer.addView(row, matchWrap().apply { topMargin = dp(4); bottomMargin = dp(4) })
            }
        }

        val style = pipeline.lightningSpectrumStyle()
        lightningContainer.addView(TextView(context).apply {
            text = "Thin antialiased lightning uses seeded random paths and multiple tendrils. Zero-valued bands have zero height."
            textSize = 12f
        }, matchWrap().apply { bottomMargin = dp(8) })
        fun addSlider(
            title: String,
            minimum: Float,
            maximum: Float,
            initialValue: Float,
            display: (Float) -> String,
            update: (LightningSpectrumStyle, Float) -> LightningSpectrumStyle,
        ) {
            val value = initialValue.coerceIn(minimum, maximum)
            val label = TextView(context).apply { textSize = 12f }
            val seek = SeekBar(context).apply {
                max = 100
                progress = (((value - minimum) / (maximum - minimum)) * max).toInt().coerceIn(0, max)
            }
            fun render(progress: Int, apply: Boolean) {
                val amount = minimum + (maximum - minimum) * progress / seek.max
                label.text = "$title  ${display(amount)}"
                if (apply) {
                    pipeline.updateLightningSpectrumStyle(update(pipeline.lightningSpectrumStyle(), amount))
                    commitGraph()
                }
            }
            render(seek.progress, false)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = render(progress, fromUser)
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            lightningContainer.addView(label, matchWrap())
            lightningContainer.addView(seek, matchWrap().apply { bottomMargin = dp(4) })
        }
        fun number(value: Float) = String.format(Locale.US, "%.2f", value)
        addSlider("Spectrum gain", 0f, 5f, style.gain, ::number) { current, value -> current.copy(gain = value) }
        addSlider("Maximum height", .1f, 1f, style.maxHeight, { "${(it * 100).toInt()}%" }) { current, value -> current.copy(maxHeight = value) }
        addSlider("Graph width", .5f, 1f, style.widthScale, { "${(it * 100).toInt()}%" }) { current, value -> current.copy(widthScale = value) }
        addSlider("Tendrils per bolt", 1f, 8f, style.branchCount.toFloat(), { it.toInt().toString() }) { current, value -> current.copy(branchCount = value.toInt().coerceIn(1, 8)) }
        addSlider("Lightning speed", 0f, 40f, style.animationSpeed, { "${it.toInt()}×" }) { current, value -> current.copy(animationSpeed = value) }
        addSlider("Electric glow", 0f, 2f, style.glow, ::number) { current, value -> current.copy(glow = value) }
        addSlider("Idle baseline", 0f, .2f, style.idleHeight, ::number) { current, value -> current.copy(idleHeight = value) }

        renderChain()
        renderAvailable()
        val scroll = ScrollView(context).apply { addView(content) }
        ThemeManager.createDialogBuilder(context)
            .setTitle("FX pipeline editor")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text
        textSize = 16f
        setPadding(0, 0, 0, 0)
    }

    private fun actionButton(text: String, enabled: Boolean = true, action: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        isEnabled = enabled
        setOnClickListener { action() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}
