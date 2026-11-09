package com.example.agent

import android.content.Context
import com.example.model.AgentStep
import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.model.SpaceInputValue
import com.example.model.StepStatus
import com.example.network.HfApiClient
import org.json.JSONArray
import org.json.JSONObject

class SpaceAgentEngine private constructor(
    private val executeRequest: suspend (ExecutionRequest, (String) -> Unit) -> Result<String>
) {
    constructor(apiClient: HfApiClient) : this(
        executeRequest = { request, onLog ->
            apiClient.executeSpaceWithInputs(
                    context = request.context,
                    spaceId = request.spaceId,
                    apiName = request.apiName,
                    inputs = request.inputs,
                    onProgressLog = onLog
            )
        }
    )

    internal constructor(
        requestExecutor: suspend (ExecutionRequest, (String) -> Unit) -> Result<String>,
        @Suppress("UNUSED_PARAMETER") testing: Unit = Unit
    ) : this(requestExecutor)

    internal data class ExecutionRequest(
        val context: Context?,
        val goal: String,
        val spaceId: String,
        val apiName: String,
        val inputs: List<SpaceInputValue>
    )

    data class AgentResult(
        val finalOutput: String,
        val imageUrl: String? = null,
        val steps: List<AgentStep>,
        val executionTimeMs: Long
    )

    suspend fun executeAgentTask(
        context: Context? = null,
        goal: String,
        agentType: AgentType,
        space: HfSpace,
        apiName: String = "predict",
        inputs: List<SpaceInputValue> = emptyList(),
        onStepUpdate: (stepIndex: Int, step: AgentStep) -> Unit,
        onLog: (String) -> Unit
    ): AgentResult {
        require(apiName.isNotBlank()) { "A Space endpoint is required." }
        require(context != null || inputs.none { !it.isNull && it.inputType.isMediaOrFile && !it.fileUri.isNullOrBlank() }) {
            "A Context is required to upload selected files."
        }

        val startTime = System.currentTimeMillis()
        val steps = createInitialSteps(space, inputs)
        onLog("🚀 Starting ${agentType.displayName} request on ${space.id}")
        onLog("🔌 Target endpoint: /$apiName")

        updateStep(0, steps, StepStatus.RUNNING, "Validating request inputs...", onStepUpdate, onLog)
        inputs.forEachIndexed { index, input -> onLog("   • Input #${index + 1}: ${input.toDisplaySummary()}") }
        updateStep(0, steps, StepStatus.SUCCESS, "Request inputs are ready.", onStepUpdate, onLog)

        val mediaCount = inputs.count { it.inputType.isMediaOrFile && !it.fileUri.isNullOrBlank() }
        updateStep(
            1,
            steps,
            StepStatus.RUNNING,
            if (mediaCount > 0) "Preparing $mediaCount file upload(s)..." else "Preparing endpoint payload...",
            onStepUpdate,
            onLog
        )
        updateStep(1, steps, StepStatus.SUCCESS, "Payload contains ${inputs.size} argument(s).", onStepUpdate, onLog)

        updateStep(2, steps, StepStatus.RUNNING, "Waiting for /$apiName on ${space.id}...", onStepUpdate, onLog)
        onLog("📡 The foreground service will keep the request active while Android permits background execution.")
        val request = ExecutionRequest(context, goal, space.id, apiName, inputs)
        val rawResponse = try {
            executeRequest(request, onLog).getOrThrow()
        } catch (error: Throwable) {
            updateStep(2, steps, StepStatus.FAILED, error.message ?: "Space request failed.", onStepUpdate, onLog)
            throw error
        }
        check(rawResponse.isNotBlank()) { "Space returned an empty response." }
        updateStep(2, steps, StepStatus.SUCCESS, "Space returned a live response.", onStepUpdate, onLog)

        updateStep(3, steps, StepStatus.RUNNING, "Formatting the returned data...", onStepUpdate, onLog)
        val (finalOutput, imageUrl) = formatLiveOutput(rawResponse, space, apiName)
        updateStep(3, steps, StepStatus.SUCCESS, "Returned data is ready.", onStepUpdate, onLog)

        val duration = System.currentTimeMillis() - startTime
        onLog("✅ Space request completed in ${duration / 1000.0}s")
        return AgentResult(finalOutput, imageUrl, steps.toList(), duration)
    }

    private fun createInitialSteps(space: HfSpace, inputs: List<SpaceInputValue>): MutableList<AgentStep> {
        val mediaCount = inputs.count { it.inputType.isMediaOrFile && !it.fileUri.isNullOrBlank() }
        return mutableListOf(
            AgentStep(1, "Input Validation", "Checking prompt and selected inputs"),
            AgentStep(2, "Payload Preparation", if (mediaCount > 0) "Preparing $mediaCount attached file(s)" else "Preparing endpoint arguments"),
            AgentStep(3, "Space Execution", "Running /${space.name} on ${space.id}"),
            AgentStep(4, "Result Formatting", "Displaying returned text, data, and files")
        )
    }

    private fun updateStep(
        index: Int,
        steps: MutableList<AgentStep>,
        status: StepStatus,
        logMessage: String,
        onStepUpdate: (Int, AgentStep) -> Unit,
        onLog: (String) -> Unit
    ) {
        val current = steps[index]
        val updated = current.copy(status = status, logOutput = logMessage)
        steps[index] = updated
        onStepUpdate(index, updated)
        onLog("  [Step ${index + 1}/${steps.size}] ${current.title}: $logMessage")
    }

    private fun formatLiveOutput(rawResponse: String, space: HfSpace, apiName: String): Pair<String, String?> {
        val root = try {
            JSONObject(rawResponse)
        } catch (_: Exception) {
            return rawResponse to null
        }
        val data = root.optJSONArray("data")
            ?: return "```json\n${root.toString(2)}\n```" to findFirstMediaUrl(root, space)
        if (data.length() == 0) return "```json\n${root.toString(2)}\n```" to null

        var firstMediaUrl: String? = null
        val output = buildString {
            appendLine("### Output from `${space.id}` (`/$apiName`)")
            appendLine()
            for (index in 0 until data.length()) {
                val item = data.opt(index)
                val mediaUrl = findFirstMediaUrl(item, space)
                if (firstMediaUrl == null) firstMediaUrl = mediaUrl
                when (item) {
                    is JSONObject -> if (mediaUrl != null) {
                        val name = item.optString("orig_name").ifBlank { item.optString("path") }
                        appendLine("- **Output file${if (name.isBlank()) "" else " (`$name`)"}:** $mediaUrl")
                    } else {
                        appendLine("```json")
                        appendLine(item.toString(2))
                        appendLine("```")
                    }
                    is JSONArray -> {
                        appendLine("```json")
                        appendLine(item.toString(2))
                        appendLine("```")
                    }
                    JSONObject.NULL, null -> appendLine("null")
                    else -> appendLine(item.toString())
                }
            }
        }.trim()
        return output to firstMediaUrl
    }

    private fun findFirstMediaUrl(value: Any?, space: HfSpace): String? = when (value) {
        is JSONObject -> value.optString("url").takeIf { it.isNotBlank() }
            ?: value.optString("path").takeIf { it.isNotBlank() }?.let { "${space.directEmbedUrl}/gradio_api/file=$it" }
            ?: value.keys().asSequence().mapNotNull { findFirstMediaUrl(value.opt(it), space) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findFirstMediaUrl(value.opt(it), space) }.firstOrNull()
        else -> null
    }
}
