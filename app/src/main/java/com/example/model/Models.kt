package com.example.model

import org.json.JSONArray
import org.json.JSONObject

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

enum class SpaceSortOption(val apiSortKey: String, val displayName: String, val badgeEmoji: String) {
    TRENDING("trendingScore", "Trending", "🔥"),
    LIKES("likes", "Most Likes", "❤️"),
    RECENTLY_UPDATED("lastModified", "Recently Updated", "🕒"),
    RECENTLY_CREATED("createdAt", "Recently Created", "✨")
}

enum class SpaceCategoryFilter(
    val id: String,
    val displayName: String,
    val emoji: String,
    val searchKeywords: String
) {
    ALL("all", "All Spaces", "🌐", ""),
    IMAGE_GEN("image-generation", "Image Generation", "🎨", "flux image generation text-to-image"),
    VIDEO_GEN("video-generation", "Video Generation", "🎬", "video generation text-to-video"),
    TEXT_GEN("text-generation", "Text Generation", "📝", "chat llm text generation"),
    AUDIO_SPEECH("audio", "Audio & Speech", "🎙️", "tts audio speech whisper"),
    IMAGE_EDIT("image-editing", "Image Editing", "🪄", "image editing upscale background"),
    MODEL_3D("3d", "3D Modeling", "🧊", "3d mesh trellis"),
    AGENTS("agents", "Agents & Reasoning", "🧠", "agent reasoning deepseek"),
    CODE("code", "Code & Dev", "💻", "coder code qwen")
}

enum class SpaceSdkFilter(val sdkKey: String, val displayName: String) {
    ALL("", "All SDKs"),
    GRADIO("gradio", "Gradio"),
    STREAMLIT("streamlit", "Streamlit"),
    DOCKER("docker", "Docker"),
    STATIC("static", "Static")
}

enum class SpaceRuntimeFilter(val displayName: String) {
    ALL("All Status"),
    RUNNING_ONLY("🟢 Running"),
    ZERO_GPU("⚡ ZeroGPU / GPU"),
    SAVED("★ Saved")
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

enum class SpaceInputType(val displayName: String, val iconEmoji: String) {
    TEXT("Text / Prompt", "📝"),
    NUMBER("Number", "🔢"),
    SLIDER("Slider Range", "🎚️"),
    BOOLEAN("Checkbox / Toggle", "☑️"),
    DROPDOWN("Choice / Dropdown", "📋"),
    IMAGE("Image Upload", "🖼️"),
    VIDEO("Video Upload", "🎬"),
    AUDIO("Audio Upload", "🎵"),
    FILE("File Upload", "📎");

    val isMediaOrFile: Boolean
        get() = this == IMAGE || this == VIDEO || this == AUDIO || this == FILE
}

data class SpaceParameterSpec(
    val index: Int,
    val name: String,
    val label: String,
    val componentName: String,
    val inputType: SpaceInputType,
    val isRequired: Boolean = true,
    val defaultValue: String = "",
    val min: Float = 0f,
    val max: Float = 100f,
    val step: Float = 1f,
    val choices: List<String> = emptyList(),
    val description: String = ""
)

data class SpaceOutputSpec(
    val label: String,
    val componentName: String
)

data class SpaceEndpointSpec(
    val apiName: String,
    val parameters: List<SpaceParameterSpec>,
    val outputs: List<SpaceOutputSpec> = emptyList()
)

data class SpaceSchema(
    val spaceId: String,
    val endpoints: List<SpaceEndpointSpec>,
    val isLiveFetched: Boolean = true,
    val schemaSourceNote: String = "Synced 1:1 from Space Gradio API"
)

data class SpaceInputValue(
    val paramIndex: Int,
    val paramName: String,
    val paramLabel: String,
    val inputType: SpaceInputType,
    val textValue: String = "",
    val numberValue: Float = 0f,
    val booleanValue: Boolean = false,
    val fileUri: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0L,
    val mimeType: String? = null
) {
    fun toDisplaySummary(): String {
        return when (inputType) {
            SpaceInputType.IMAGE, SpaceInputType.VIDEO, SpaceInputType.AUDIO, SpaceInputType.FILE -> {
                if (!fileName.isNullOrBlank()) {
                    "${inputType.iconEmoji} $paramLabel: $fileName (${formatBytes(fileSizeBytes)})"
                } else {
                    "${inputType.iconEmoji} $paramLabel: [No file selected]"
                }
            }
            SpaceInputType.SLIDER, SpaceInputType.NUMBER -> {
                val formatted = if (numberValue % 1f == 0f) numberValue.toInt().toString() else String.format("%.2f", numberValue)
                "🎚️ $paramLabel: $formatted"
            }
            SpaceInputType.BOOLEAN -> "☑️ $paramLabel: $booleanValue"
            SpaceInputType.DROPDOWN -> "📋 $paramLabel: $textValue"
            SpaceInputType.TEXT -> "📝 $paramLabel: ${textValue.ifBlank { "(empty)" }}"
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("paramIndex", paramIndex)
            put("paramName", paramName)
            put("paramLabel", paramLabel)
            put("inputType", inputType.name)
            put("textValue", textValue)
            put("numberValue", numberValue.toDouble())
            put("booleanValue", booleanValue)
            put("fileUri", fileUri ?: "")
            put("fileName", fileName ?: "")
            put("fileSizeBytes", fileSizeBytes)
            put("mimeType", mimeType ?: "")
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): SpaceInputValue {
            val typeStr = obj.optString("inputType", SpaceInputType.TEXT.name)
            val inputType = try {
                SpaceInputType.valueOf(typeStr)
            } catch (_: Exception) {
                SpaceInputType.TEXT
            }
            return SpaceInputValue(
                paramIndex = obj.optInt("paramIndex", 0),
                paramName = obj.optString("paramName", "input"),
                paramLabel = obj.optString("paramLabel", "Input"),
                inputType = inputType,
                textValue = obj.optString("textValue", ""),
                numberValue = obj.optDouble("numberValue", 0.0).toFloat(),
                booleanValue = obj.optBoolean("booleanValue", false),
                fileUri = obj.optString("fileUri", "").takeIf { it.isNotBlank() },
                fileName = obj.optString("fileName", "").takeIf { it.isNotBlank() },
                fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                mimeType = obj.optString("mimeType", "").takeIf { it.isNotBlank() }
            )
        }

        fun listToJsonString(inputs: List<SpaceInputValue>): String {
            val arr = JSONArray()
            inputs.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJsonString(jsonStr: String?): List<SpaceInputValue> {
            if (jsonStr.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<SpaceInputValue>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(fromJson(obj))
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format("%.2f MB", mb)
        }
    }
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
    val demoPrompt: String = "",
    val emoji: String = "🚀",
    val runtimeStage: String = "RUNNING",
    val hardwareTier: String = "cpu-basic",
    val trendingScore: Int = 0,
    val lastModified: String = "",
    val isZeroGpu: Boolean = false
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
