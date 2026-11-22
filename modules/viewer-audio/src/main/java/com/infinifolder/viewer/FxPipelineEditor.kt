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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.infinifolder.theme.ThemeManager
import org.infiniforge.fx.api.BuiltinFx
import org.infiniforge.fx.api.FxEffectDescriptor
import org.infiniforge.fx.api.FxParameterDescriptor
import org.infiniforge.fx.api.FxParameterType
import org.infiniforge.fx.api.FxParameterValue
import java.util.Locale
import kotlin.math.roundToInt

/** App-owned effect-chain picker and descriptor-driven parameter editor. */
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
            setPadding(dp(12), dp(10), dp(12), dp(14))
            setBackgroundColor(BACKGROUND)
        }
        val chainContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val addContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val chainTitle = sectionTitle("Effect chain")

        content.addView(TextView(context).apply {
            text = "Select an effect, edit its parameters, move it, or delete it. Add effect appends a stage."
            textSize = 12f
            setTextColor(SECONDARY_TEXT)
        }, matchWrap().apply { bottomMargin = dp(10) })
        content.addView(chainTitle, matchWrap().apply { bottomMargin = dp(6) })
        content.addView(chainContainer, matchWrap())
        content.addView(addContainer, matchWrap().apply { topMargin = dp(8) })

        fun commitGraph() = onGraphChanged()

        lateinit var renderAddDropdown: () -> Unit
        fun renderChain() {
            chainContainer.removeAllViews()
            val selected = pipeline.currentEffects()
            chainTitle.text = "Effect chain (${selected.size}/${FxEffectPipeline.MAX_STAGES})"
            if (selected.isEmpty()) {
                chainContainer.addView(TextView(context).apply {
                    text = "No effects — artwork passes through."
                    textSize = 13f
                    setTextColor(SECONDARY_TEXT)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                }, matchWrap())
            }
            selected.forEachIndexed { index, effectId ->
                val selectedIndex = effects.indexOfFirst { it.id == effectId }.coerceAtLeast(0)
                val descriptor = effects[selectedIndex]
                val spinner = styledDropdown(
                    choices = effects.map { "${index + 1}. ${it.title}" },
                    selectedPosition = selectedIndex,
                    contentDescription = "Effect ${index + 1}: ${descriptor.title}",
                )
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position == selectedIndex) return
                        val replacement = effects.getOrNull(position)?.id ?: return
                        pipeline.replaceStage(index, replacement)
                        commitGraph()
                        renderChain()
                    }
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(spinnerRow(spinner), LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(2)
                })
                row.addView(iconButton("✏️", "Edit ${descriptor.title}") {
                    showStageEditor(index, descriptor, ::commitGraph)
                }, iconLayoutParams())
                row.addView(iconButton("⬆️", "Move ${descriptor.title} up", enabled = index > 0) {
                    pipeline.moveStage(index, index - 1)
                    commitGraph()
                    renderChain()
                }, iconLayoutParams())
                row.addView(iconButton("⬇️", "Move ${descriptor.title} down", enabled = index < selected.lastIndex) {
                    pipeline.moveStage(index, index + 1)
                    commitGraph()
                    renderChain()
                }, iconLayoutParams())
                row.addView(iconButton("🗑️", "Delete ${descriptor.title}", destructive = true) {
                    pipeline.removeStage(index)
                    commitGraph()
                    renderChain()
                }, iconLayoutParams())
                chainContainer.addView(row, matchWrap().apply { bottomMargin = dp(8) })
            }
            renderAddDropdown()
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

    private fun showStageEditor(index: Int, descriptor: FxEffectDescriptor, onChanged: () -> Unit) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(BACKGROUND)
        }
        content.addView(TextView(context).apply {
            text = descriptor.description
            textSize = 13f
            setTextColor(SECONDARY_TEXT)
        }, matchWrap().apply { bottomMargin = dp(12) })

        if (descriptor.parameters.isEmpty()) {
            content.addView(TextView(context).apply {
                text = "This effect has no adjustable parameters."
                textSize = 13f
                setTextColor(SECONDARY_TEXT)
            }, matchWrap())
        } else {
            val current = pipeline.stageParameters(index)
            descriptor.parameters.forEach { parameter ->
                val value = current.getValue(parameter.name)
                when (parameter.type) {
                    FxParameterType.FLOAT, FxParameterType.INT -> addNumericParameter(
                        content, index, parameter, value, onChanged,
                    )
                    FxParameterType.BOOLEAN -> addBooleanParameter(
                        content, index, parameter, value as FxParameterValue.BoolValue, onChanged,
                    )
                    FxParameterType.ENUM -> addEnumParameter(
                        content, index, parameter, value as FxParameterValue.EnumValue, onChanged,
                    )
                    FxParameterType.VECTOR -> content.addView(TextView(context).apply {
                        text = "${prettyName(parameter.name)}: vector editor is not available yet."
                        textSize = 12f
                        setTextColor(SECONDARY_TEXT)
                    }, matchWrap().apply { bottomMargin = dp(8) })
                }
            }
        }
        val scroll = ScrollView(context).apply {
            addView(content)
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
        }
        ThemeManager.createDialogBuilder(context)
            .setTitle(descriptor.title)
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun addNumericParameter(
        parent: LinearLayout,
        index: Int,
        parameter: FxParameterDescriptor,
        initial: FxParameterValue,
        onChanged: () -> Unit,
    ) {
        val minimum = parameter.minimum ?: 0f
        val maximum = parameter.maximum ?: 1f
        if (maximum <= minimum) return
        val isInteger = parameter.type == FxParameterType.INT
        val steps = if (isInteger) (maximum - minimum).roundToInt().coerceAtLeast(1) else 100
        val label = TextView(context).apply {
            textSize = 12f
            setTextColor(PRIMARY_TEXT)
        }
        val seek = SeekBar(context).apply {
            max = steps
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            progress = ((((initial.asFloat() - minimum) / (maximum - minimum)) * steps).roundToInt())
                .coerceIn(0, steps)
        }
        fun render(progress: Int, apply: Boolean) {
            val raw = minimum + (maximum - minimum) * progress / steps
            val value = if (isInteger) {
                FxParameterValue.IntValue(raw.roundToInt())
            } else {
                FxParameterValue.FloatValue(raw)
            }
            val formatted = if (isInteger) raw.roundToInt().toString() else String.format(Locale.US, "%.2f", raw)
            val units = parameter.units?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            label.text = "${prettyName(parameter.name)}  $formatted$units\n${parameter.description}"
            if (apply) {
                pipeline.updateStageParameter(index, parameter.name, value)
                onChanged()
            }
        }
        render(seek.progress, false)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = render(progress, fromUser)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        parent.addView(label, matchWrap())
        parent.addView(seek, matchWrap().apply { bottomMargin = dp(8) })
    }

    private fun addBooleanParameter(
        parent: LinearLayout,
        index: Int,
        parameter: FxParameterDescriptor,
        initial: FxParameterValue.BoolValue,
        onChanged: () -> Unit,
    ) {
        val toggle = Switch(context).apply {
            text = prettyName(parameter.name)
            textSize = 13f
            setTextColor(PRIMARY_TEXT)
            isChecked = initial.value
            contentDescription = "${prettyName(parameter.name)}. ${parameter.description}"
            setOnCheckedChangeListener { _, checked ->
                pipeline.updateStageParameter(index, parameter.name, FxParameterValue.BoolValue(checked))
                onChanged()
            }
        }
        parent.addView(toggle, matchWrap())
        parent.addView(TextView(context).apply {
            text = parameter.description
            textSize = 11f
            setTextColor(SECONDARY_TEXT)
        }, matchWrap().apply { bottomMargin = dp(8) })
    }

    private fun addEnumParameter(
        parent: LinearLayout,
        index: Int,
        parameter: FxParameterDescriptor,
        initial: FxParameterValue.EnumValue,
        onChanged: () -> Unit,
    ) {
        parent.addView(TextView(context).apply {
            text = "${prettyName(parameter.name)} — ${parameter.description}"
            textSize = 12f
            setTextColor(PRIMARY_TEXT)
        }, matchWrap().apply { bottomMargin = dp(4) })
        var selected = parameter.enumValues.indexOf(initial.value).coerceAtLeast(0)
        val spinner = styledDropdown(
            choices = parameter.enumValues,
            selectedPosition = selected,
            contentDescription = "${prettyName(parameter.name)}: ${initial.value}",
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == selected) return
                val choice = parameter.enumValues.getOrNull(position) ?: return
                selected = position
                pipeline.updateStageParameter(index, parameter.name, FxParameterValue.EnumValue(choice))
                onChanged()
            }
        }
        parent.addView(spinnerRow(spinner), matchWrap().apply { bottomMargin = dp(10) })
    }

    private fun FxParameterValue.asFloat(): Float = when (this) {
        is FxParameterValue.FloatValue -> value
        is FxParameterValue.IntValue -> value.toFloat()
        else -> error("Expected a numeric parameter value")
    }

    private fun prettyName(name: String): String = name
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replaceFirstChar { it.uppercase(Locale.US) }

    private fun iconButton(
        glyph: String,
        label: String,
        enabled: Boolean = true,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ) = Button(context).apply {
        text = glyph
        textSize = 17f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        setTextColor(if (destructive) DELETE_TEXT else PRIMARY_TEXT)
        background = GradientDrawable().apply {
            setColor(ROW_BACKGROUND)
            cornerRadius = dp(7).toFloat()
            setStroke(dp(1), if (destructive) DELETE_TEXT else ROW_BORDER)
        }
        alpha = if (enabled) 1f else .38f
        isEnabled = enabled
        contentDescription = label
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun iconLayoutParams() = LinearLayout.LayoutParams(dp(38), dp(42)).apply {
        marginStart = dp(2)
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
        setPadding(dp(4), 0, dp(2), 0)
        this.isEnabled = isEnabled
        this.contentDescription = contentDescription
    }

    private fun spinnerRow(spinner: Spinner, prompt: Boolean = false): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
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
        }, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT))
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
                setPadding(dp(10), 0, dp(10), 0)
                minHeight = dp(if (inPopup) 48 else 48)
                setBackgroundColor(if (inPopup) POPUP_BACKGROUND else Color.TRANSPARENT)
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48),
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
