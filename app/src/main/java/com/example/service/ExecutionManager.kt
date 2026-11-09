package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.AgentSessionEntity
import com.example.data.SpaceDatabase
import com.example.model.AgentStep
import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.model.SessionStatus
import com.example.model.SpaceInputValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ExecutionManager {
    private const val TAG = "ExecutionManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val summaryGoal = if (goal.isNotBlank()) {
            goal
        } else {
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

        _currentSession.value = session
        _isRunning.value = true
        _activeSpaceName.value = space.title
        _currentSteps.value = emptyList()
        _liveLogs.value = listOf("Initializing background execution service for ${space.id} (/$apiName)...")

        // Save session in database immediately
        scope.launch {
            try {
                SpaceDatabase.getDatabase(context).agentSessionDao().insertSession(session)
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting initial session", e)
            }
        }

        val inputsJson = SpaceInputValue.listToJsonString(inputs)

        // Start Foreground Service so switching away or locking the screen never causes timeouts!
        val intent = Intent(context, SpaceAgentExecutionService::class.java).apply {
            action = SpaceAgentExecutionService.ACTION_START_AGENT_TASK
            putExtra(SpaceAgentExecutionService.EXTRA_SESSION_ID, sessionId)
            putExtra(SpaceAgentExecutionService.EXTRA_GOAL, summaryGoal)
            putExtra(SpaceAgentExecutionService.EXTRA_AGENT_TYPE, agentType.name)
            putExtra(SpaceAgentExecutionService.EXTRA_SPACE_ID, space.id)
            putExtra(SpaceAgentExecutionService.EXTRA_SPACE_NAME, space.title)
            putExtra(SpaceAgentExecutionService.EXTRA_SPACE_URL, space.endpointUrl)
            putExtra(SpaceAgentExecutionService.EXTRA_API_NAME, apiName)
            putExtra(SpaceAgentExecutionService.EXTRA_INPUTS_JSON, inputsJson)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        return sessionId
    }

    fun toggleWebViewKeepAlive(context: Context, spaceTitle: String, enabled: Boolean) {
        _isKeepAliveActive.value = enabled
        _activeSpaceName.value = if (enabled) spaceTitle else ""

        val intent = Intent(context, SpaceAgentExecutionService::class.java).apply {
            action = if (enabled) {
                SpaceAgentExecutionService.ACTION_START_KEEP_ALIVE
            } else {
                SpaceAgentExecutionService.ACTION_STOP_SERVICE
            }
            putExtra(SpaceAgentExecutionService.EXTRA_SPACE_NAME, spaceTitle)
        }

        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
    }

    fun cancelTask(context: Context) {
        val current = _currentSession.value ?: return
        _isRunning.value = false
        addLog("⛔ Task cancelled by user.")

        scope.launch {
            val updated = current.copy(
                status = SessionStatus.CANCELLED.name,
                completedAt = System.currentTimeMillis()
            )
            _currentSession.value = updated
            SpaceDatabase.getDatabase(context).agentSessionDao().updateSession(updated)
        }

        val intent = Intent(context, SpaceAgentExecutionService::class.java).apply {
            action = SpaceAgentExecutionService.ACTION_STOP_SERVICE
        }
        context.stopService(intent)
    }

    // Callbacks from Service
    fun updateSteps(steps: List<AgentStep>) {
        _currentSteps.value = steps
    }

    fun addLog(log: String) {
        _liveLogs.value = _liveLogs.value + log
    }

    fun finishTask(
        context: Context,
        finalOutput: String,
        imageUrl: String?,
        steps: List<AgentStep>,
        durationMs: Long
    ) {
        val current = _currentSession.value ?: return
        _isRunning.value = false

        val stepsArray = JSONArray()
        for (s in steps) {
            val obj = JSONObject()
            obj.put("stepNumber", s.stepNumber)
            obj.put("title", s.title)
            obj.put("description", s.description)
            obj.put("status", s.status.name)
            obj.put("log", s.logOutput)
            stepsArray.put(obj)
        }

        val updated = current.copy(
            status = SessionStatus.COMPLETED.name,
            completedAt = System.currentTimeMillis(),
            finalOutput = finalOutput,
            imageUrl = imageUrl,
            stepsJson = stepsArray.toString(),
            executionDurationMs = durationMs
        )
        _currentSession.value = updated

        scope.launch {
            try {
                SpaceDatabase.getDatabase(context).agentSessionDao().updateSession(updated)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating completed session", e)
            }
        }
    }

    fun failTask(context: Context, errorMessage: String) {
        val current = _currentSession.value ?: return
        _isRunning.value = false
        addLog("❌ Error: $errorMessage")

        val updated = current.copy(
            status = SessionStatus.FAILED.name,
            completedAt = System.currentTimeMillis(),
            finalOutput = "Error: $errorMessage"
        )
        _currentSession.value = updated

        scope.launch {
            try {
                SpaceDatabase.getDatabase(context).agentSessionDao().updateSession(updated)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating failed session", e)
            }
        }
    }
}
