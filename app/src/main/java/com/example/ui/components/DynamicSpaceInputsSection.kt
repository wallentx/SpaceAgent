package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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

    var activeFilePickerParamIndex by remember { mutableIntStateOf(-1) }
    var showAddFieldDialog by remember { mutableStateOf(false) }
    var customFieldLabel by remember { mutableStateOf("") }
    var customFieldType by remember { mutableStateOf(SpaceInputType.VIDEO) }

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
        // Schema Header Card: Endpoint Selector + Sync Status + Add Custom Input
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isLoadingSchema) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = HfYellow
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "1:1 SPACE INPUT PARAMETERS (${parameterSpecs.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CyanAccent
                            )
                            Text(
                                text = schema?.schemaSourceNote ?: "Inspecting Space API schema...",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.loadSchemaForSpace(selectedSpace) },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("resync_schema_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Re-sync Space Schema",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        FilledTonalButton(
                            onClick = { showAddFieldDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("add_custom_input_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Input", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Endpoint selector chips if the Space exposes multiple endpoints
                val endpoints = schema?.endpoints ?: emptyList()
                if (endpoints.size > 1) {
                    Text(
                        text = "Space Endpoints / Functions:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        endpoints.forEachIndexed { idx, ep ->
                            FilterChip(
                                selected = selectedEndpointIndex == idx,
                                onClick = { viewModel.selectEndpoint(idx) },
                                label = {
                                    Text(
                                        text = "/${ep.apiName} (${ep.parameters.size} inputs)",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            }
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
        }
    }

    if (showAddFieldDialog) {
        AlertDialog(
            onDismissRequest = { showAddFieldDialog = false },
            title = {
                Text("Add Space Input Parameter", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Add a file uploader (Video, Image, Audio, File) or parameter field to send to this Space:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = customFieldLabel,
                        onValueChange = { customFieldLabel = it },
                        label = { Text("Parameter Label (e.g. Input Video)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Input Type:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            SpaceInputType.VIDEO,
                            SpaceInputType.IMAGE,
                            SpaceInputType.AUDIO,
                            SpaceInputType.FILE,
                            SpaceInputType.TEXT,
                            SpaceInputType.SLIDER,
                            SpaceInputType.BOOLEAN
                        ).chunked(2).forEach { rowTypes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowTypes.forEach { t ->
                                    FilterChip(
                                        selected = customFieldType == t,
                                        onClick = { customFieldType = t },
                                        label = {
                                            Text("${t.iconEmoji} ${t.displayName}", fontSize = 11.sp)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addCustomParameter(
                            label = customFieldLabel.ifBlank { customFieldType.displayName },
                            inputType = customFieldType
                        )
                        customFieldLabel = ""
                        showAddFieldDialog = false
                    }
                ) {
                    Text("Add Field")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFieldDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SpaceParameterInputCard(
    spec: SpaceParameterSpec,
    value: SpaceInputValue,
    onTextChange: (String) -> Unit,
    onNumberChange: (Float) -> Unit,
    onBooleanChange: (Boolean) -> Unit,
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
            // Parameter Header Row: Label + Component Badge
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
                    Text(text = spec.inputType.iconEmoji, fontSize = 14.sp)
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

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = spec.componentName.uppercase(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (spec.description.isNotBlank()) {
                Text(
                    text = spec.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (spec.inputType) {
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

                SpaceInputType.SLIDER -> {
                    val formattedVal = if (spec.step >= 1f && value.numberValue % 1f == 0f) {
                        value.numberValue.toInt().toString()
                    } else {
                        String.format("%.2f", value.numberValue)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Range: ${spec.min} – ${spec.max}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = formattedVal,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Slider(
                        value = value.numberValue.coerceIn(spec.min, spec.max.coerceAtLeast(spec.min + 0.01f)),
                        onValueChange = onNumberChange,
                        valueRange = spec.min..spec.max.coerceAtLeast(spec.min + 0.01f)
                    )
                }

                SpaceInputType.NUMBER -> {
                    var numText by remember(value.numberValue) {
                        mutableStateOf(
                            if (value.numberValue % 1f == 0f) value.numberValue.toInt().toString()
                            else value.numberValue.toString()
                        )
                    }
                    OutlinedTextField(
                        value = numText,
                        onValueChange = { str ->
                            numText = str
                            str.toFloatOrNull()?.let { onNumberChange(it) }
                        },
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            spec.choices.forEach { choice ->
                                FilterChip(
                                    selected = value.textValue == choice,
                                    onClick = { onTextChange(choice) },
                                    label = { Text(choice, fontSize = 12.sp) },
                                    shape = RoundedCornerShape(8.dp)
                                )
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
                        if (inputType == SpaceInputType.IMAGE) {
                            AsyncImage(
                                model = value.fileUri,
                                contentDescription = value.fileName,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
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
                                text = "${value.mimeType ?: inputType.displayName} • Ready to upload",
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
