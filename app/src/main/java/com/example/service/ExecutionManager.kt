package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.AgentSessionEntity
import com.example.data.InputUriAccess
import com.example.data.SpaceDatabase
import com.example.model.AgentStep
import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.model.SessionStatus
import com.example.model.SpaceInputValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class ActiveSessionGate {
    private var activeSessionId: String? = null

    @Synchronized
    fun tryStart(sessionId: String): Boolean {
        if (activeSessionId != null) return false
        activeSessionId = sessionId
        return true
    }

    @Synchronized
    fun accepts(sessionId: String): Boolean = activeSessionId == sessionId

    @Synchronized
    fun finish(sessionId: String): Boolean {
        if (activeSessionId != sessionId) return false
        activeSessionId = null
        return true
    }

    @Synchronized
    fun currentId(): String? = activeSessionId
}

object ExecutionManager {
    private const val TAG = "ExecutionManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val sessionGate = ActiveSessionGate()
    private val initialInsertJobs = ConcurrentHashMap<String, Job>()
    private var keepAliveSpaceName = ""

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isKeepAliveActive = MutableStateFlow(false)
    val isKeepAliveActive: StateFlow<Boolean> = _isKeepAliveActive.asStateFlow()

    private val _activeSpaceName = MutableStateFlow("")
    val activeSpaceName: StateFlow<String> = _activeSpaceName.asStateFlow()

    private val _currentSession = MutableStateFlow<AgentSessionEntity?>(null)
    val currentSession: StateFlow<AgentSessionEntity?> = _currentSession.asStateFlow()

    private val _currentSteps = MutableStateFlow<List<AgentStep>>(emptyList())
    val currentSteps: StateFlow<List<AgentStep>> = _currentSteps.asStateFlow()

    private val _liveLogs = MutableStateFlow<List<String>>(emptyList())
    val liveLogs: StateFlow<List<String>> = _liveLogs.asStateFlow()

    fun startTask(
        context: Context,
        goal: String,
        agentType: AgentType,
        space: HfSpace,
        apiName: String = "predict",
        inputs: List<SpaceInputValue> = emptyList()
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val summaryGoal = goal.ifBlank {
            inputs.joinToString(" | ") { it.toDisplaySummary() }.ifBlank { "Run ${space.title} (/$apiName)" }
        }
        val session = AgentSessionEntity(
            id = sessionId,
            title = summaryGoal.take(45) + if (summaryGoal.length > 45) "..." else "",
            goal = summaryGoal,
            spaceId = space.id,
            spaceTitle = space.title,
            agentType = agentType.name,
            status = SessionStatus.RUNNING.name,
            createdAt = System.currentTimeMillis()
        )

        synchronized(stateLock) {
            if (!sessionGate.tryStart(sessionId)) {
                val activeId = sessionGate.currentId() ?: sessionId
                _liveLogs.value = _liveLogs.value + "⚠️ A Space task is already running. Wait for it to finish or cancel it first."
                return activeId
            }
            InputUriAccess.retain(context, InputUriAccess.sessionOwner(sessionId), inputs)
            _currentSession.value = session
            _isRunning.value = true
            _currentSteps.value = emptyList()
            _liveLogs.value = listOf("Initializing background execution for ${space.id} (/$apiName)...")
            updateActiveSpaceNameLocked()
        }

        initialInsertJobs[sessionId] = scope.launch {
            try {
                SpaceDatabase.getDatabase(context).agentSessionDao().insertSession(session)
            } catch (error: Exception) {
                Log.e(TAG, "Error inserting initial session", error)
            }
        }

        val intent = Intent(context, SpaceAgentExecutionService::class.java).apply {
            action = SpaceAgentExecutionService.ACTION_START_AGENT_TASK
            putExtra(SpaceAgentExecutionService.EXTRA_SESSION_ID, sessionId)
            putExtra(SpaceAgentExecutionService.EXTRA_GOAL, summaryGoal)
            putExtra(SpaceAgentExecutionService.EXTRA_AGENT_TYPE, agentType.name)
            putExtra(SpaceAgentExecutionService.EXTRA_SPACE_ID, space.id)
            putExtra(SpaceAgentExecutionService.EXTRA_SPACE_NAME, space.title)
            putExtra(SpaceAgentExecutionService.EXTRA_SPACE_URL, space.endpointUrl)
            putExtra(SpaceAgentExecutionService.EXTRA_API_NAME, apiName)
            putExtra(SpaceAgentExecutionService.EXTRA_INPUTS_JSON, SpaceInputValue.listToJsonString(inputs))
        }

        try {
            startService(context, intent, foreground = true)
        } catch (error: Exception) {
            InputUriAccess.release(InputUriAccess.sessionOwner(sessionId))
            failTask(sessionId, context, error.localizedMessage ?: "Unable to start background execution")
        }
        return sessionId
    }

