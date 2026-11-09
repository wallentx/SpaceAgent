package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.SpaceInputType
import com.example.model.SpaceInputValue
import com.example.model.SpaceParameterSpec
import com.example.model.SpaceSchema
import com.example.ui.SpaceAgentViewModel
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.HfYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicSpaceInputsSection(
    viewModel: SpaceAgentViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val schema by viewModel.activeSpaceSchema.collectAsState()
    val isLoadingSchema by viewModel.isLoadingSchema.collectAsState()
    val selectedEndpointIndex by viewModel.selectedEndpointIndex.collectAsState()
    val parameterSpecs by viewModel.currentEndpointSpecs.collectAsState()
    val inputValues by viewModel.currentInputValues.collectAsState()
    val selectedSpace by viewModel.selectedSpaceForAgent.collectAsState()
    val inputErrors by viewModel.inputErrors.collectAsState()
    val executionError by viewModel.executionError.collectAsState()
    val isExecuting by viewModel.isInteractiveExecuting.collectAsState()
    val isAgentRunning by viewModel.isTaskRunning.collectAsState()

    var activeFilePickerParamIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(-1) }
    // Zero-permission Photo/Video Picker
    val visualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && activeFilePickerParamIndex >= 0) {
            viewModel.updateFileInputFromUri(context, activeFilePickerParamIndex, uri)
        }
    }

    // Zero-permission Storage Access Framework Document Picker (supports Video, Audio, Image, 3D, PDF, Any File)
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && activeFilePickerParamIndex >= 0) {
            viewModel.updateFileInputFromUri(context, activeFilePickerParamIndex, uri)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val endpoints = schema?.endpoints.orEmpty()
        if (endpoints.size > 1) {
            ScrollableTabRow(selectedTabIndex = selectedEndpointIndex.coerceIn(endpoints.indices), edgePadding = 0.dp) {
                endpoints.forEachIndexed { index, endpoint ->
                    Tab(selected = selectedEndpointIndex == index,
                        enabled = !isExecuting && !isAgentRunning,
                        onClick = { viewModel.selectEndpoint(index) },
                        text = { Text(endpoint.displayName) })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Inputs", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { viewModel.loadSchemaForSpace(selectedSpace) },
                enabled = !isLoadingSchema && !isExecuting && !isAgentRunning,
                modifier = Modifier.testTag("resync_schema_btn")) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh controls")
            }
        }
        if (isLoadingSchema) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (schema?.isLiveFetched == false && !isLoadingSchema) {
            Text(schema?.schemaSourceNote.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        // Render every single Space Parameter 1:1
        parameterSpecs.forEach { spec ->
            val currentVal = inputValues.firstOrNull { it.paramIndex == spec.index }
                ?: SpaceInputValue(
                    paramIndex = spec.index,
                    paramName = spec.name,
                    paramLabel = spec.label,
                    inputType = spec.inputType
                )

            SpaceParameterInputCard(
                spec = spec,
                value = currentVal,
                onTextChange = { viewModel.updateTextInput(spec.index, it) },
                onNumberChange = { viewModel.updateNumberInput(spec.index, it) },
                onBooleanChange = { viewModel.updateBooleanInput(spec.index, it) },
                onNullChange = { viewModel.setInputNull(spec.index, it) },
                onPickVisualMedia = { mediaType ->
                    activeFilePickerParamIndex = spec.index
                    visualMediaLauncher.launch(PickVisualMediaRequest(mediaType))
                },
                onPickDocument = { mimeTypes ->
                    activeFilePickerParamIndex = spec.index
                    documentLauncher.launch(mimeTypes)
                },
                onClearFile = {
                    viewModel.updateFileInputFromUri(context, spec.index, null)
                }
            )
            inputErrors[spec.index]?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        executionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpaceParameterInputCard(
    spec: SpaceParameterSpec,
    value: SpaceInputValue,
    onTextChange: (String) -> Unit,
    onNumberChange: (String) -> Unit,
    onBooleanChange: (Boolean) -> Unit,
    onNullChange: (Boolean) -> Unit,
    onPickVisualMedia: (ActivityResultContracts.PickVisualMedia.VisualMediaType) -> Unit,
    onPickDocument: (Array<String>) -> Unit,
    onClearFile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("space_param_card_${spec.index}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Use the author's input label; component internals belong in API details.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = spec.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (spec.isRequired) {
                        Text(
                            text = "*",
                            fontWeight = FontWeight.Bold,
                            color = HfYellow,
                            fontSize = 14.sp
                        )
                    }
                }


            }

            if (spec.description.isNotBlank()) {
                Text(
                    text = spec.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!spec.isRequired && spec.defaultIsNull) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use a value", fontSize = 13.sp)
                    Switch(checked = !value.isNull, onCheckedChange = { onNullChange(!it) })
                }
            }

            if (!value.isNull) when (spec.inputType) {
                SpaceInputType.VIDEO -> {
                    MediaUploadBox(
                        inputType = SpaceInputType.VIDEO,
                        value = value,
                        primaryButtonLabel = "Select Video",
                        secondaryButtonLabel = "Browse Files",
                        onPrimaryPick = {
                            onPickVisualMedia(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        },
                        onSecondaryPick = {
                            onPickDocument(arrayOf("video/*", "application/octet-stream"))
                        },
                        onClear = onClearFile
                    )
                }

                SpaceInputType.IMAGE -> {
                    MediaUploadBox(
                        inputType = SpaceInputType.IMAGE,
                        value = value,
                        primaryButtonLabel = "Select Image",
                        secondaryButtonLabel = "Browse Files",
                        onPrimaryPick = {
                            onPickVisualMedia(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        },
                        onSecondaryPick = {
                            onPickDocument(arrayOf("image/*"))
                        },
                        onClear = onClearFile
                    )
                }

                SpaceInputType.AUDIO -> {
                    MediaUploadBox(
                        inputType = SpaceInputType.AUDIO,
                        value = value,
                        primaryButtonLabel = "Select Audio File",
                        secondaryButtonLabel = null,
                        onPrimaryPick = {
                            onPickDocument(arrayOf("audio/*", "video/mp4", "*/*"))
                        },
                        onSecondaryPick = null,
                        onClear = onClearFile
                    )
                }

                SpaceInputType.FILE -> {
                    MediaUploadBox(
                        inputType = SpaceInputType.FILE,
                        value = value,
                        primaryButtonLabel = "Upload File / Media",
                        secondaryButtonLabel = "Gallery Media",
                        onPrimaryPick = {
                            onPickDocument(arrayOf("*/*"))
                        },
                        onSecondaryPick = {
                            onPickVisualMedia(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        },
                        onClear = onClearFile
                    )
                }

                SpaceInputType.SLIDER, SpaceInputType.NUMBER -> {
                    val min = spec.min
                    val max = spec.max
                    val step = spec.step
                    if (spec.inputType == SpaceInputType.SLIDER && min != null && max != null && max > min) {
                        Text("${min} – ${max}", fontSize = 12.sp)
                        Slider(
                            value = value.numberValue?.takeIf { it.isFinite() }
                                ?.coerceIn(min.toDouble(), max.toDouble())?.toFloat() ?: min,
                            onValueChange = { raw ->
                                val rounded = if (step != null && step > 0f)
                                    min + kotlin.math.round((raw - min) / step) * step else raw
                                onNumberChange(rounded.coerceIn(min, max).toString())
                            },
                            valueRange = min..max
                        )
                    }
                        OutlinedTextField(
                            value = value.textValue,
                            onValueChange = onNumberChange,
                            isError = value.textValue.isNotBlank() && value.numberValue?.isFinite() != true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                }

                SpaceInputType.BOOLEAN -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (value.booleanValue) "Enabled (True)" else "Disabled (False)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (value.booleanValue) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = value.booleanValue,
                            onCheckedChange = onBooleanChange
                        )
                    }
                }

                SpaceInputType.DROPDOWN -> {
                    if (spec.choices.isNotEmpty()) {
                        if (spec.componentName.equals("Radio", ignoreCase = true)) {
                            FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                spec.choices.forEach { choice ->
                                    Row(modifier = Modifier.heightIn(min = 48.dp).selectable(selected = value.textValue == choice,
                                        role = Role.RadioButton, onClick = { onTextChange(choice) }).padding(end = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = value.textValue == choice, onClick = null)
                                        Text(choice)
                                    }
                                }
                            }
                        } else {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(value.textValue.ifBlank { "Choose an option" }, Modifier.weight(1f))
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    spec.choices.forEach { choice ->
                                        DropdownMenuItem(text = { Text(choice) }, onClick = { onTextChange(choice); expanded = false })
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = value.textValue,
                            onValueChange = onTextChange,
                            placeholder = { Text("Enter option value...") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                SpaceInputType.TEXT -> {
                    OutlinedTextField(
                        value = value.textValue,
                        onValueChange = onTextChange,
                        placeholder = {
                            Text("Enter ${spec.label}...")
                        },
                        minLines = 2,
                        maxLines = 5,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("param_text_input_${spec.index}")
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaUploadBox(
    inputType: SpaceInputType,
    value: SpaceInputValue,
    primaryButtonLabel: String,
    secondaryButtonLabel: String?,
    onPrimaryPick: () -> Unit,
    onSecondaryPick: (() -> Unit)?,
    onClear: () -> Unit
) {
    val hasFile = !value.fileUri.isNullOrBlank()

    Surface(
        color = if (hasFile) Color(0xFF064E3B).copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (hasFile && inputType == SpaceInputType.IMAGE) {
                AsyncImage(model = value.fileUri, contentDescription = value.fileName,
                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp))
                        .testTag("input_image_preview"), contentScale = ContentScale.Fit)
            }
            if (hasFile) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (inputType != SpaceInputType.IMAGE) {
                            Surface(
                                color = EmeraldSuccess.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = when (inputType) {
                                            SpaceInputType.VIDEO -> Icons.Default.Videocam
                                            SpaceInputType.AUDIO -> Icons.Default.GraphicEq
                                            else -> Icons.Default.AttachFile
                                        },
                                        contentDescription = null,
                                        tint = EmeraldSuccess
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = value.fileName ?: "Selected media",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (value.fileSizeBytes > 0) android.text.format.Formatter.formatShortFileSize(LocalContext.current, value.fileSizeBytes) else "Selected",
                                fontSize = 11.sp,
                                color = EmeraldSuccess
                            )
                        }
                    }

                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove File",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (inputType) {
                            SpaceInputType.VIDEO -> Icons.Default.VideoFile
                            SpaceInputType.IMAGE -> Icons.Default.Image
                            SpaceInputType.AUDIO -> Icons.Default.AudioFile
                            else -> Icons.Default.UploadFile
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "No ${inputType.displayName.lowercase()} selected yet",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPrimaryPick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasFile) "Replace" else primaryButtonLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (secondaryButtonLabel != null && onSecondaryPick != null) {
                    OutlinedButton(
                        onClick = onSecondaryPick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = secondaryButtonLabel, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
