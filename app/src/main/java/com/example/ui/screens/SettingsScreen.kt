package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.SpaceAgentViewModel
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.HfYellow

@Composable
fun SettingsScreen(
    viewModel: SpaceAgentViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hfToken by viewModel.hfToken.collectAsState()
    val tokenError by viewModel.tokenError.collectAsState()
    val isSavingToken by viewModel.isSavingToken.collectAsState()
    val tokenSaveStatus by viewModel.tokenSaveStatus.collectAsState()
    var showToken by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf(hfToken) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Notification permission enabled!", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings & Configuration",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Hugging Face API Token Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = HfYellow
                    )
                    Text(
                        text = "Hugging Face Access Token",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Text(
                    text = "Optional. Hugging Face Spaces can be used publicly without a token, but adding your user access token (hf_...) increases rate limits and allows accessing your private Spaces.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    placeholder = { Text("hf_xxxxxxxxxxxxxxxxxxxxxxx") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showToken) "Hide token" else "Show token")
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hf_token_input")
                )

                tokenError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (tokenInput.trim() == hfToken) {
                    tokenSaveStatus?.let { Text(it, color = EmeraldSuccess) }
                }
                Text("Tokens are encrypted on this device and excluded from backup.", fontSize = 12.sp)
                Button(
                    enabled = !isSavingToken,
                    onClick = {
                        viewModel.setHfToken(tokenInput.trim())

                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("save_token_btn")
                ) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isSavingToken) "Saving…" else "Save Token")
                }
            }
        }

        // Anti-Timeout Architecture Explainer Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = EmeraldSuccess
                    )
                    Text(
                        text = "Background Execution Limits",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Text(
                    text = "A foreground service helps tasks continue when you switch apps. Android, connectivity changes, and the remote Space can still interrupt a run. Failed and cancelled tasks are reported in history.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FeatureRow(
                            icon = "⚙️",
                            title = "Android Foreground Service",
                            desc = "Runs with FOREGROUND_SERVICE_DATA_SYNC to keep background execution prioritized by the OS."
                        )
                        FeatureRow(
                            icon = "🔋",
                            title = "CPU Partial WakeLock",
                            desc = "Prevents CPU sleep states during active Space inferences and token streaming."
                        )
                        FeatureRow(
                            icon = "🌐",
                            title = "Keep-Alive Socket Engine",
                            desc = "Uses bounded network timeouts and reports connection failures."
                        )
                        FeatureRow(
                            icon = "🔔",
                            title = "Task Completion Alerts",
                            desc = "Posts a result notification when permitted by Android."
                        )
                    }
                }
            }
        }

        // Notification Permission Card (if applicable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Background Notifications",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (hasNotificationPermission) "Enabled — alerts on task completion" else "Disabled — tap enable to receive alerts",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!hasNotificationPermission) {
                        FilledTonalButton(
                            onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("enable_notifications_btn")
                        ) {
                            Text("Enable", fontSize = 12.sp)
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Enabled",
                            tint = EmeraldSuccess
                        )
                    }
                }
            }
        }

        // About Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "About SpaceAgent",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "A specialized mobile front-end for Hugging Face Spaces featuring autonomous agent mode, background execution resilience, and instant task completion notifications.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Version 1.0 • Built with Jetpack Compose & M3",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun FeatureRow(icon: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = icon, fontSize = 14.sp)
        Column {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(text = desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
