package com.example.ui

import com.example.model.SpaceInputType
import com.example.model.SpaceInputValue
import com.example.model.SpaceParameterSpec

/** Pure form operations shared by loading, validation, and regression tests. */
internal object SpaceFormState {
    fun values(specs: List<SpaceParameterSpec>, previous: List<SpaceInputValue> = emptyList()): List<SpaceInputValue> =
        specs.map { spec ->
            previous.firstOrNull { it.paramName == spec.name && it.inputType == spec.inputType }
                ?.copy(paramIndex = spec.index, paramLabel = spec.label)
                ?: SpaceInputValue(
                    paramIndex = spec.index,
                    paramName = spec.name,
                    paramLabel = spec.label,
                    inputType = spec.inputType,
                    textValue = when (spec.inputType) {
                        SpaceInputType.TEXT, SpaceInputType.DROPDOWN,
                        SpaceInputType.NUMBER, SpaceInputType.SLIDER -> spec.defaultValue
                        else -> ""
                    },
                    numberValue = spec.defaultValue.toDoubleOrNull(),
                    booleanValue = spec.defaultValue.equals("true", ignoreCase = true),
                    isNull = !spec.isRequired && spec.defaultIsNull
                )
        }

    fun errors(specs: List<SpaceParameterSpec>, values: List<SpaceInputValue>): Map<Int, String> =
        specs.mapNotNull { spec ->
            val value = values.firstOrNull { it.paramIndex == spec.index }
            val error = when {
                value == null -> "${spec.label} is missing."
                value.isNull -> if (!spec.isRequired && spec.defaultIsNull) null else "Enter ${spec.label}."
                spec.isRequired && spec.inputType.isMediaOrFile && value.fileUri.isNullOrBlank() -> "Select ${spec.label}."
                spec.isRequired && spec.inputType in listOf(SpaceInputType.TEXT, SpaceInputType.DROPDOWN) && value.textValue.isBlank() -> "Enter ${spec.label}."
                spec.inputType in listOf(SpaceInputType.NUMBER, SpaceInputType.SLIDER) -> when {
                    value.numberValue == null || !value.numberValue.isFinite() -> "Enter a valid number for ${spec.label}."
                    spec.min != null && value.numberValue < spec.min -> "${spec.label} must be at least ${spec.min}."
                    spec.max != null && value.numberValue > spec.max -> "${spec.label} must be at most ${spec.max}."
                    else -> null
                }
                spec.inputType == SpaceInputType.DROPDOWN && spec.choices.isNotEmpty() && value.textValue !in spec.choices -> "Choose a valid ${spec.label}."
                else -> null
            }
            error?.let { spec.index to it }
        }.toMap()
}
