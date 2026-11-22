package com.infinifolder.viewer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    fun show() {
        val effects = pipeline.availableFilters().sortedBy { it.title }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            setBackgroundColor(BACKGROUND)
        }
        val chainContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val addContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val lightningContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val lightningTitle = sectionTitle("Lightning spectrum settings")
        val chainTitle = sectionTitle("Effect chain")

        content.addView(TextView(context).apply {
            text = "Choose an effect in each row. Delete is the last choice; Add effect appends a stage."
            textSize = 12f
            setTextColor(SECONDARY_TEXT)
        }, matchWrap().apply { bottomMargin = dp(10) })
        content.addView(chainTitle, matchWrap().apply { bottomMargin = dp(6) })
        content.addView(chainContainer, matchWrap())
        content.addView(addContainer, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(14) })
        content.addView(lightningTitle, matchWrap().apply { bottomMargin = dp(4) })
        content.addView(lightningContainer, matchWrap())

        fun commitGraph() = onGraphChanged()

        lateinit var renderAddDropdown: () -> Unit
        fun renderLightningSettings(isInChain: Boolean) {
            lightningContainer.removeAllViews()
            if (!isInChain) {
                lightningContainer.addView(TextView(context).apply {
                    text = "Add Lightning spectrum to the chain to configure it."
                    textSize = 12f
                    setTextColor(SECONDARY_TEXT)
                    setPadding(0, dp(4), 0, dp(8))
                }, matchWrap())
                return
            }

            val style = pipeline.lightningSpectrumStyle()
            lightningContainer.addView(TextView(context).apply {
                text = "Blended over the image at this chain position. Zero-valued bands have zero height."
                textSize = 12f
                setTextColor(SECONDARY_TEXT)
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
                val label = TextView(context).apply {
                    textSize = 12f
                    setTextColor(PRIMARY_TEXT)
                }
                val seek = SeekBar(context).apply {
                    max = 100
                    progress = (((value - minimum) / (maximum - minimum)) * max).toInt().coerceIn(0, max)
                    progressTintList = ColorStateList.valueOf(ACCENT)
                    thumbTintList = ColorStateList.valueOf(ACCENT)
                }
                fun render(progress: Int, apply: Boolean) {
                    val amount = minimum + (maximum - minimum) * progress / seek.max
                    label.text = "$title  " + display(amount)
                    if (apply) {
                        pipeline.updateLightningSpectrumStyle(update(pipeline.lightningSpectrumStyle(), amount))
                        commitGraph()
                    }
                }
                render(seek.progress, false)
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) =
                        render(progress, fromUser)
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
                lightningContainer.addView(label, matchWrap())
                lightningContainer.addView(seek, matchWrap().apply { bottomMargin = dp(4) })
            }

            fun number(value: Float) = String.format(Locale.US, "%.2f", value)
            addSlider("Spectrum gain", 0f, 5f, style.gain, ::number) { current, value -> current.copy(gain = value) }
            addSlider("Maximum height", .1f, 1f, style.maxHeight, { String.format(Locale.US, "%d%%", (it * 100).toInt()) }) { current, value -> current.copy(maxHeight = value) }
            addSlider("Graph width", .5f, 1f, style.widthScale, { String.format(Locale.US, "%d%%", (it * 100).toInt()) }) { current, value -> current.copy(widthScale = value) }
            addSlider("Tendrils per bolt", 1f, 8f, style.branchCount.toFloat(), { it.toInt().toString() }) { current, value -> current.copy(branchCount = value.toInt().coerceIn(1, 8)) }
            addSlider("Lightning speed", 0f, 40f, style.animationSpeed, { String.format(Locale.US, "%d×", it.toInt()) }) { current, value -> current.copy(animationSpeed = value) }
            addSlider("Electric glow", 0f, 2f, style.glow, ::number) { current, value -> current.copy(glow = value) }
            addSlider("Idle baseline", 0f, .2f, style.idleHeight, ::number) { current, value -> current.copy(idleHeight = value) }
        }

        fun renderChain() {
            chainContainer.removeAllViews()
            val selected = pipeline.currentEffects()
            chainTitle.text = "Effect chain (${selected.size}/${FxEffectPipeline.MAX_STAGES})"
            if (selected.isEmpty()) {
                chainContainer.addView(TextView(context).apply {
                    text = "No filters — artwork passes through."
                    textSize = 13f
                    setTextColor(SECONDARY_TEXT)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                }, matchWrap())
            }
            selected.forEachIndexed { index, effectId ->
                val selectedIndex = effects.indexOfFirst { it.id == effectId }.coerceAtLeast(0)
                val choices = effects.map { descriptor ->
                    "${index + 1}. ${descriptor.title}"
                } + "Delete effect"
                val spinner = styledDropdown(
                    choices = choices,
                    selectedPosition = selectedIndex,
                    destructivePosition = effects.size,
                    contentDescription = "Effect ${index + 1}: ${effects[selectedIndex].title}",
                )
                var currentSelection = selectedIndex
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position == currentSelection) return
                        if (position == effects.size) {
                            pipeline.removeStage(index)
                        } else {
                            val replacement = effects.getOrNull(position)?.id ?: return
                            pipeline.replaceStage(index, replacement)
                            currentSelection = position
                        }
                        commitGraph()
                        renderChain()
                    }
                }
                chainContainer.addView(spinnerRow(spinner), matchWrap().apply { bottomMargin = dp(8) })
            }
            renderAddDropdown()
            renderLightningSettings(selected.any { it == "generator:lightning-spectrum" })
        }

        renderAddDropdown = {
            addContainer.removeAllViews()
            val addChoices = listOf("＋ Add effect…") + effects.map { it.title }
            val addSpinner = styledDropdown(
                choices = addChoices,
                selectedPosition = 0,
                promptPosition = 0,
                isEnabled = pipeline.currentEffects().size < FxEffectPipeline.MAX_STAGES,
                contentDescription = "Add an effect to the chain",
            )
            addSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) return
                    val effect = effects.getOrNull(position - 1) ?: return
                    pipeline.addStage(effect.id)
                    commitGraph()
                    renderChain()
                }
            }
            addContainer.addView(spinnerRow(addSpinner, prompt = true), matchWrap())
            if (pipeline.currentEffects().size >= FxEffectPipeline.MAX_STAGES) {
                addContainer.addView(TextView(context).apply {
                    text = "Maximum of ${FxEffectPipeline.MAX_STAGES} effects"
                    textSize = 11f
                    setTextColor(SECONDARY_TEXT)
                    setPadding(dp(10), dp(4), 0, 0)
                }, matchWrap())
            }
        }


        renderChain()
        val scroll = ScrollView(context).apply {
            addView(content)
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
        }
        ThemeManager.createDialogBuilder(context)
            .setTitle("FX pipeline editor")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun styledDropdown(
        choices: List<String>,
        selectedPosition: Int,
        destructivePosition: Int? = null,
        promptPosition: Int? = null,
        isEnabled: Boolean = true,
        contentDescription: String,
    ) = Spinner(context, Spinner.MODE_DROPDOWN).apply {
        adapter = FxChoiceAdapter(choices, destructivePosition, promptPosition)
        setSelection(selectedPosition, false)
        setPopupBackgroundDrawable(ColorDrawable(POPUP_BACKGROUND))
        dropDownWidth = ViewGroup.LayoutParams.MATCH_PARENT
        background = ColorDrawable(Color.TRANSPARENT)
        setPadding(dp(8), 0, dp(4), 0)
        this.isEnabled = isEnabled
        this.contentDescription = contentDescription
    }

    private fun spinnerRow(spinner: Spinner, prompt: Boolean = false): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            background = GradientDrawable().apply {
                setColor(ROW_BACKGROUND)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), if (prompt) ACCENT else ROW_BORDER)
            }
            alpha = if (spinner.isEnabled) 1f else .58f
        }
        row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(TextView(context).apply {
            text = "▾"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(if (prompt) ACCENT else PRIMARY_TEXT)
            setOnClickListener { if (spinner.isEnabled) spinner.performClick() }
        }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT))
        return row
    }

    private fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(PRIMARY_TEXT)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private inner class FxChoiceAdapter(
        private val choices: List<String>,
        private val destructivePosition: Int?,
        private val promptPosition: Int?,
    ) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, choices) {
        override fun getCount(): Int = choices.size
        override fun getItem(position: Int): String = choices[position]
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = choiceView(position, convertView, false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = choiceView(position, convertView, true)

        private fun choiceView(position: Int, convertView: View?, inPopup: Boolean): TextView =
            (convertView as? TextView ?: TextView(context)).apply {
                text = choices[position]
                textSize = 14f
                typeface = if (position == destructivePosition) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(when (position) {
                    destructivePosition -> DELETE_TEXT
                    promptPosition -> ACCENT
                    else -> PRIMARY_TEXT
                })
                setPadding(dp(12), 0, dp(12), 0)
                minHeight = dp(if (inPopup) 48 else 52)
                setBackgroundColor(if (inPopup) POPUP_BACKGROUND else Color.TRANSPARENT)
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(if (inPopup) 48 else 52),
                )
            }
    }

    private companion object {
        const val BACKGROUND = 0xFF17191D.toInt()
        const val ROW_BACKGROUND = 0xFF25282E.toInt()
        const val POPUP_BACKGROUND = 0xFF25282E.toInt()
        const val ROW_BORDER = 0xFF4B515B.toInt()
        const val PRIMARY_TEXT = 0xFFF4F5F7.toInt()
        const val SECONDARY_TEXT = 0xFFB8BEC8.toInt()
        const val ACCENT = 0xFF73CFFF.toInt()
        const val DELETE_TEXT = 0xFFFF8A80.toInt()
    }
}
