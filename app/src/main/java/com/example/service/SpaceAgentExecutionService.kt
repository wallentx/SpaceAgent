package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.agent.SpaceAgentEngine
import com.example.data.HfTokenStore
import com.example.data.InputUriAccess
import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.model.SpaceInputValue
import com.example.network.HfApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SpaceAgentExecutionService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var agentJob: Job? = null
    private var activeSessionId: String? = null
    private var keepAliveActive = false
    private var keepAliveSpaceTitle = "Interactive Space"
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val TAG = "SpaceAgentService"
        const val CHANNEL_ID_RUNNING = "space_agent_running_channel"
        const val CHANNEL_ID_COMPLETED = "space_agent_completed_channel"
        const val NOTIFICATION_ID_RUNNING = 1001
        const val NOTIFICATION_ID_COMPLETED = 1002

        const val ACTION_START_AGENT_TASK = "com.example.action.START_AGENT_TASK"
        const val ACTION_CANCEL_AGENT_TASK = "com.example.action.CANCEL_AGENT_TASK"
        const val ACTION_START_KEEP_ALIVE = "com.example.action.START_KEEP_ALIVE"
        const val ACTION_STOP_KEEP_ALIVE = "com.example.action.STOP_KEEP_ALIVE"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_GOAL = "extra_goal"
        const val EXTRA_AGENT_TYPE = "extra_agent_type"
        const val EXTRA_SPACE_ID = "extra_space_id"
        const val EXTRA_SPACE_NAME = "extra_space_name"
        const val EXTRA_SPACE_URL = "extra_space_url"
        const val EXTRA_API_NAME = "extra_api_name"
        const val EXTRA_INPUTS_JSON = "extra_inputs_json"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_START_AGENT_TASK -> startAgent(intent)
            ACTION_CANCEL_AGENT_TASK -> cancelAgent(intent.getStringExtra(EXTRA_SESSION_ID))
            ACTION_START_KEEP_ALIVE -> startKeepAlive(intent.getStringExtra(EXTRA_SPACE_NAME))
            ACTION_STOP_KEEP_ALIVE -> stopKeepAlive()
            ACTION_STOP_SERVICE -> {
                keepAliveActive = false
                agentJob?.cancel()
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAgent(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        if (!ExecutionManager.isSessionActive(sessionId)) {
            InputUriAccess.release(InputUriAccess.sessionOwner(sessionId))
            stopIfIdle()
            return
        }
        if (agentJob?.isActive == true) {
            if (activeSessionId == sessionId) return
            Log.w(TAG, "Ignoring overlapping task $sessionId; $activeSessionId is active")
            ExecutionManager.failTask(sessionId, applicationContext, "Another Space task is already running")
            InputUriAccess.release(InputUriAccess.sessionOwner(sessionId))
            return
        }

        val goal = intent.getStringExtra(EXTRA_GOAL) ?: "Hugging Face Space Task"
        val agentType = runCatching {
            AgentType.valueOf(intent.getStringExtra(EXTRA_AGENT_TYPE) ?: AgentType.GENERAL.name)
        }.getOrDefault(AgentType.GENERAL)
        val spaceId = intent.getStringExtra(EXTRA_SPACE_ID) ?: return
        val spaceTitle = intent.getStringExtra(EXTRA_SPACE_NAME) ?: "HF Space"
        val space = HfSpace(
            id = spaceId,
            author = spaceId.substringBefore("/"),
            name = spaceId.substringAfter("/"),
            title = spaceTitle,
            description = "",
            sdk = "gradio",
            category = "General",
            likes = 0,
            endpointUrl = intent.getStringExtra(EXTRA_SPACE_URL) ?: HfSpace.toSanitizedSpaceEndpoint(spaceId)
        )
        val apiName = intent.getStringExtra(EXTRA_API_NAME) ?: "predict"
        val inputs = SpaceInputValue.listFromJsonString(intent.getStringExtra(EXTRA_INPUTS_JSON))

        activeSessionId = sessionId
        ensureWakeLock()
        startForegroundServiceCompat(
            buildOngoingNotification("Agent: $spaceTitle", "Starting /$apiName")
        )
        runAgentTask(sessionId, goal, agentType, space, apiName, inputs)
    }

    private fun runAgentTask(
        sessionId: String,
        goal: String,
        agentType: AgentType,
        space: HfSpace,
        apiName: String,
        inputs: List<SpaceInputValue>
    ) {
        agentJob = serviceScope.launch {
            try {
                val token = HfTokenStore(applicationContext).read()
                val engine = SpaceAgentEngine(HfApiClient { token })
                val result = engine.executeAgentTask(
                    context = applicationContext,
                    goal = goal,
                    agentType = agentType,
                    space = space,
                    apiName = apiName,
                    inputs = inputs,
                    onStepUpdate = { _, step ->
                        if (ExecutionManager.updateStep(sessionId, step)) {
                            updateOngoingNotification(
                                "Step ${step.stepNumber}/4: ${step.title}",
                                "${space.title} — ${step.logOutput}"
                            )
                        }
                    },
                    onLog = { ExecutionManager.addLog(sessionId, it) }
                )

                if (ExecutionManager.finishTask(
                        sessionId = sessionId,
                        context = applicationContext,
                        finalOutput = result.finalOutput,
                        imageUrl = result.imageUrl,
                        steps = result.steps,
                        durationMs = result.executionTimeMs
                    )
                ) {
                    showCompletionNotification(
                        title = "Task completed on ${space.name}",
                        text = "Tap to view the returned result."
                    )
                }
            } catch (cancelled: CancellationException) {
                Log.d(TAG, "Task $sessionId cancelled")
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Agent task $sessionId failed", error)
                if (ExecutionManager.failTask(
                        sessionId,
                        applicationContext,
                        error.localizedMessage ?: "Unknown error"
                    )
                ) {
                    showCompletionNotification(
                        title = "Space task failed",
                        text = error.localizedMessage ?: "The Space request did not complete."
                    )
                }
            } finally {
                if (activeSessionId == sessionId) activeSessionId = null
                agentJob = null
                stopIfIdle()
            }
        }.also { job ->
            job.invokeOnCompletion { InputUriAccess.release(InputUriAccess.sessionOwner(sessionId)) }
        }
    }

    private fun cancelAgent(sessionId: String?) {
        if (sessionId == null || activeSessionId != sessionId) return
        agentJob?.cancel(CancellationException("Cancelled by user"))
    }

    private fun startKeepAlive(spaceTitle: String?) {
        keepAliveActive = true
        keepAliveSpaceTitle = spaceTitle ?: "Interactive Space"
        ensureWakeLock()
        if (agentJob?.isActive != true) {
            startForegroundServiceCompat(
                buildOngoingNotification(
                    "Space active: $keepAliveSpaceTitle",
                    "Foreground keep-alive is enabled"
                )
            )
        }
    }

    private fun stopKeepAlive() {
        keepAliveActive = false
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (agentJob?.isActive == true) return
        if (keepAliveActive) {
            ensureWakeLock()
            updateOngoingNotification(
                "Space active: $keepAliveSpaceTitle",
                "Foreground keep-alive is enabled"
            )
            return
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpaceAgent:ExecutionLock").apply {
                setReferenceCounted(false)
                acquire(15 * 60 * 1000L)
            }
        } catch (error: Exception) {
            Log.w(TAG, "WakeLock could not be acquired", error)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (error: Exception) {
            Log.w(TAG, "WakeLock could not be released", error)
        }
        wakeLock = null
    }

    private fun startForegroundServiceCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID_RUNNING, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_RUNNING, "Space tasks", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress while a Hugging Face Space request is active"
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_COMPLETED, "Space task results", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a Space task finishes"
            }
        )
    }

    private fun buildOngoingNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID_RUNNING)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent(0))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateOngoingNotification(title: String, text: String) {
        notificationManager.notify(NOTIFICATION_ID_RUNNING, buildOngoingNotification(title, text))
    }

    private fun showCompletionNotification(title: String, text: String) {
        notificationManager.notify(
            NOTIFICATION_ID_COMPLETED,
            NotificationCompat.Builder(this, CHANNEL_ID_COMPLETED)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(openAppPendingIntent(1))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    private fun openAppPendingIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    override fun onDestroy() {
        activeSessionId?.let {
            ExecutionManager.failTask(it, applicationContext, "Background service stopped before completion")
        }
        activeSessionId = null
        serviceScope.cancel()
        releaseWakeLock()
        ExecutionManager.onServiceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
