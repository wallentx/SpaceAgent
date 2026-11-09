package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.*
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.HfYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceAgentApp(viewModel: SpaceAgentViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val isRunning by viewModel.isTaskRunning.collectAsState()
    val isKeepAliveActive by viewModel.isKeepAliveActive.collectAsState()

    // Back handling
    BackHandler(enabled = currentScreen != AppScreen.SPACES_BROWSER) {
        if (currentScreen == AppScreen.WEB_VIEW) {
            // Handled inside WebSpaceScreen for web history
        } else {
            viewModel.navigateTo(AppScreen.SPACES_BROWSER)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("space_agent_main_app"),
        topBar = {
            if (currentScreen != AppScreen.WEB_VIEW) TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "🤗 SpaceAgent",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Running state indicator badge
                        if (isRunning) {
                            Surface(
                                color = HfYellow.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        color = HfYellow,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Agent Running",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = HfYellow
                                    )
                                }
                            }
                        } else if (isKeepAliveActive) {
                            Surface(
                                color = EmeraldSuccess.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "Background ON",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldSuccess,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (currentScreen != AppScreen.SETTINGS) {
                        IconButton(
                            onClick = { viewModel.navigateTo(AppScreen.SETTINGS) },
                            modifier = Modifier.testTag("nav_settings_icon")
                        ) {
                            Icon(imageVector = Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (currentScreen != AppScreen.WEB_VIEW) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    NavigationBarItem(
                        selected = currentScreen == AppScreen.SPACES_BROWSER,
                        onClick = { viewModel.navigateTo(AppScreen.SPACES_BROWSER) },
                        icon = {
                            Icon(
                                imageVector = if (currentScreen == AppScreen.SPACES_BROWSER) Icons.Filled.Explore else Icons.Outlined.Explore,
                                contentDescription = "Spaces"
                            )
                        },
                        label = { Text("Spaces") },
                        modifier = Modifier.testTag("nav_spaces_tab")
                    )

                    NavigationBarItem(
                        selected = currentScreen == AppScreen.AGENT_STUDIO,
                        onClick = { viewModel.navigateTo(AppScreen.AGENT_STUDIO) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (isRunning) {
                                        Badge(containerColor = HfYellow)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (currentScreen == AppScreen.AGENT_STUDIO) Icons.Filled.SmartToy else Icons.Outlined.SmartToy,
                                    contentDescription = "Agent Studio"
                                )
                            }
                        },
                        label = { Text("Agent Mode") },
                        modifier = Modifier.testTag("nav_agent_tab")
                    )

                    NavigationBarItem(
                        selected = currentScreen == AppScreen.HISTORY,
                        onClick = { viewModel.navigateTo(AppScreen.HISTORY) },
                        icon = {
                            Icon(
                                imageVector = if (currentScreen == AppScreen.HISTORY) Icons.Filled.History else Icons.Outlined.History,
                                contentDescription = "History"
                            )
                        },
                        label = { Text("History") },
                        modifier = Modifier.testTag("nav_history_tab")
                    )

                    NavigationBarItem(
                        selected = currentScreen == AppScreen.SETTINGS,
                        onClick = { viewModel.navigateTo(AppScreen.SETTINGS) },
                        icon = {
                            Icon(
                                imageVector = if (currentScreen == AppScreen.SETTINGS) Icons.Filled.Tune else Icons.Outlined.Tune,
                                contentDescription = "Settings"
                            )
                        },
                        label = { Text("Settings") },
                        modifier = Modifier.testTag("nav_settings_tab")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                AppScreen.SPACES_BROWSER -> SpacesListScreen(viewModel = viewModel)
                AppScreen.AGENT_STUDIO -> AgentStudioScreen(viewModel = viewModel)
                AppScreen.WEB_VIEW -> WebSpaceScreen(viewModel = viewModel)
                AppScreen.HISTORY -> HistoryScreen(viewModel = viewModel)
                AppScreen.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
