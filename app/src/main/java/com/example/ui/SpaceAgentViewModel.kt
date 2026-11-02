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
import com.example.network.HfApiClient
import com.example.service.ExecutionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Space Browser State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _isSearchingHub = MutableStateFlow(false)
    val isSearchingHub: StateFlow<Boolean> = _isSearchingHub.asStateFlow()

    private val _hubSearchResults = MutableStateFlow<List<HfSpace>>(emptyList())
    val hubSearchResults: StateFlow<List<HfSpace>> = _hubSearchResults.asStateFlow()

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

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun openSpaceInWeb(space: HfSpace) {
        _selectedWebSpace.value = space
        _currentScreen.value = AppScreen.WEB_VIEW
    }

    fun openSpaceInAgent(space: HfSpace) {
        _selectedSpaceForAgent.value = space
        // Auto-match agent type to space category
        _selectedAgentType.value = when (space.category) {
            "Reasoning" -> AgentType.REASONING
            "Code" -> AgentType.CODER
            "Image" -> AgentType.IMAGE_STUDIO
            else -> AgentType.GENERAL
        }
        if (space.demoPrompt.isNotBlank()) {
            _agentGoalPrompt.value = space.demoPrompt
        }
        _currentScreen.value = AppScreen.AGENT_STUDIO
    }

    fun setAgentType(type: AgentType) {
        _selectedAgentType.value = type
        // Recommend a space for this agent type
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

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _hubSearchResults.value = emptyList()
        }
    }

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    fun searchHub(query: String) {
        if (query.isBlank()) return
        _isSearchingHub.value = true
        viewModelScope.launch {
            try {
                val results = apiClient.searchSpaces(query)
                _hubSearchResults.value = results
            } catch (_: Exception) {
                _hubSearchResults.value = emptyList()
            } finally {
                _isSearchingHub.value = false
            }
        }
    }

    fun toggleFavorite(space: HfSpace) {
        viewModelScope.launch {
            spaceDao.setFavorite(space.id, !space.isFavorite)
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
                val entity = SpaceEntity(
                    id = cleanId,
                    author = author,
                    name = name,
                    title = name.replace("-", " ").replace("_", " "),
                    description = "Custom Space added by user ($cleanId)",
                    sdk = "gradio",
                    category = "General",
                    likes = 1,
                    endpointUrl = HfSpace.toSanitizedSpaceEndpoint(cleanId),
                    isFavorite = true,
                    isCurated = false,
                    demoPrompt = "Run an agent task on $cleanId"
                )
                spaceDao.insertSpace(entity)
            }
        }
    }

    fun setHfToken(token: String) {
        _hfToken.value = token
        prefs.edit().putString("hf_api_token", token).apply()
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
