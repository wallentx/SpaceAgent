package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SpaceInputType
import com.example.ui.AppScreen
import com.example.ui.SpaceAgentViewModel
import com.example.ui.components.CodeBlockView
import com.example.ui.components.DynamicSpaceInputsSection
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSpaceScreen(
    viewModel: SpaceAgentViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val space by viewModel.selectedWebSpace.collectAsState()
    val isKeepAliveActive by viewModel.isKeepAliveActive.collectAsState()
    val inputValues by viewModel.currentInputValues.collectAsState()
    val interactiveOutput by viewModel.interactiveOutput.collectAsState()
    val isInteractiveExecuting by viewModel.isInteractiveExecuting.collectAsState()

    var showApiInspector by remember { mutableStateOf(false) }

    BackHandler {
        viewModel.navigateTo(AppScreen.SPACES_BROWSER)
    }

    if (space == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No space selected")
        }
        return
    }

    val currentSpace = space!!
    val isEndpointReady by viewModel.isEndpointReady.collectAsState()
    val isAgentRunning by viewModel.isTaskRunning.collectAsState()
    val currentApiName = viewModel.getCurrentApiName()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("web_space_screen")
    ) {
        // Space Control Top Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.navigateTo(AppScreen.SPACES_BROWSER) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${currentSpace.emoji} ${currentSpace.title}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentSpace.id,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Launch in Agent Mode Button
                    FilledTonalButton(
                        onClick = { viewModel.navigateTo(AppScreen.AGENT_STUDIO) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("web_to_agent_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Agent", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Secondary Toolbar: Mode Switcher & Background Keep-Alive Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = !showApiInspector,
                            onClick = { showApiInspector = false },
                            label = { Text("1:1 Space UI", fontSize = 11.sp) },
                            modifier = Modifier.testTag("native_ui_mode_chip")
                        )

                        FilterChip(
                            selected = showApiInspector,
                            onClick = { showApiInspector = true },
                            label = { Text("API & Client", fontSize = 11.sp) },
                            modifier = Modifier.testTag("api_inspector_mode_chip")
                        )

                        IconButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Space URL", currentSpace.hfWebUrl)
                                )
                                Toast.makeText(
                                    context,
                                    "URL copied to clipboard",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share URL",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                try {
                                    val intent =
                                        Intent(Intent.ACTION_VIEW, Uri.parse(currentSpace.hfWebUrl))
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Could not open external browser",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = "Open in External Browser",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Anti-Timeout Background Keep-Alive Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isKeepAliveActive) "Background ON" else "Background mode",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isKeepAliveActive) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = isKeepAliveActive,
                            onCheckedChange = { viewModel.toggleWebViewKeepAlive(context) },
                            modifier = Modifier.testTag("webview_keep_alive_switch")
                        )
                    }
                }
            }
        }

        // Keep-Alive Active Notification Banner
        if (isKeepAliveActive) {
            Surface(
                color = Color(0xFF064E3B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Background mode is on. Android or the Space can still stop a request.",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }
        }

        if (!showApiInspector) {
            // 1:1 Native Interactive Space Playground with full File/Video/Image/Audio Upload & Multi-Input Support
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .testTag("native_space_playground"),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NATIVE GRADIO CONSOLE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CyanAccent
                            )
                            Text(
                                text = "SDK: ${currentSpace.sdk.uppercase()} • ${currentSpace.hardwareTier}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = currentSpace.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Host: ${currentSpace.directEmbedUrl}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                // 1:1 Dynamic Space Inputs (Video, Image, Audio, File Uploaders, Sliders, Dropdowns, Toggles, Text)
                DynamicSpaceInputsSection(viewModel = viewModel)

                // Submit to Space / Run in Agent Mode Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.executeInteractiveSpaceCall(context) },
                        enabled = !isInteractiveExecuting && !isAgentRunning && isEndpointReady,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("interactive_submit_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isInteractiveExecuting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Running...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(imageVector = Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Submit (${inputValues.size} Inputs)", fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.startAgentExecution(context)
                            viewModel.navigateTo(AppScreen.AGENT_STUDIO)
                        },
                        enabled = !isInteractiveExecuting && !isAgentRunning && isEndpointReady,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Run in Agent Mode")
                    }
                }

                // Output Result Box
                interactiveOutput?.let { out ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "SPACE EXECUTION OUTPUT (/$currentApiName)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = EmeraldSuccess
                            )
                            Text(
                                text = out,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE2E8F0),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        } else {
            // API & Gradio Client Inspector reflecting exact 1:1 Space parameters
            val pythonArgs = inputValues.joinToString(",\n    ") { iv ->
                when (iv.inputType) {
                    SpaceInputType.VIDEO, SpaceInputType.IMAGE, SpaceInputType.AUDIO, SpaceInputType.FILE -> {
                        "${iv.paramName}=handle_file(\"${iv.fileName ?: "sample_media.mp4"}\")"
                    }
                    SpaceInputType.SLIDER, SpaceInputType.NUMBER -> {
                        "${iv.paramName}=${iv.numberValue}"
                    }
                    SpaceInputType.BOOLEAN -> {
                        "${iv.paramName}=${if (iv.booleanValue) "True" else "False"}"
                    }
                    else -> {
                        "${iv.paramName}=\"${iv.textValue.replace("\"", "\\\"")}\""
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .testTag("space_api_inspector"),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "1:1 Gradio API & Client Integration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Generated directly from ${currentSpace.id}'s 1:1 parameter schema (/$currentApiName).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CodeBlockView(
                    code = """
from gradio_client import Client, handle_file

client = Client("${currentSpace.id}")
result = client.predict(
    $pythonArgs,
    api_name="/$currentApiName"
)
print(result)
                    """.trimIndent()
                )
            }
        }
    }
}