    fun toggleWebViewKeepAlive(context: Context, spaceTitle: String, enabled: Boolean) {
        synchronized(stateLock) {
            _isKeepAliveActive.value = enabled
            keepAliveSpaceName = if (enabled) spaceTitle else ""
            updateActiveSpaceNameLocked()
        }

        val intent = Intent(context, SpaceAgentExecutionService::class.java).apply {
            action = if (enabled) {
                SpaceAgentExecutionService.ACTION_START_KEEP_ALIVE
            } else {
                SpaceAgentExecutionService.ACTION_STOP_KEEP_ALIVE
            }
            putExtra(SpaceAgentExecutionService.EXTRA_SPACE_NAME, spaceTitle)
        }
        try {
            startService(context, intent, foreground = enabled)
        } catch (error: Exception) {
            synchronized(stateLock) {
                _isKeepAliveActive.value = false
                keepAliveSpaceName = ""
                updateActiveSpaceNameLocked()
            }
            Log.e(TAG, "Unable to change keep-alive state", error)
        }
    }

    fun cancelTask(context: Context) {
        val sessionId = synchronized(stateLock) {
            val current = _currentSession.value ?: return
            if (!sessionGate.finish(current.id)) return
            val updated = current.copy(
                status = SessionStatus.CANCELLED.name,
                completedAt = System.currentTimeMillis()
            )
            _currentSession.value = updated
            _isRunning.value = false
            _liveLogs.value = _liveLogs.value + "⛔ Task cancelled by user."
            updateActiveSpaceNameLocked()
            persistTerminalSession(context, updated)
            current.id
        }

        val intent = Intent(context, SpaceAgentExecutionService::class.java).apply {
            action = SpaceAgentExecutionService.ACTION_CANCEL_AGENT_TASK
            putExtra(SpaceAgentExecutionService.EXTRA_SESSION_ID, sessionId)
        }
        try {
            context.startService(intent)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to deliver cancellation to service", error)
        }
    }

    fun updateStep(sessionId: String, step: AgentStep): Boolean = synchronized(stateLock) {
        if (!sessionGate.accepts(sessionId)) return@synchronized false
        _currentSteps.value = _currentSteps.value.toMutableList().apply {
            val index = indexOfFirst { it.stepNumber == step.stepNumber }
            if (index >= 0) set(index, step) else add(step)
        }
        true
    }

    fun addLog(sessionId: String, log: String): Boolean = synchronized(stateLock) {
        if (!sessionGate.accepts(sessionId)) return@synchronized false
        _liveLogs.value = _liveLogs.value + log
        true
    }

    fun finishTask(
        sessionId: String,
        context: Context,
        finalOutput: String,
        imageUrl: String?,
        steps: List<AgentStep>,
        durationMs: Long
    ): Boolean {
        val updated = synchronized(stateLock) {
            val current = _currentSession.value ?: return false
            if (current.id != sessionId || !sessionGate.finish(sessionId)) return false
            current.copy(
                status = SessionStatus.COMPLETED.name,
                completedAt = System.currentTimeMillis(),
                finalOutput = finalOutput,
                imageUrl = imageUrl,
                stepsJson = steps.toJsonString(),
                executionDurationMs = durationMs
            ).also {
                _currentSession.value = it
                _currentSteps.value = steps
                _isRunning.value = false
                updateActiveSpaceNameLocked()
            }
        }
        persistTerminalSession(context, updated)
        return true
    }

    fun failTask(sessionId: String, context: Context, errorMessage: String): Boolean {
        val updated = synchronized(stateLock) {
            val current = _currentSession.value ?: return false
            if (current.id != sessionId || !sessionGate.finish(sessionId)) return false
            current.copy(
                status = SessionStatus.FAILED.name,
                completedAt = System.currentTimeMillis(),
                finalOutput = "Error: $errorMessage"
            ).also {
                _currentSession.value = it
                _isRunning.value = false
                _liveLogs.value = _liveLogs.value + "❌ Error: $errorMessage"
                updateActiveSpaceNameLocked()
            }
        }
        persistTerminalSession(context, updated)
        return true
    }

    fun isSessionActive(sessionId: String): Boolean = sessionGate.accepts(sessionId)

    fun onServiceDestroyed() {
        synchronized(stateLock) {
            _isKeepAliveActive.value = false
            keepAliveSpaceName = ""
            updateActiveSpaceNameLocked()
        }
    }

    private fun persistTerminalSession(context: Context, session: AgentSessionEntity) {
        scope.launch {
            initialInsertJobs.remove(session.id)?.join()
            try {
                SpaceDatabase.getDatabase(context).agentSessionDao().insertSession(session)
            } catch (error: Exception) {
                Log.e(TAG, "Error persisting terminal session ${session.id}", error)
            }
        }
    }

    private fun updateActiveSpaceNameLocked() {
        _activeSpaceName.value = when {
            _isRunning.value -> _currentSession.value?.spaceTitle.orEmpty()
            _isKeepAliveActive.value -> keepAliveSpaceName
            else -> ""
        }
    }

    private fun startService(context: Context, intent: Intent, foreground: Boolean) {
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun List<AgentStep>.toJsonString(): String {
        val array = JSONArray()
        forEach { step ->
            array.put(JSONObject().apply {
                put("stepNumber", step.stepNumber)
                put("title", step.title)
                put("description", step.description)
                put("status", step.status.name)
                put("log", step.logOutput)
            })
        }
        return array.toString()
    }
}
