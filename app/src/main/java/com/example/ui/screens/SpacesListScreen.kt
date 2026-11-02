package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SpaceCategoryFilter
import com.example.model.SpaceRuntimeFilter
import com.example.model.SpaceSdkFilter
import com.example.model.SpaceSortOption
import com.example.ui.AppScreen
import com.example.ui.SpaceAgentViewModel
import com.example.ui.components.AntiTimeoutBanner
import com.example.ui.components.SpaceCard
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.HfYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesListScreen(
    viewModel: SpaceAgentViewModel,
    modifier: Modifier = Modifier
) {
    val liveSpaces by viewModel.liveSpaces.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedCategoryFilter by viewModel.selectedCategoryFilter.collectAsState()
    val selectedSdkFilter by viewModel.selectedSdkFilter.collectAsState()
    val selectedRuntimeFilter by viewModel.selectedRuntimeFilter.collectAsState()
    val selectedSortOption by viewModel.selectedSortOption.collectAsState()
    val isLoadingLiveSpaces by viewModel.isLoadingLiveSpaces.collectAsState()
    val liveSyncStatus by viewModel.liveSyncStatus.collectAsState()

    val isRunning by viewModel.isTaskRunning.collectAsState()
    val isKeepAlive by viewModel.isKeepAliveActive.collectAsState()
    val activeSpaceName by viewModel.activeSpaceName.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var customSpaceInput by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showSdkMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_custom_space_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Custom Space")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            // Anti-Timeout ongoing banner if service is running
            if (isRunning || isKeepAlive) {
                AntiTimeoutBanner(
                    activeSpaceTitle = activeSpaceName.ifBlank { "Hugging Face Space" },
                    onOpenActiveTask = {
                        viewModel.navigateTo(AppScreen.AGENT_STUDIO)
                    },
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            // Live Feed Status Bar + Refresh Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isLoadingLiveSpaces) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = HfYellow
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = liveSyncStatus,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { viewModel.refreshLiveSpaces(immediate = true) },
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("refresh_live_spaces_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Live Spaces",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Live Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search Spaces on huggingface.co/spaces...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("space_search_input")
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Task / Category Filter Pills (matching https://huggingface.co/spaces)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SpaceCategoryFilter.values().forEach { category ->
                    FilterChip(
                        selected = selectedCategoryFilter == category,
                        onClick = { viewModel.setCategoryFilter(category) },
                        label = {
                            Text("${category.emoji} ${category.displayName}", fontSize = 12.sp)
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("category_chip_${category.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Sort, SDK, and Runtime/Hardware Filter Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sort Dropdown Button
                Box {
                    AssistChip(
                        onClick = { showSortMenu = true },
                        label = {
                            Text(
                                text = "Sort: ${selectedSortOption.badgeEmoji} ${selectedSortOption.displayName}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Sort Options",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("sort_dropdown_chip")
                    )

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SpaceSortOption.values().forEach { sortOpt ->
                            DropdownMenuItem(
                                text = {
                                    Text("${sortOpt.badgeEmoji} ${sortOpt.displayName}")
                                },
                                onClick = {
                                    viewModel.setSortOption(sortOpt)
                                    showSortMenu = false
                                },
                                leadingIcon = {
                                    if (selectedSortOption == sortOpt) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                // SDK Filter Dropdown Button
                Box {
                    AssistChip(
                        onClick = { showSdkMenu = true },
                        label = {
                            Text(
                                text = "SDK: ${selectedSdkFilter.displayName}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "SDK Filter",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("sdk_dropdown_chip")
                    )

                    DropdownMenu(
                        expanded = showSdkMenu,
                        onDismissRequest = { showSdkMenu = false }
                    ) {
                        SpaceSdkFilter.values().forEach { sdkOpt ->
                            DropdownMenuItem(
                                text = { Text(sdkOpt.displayName) },
                                onClick = {
                                    viewModel.setSdkFilter(sdkOpt)
                                    showSdkMenu = false
                                },
                                leadingIcon = {
                                    if (selectedSdkFilter == sdkOpt) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                // Runtime / Hardware / Saved Filter Chips
                SpaceRuntimeFilter.values().forEach { runtimeOpt ->
                    FilterChip(
                        selected = selectedRuntimeFilter == runtimeOpt,
                        onClick = { viewModel.setRuntimeFilter(runtimeOpt) },
                        label = { Text(runtimeOpt.displayName, fontSize = 11.sp) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("runtime_chip_${runtimeOpt.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Spaces List
            if (liveSpaces.isEmpty() && !isLoadingLiveSpaces) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No Spaces matched these filters",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Button(
                            onClick = {
                                viewModel.setSearchQuery("")
                                viewModel.setCategoryFilter(SpaceCategoryFilter.ALL)
                                viewModel.setSdkFilter(SpaceSdkFilter.ALL)
                                viewModel.setRuntimeFilter(SpaceRuntimeFilter.ALL)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FilterAltOff, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Filters")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("spaces_lazy_list"),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(
                        items = liveSpaces,
                        key = { it.id }
                    ) { space ->
                        SpaceCard(
                            space = space,
                            onOpenAgent = { viewModel.openSpaceInAgent(it) },
                            onOpenWeb = { viewModel.openSpaceInWeb(it) },
                            onToggleFavorite = { viewModel.toggleFavorite(it) }
                        )
                    }
                }
            }
        }
    }

    // Add Custom Space Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(text = "Add Hugging Face Space", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter any Hugging Face Space repo ID (e.g. \"meta-llama/Llama-3.2-3B-Instruct\") or full Space URL.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customSpaceInput,
                        onValueChange = { customSpaceInput = it },
                        placeholder = { Text("author/space-name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_custom_space_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customSpaceInput.isNotBlank()) {
                            viewModel.addCustomSpace(customSpaceInput)
                            customSpaceInput = ""
                            showAddDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_add_space_btn")
                ) {
                    Text("Add Space")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
