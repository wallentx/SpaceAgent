package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AgentStep
import com.example.model.HfSpace
import com.example.model.StepStatus
import com.example.ui.theme.*

@Composable
fun SpaceCard(
    space: HfSpace,
    onOpenAgent: (HfSpace) -> Unit,
    onOpenWeb: (HfSpace) -> Unit,
    onToggleFavorite: (HfSpace) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("space_card_${space.id.replace('/', '_')}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top Row: Runtime Stage, ZeroGPU/Hardware, SDK, and Likes/Bookmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    RuntimeStatusBadge(
                        stage = space.runtimeStage,
                        isZeroGpu = space.isZeroGpu
                    )

                    CategoryChip(category = space.category)

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = space.sdk.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (space.trendingScore > 0) {
                        Text(
                            text = "🔥${space.trendingScore}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = HfYellow,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Likes",
                        tint = HfYellowVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = formatCompactLikes(space.likes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { onToggleFavorite(space) },
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("favorite_btn_${space.id.replace('/', '_')}")
                    ) {
                        Icon(
                            imageVector = if (space.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Bookmark Space",
                            tint = if (space.isFavorite) HfYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Space Emoji + Title & Author Repo ID
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = space.emoji, fontSize = 18.sp)
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = space.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = space.id,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = space.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons: Agent Mode (Primary) & Interactive Console (Secondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onOpenAgent(space) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_agent_btn_${space.id.replace('/', '_')}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Agent Mode", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = { onOpenWeb(space) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_web_btn_${space.id.replace('/', '_')}"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Interactive", fontSize = 13.sp)
                }
            }
        }
    }
}

private fun formatCompactLikes(likes: Int): String {
    return when {
        likes >= 1000 -> String.format("%.1fk", likes / 1000.0)
        else -> likes.toString()
    }
}

@Composable
fun RuntimeStatusBadge(stage: String, isZeroGpu: Boolean) {
    val isRunning = stage.contains("RUNNING", ignoreCase = true)
    val bgColor = when {
        isZeroGpu -> HfYellow.copy(alpha = 0.2f)
        isRunning -> EmeraldSuccess.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = when {
        isZeroGpu -> HfYellow
        isRunning -> EmeraldSuccess
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        isZeroGpu -> "⚡ ZeroGPU"
        isRunning -> "🟢 Running"
        stage.contains("BUILD", ignoreCase = true) -> "🔨 Building"
        stage.contains("SLEEP", ignoreCase = true) -> "💤 Sleeping"
        else -> stage.take(10)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun CategoryChip(category: String) {
    val catLower = category.lowercase()
    val (bgColor, textColor, emoji) = when {
        catLower.contains("reason") || catLower.contains("agent") -> Triple(Color(0xFF818CF8).copy(alpha = 0.2f), Color(0xFF818CF8), "🧠")
        catLower.contains("code") -> Triple(Color(0xFF38BDF8).copy(alpha = 0.2f), Color(0xFF38BDF8), "💻")
        catLower.contains("video") -> Triple(Color(0xFFF59E0B).copy(alpha = 0.2f), Color(0xFFF59E0B), "🎬")
        catLower.contains("edit") -> Triple(Color(0xFFA855F7).copy(alpha = 0.2f), Color(0xFFA855F7), "🪄")
        catLower.contains("image") -> Triple(Color(0xFFF472B6).copy(alpha = 0.2f), Color(0xFFF472B6), "🎨")
        catLower.contains("3d") -> Triple(Color(0xFF2DD4BF).copy(alpha = 0.2f), Color(0xFF2DD4BF), "🧊")
        catLower.contains("audio") || catLower.contains("speech") -> Triple(Color(0xFF34D399).copy(alpha = 0.2f), Color(0xFF34D399), "🎙️")
        else -> Triple(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, "📝")
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(text = emoji, fontSize = 10.sp)
            Text(
                text = category,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

@Composable
fun AgentStepRow(step: AgentStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Status Icon Circle
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when (step.status) {
                        StepStatus.SUCCESS -> EmeraldSuccess.copy(alpha = 0.2f)
                        StepStatus.RUNNING -> HfYellow.copy(alpha = 0.25f)
                        StepStatus.FAILED -> CoralError.copy(alpha = 0.2f)
                        StepStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (step.status) {
                StepStatus.SUCCESS -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Success",
                    tint = EmeraldSuccess,
                    modifier = Modifier.size(18.dp)
                )
                StepStatus.RUNNING -> CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    color = HfYellow,
                    modifier = Modifier.size(18.dp)
                )
                StepStatus.FAILED -> Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Failed",
                    tint = CoralError,
                    modifier = Modifier.size(18.dp)
                )
                StepStatus.PENDING -> Text(
                    text = "${step.stepNumber}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = step.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = if (step.status == StepStatus.RUNNING) HfYellow else MaterialTheme.colorScheme.onSurface
                )
                if (step.status == StepStatus.RUNNING) {
                    Text(
                        text = "Active",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HfYellow
                    )
                }
            }

            Text(
                text = if (step.logOutput.isNotBlank()) step.logOutput else step.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CodeBlockView(code: String) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("code_block_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GENERATED CODE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = CyanAccent
                )
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Agent Code", code))
                        Toast.makeText(context, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("copy_code_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Code",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun AntiTimeoutBanner(
    activeSpaceTitle: String,
    onOpenActiveTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenActiveTask() }
            .testTag("anti_timeout_banner"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF064E3B).copy(alpha = 0.9f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = EmeraldSuccess,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "Anti-Timeout Engine Active",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Running on $activeSpaceTitle (Protected)",
                        fontSize = 11.sp,
                        color = Color(0xFFA7F3D0)
                    )
                }
            }

            TextButton(
                onClick = onOpenActiveTask,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    text = "View",
                    fontWeight = FontWeight.Bold,
                    color = EmeraldSuccess,
                    fontSize = 12.sp
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = EmeraldSuccess,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
