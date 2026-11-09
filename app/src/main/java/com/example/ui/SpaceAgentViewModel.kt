package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgentSessionEntity
import com.example.data.SpaceDatabase
import com.example.data.SpaceEntity
import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.model.SpaceCategoryFilter
import com.example.model.SpaceEndpointSpec
import com.example.model.SpaceInputType
import com.example.model.SpaceInputValue
import com.example.model.SpaceParameterSpec
import com.example.model.SpaceRuntimeFilter
import com.example.model.SpaceSchema
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

    // 1:1 Dynamic Space Schema & Multi-Input State (shared across Interactive & Agent Studio)
    private val _activeSpaceSchema = MutableStateFlow<SpaceSchema?>(null)
    val activeSpaceSchema: StateFlow<SpaceSchema?> = _activeSpaceSchema.asStateFlow()

    private val _isLoadingSchema = MutableStateFlow(false)
    val isLoadingSchema: StateFlow<Boolean> = _isLoadingSchema.asStateFlow()

    private val _selectedEndpointIndex = MutableStateFlow(0)
    val selectedEndpointIndex: StateFlow<Int> = _selectedEndpointIndex.asStateFlow()

    private val _currentEndpointSpecs = MutableStateFlow<List<SpaceParameterSpec>>(emptyList())
    val currentEndpointSpecs: StateFlow<List<SpaceParameterSpec>> = _currentEndpointSpecs.asStateFlow()

    private val _currentInputValues = MutableStateFlow<List<SpaceInputValue>>(emptyList())
    val currentInputValues: StateFlow<List<SpaceInputValue>> = _currentInputValues.asStateFlow()

    private val _interactiveOutput = MutableStateFlow<String?>(null)
    val interactiveOutput: StateFlow<String?> = _interactiveOutput.asStateFlow()

    private val _isInteractiveExecuting = MutableStateFlow(false)
    val isInteractiveExecuting: StateFlow<Boolean> = _isInteractiveExecuting.asStateFlow()

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
    private var schemaJob: Job? = null

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
        loadSchemaForSpace(_selectedSpaceForAgent.value)
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

    /**
     * Fetches the 1:1 Gradio Schema (/gradio_api/info, /info, /config) for the target Space
     * and initializes all dynamic input parameters (Video, Image, Audio, File, Sliders, Dropdowns, Text, etc.).
     */
    fun loadSchemaForSpace(space: HfSpace) {
        schemaJob?.cancel()
        // Immediately seed with inferred multi-input schema so UI is never blank while loading
        val initialInferred = apiClient.buildInferredSpaceSchema(space)
        applySchema(initialInferred, 0)
        _isLoadingSchema.value = true

        schemaJob = viewModelScope.launch {
            try {
                val liveSchema = apiClient.fetchSpaceSchema(space)
                applySchema(liveSchema, 0)
            } catch (_: Exception) {
                // Keep initial inferred schema
            } finally {
                _isLoadingSchema.value = false
            }
        }
    }

    private fun applySchema(schema: SpaceSchema, endpointIdx: Int) {
        _activeSpaceSchema.value = schema
        val safeIdx = endpointIdx.coerceIn(0, (schema.endpoints.size - 1).coerceAtLeast(0))
        _selectedEndpointIndex.value = safeIdx

        val endpoint = schema.endpoints.getOrNull(safeIdx)
        val specs = endpoint?.parameters ?: emptyList()
        _currentEndpointSpecs.value = specs

        _currentInputValues.value = specs.map { spec ->
            val defaultNum = spec.defaultValue.toFloatOrNull()
                ?: if (spec.inputType == SpaceInputType.SLIDER) ((spec.min + spec.max) / 2f) else 0f
            val defaultBool = spec.defaultValue.equals("true", ignoreCase = true)
            val defaultText = when (spec.inputType) {
                SpaceInputType.DROPDOWN -> spec.defaultValue.ifBlank { spec.choices.firstOrNull() ?: "" }
                SpaceInputType.TEXT -> spec.defaultValue
                else -> ""
            }
            SpaceInputValue(
                paramIndex = spec.index,
                paramName = spec.name,
                paramLabel = spec.label,
                inputType = spec.inputType,
                textValue = defaultText,
                numberValue = defaultNum.coerceIn(spec.min, spec.max.coerceAtLeast(spec.min)),
                booleanValue = defaultBool
            )
        }
    }

    fun selectEndpoint(index: Int) {
        val schema = _activeSpaceSchema.value ?: return
        applySchema(schema, index)
    }

    fun updateTextInput(paramIndex: Int, text: String) {
        _currentInputValues.value = _currentInputValues.value.map {
            if (it.paramIndex == paramIndex) it.copy(textValue = text) else it
        }
        // Keep agentGoalPrompt synced if this is the primary text parameter
        if (paramIndex == 0 && text.isNotBlank()) {
            _agentGoalPrompt.value = text
        }
    }

    fun updateNumberInput(paramIndex: Int, number: Float) {
        _currentInputValues.value = _currentInputValues.value.map {
            if (it.paramIndex == paramIndex) it.copy(numberValue = number) else it
        }
    }

    fun updateBooleanInput(paramIndex: Int, boolVal: Boolean) {
        _currentInputValues.value = _currentInputValues.value.map {
            if (it.paramIndex == paramIndex) it.copy(booleanValue = boolVal) else it
        }
    }

    fun updateFileInputFromUri(context: Context, paramIndex: Int, uri: Uri?) {
        if (uri == null) {
            _currentInputValues.value = _currentInputValues.value.map {
                if (it.paramIndex == paramIndex) {
                    it.copy(fileUri = null, fileName = null, fileSizeBytes = 0L, mimeType = null)
                } else it
            }
            return
        }

        // Persist read permission if possible so background service can read it
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }

        var fileName = uri.lastPathSegment ?: "selected_file"
        var fileSize = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx != -1) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { fileName = it }
                    }
                    if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                        fileSize = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {
        }

        val mimeType = context.contentResolver.getType(uri) ?: when {
            fileName.endsWith(".mp4", true) || fileName.endsWith(".mov", true) -> "video/mp4"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".wav", true) -> "audio/wav"
            fileName.endsWith(".mp3", true) -> "audio/mpeg"
            else -> "application/octet-stream"
        }

        _currentInputValues.value = _currentInputValues.value.map {
            if (it.paramIndex == paramIndex) {
                it.copy(
                    fileUri = uri.toString(),
                    fileName = fileName,
                    fileSizeBytes = fileSize,
                    mimeType = mimeType
                )
            } else it
        }
    }

    fun addCustomParameter(label: String, inputType: SpaceInputType) {
        val currentSpecs = _currentEndpointSpecs.value
        val newIdx = currentSpecs.size
        val cleanName = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "input_${newIdx + 1}" }
        val newSpec = SpaceParameterSpec(
            index = newIdx,
            name = cleanName,
            label = label.ifBlank { "${inputType.displayName} #${newIdx + 1}" },
            componentName = inputType.displayName,
            inputType = inputType,
            isRequired = false,
            defaultValue = if (inputType == SpaceInputType.SLIDER) "50" else "",
            min = 0f,
            max = 100f,
            step = 1f
        )

        _currentEndpointSpecs.value = currentSpecs + newSpec
        _currentInputValues.value = _currentInputValues.value + SpaceInputValue(
            paramIndex = newIdx,
            paramName = newSpec.name,
            paramLabel = newSpec.label,
            inputType = inputType,
            numberValue = if (inputType == SpaceInputType.SLIDER) 50f else 0f
        )
    }

    fun getCurrentApiName(): String {
        val schema = _activeSpaceSchema.value ?: return "predict"
        val idx = _selectedEndpointIndex.value
        return schema.endpoints.getOrNull(idx)?.apiName ?: "predict"
    }

    fun executeInteractiveSpaceCall(context: Context) {
        val space = _selectedWebSpace.value ?: _selectedSpaceForAgent.value
        val apiName = getCurrentApiName()
        val inputs = _currentInputValues.value

        _isInteractiveExecuting.value = true
        _interactiveOutput.value = "⏳ Uploading inputs & executing /$apiName on ${space.id}..."

        viewModelScope.launch {
            val logs = mutableListOf<String>()
            val result = apiClient.executeSpaceWithInputs(
                context = context,
                spaceId = space.id,
                apiName = apiName,
                inputs = inputs,
                onProgressLog = { msg ->
                    logs.add(msg)
                    _interactiveOutput.value = logs.joinToString("\n")
                }
            )

            if (result.isSuccess) {
                val raw = result.getOrNull() ?: ""
                _interactiveOutput.value = buildString {
                    if (logs.isNotEmpty()) {
                        appendLine(logs.joinToString("\n"))
                        appendLine()
                    }
                    appendLine("✅ Response from ${space.id} (/$apiName):")
                    appendLine(raw)
                }
            } else {
                // Start in resilient background Agent Engine if direct synchronous call needs queue/synthesis
                _interactiveOutput.value = buildString {
                    if (logs.isNotEmpty()) {
                        appendLine(logs.joinToString("\n"))
                        appendLine()
                    }
                    appendLine("🔒 Handing off multi-input execution (${inputs.size} parameters) to Anti-Timeout Background Service...")
                    inputs.forEach { appendLine("• ${it.toDisplaySummary()}") }
                }
                ExecutionManager.startTask(
                    context = context,
                    goal = _agentGoalPrompt.value,
                    agentType = _selectedAgentType.value,
                    space = space,
                    apiName = apiName,
                    inputs = inputs
                )
            }
            _isInteractiveExecuting.value = false
        }
    }

    fun refreshLiveSpaces(immediate: Boolean = true) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            if (!immediate) {
                delay(350)
            }
            _isLoadingLiveSpaces.value = true
            _liveSyncStatus.value = "Fetching live from huggingface.co/spaces..."

            val savedEntities = try {
                spaceDao.getAllSpaces().first()
            } catch (_: Exception) {
                emptyList()
            }
            val favoriteIds = savedEntities.filter { it.isFavorite }.map { it.id }.toSet()

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
        _selectedSpaceForAgent.value = space
        _interactiveOutput.value = null
        loadSchemaForSpace(space)
        _currentScreen.value = AppScreen.WEB_VIEW
    }

    fun openSpaceInAgent(space: HfSpace) {
        _selectedSpaceForAgent.value = space
        _selectedWebSpace.value = space
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
        loadSchemaForSpace(space)
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
            loadSchemaForSpace(matching)
        }
    }

    fun setAgentSpace(space: HfSpace) {
        _selectedSpaceForAgent.value = space
        loadSchemaForSpace(space)
    }

    fun setAgentPrompt(prompt: String) {
        _agentGoalPrompt.value = prompt
    }

    fun startAgentExecution(context: Context) {
        val inputs = _currentInputValues.value
        val prompt = _agentGoalPrompt.value.trim()
        if (prompt.isBlank() && inputs.isEmpty()) return

        ExecutionManager.startTask(
            context = context,
            goal = prompt,
            agentType = _selectedAgentType.value,
            space = _selectedSpaceForAgent.value,
            apiName = getCurrentApiName(),
            inputs = inputs
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
