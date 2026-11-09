package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.HfTokenStore
import com.example.data.InputUriAccess
import com.example.data.toHfSpace
import com.example.data.AgentSessionEntity
import com.example.data.SpaceDatabase
import com.example.data.SpaceEntity
import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.model.SpaceCategoryFilter
import com.example.model.SpaceInputValue
import com.example.model.SpaceParameterSpec
import com.example.model.SpaceRuntimeFilter
import com.example.model.SpaceSchema
import com.example.model.SpaceSdkFilter
import com.example.model.SpaceSortOption
import com.example.network.HfApiClient
import com.example.service.ExecutionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

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
    private val tokenStore = HfTokenStore(application)
    private val formInputOwner = Any()
    private val _tokenError = MutableStateFlow<String?>(null)
    val tokenError = _tokenError.asStateFlow()
    private val _isSavingToken = MutableStateFlow(false)
    val isSavingToken = _isSavingToken.asStateFlow()
    private val _tokenSaveStatus = MutableStateFlow<String?>(null)
    val tokenSaveStatus = _tokenSaveStatus.asStateFlow()
    private val _hfToken = MutableStateFlow(try { tokenStore.read() } catch (_: Exception) {
        _tokenError.value = "Could not read the saved token. Save it again in Settings."
        ""
    })
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
    private val _inputErrors = MutableStateFlow<Map<Int, String>>(emptyMap())
    val inputErrors = _inputErrors.asStateFlow()
    private val _executionError = MutableStateFlow<String?>(null)
    val executionError = _executionError.asStateFlow()
    private val _customSpaceError = MutableStateFlow<String?>(null)
    val customSpaceError = _customSpaceError.asStateFlow()
    val isEndpointReady = combine(_activeSpaceSchema, _isLoadingSchema, _selectedEndpointIndex) { schema, loading, index ->
        !loading && schema?.isLiveFetched == true && schema.endpoints.getOrNull(index) != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
    private val savedSpaceFilter = combine(
        _searchQuery, _selectedCategoryFilter, _selectedSdkFilter, _selectedSortOption
    ) { query, category, sdk, sort -> SavedSpaceFilter(query, category, sdk, sort) }
    val liveSpaces: StateFlow<List<HfSpace>> = combine(
        _liveSpaces, spaceDao.getAllSpaces(), _selectedRuntimeFilter, savedSpaceFilter
    ) { spaces, saved, filter, savedFilter ->
        val favorites = saved.filter { it.isFavorite }.map { it.id }.toSet()
        if (filter == SpaceRuntimeFilter.SAVED) savedFilter.apply(saved.map { it.toHfSpace() })
        else spaces.map { it.copy(isFavorite = it.id in favorites) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        val previousSchema = _activeSpaceSchema.value
        val previousApiName = previousSchema?.endpoints?.getOrNull(_selectedEndpointIndex.value)?.apiName
        if (previousSchema?.spaceId != space.id) {
            applySchema(SpaceSchema(space.id, emptyList(), false, "Loading Space endpoints…"), 0)
        }
        _isLoadingSchema.value = true
        schemaJob = viewModelScope.launch {
            try {
                val schema = apiClient.fetchSpaceSchema(space)
                val index = schema.endpoints.indexOfFirst { it.apiName == previousApiName }.coerceAtLeast(0)
                // Same endpoint refresh keeps edits made while the request was in flight.
                val retained = if (previousSchema?.spaceId == space.id &&
                    schema.endpoints.getOrNull(index)?.apiName == previousApiName) _currentInputValues.value else emptyList()
                if (schema.endpoints.isEmpty() && previousSchema?.spaceId == space.id) {
                    applySchema(previousSchema.copy(isLiveFetched = false, schemaSourceNote = schema.schemaSourceNote),
                        _selectedEndpointIndex.value, _currentInputValues.value)
                } else {
                    applySchema(schema, index, retained)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val unavailable = previousSchema?.takeIf { it.spaceId == space.id }
                    ?.copy(isLiveFetched = false, schemaSourceNote = "Could not refresh endpoints. Retry before running.")
                    ?: SpaceSchema(space.id, emptyList(), false, "Could not load endpoints. Retry or open the Space website.")
                applySchema(unavailable, _selectedEndpointIndex.value, _currentInputValues.value)
            } finally {
                if (kotlinx.coroutines.currentCoroutineContext().isActive) _isLoadingSchema.value = false
            }
        }
    }

    private fun applySchema(schema: SpaceSchema, endpointIdx: Int, previous: List<SpaceInputValue> = emptyList()) {
        _activeSpaceSchema.value = schema
        val safeIdx = endpointIdx.coerceIn(0, (schema.endpoints.size - 1).coerceAtLeast(0))
        _selectedEndpointIndex.value = safeIdx
        val specs = schema.endpoints.getOrNull(safeIdx)?.parameters.orEmpty()
        _currentEndpointSpecs.value = specs
        _currentInputValues.value = SpaceFormState.values(specs, previous)
        syncInputAccess()
        _inputErrors.value = emptyMap()
        _executionError.value = null
    }

    private fun validateExecution(space: HfSpace): Boolean {
        val schema = _activeSpaceSchema.value
        if (_isLoadingSchema.value || schema == null || schema.spaceId != space.id || !schema.isLiveFetched ||
            schema.endpoints.getOrNull(_selectedEndpointIndex.value) == null) {
            _executionError.value = "Load a verified Space endpoint before running."
            return false
        }
        _inputErrors.value = SpaceFormState.errors(_currentEndpointSpecs.value, _currentInputValues.value)
        _executionError.value = if (_inputErrors.value.isEmpty()) null else "Check the highlighted inputs."
        return _inputErrors.value.isEmpty()
    }

    fun selectEndpoint(index: Int) {
        val schema = _activeSpaceSchema.value ?: return
        applySchema(schema, index)
    }

    fun updateTextInput(paramIndex: Int, text: String) {
        updateInput(paramIndex) { it.copy(textValue = text, isNull = false) }
        // Keep agentGoalPrompt synced if this is the primary text parameter
        if (paramIndex == 0 && text.isNotBlank()) {
            _agentGoalPrompt.value = text
        }
    }

    fun updateNumberInput(paramIndex: Int, text: String) {
        updateInput(paramIndex) { it.copy(textValue = text, numberValue = text.toDoubleOrNull(), isNull = false) }
    }

    fun updateBooleanInput(paramIndex: Int, boolVal: Boolean) {
        updateInput(paramIndex) { it.copy(booleanValue = boolVal, isNull = false) }
    }

    fun setInputNull(paramIndex: Int, isNull: Boolean) {
        val spec = _currentEndpointSpecs.value.firstOrNull { it.index == paramIndex } ?: return
        if (!spec.isRequired && spec.defaultIsNull) updateInput(paramIndex) { it.copy(isNull = isNull) }
    }

    private fun updateInput(paramIndex: Int, transform: (SpaceInputValue) -> SpaceInputValue) {
        _currentInputValues.value = _currentInputValues.value.map {
            if (it.paramIndex == paramIndex) transform(it) else it
        }
        _inputErrors.value = _inputErrors.value - paramIndex
        _executionError.value = null
        syncInputAccess()
    }

    private fun syncInputAccess() {
        InputUriAccess.retain(getApplication(), formInputOwner, _currentInputValues.value)
    }

    override fun onCleared() {
        InputUriAccess.release(formInputOwner)
        super.onCleared()
    }

    fun updateFileInputFromUri(context: Context, paramIndex: Int, uri: Uri?) {
        val spec = _currentEndpointSpecs.value.firstOrNull { it.index == paramIndex && it.inputType.isMediaOrFile }
            ?: return
        if (uri == null) {
            updateInput(paramIndex) {
                it.copy(fileUri = null, fileName = null, fileSizeBytes = 0L, mimeType = null,
                    isNull = !spec.isRequired && spec.defaultIsNull)
            }
            return
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

        updateInput(paramIndex) {
                it.copy(
                    fileUri = uri.toString(),
                    fileName = fileName,
                    fileSizeBytes = fileSize,
                    mimeType = mimeType,
                    isNull = false
                )
        }
    }

    fun getCurrentApiName(): String {
        val schema = _activeSpaceSchema.value ?: return "predict"
        val idx = _selectedEndpointIndex.value
        return schema.endpoints.getOrNull(idx)?.apiName ?: "predict"
    }

    fun executeInteractiveSpaceCall(context: Context) {
        val space = _selectedWebSpace.value ?: _selectedSpaceForAgent.value
        if (_isInteractiveExecuting.value || isTaskRunning.value || !validateExecution(space)) return
        val apiName = getCurrentApiName()
        val inputs = _currentInputValues.value
        val executionInputOwner = Any()
        InputUriAccess.retain(context, executionInputOwner, inputs)
        _isInteractiveExecuting.value = true
        _interactiveOutput.value = "Executing /$apiName on ${space.id}…"
        viewModelScope.launch {
            try {
                val result = apiClient.executeSpaceWithInputs(
                    context.applicationContext, space.id, apiName, inputs,
                    onProgressLog = { _interactiveOutput.value = it }
                )
                _interactiveOutput.value = result.fold(
                    onSuccess = { "Response from ${space.id} (/$apiName):\n$it" },
                    onFailure = { "Execution failed: ${it.message ?: "Unknown error"}" }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _interactiveOutput.value = "Execution failed: ${error.message ?: "Unknown error"}"
            } finally {
                _isInteractiveExecuting.value = false
            }
        }.invokeOnCompletion { InputUriAccess.release(executionInputOwner) }
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

            if (_selectedRuntimeFilter.value == SpaceRuntimeFilter.SAVED) {
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
                _liveSyncStatus.value = "Saved Spaces • available offline"
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
                    spaceDao.cacheSpaces(toCache)
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
            _selectedWebSpace.value = matching
            _selectedSpaceForAgent.value = matching
            if (matching.demoPrompt.isNotBlank()) {
                _agentGoalPrompt.value = matching.demoPrompt
            }
            loadSchemaForSpace(matching)
        }
    }

    fun setAgentSpace(space: HfSpace) {
        _selectedWebSpace.value = space
        _selectedSpaceForAgent.value = space
        loadSchemaForSpace(space)
    }

    fun setAgentPrompt(prompt: String) {
        _agentGoalPrompt.value = prompt
    }

    fun startAgentExecution(context: Context) {
        val inputs = _currentInputValues.value
        val prompt = _agentGoalPrompt.value.trim()
        if (isTaskRunning.value || _isInteractiveExecuting.value || !validateExecution(_selectedSpaceForAgent.value)) return

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

    fun addCustomSpace(spaceIdOrUrl: String, onAdded: () -> Unit = {}) {
        _customSpaceError.value = null
        val id = try { SpaceAddress.parse(spaceIdOrUrl) } catch (error: Exception) {
            _customSpaceError.value = error.message ?: "Invalid Space address."
            return
        }
        viewModelScope.launch {
            try {
                val (author, name) = id.split('/')
                val space = HfSpace(id, author, name, name.replace('-', ' '),
                    "Custom Space ($id)", "gradio", "", 0,
                    HfSpace.toSanitizedSpaceEndpoint(id), isFavorite = true)
                spaceDao.insertSpace(SpaceEntity(
                    id = id, author = author, name = name, title = space.title,
                    description = space.description, sdk = space.sdk, category = space.category,
                    likes = 0, endpointUrl = space.endpointUrl, isFavorite = true
                ))
                _liveSpaces.value = listOf(space) + _liveSpaces.value.filterNot { it.id == id }
                onAdded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _customSpaceError.value = "Could not save this Space. Please try again."
            }
        }
    }

    fun setHfToken(token: String) {
        if (_isSavingToken.value) return
        _isSavingToken.value = true
        _tokenSaveStatus.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { tokenStore.write(token) }
                _hfToken.value = token
                _tokenError.value = null
                _tokenSaveStatus.value = if (token.isBlank()) "Token removed." else "Token saved."
                refreshLiveSpaces(immediate = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _tokenError.value = "Could not save the token. Please try again."
            } finally {
                _isSavingToken.value = false
            }
        }
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
