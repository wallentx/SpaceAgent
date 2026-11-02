package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import com.example.model.HfSpace
import com.example.ui.AppScreen
import com.example.ui.SpaceAgentViewModel
import com.example.ui.components.AntiTimeoutBanner
import com.example.ui.components.SpaceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesListScreen(
    viewModel: SpaceAgentViewModel,
    modifier: Modifier = Modifier
) {
    val localEntities by viewModel.localSpaces.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val isSearchingHub by viewModel.isSearchingHub.collectAsState()
    val hubResults by viewModel.hubSearchResults.collectAsState()
    val isRunning by viewModel.isTaskRunning.collectAsState()
    val isKeepAlive by viewModel.isKeepAliveActive.collectAsState()
    val activeSpaceName by viewModel.activeSpaceName.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var customSpaceInput by remember { mutableStateOf("") }

    // Map entities to domain HfSpace
    val localSpaces = remember(localEntities) {
        localEntities.map { entity ->
            HfSpace(
                id = entity.id,
                author = entity.author,
                name = entity.name,
                title = entity.title,
                description = entity.description,
                sdk = entity.sdk,
                category = entity.category,
                likes = entity.likes,
                endpointUrl = entity.endpointUrl,
                isFavorite = entity.isFavorite,
                isCurated = entity.isCurated,
                demoPrompt = entity.demoPrompt
            )
        }
    }

    // Filter spaces
    val displayedSpaces = remember(localSpaces, hubResults, searchQuery, selectedCategory) {
        if (hubResults.isNotEmpty() && searchQuery.isNotBlank()) {
            hubResults
        } else {
            localSpaces.filter { space ->
                val matchesCategory = selectedCategory == "All" || space.category.equals(selectedCategory, ignoreCase = true)
                val matchesSearch = searchQuery.isBlank() ||
                        space.name.contains(searchQuery, ignoreCase = true) ||
                        space.id.contains(searchQuery, ignoreCase = true) ||
                        space.title.contains(searchQuery, ignoreCase = true) ||
                        space.description.contains(searchQuery, ignoreCase = true)
                matchesCategory && matchesSearch
            }
        }
    }

    val categories = listOf("All", "Reasoning", "Code", "Image", "Agent", "Audio")

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
            Spacer(modifier = Modifier.height(8.dp))

            // Anti-Timeout ongoing banner if service is running
            if (isRunning || isKeepAlive) {
                AntiTimeoutBanner(
                    activeSpaceTitle = activeSpaceName.ifBlank { "Hugging Face Space" },
                    onOpenActiveTask = {
                        viewModel.navigateTo(AppScreen.AGENT_STUDIO)
                    },
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search Spaces (e.g. Qwen, FLUX, DeepSeek)...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.searchHub(searchQuery) }) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = "Search HF Hub",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("space_search_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { viewModel.setCategory(category) },
                        label = { Text(category) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("category_chip_$category")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Hub search indicator
            if (isSearchingHub) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Searching Hugging Face Hub...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Spaces List
            if (displayedSpaces.isEmpty() && !isSearchingHub) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SentimentDissatisfied,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No Spaces found matching query",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (searchQuery.isNotBlank()) {
                            Button(
                                onClick = { viewModel.searchHub(searchQuery) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Search Live on Hugging Face Hub")
                            }
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
                        items = displayedSpaces,
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
