package com.example.model

enum class AgentType(val displayName: String, val iconEmoji: String, val defaultSpaceId: String) {
    REASONING(
        displayName = "Deep Reasoning",
        iconEmoji = "🧠",
        defaultSpaceId = "deepseek-ai/DeepSeek-R1"
    ),
    CODER(
        displayName = "Code Engineer",
        iconEmoji = "💻",
        defaultSpaceId = "Qwen/Qwen2.5-Coder-32B-Instruct"
    ),
    IMAGE_STUDIO(
        displayName = "Diffusion Studio",
        iconEmoji = "🎨",
        defaultSpaceId = "black-forest-labs/FLUX.1-schnell"
    ),
    GENERAL(
        displayName = "Autonomous Agent",
        iconEmoji = "🤖",
        defaultSpaceId = "smolagents/agents-market"
    )
}

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}

data class AgentStep(
    val stepNumber: Int,
    val title: String,
    val description: String,
    val status: StepStatus = StepStatus.PENDING,
    val logOutput: String = "",
    val durationMs: Long = 0L
)

enum class SessionStatus {
    IDLE,
    PLANNING,
    RUNNING,
    EXECUTING_SPACE,
    ANALYZING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class HfSpace(
    val id: String,
    val author: String,
    val name: String,
    val title: String,
    val description: String,
    val sdk: String,
    val category: String,
    val likes: Int,
    val endpointUrl: String,
    val isFavorite: Boolean = false,
    val isCurated: Boolean = false,
    val demoPrompt: String = ""
) {
    val hfWebUrl: String
        get() = "https://huggingface.co/spaces/$id"

    val directEmbedUrl: String
        get() = toSanitizedSpaceEndpoint(id)

    companion object {
        /**
         * Converts a Hugging Face Space ID (e.g. "black-forest-labs/FLUX.1-schnell")
         * into a valid single-level subdomain for *.hf.space wildcard SSL certificates
         * by replacing all non-alphanumeric characters (including dots and slashes) with hyphens.
         */
        fun toSanitizedSpaceEndpoint(spaceId: String): String {
            val sanitized = spaceId
                .replace(Regex("[^a-zA-Z0-9]+"), "-")
                .trim('-')
                .lowercase()
            return "https://$sanitized.hf.space"
        }
    }
}
