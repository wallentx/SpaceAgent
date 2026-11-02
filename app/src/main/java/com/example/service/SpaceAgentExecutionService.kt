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
import com.example.model.AgentStep
import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.network.HfApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SpaceAgentExecutionService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val TAG = "SpaceAgentService"
        const val CHANNEL_ID_RUNNING = "space_agent_running_channel"
        const val CHANNEL_ID_COMPLETED = "space_agent_completed_channel"
        const val NOTIFICATION_ID_RUNNING = 1001
        const val NOTIFICATION_ID_COMPLETED = 1002

        const val ACTION_START_AGENT_TASK = "com.example.action.START_AGENT_TASK"
        const val ACTION_START_KEEP_ALIVE = "com.example.action.START_KEEP_ALIVE"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_GOAL = "extra_goal"
        const val EXTRA_AGENT_TYPE = "extra_agent_type"
        const val EXTRA_SPACE_ID = "extra_space_id"
        const val EXTRA_SPACE_NAME = "extra_space_name"
        const val EXTRA_SPACE_URL = "extra_space_url"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SpaceAgent:ExecutionLock"
            ).apply {
                setReferenceCounted(false)
                acquire(15 * 60 * 1000L) // Safe 15 min lock
            }
            Log.d(TAG, "WakeLock acquired for background space execution resilience")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock could not be acquired: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START_AGENT_TASK -> {
                val goal = intent.getStringExtra(EXTRA_GOAL) ?: "Hugging Face Space Task"
                val agentTypeName = intent.getStringExtra(EXTRA_AGENT_TYPE) ?: AgentType.GENERAL.name
                val spaceId = intent.getStringExtra(EXTRA_SPACE_ID) ?: "deepseek-ai/DeepSeek-R1"
                val spaceName = intent.getStringExtra(EXTRA_SPACE_NAME) ?: "HF Space"
                val spaceUrl = intent.getStringExtra(EXTRA_SPACE_URL) ?: "https://huggingface.co/spaces/$spaceId"

                val agentType = try {
                    AgentType.valueOf(agentTypeName)
                } catch (_: Exception) {
                    AgentType.GENERAL
                }

                val space = HfSpace(
                    id = spaceId,
                    author = spaceId.substringBefore("/"),
                    name = spaceId.substringAfter("/"),
                    title = spaceName,
                    description = "",
                    sdk = "gradio",
                    category = "General",
                    likes = 0,
                    endpointUrl = spaceUrl
                )

                startForegroundServiceCompat(
                    NOTIFICATION_ID_RUNNING,
                    buildOngoingNotification("Agent Mode: Initializing $spaceName...", "Preparing execution plan")
                )

                runAgentTask(goal, agentType, space)
            }

            ACTION_START_KEEP_ALIVE -> {
                val spaceTitle = intent.getStringExtra(EXTRA_SPACE_NAME) ?: "Interactive Space"
                startForegroundServiceCompat(
                    NOTIFICATION_ID_RUNNING,
                    buildOngoingNotification(
                        "Space Active: $spaceTitle",
                        "Background keep-alive active — will not time out when switched away"
                    )
                )
            }

            ACTION_STOP_SERVICE -> {
                stopForegroundAndSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceCompat(notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun runAgentTask(goal: String, agentType: AgentType, space: HfSpace) {
        serviceScope.launch {
            val sharedPrefs = getSharedPreferences("space_agent_prefs", Context.MODE_PRIVATE)
            val token = sharedPrefs.getString("hf_api_token", null)

            val apiClient = HfApiClient { token }
            val engine = SpaceAgentEngine(apiClient)

            try {
                val result = engine.executeAgentTask(
                    goal = goal,
                    agentType = agentType,
                    space = space,
                    onStepUpdate = { stepIndex, step ->
                        ExecutionManager.updateSteps(
                            ExecutionManager.currentSteps.value.toMutableList().apply {
                                val idx = indexOfFirst { it.stepNumber == step.stepNumber }
                                if (idx != -1) set(idx, step) else add(step)
                            }
                        )
                        updateOngoingNotification(
                            "Step ${step.stepNumber}/4: ${step.title}",
                            "${space.title} — ${step.description}"
                        )
                    },
                    onLog = { logMsg ->
                        ExecutionManager.addLog(logMsg)
                    }
                )

                ExecutionManager.finishTask(
                    context = applicationContext,
                    finalOutput = result.finalOutput,
                    imageUrl = result.imageUrl,
                    steps = result.steps,
                    durationMs = result.executionTimeMs
                )

                // Show high-priority completion notification so user knows result is ready
                showCompletionNotification(
                    title = "🎉 Task Completed on ${space.name}!",
                    text = "Goal finished: ${goal.take(50)}... Tap to view results."
                )

            } catch (e: Exception) {
                Log.e(TAG, "Agent task failed in service", e)
                ExecutionManager.failTask(applicationContext, e.localizedMessage ?: "Unknown error")
                showCompletionNotification(
                    title = "❌ Space Task Notice",
                    text = "Task finished with status: ${e.message}"
                )
            } finally {
                stopForegroundAndSelf()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val runningChannel = NotificationChannel(
                CHANNEL_ID_RUNNING,
                "Space Agent Background Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live progress when Hugging Face Spaces are running in agent mode"
                setShowBadge(false)
            }

            val completedChannel = NotificationChannel(
                CHANNEL_ID_COMPLETED,
                "Space Agent Completed Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts you when background Space tasks finish execution"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(runningChannel)
            notificationManager.createNotificationChannel(completedChannel)
        }
    }

    private fun buildOngoingNotification(title: String, text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_RUNNING)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true) // Indeterminate animation
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateOngoingNotification(title: String, text: String) {
        val notification = buildOngoingNotification(title, text)
        notificationManager.notify(NOTIFICATION_ID_RUNNING, notification)
    }

    private fun showCompletionNotification(title: String, text: String) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETED)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETED, notification)
    }

    private fun stopForegroundAndSelf() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        Log.d(TAG, "SpaceAgentExecutionService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
