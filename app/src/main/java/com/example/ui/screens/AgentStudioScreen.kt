package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.model.SessionStatus
import com.example.ui.SpaceAgentViewModel
import com.example.ui.components.AgentStepRow
import com.example.ui.components.CodeBlockView
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentStudioScreen(
    viewModel: SpaceAgentViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedSpace by viewModel.selectedSpaceForAgent.collectAsState()
    val selectedAgentType by viewModel.selectedAgentType.collectAsState()
    val goalPrompt by viewModel.agentGoalPrompt.collectAsState()
    val isRunning by viewModel.isTaskRunning.collectAsState()
    val currentSteps by viewModel.currentSteps.collectAsState()
    val liveLogs by viewModel.liveLogs.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val localSpaces by viewModel.localSpaces.collectAsState()

    var showSpaceSelectorDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Example prompt presets per agent type
    val presets = remember(selectedAgentType) {
        when (selectedAgentType) {
            AgentType.REASONING -> listOf(
                "If 5 machines make 5 widgets in 5 minutes, how long do 100 machines need for 100 widgets?",
                "Analyze the paradox of Schrödinger's cat in terms of many-worlds vs Copenhagen interpretation",
                "Solve this logic grid: Alice, Bob, and Charlie each wear a red or blue hat..."
            )
            AgentType.CODER -> listOf(
                "Write an optimized Python fast-Fourier-transform with benchmark comparison",
                "Create a Kotlin coroutine flow channel with debounce and backpressure management",
                "Build a clean REST API client in Python with retry backoff and rate limit handling"
            )
            AgentType.IMAGE_STUDIO -> listOf(
                "A futuristic cybernetic explorer looking at bioluminescent flora in a neon cave, 8k cinematic",
                "Vintage oil painting of an astronomer observing a supernova through a steampunk telescope",
                "Minimalist 3D isometric laboratory with holographic displays and warm golden lighting"
            )
            AgentType.GENERAL -> listOf(
                "Summarize key breakthroughs in LLM reasoning models over the past 6 months",
                "Formulate a complete marketing launch strategy for an open-source AI developer tool",
                "Design a robust system architecture for multi-agent tool execution"
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("agent_studio_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Agent Mode Selector Cards
        Text(
            text = "Select Agent Persona",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AgentType.values().forEach { type ->
                val isSelected = selectedAgentType == type
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.setAgentType(type) },
                    label = {
                        Text("${type.iconEmoji} ${type.displayName}")
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("agent_type_chip_${type.name}")
                )
            }
        }

        // Active Space Header & Picker
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Utilizing Space:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = selectedSpace.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = selectedSpace.id,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = { showSpaceSelectorDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("change_space_button")
                ) {
                    Text(text = "Change", fontSize = 12.sp)
                }
            }
        }

        // Anti-Timeout Guarantee Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0F172A),
            border = ButtonDefaults.outlinedButtonBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = EmeraldSuccess,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "Zero-Timeout Background Engine",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Runs via Foreground Service with CPU lock. Feel free to switch apps or turn off your screen while this space processes.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // Prompt Input
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Agent Goal / Prompt",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (goalPrompt.isNotBlank()) {
                    TextButton(
                        onClick = { viewModel.setAgentPrompt("") },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Clear", fontSize = 12.sp)
                    }
                }
            }

            OutlinedTextField(
                value = goalPrompt,
                onValueChange = { viewModel.setAgentPrompt(it) },
                placeholder = { Text("Describe the task, code, or prompt you want the Agent to execute on this Space...") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("agent_goal_input")
            )

            // Preset Prompts Chips
            Text(
                text = "Preset prompts:",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { preset ->
                    SuggestionChip(
                        onClick = { viewModel.setAgentPrompt(preset) },
                        label = {
                            Text(
                                text = preset.take(30) + "...",
                                fontSize = 11.sp
                            )
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }

        // Execution Actions (Run / Cancel)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { viewModel.startAgentExecution(context) },
                enabled = !isRunning && goalPrompt.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("run_agent_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Executing Space...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Run Agent Task", fontWeight = FontWeight.Bold)
                }
            }

            if (isRunning) {
                OutlinedButton(
                    onClick = { viewModel.cancelAgentExecution(context) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralError),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("cancel_agent_button")
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Stop")
                }
            }
        }

        // Live Execution Stepper (Shows during or after execution)
        if (currentSteps.isNotEmpty()) {
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
                    Text(
                        text = "AGENT EXECUTION PIPELINE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    currentSteps.forEach { step ->
                        AgentStepRow(step = step)
                    }
                }
            }
        }

        // Live Logs Box
        if (liveLogs.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LIVE AGENT TERMINAL",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = CyanAccent
                        )
                        if (isRunning) {
                            Text(
                                text = "STREAMING",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = EmeraldSuccess
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        liveLogs.takeLast(15).forEach { log ->
                            Text(
                                text = log,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFFCBD5E1),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }

        // Final Artifact Output View
        val session = currentSession
        if (session != null && !session.finalOutput.isNullOrBlank()) {
            Text(
                text = "Generated Space Result",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Image Preview if available
            if (!session.imageUrl.isNullOrBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    AsyncImage(
                        model = session.imageUrl,
                        contentDescription = "Generated Space Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // Output Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ARTIFACT READY",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = EmeraldSuccess
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Agent Output", session.finalOutput))
                                Toast.makeText(context, "Copied output to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Output",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (session.finalOutput.contains("```")) {
                        // Extract code block
                        val codePart = session.finalOutput.substringAfter("```python\n")
                            .substringBefore("```")
                            .ifBlank {
                                session.finalOutput.substringAfter("```").substringBefore("```")
                            }
                        CodeBlockView(code = codePart)
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Text(
                        text = session.finalOutput,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }

    // Space Selection Dialog
    if (showSpaceSelectorDialog) {
        AlertDialog(
            onDismissRequest = { showSpaceSelectorDialog = false },
            title = {
                Text(text = "Choose Space for Agent", fontWeight = FontWeight.Bold)
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(localSpaces.size) { index ->
                        val spaceEntity = localSpaces[index]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAgentSpace(
                                        HfSpace(
                                            id = spaceEntity.id,
                                            author = spaceEntity.author,
                                            name = spaceEntity.name,
                                            title = spaceEntity.title,
                                            description = spaceEntity.description,
                                            sdk = spaceEntity.sdk,
                                            category = spaceEntity.category,
                                            likes = spaceEntity.likes,
                                            endpointUrl = spaceEntity.endpointUrl,
                                            isFavorite = spaceEntity.isFavorite,
                                            isCurated = spaceEntity.isCurated,
                                            demoPrompt = spaceEntity.demoPrompt
                                        )
                                    )
                                    showSpaceSelectorDialog = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (spaceEntity.id == selectedSpace.id)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = spaceEntity.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = spaceEntity.id,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpaceSelectorDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
