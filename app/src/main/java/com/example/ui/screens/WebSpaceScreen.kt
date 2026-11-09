package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.ui.*
import com.example.ui.components.*
import org.json.JSONObject

private enum class SpaceViewMode { ORIGINAL, NATIVE, DETAILS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSpaceScreen(viewModel: SpaceAgentViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val space by viewModel.selectedWebSpace.collectAsState()
    val schema by viewModel.activeSpaceSchema.collectAsState()
    val endpointIndex by viewModel.selectedEndpointIndex.collectAsState()
    val inputValues by viewModel.currentInputValues.collectAsState()
    val state by viewModel.interactiveState.collectAsState()
    val executing by viewModel.isInteractiveExecuting.collectAsState()
    val agentRunning by viewModel.isTaskRunning.collectAsState()
    val ready by viewModel.isEndpointReady.collectAsState()
    val keepAlive by viewModel.isKeepAliveActive.collectAsState()
    val token by viewModel.hfToken.collectAsState()
    val saving by viewModel.isSavingResult.collectAsState()
    val downloadMessage by viewModel.downloadMessage.collectAsState()
    val current = space ?: return
    val endpoint = schema?.takeIf { it.spaceId == current.id }?.endpoints?.getOrNull(endpointIndex)
    val selectedState = state?.takeIf { it.spaceId == current.id && it.apiName == endpoint?.apiName }
    var mode by rememberSaveable(current.id) { mutableStateOf(SpaceViewMode.ORIGINAL) }
    var openedOriginal by rememberSaveable(current.id) { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var pendingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingName by rememberSaveable { mutableStateOf("") }
    var pendingMime by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOrigin by rememberSaveable { mutableStateOf("") }
    val saveLauncher = rememberLauncherForActivityResult(SaveSpaceResult()) { destination ->
        val url = pendingUrl
        if (destination != null && url != null) {
            viewModel.saveResultFile(SpaceResultFile(url, pendingName, ResultMediaKind.FILE, pendingMime), pendingOrigin, destination)
        }
        pendingUrl = null
    }
    val saveFile: (SpaceResultFile) -> Unit = { file ->
        if (saving || pendingUrl != null) {
            Toast.makeText(context, "Finish the current save first.", Toast.LENGTH_SHORT).show()
        } else {
        pendingUrl = file.url
        pendingName = file.name
        pendingMime = file.mimeType
        pendingOrigin = current.directEmbedUrl
        saveLauncher.launch(file)
        }
    }
    fun openUrl(url: String) {
        if (!SpaceWebPolicy.isWebLink(url)) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: RuntimeException) { Toast.makeText(context, "No browser is available.", Toast.LENGTH_SHORT).show() }
    }
    BackHandler { viewModel.navigateTo(AppScreen.SPACES_BROWSER) }

    Column(modifier.fillMaxSize().testTag("web_space_screen")) {
        Surface(tonalElevation = 2.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.navigateTo(AppScreen.SPACES_BROWSER) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to Spaces")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(current.id, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { viewModel.toggleFavorite(current) }) {
                        Icon(if (current.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (current.isFavorite) "Remove saved Space" else "Save Space")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Space actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Share Space") }, onClick = {
                                menu = false
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, current.hfWebUrl)
                                }, "Share Space"))
                            })
                            DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                                menu = false
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText(current.title, current.hfWebUrl))
                            })
                            DropdownMenuItem(text = { Text("Open on Hugging Face") }, onClick = { menu = false; openUrl(current.hfWebUrl) })
                            DropdownMenuItem(text = { Text("Files and source") }, onClick = { menu = false; openUrl("${current.hfWebUrl}/tree/main") })
                            DropdownMenuItem(text = { Text("Community") }, onClick = { menu = false; openUrl("${current.hfWebUrl}/discussions") })
                            DropdownMenuItem(text = { Text(if (keepAlive) "Turn background mode off" else "Turn background mode on") },
                                onClick = { menu = false; viewModel.toggleWebViewKeepAlive(context) })
                        }
                    }
                }
                if (mode != SpaceViewMode.ORIGINAL) Row(Modifier.fillMaxWidth().padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RuntimeStatusBadge(current.runtimeStage, current.isZeroGpu)
                    Text("${current.likes} ${if (current.likes == 1) "like" else "likes"}", style = MaterialTheme.typography.bodySmall)
                    if (keepAlive) Text("Background on", style = MaterialTheme.typography.bodySmall)
                }
                TabRow(selectedTabIndex = mode.ordinal) {
                    listOf("Space", "Native", "Details").forEachIndexed { index, label ->
                        Tab(selected = mode.ordinal == index,
                            onClick = { mode = SpaceViewMode.entries[index]; if (mode == SpaceViewMode.ORIGINAL) openedOriginal = true },
                            text = { Text(label) })
                    }
                }
            }
        }
        if (saving || downloadMessage != null) Text(downloadMessage.orEmpty(), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (openedOriginal) key(current.id) {
                OriginalSpaceView(current, visible = mode == SpaceViewMode.ORIGINAL,
                    onDownload = { url, name, mime -> saveFile(SpaceResultFile(url, name, ResultMediaKind.FILE, mime)) },
                    onExternalLink = ::openUrl,
                    modifier = if (mode == SpaceViewMode.ORIGINAL) Modifier.fillMaxSize() else Modifier.size(0.dp))
            }
            when (mode) {
                SpaceViewMode.ORIGINAL -> Unit
                SpaceViewMode.NATIVE -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("native_space_playground"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DynamicSpaceInputsSection(viewModel)
                    if (executing) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(selectedState?.message ?: "A request is running in another Space.", Modifier.weight(1f))
                            TextButton(onClick = viewModel::cancelInteractiveExecution) { Text("Cancel") }
                        }
                    } else {
                        Button(onClick = { viewModel.executeInteractiveSpaceCall(context) },
                            enabled = ready && !agentRunning, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("interactive_submit_btn")) {
                            Text(endpoint?.submitLabel ?: "Run")
                        }
                        TextButton(onClick = {
                            viewModel.startAgentExecution(context); viewModel.navigateTo(AppScreen.AGENT_STUDIO)
                        }, enabled = ready && !agentRunning, modifier = Modifier.testTag("web_to_agent_btn")) { Text("Run as a background task") }
                    }
                    if (selectedState?.status == SessionStatus.FAILED) {
                        Text(selectedState!!.message, color = MaterialTheme.colorScheme.error)
                    } else if (selectedState?.status == SessionStatus.CANCELLED) Text("Request cancelled.")
                    else if (selectedState?.status == SessionStatus.COMPLETED && selectedState?.result?.outputs.isNullOrEmpty()) {
                        Text("Completed. This endpoint returned no output.")
                    }
                    val outputs = selectedState?.result?.outputs ?: endpoint?.outputs.orEmpty().map {
                        SpaceResultOutput(it.label, SpaceResultContent.Empty)
                    }
                    SpaceResultsSection(outputs, saveFile, { openUrl(it.url) }, saving, current.directEmbedUrl, token)
                    Spacer(Modifier.height(24.dp))
                }
                SpaceViewMode.DETAILS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("About this Space", style = MaterialTheme.typography.titleLarge)
                    if (current.description.isNotBlank()) Text(current.description)
                    Text("Author: ${current.author}")
                    Text("SDK: ${current.sdk}")
                    Text("Hardware: ${if (current.isZeroGpu) "ZeroGPU" else current.hardwareTier.ifBlank { "Not reported" }}")
                    Text("The Space tab runs the author's original interface. Native provides direct endpoint controls and results.")
                    Text("Background mode keeps a foreground service active. Android and the Space can still limit requests.")
                    endpoint?.let {
                        Text("API: /${it.apiName}", style = MaterialTheme.typography.titleMedium)
                        CodeBlockView(pythonExample(current, it, inputValues), title = "Python example")
                    }
                    selectedState?.result?.let { result ->
                        Text("Response details", style = MaterialTheme.typography.titleMedium)
                        CodeBlockView(runCatching { JSONObject(result.rawResponse).toString(2) }.getOrDefault(result.rawResponse), title = "Response data")
                    }
                    selectedState?.details?.takeIf { it.isNotEmpty() }?.let {
                        CodeBlockView(it.joinToString("\n"), title = "Request details")
                    }
                }
            }
        }
    }
}

private fun pythonExample(space: HfSpace, endpoint: SpaceEndpointSpec, inputs: List<SpaceInputValue>): String = buildString {
    appendLine("from gradio_client import Client, handle_file")
    appendLine()
    appendLine("client = Client(${JSONObject.quote(space.id)})")
    appendLine("result = client.predict(")
    inputs.forEach { input ->
        val value = when {
            input.isNull -> "None"
            input.inputType.isMediaOrFile -> "handle_file(${JSONObject.quote(input.fileName ?: "choose-a-file")})"
            input.inputType == SpaceInputType.NUMBER || input.inputType == SpaceInputType.SLIDER ->
                input.textValue.toLongOrNull()?.toString() ?: input.numberValue?.takeIf { it.isFinite() }?.toString() ?: "None"
            input.inputType == SpaceInputType.BOOLEAN -> if (input.booleanValue) "True" else "False"
            else -> JSONObject.quote(input.textValue)
        }
        appendLine("    $value,")
    }
    appendLine("    api_name=${JSONObject.quote("/${endpoint.apiName}")}")
    appendLine(")")
    append("print(result)")
}
