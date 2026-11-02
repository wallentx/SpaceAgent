package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgentSessionEntity
import com.example.data.SpaceDatabase
import com.example.data.SpaceEntity
import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.model.SpaceCategoryFilter
import com.example.model.SpaceRuntimeFilter
import com.example.model.SpaceSdkFilter
import com.example.model.SpaceSortOption
import com.example.network.HfApiClient
import com.example.service.ExecutionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen {
    SPACES_BROWSER,
    AGENT_STUDIO,
    WEB_VIEW,
    HISTORY,
    SETTINGS
}

class SpaceAgentViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SpaceDatabase.getDatabase(application)
    private val spaceDao = db.spaceDao()
    private val sessionDao = db.agentSessionDao()
    private val prefs = application.getSharedPreferences("space_agent_prefs", Context.MODE_PRIVATE)

    private val _hfToken = MutableStateFlow(prefs.getString("hf_api_token", "") ?: "")
    val hfToken: StateFlow<String> = _hfToken.asStateFlow()

    private val apiClient = HfApiClient { _hfToken.value }

    // Navigation
    private val _currentScreen = MutableStateFlow(AppScreen.SPACES_BROWSER)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _selectedWebSpace = MutableStateFlow<HfSpace?>(null)
    val selectedWebSpace: StateFlow<HfSpace?> = _selectedWebSpace.asStateFlow()

    private val _selectedHistorySession = MutableStateFlow<AgentSessionEntity?>(null)
    val selectedHistorySession: StateFlow<AgentSessionEntity?> = _selectedHistorySession.asStateFlow()

    // Agent Studio State
    private val _selectedSpaceForAgent = MutableStateFlow<HfSpace>(apiClient.getCuratedSpaces().first())
    val selectedSpaceForAgent: StateFlow<HfSpace> = _selectedSpaceForAgent.asStateFlow()

    private val _selectedAgentType = MutableStateFlow(AgentType.REASONING)
    val selectedAgentType: StateFlow<AgentType> = _selectedAgentType.asStateFlow()

    private val _agentGoalPrompt = MutableStateFlow("Analyze and solve: If 5 machines take 5 minutes to make 5 widgets, how long will 100 machines take to make 100 widgets?")
    val agentGoalPrompt: StateFlow<String> = _agentGoalPrompt.asStateFlow()

    // Live Hugging Face Spaces Browser State (mirrors https://huggingface.co/spaces)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategoryFilter = MutableStateFlow(SpaceCategoryFilter.ALL)
    val selectedCategoryFilter: StateFlow<SpaceCategoryFilter> = _selectedCategoryFilter.asStateFlow()

    private val _selectedSdkFilter = MutableStateFlow(SpaceSdkFilter.ALL)
    val selectedSdkFilter: StateFlow<SpaceSdkFilter> = _selectedSdkFilter.asStateFlow()

    private val _selectedRuntimeFilter = MutableStateFlow(SpaceRuntimeFilter.ALL)
    val selectedRuntimeFilter: StateFlow<SpaceRuntimeFilter> = _selectedRuntimeFilter.asStateFlow()

    private val _selectedSortOption = MutableStateFlow(SpaceSortOption.TRENDING)
    val selectedSortOption: StateFlow<SpaceSortOption> = _selectedSortOption.asStateFlow()

    private val _isLoadingLiveSpaces = MutableStateFlow(false)
    val isLoadingLiveSpaces: StateFlow<Boolean> = _isLoadingLiveSpaces.asStateFlow()

    private val _liveSpaces = MutableStateFlow<List<HfSpace>>(emptyList())
    val liveSpaces: StateFlow<List<HfSpace>> = _liveSpaces.asStateFlow()

    private val _liveSyncStatus = MutableStateFlow("Syncing live with huggingface.co/spaces...")
    val liveSyncStatus: StateFlow<String> = _liveSyncStatus.asStateFlow()

    private var fetchJob: Job? = null

    // Database Flows
    val localSpaces: StateFlow<List<SpaceEntity>> = spaceDao.getAllSpaces().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val historySessions: StateFlow<List<AgentSessionEntity>> = sessionDao.getAllSessions().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    // Bridge with ExecutionManager
    val isTaskRunning = ExecutionManager.isRunning
    val isKeepAliveActive = ExecutionManager.isKeepAliveActive
    val activeSpaceName = ExecutionManager.activeSpaceName
    val currentSession = ExecutionManager.currentSession
    val currentSteps = ExecutionManager.currentSteps
    val liveLogs = ExecutionManager.liveLogs

    init {
        seedCuratedSpacesIfEmpty()
        refreshLiveSpaces(immediate = true)
    }

    private fun seedCuratedSpacesIfEmpty() {
        viewModelScope.launch {
            if (spaceDao.getSpaceCount() == 0) {
                val curated = apiClient.getCuratedSpaces().map { s ->
                    SpaceEntity(
                        id = s.id,
                        author = s.author,
                        name = s.name,
                        title = s.title,
                        description = s.description,
                        sdk = s.sdk,
                        category = s.category,
                        likes = s.likes,
                        endpointUrl = s.endpointUrl,
                        isFavorite = s.isFavorite,
                        isCurated = s.isCurated,
                        demoPrompt = s.demoPrompt
                    )
                }
                spaceDao.insertSpaces(curated)
            }
        }
    }

    fun refreshLiveSpaces(immediate: Boolean = true) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            if (!immediate) {
                delay(350) // Debounce live search typing
            }
            _isLoadingLiveSpaces.value = true
            _liveSyncStatus.value = "Fetching live from huggingface.co/spaces..."

            val savedEntities = try {
                spaceDao.getAllSpaces().first()
            } catch (_: Exception) {
                emptyList()
            }
            val favoriteIds = savedEntities.filter { it.isFavorite }.map { it.id }.toSet()

            // If user filtered strictly to Saved spaces and has no query, show saved spaces merged with live
            if (_selectedRuntimeFilter.value == SpaceRuntimeFilter.SAVED && _searchQuery.value.isBlank()) {
                val savedSpaces = savedEntities.filter { it.isFavorite }.map { e ->
                    HfSpace(
                        id = e.id,
                        author = e.author,
                        name = e.name,
                        title = e.title,
                        description = e.description,
                        sdk = e.sdk,
                        category = e.category,
                        likes = e.likes,
                        endpointUrl = e.endpointUrl,
                        isFavorite = true,
                        isCurated = e.isCurated,
                        demoPrompt = e.demoPrompt
                    )
                }
                _liveSpaces.value = savedSpaces
                _isLoadingLiveSpaces.value = false
                _liveSyncStatus.value = "Showing ${savedSpaces.size} saved Spaces"
                return@launch
            }

            val result = apiClient.fetchLiveSpaces(
                query = _searchQuery.value,
                categoryFilter = _selectedCategoryFilter.value,
                sdkFilter = _selectedSdkFilter.value,
                runtimeFilter = _selectedRuntimeFilter.value,
                sortOption = _selectedSortOption.value,
                favoriteIds = favoriteIds,
                limit = 50
            )

            result.onSuccess { fetched ->
                _liveSpaces.value = fetched
                _liveSyncStatus.value = "Live • ${fetched.size} Spaces from huggingface.co/spaces"
                // Cache top fetched spaces into local DB so Agent picker also has them
                val toCache = fetched.take(15).map { s ->
                    SpaceEntity(
                        id = s.id,
                        author = s.author,
                        name = s.name,
                        title = s.title,
                        description = s.description,
                        sdk = s.sdk,
                        category = s.category,
                        likes = s.likes,
                        endpointUrl = s.endpointUrl,
                        isFavorite = favoriteIds.contains(s.id),
                        isCurated = false,
                        demoPrompt = s.demoPrompt
                    )
                }
                try {
                    spaceDao.insertSpaces(toCache)
                } catch (_: Exception) {}
            }.onFailure { err ->
                // Fallback to cached local spaces if offline
                if (_liveSpaces.value.isEmpty()) {
                    _liveSpaces.value = savedEntities.map { e ->
                        HfSpace(
                            id = e.id,
                            author = e.author,
                            name = e.name,
                            title = e.title,
                            description = e.description,
                            sdk = e.sdk,
                            category = e.category,
                            likes = e.likes,
                            endpointUrl = e.endpointUrl,
                            isFavorite = e.isFavorite,
                            isCurated = e.isCurated,
                            demoPrompt = e.demoPrompt
                        )
                    }.ifEmpty { apiClient.getCuratedSpaces() }
                }
                _liveSyncStatus.value = "Offline fallback (${err.message ?: "check connection"})"
            }

            _isLoadingLiveSpaces.value = false
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        refreshLiveSpaces(immediate = false)
    }

    fun setCategoryFilter(filter: SpaceCategoryFilter) {
        _selectedCategoryFilter.value = filter
        refreshLiveSpaces(immediate = true)
    }

    fun setSdkFilter(filter: SpaceSdkFilter) {
        _selectedSdkFilter.value = filter
        refreshLiveSpaces(immediate = true)
    }

    fun setRuntimeFilter(filter: SpaceRuntimeFilter) {
        _selectedRuntimeFilter.value = filter
        refreshLiveSpaces(immediate = true)
    }

    fun setSortOption(sortOption: SpaceSortOption) {
        _selectedSortOption.value = sortOption
        refreshLiveSpaces(immediate = true)
    }

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun openSpaceInWeb(space: HfSpace) {
        _selectedWebSpace.value = space
        _currentScreen.value = AppScreen.WEB_VIEW
    }

    fun openSpaceInAgent(space: HfSpace) {
        _selectedSpaceForAgent.value = space
        val catLower = space.category.lowercase()
        _selectedAgentType.value = when {
            catLower.contains("reason") || catLower.contains("agent") -> AgentType.REASONING
            catLower.contains("code") -> AgentType.CODER
            catLower.contains("image") || catLower.contains("video") || catLower.contains("3d") -> AgentType.IMAGE_STUDIO
            else -> AgentType.GENERAL
        }
        if (space.demoPrompt.isNotBlank()) {
            _agentGoalPrompt.value = space.demoPrompt
        }
        _currentScreen.value = AppScreen.AGENT_STUDIO
    }

    fun setAgentType(type: AgentType) {
        _selectedAgentType.value = type
        val currentSpace = _selectedSpaceForAgent.value
        val spaces = apiClient.getCuratedSpaces()
        val matching = spaces.firstOrNull { it.id == type.defaultSpaceId }
        if (matching != null && currentSpace.category != type.name) {
            _selectedSpaceForAgent.value = matching
            if (matching.demoPrompt.isNotBlank()) {
                _agentGoalPrompt.value = matching.demoPrompt
            }
        }
    }

    fun setAgentSpace(space: HfSpace) {
        _selectedSpaceForAgent.value = space
    }

    fun setAgentPrompt(prompt: String) {
        _agentGoalPrompt.value = prompt
    }

    fun startAgentExecution(context: Context) {
        val prompt = _agentGoalPrompt.value.trim()
        if (prompt.isBlank()) return

        ExecutionManager.startTask(
            context = context,
            goal = prompt,
            agentType = _selectedAgentType.value,
            space = _selectedSpaceForAgent.value
        )
    }

    fun cancelAgentExecution(context: Context) {
        ExecutionManager.cancelTask(context)
    }

    fun toggleWebViewKeepAlive(context: Context) {
        val currentSpace = _selectedWebSpace.value ?: return
        val currentKeepAlive = isKeepAliveActive.value
        ExecutionManager.toggleWebViewKeepAlive(context, currentSpace.title, !currentKeepAlive)
    }

    fun toggleFavorite(space: HfSpace) {
        val newFav = !space.isFavorite
        // Optimistically update in live list
        _liveSpaces.value = _liveSpaces.value.map {
            if (it.id == space.id) it.copy(isFavorite = newFav) else it
        }
        viewModelScope.launch {
            val existing = spaceDao.getSpaceById(space.id)
            if (existing != null) {
                spaceDao.setFavorite(space.id, newFav)
            } else {
                spaceDao.insertSpace(
                    SpaceEntity(
                        id = space.id,
                        author = space.author,
                        name = space.name,
                        title = space.title,
                        description = space.description,
                        sdk = space.sdk,
                        category = space.category,
                        likes = space.likes,
                        endpointUrl = space.endpointUrl,
                        isFavorite = newFav,
                        isCurated = false,
                        demoPrompt = space.demoPrompt
                    )
                )
            }
        }
    }

    fun addCustomSpace(spaceIdOrUrl: String) {
        viewModelScope.launch {
            val trimmed = spaceIdOrUrl.trim()
            val cleanId = when {
                trimmed.contains("huggingface.co/spaces/") -> {
                    trimmed.substringAfter("huggingface.co/spaces/").trim('/')
                }
                trimmed.contains(".hf.space") -> {
                    trimmed.removePrefix("https://").substringBefore(".hf.space")
                }
                else -> trimmed
            }

            if (cleanId.contains("/")) {
                val parts = cleanId.split("/")
                val author = parts[0]
                val name = parts[1]
                val newSpace = HfSpace(
                    id = cleanId,
                    author = author,
                    name = name,
                    title = name.replace("-", " ").replace("_", " "),
                    description = "Custom Space added by user ($cleanId)",
                    sdk = "gradio",
                    category = "Text Generation",
                    likes = 1,
                    endpointUrl = HfSpace.toSanitizedSpaceEndpoint(cleanId),
                    isFavorite = true,
                    isCurated = false,
                    demoPrompt = "Run an agent task on $cleanId"
                )
                spaceDao.insertSpace(
                    SpaceEntity(
                        id = newSpace.id,
                        author = newSpace.author,
                        name = newSpace.name,
                        title = newSpace.title,
                        description = newSpace.description,
                        sdk = newSpace.sdk,
                        category = newSpace.category,
                        likes = newSpace.likes,
                        endpointUrl = newSpace.endpointUrl,
                        isFavorite = true,
                        isCurated = false,
                        demoPrompt = newSpace.demoPrompt
                    )
                )
                _liveSpaces.value = listOf(newSpace) + _liveSpaces.value.filterNot { it.id == newSpace.id }
            }
        }
    }

    fun setHfToken(token: String) {
        _hfToken.value = token
        prefs.edit().putString("hf_api_token", token).apply()
        refreshLiveSpaces(immediate = true)
    }

    fun selectHistorySession(session: AgentSessionEntity?) {
        _selectedHistorySession.value = session
    }

    fun deleteSession(session: AgentSessionEntity) {
        viewModelScope.launch {
            sessionDao.deleteSession(session)
            if (_selectedHistorySession.value?.id == session.id) {
                _selectedHistorySession.value = null
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            sessionDao.clearAll()
            _selectedHistorySession.value = null
        }
    }
}
