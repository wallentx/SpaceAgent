package com.example.network

import android.util.Log
import com.example.model.HfSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HfApiClient(private val tokenProvider: () -> String?) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // Extended timeout to prevent drops
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(15, TimeUnit.SECONDS) // Keep-alive ping
        .build()

    companion object {
        private const val TAG = "HfApiClient"
        private const val HF_API_BASE = "https://huggingface.co/api"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Search public Hugging Face spaces using the official Hub API
     */
    suspend fun searchSpaces(query: String = "", limit: Int = 20): List<HfSpace> = withContext(Dispatchers.IO) {
        val url = if (query.isBlank()) {
            "$HF_API_BASE/spaces?limit=$limit&sort=likes&direction=-1"
        } else {
            "$HF_API_BASE/spaces?search=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit"
        }

        val requestBuilder = Request.Builder().url(url).get()
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
            requestBuilder.header("Authorization", "Bearer $token")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Search spaces failed code: ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = JSONArray(body)
                val results = mutableListOf<HfSpace>()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank() || !id.contains("/")) continue

                    val parts = id.split("/")
                    val author = parts[0]
                    val name = parts[1]
                    val likes = item.optInt("likes", 0)
                    val sdk = item.optString("sdk", "gradio")

                    // Categorize based on tags or name
                    val category = when {
                        id.contains("r1", ignoreCase = true) || id.contains("reason", ignoreCase = true) -> "Reasoning"
                        id.contains("code", ignoreCase = true) || id.contains("coder", ignoreCase = true) -> "Code"
                        id.contains("flux", ignoreCase = true) || id.contains("diffus", ignoreCase = true) || id.contains("image", ignoreCase = true) -> "Image"
                        id.contains("whisper", ignoreCase = true) || id.contains("audio", ignoreCase = true) -> "Audio"
                        id.contains("agent", ignoreCase = true) || id.contains("smol", ignoreCase = true) -> "Agent"
                        else -> "General"
                    }

                    results.add(
                        HfSpace(
                            id = id,
                            author = author,
                            name = name,
                            title = name.replace("-", " ").replace("_", " ").replaceFirstChar { it.uppercase() },
                            description = "Hugging Face Space by $author running on $sdk ($likes likes).",
                            sdk = sdk,
                            category = category,
                            likes = likes,
                            endpointUrl = HfSpace.toSanitizedSpaceEndpoint(id),
                            isFavorite = false,
                            isCurated = false,
                            demoPrompt = "Run an agent query on $name"
                        )
                    )
                }
                return@withContext results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching spaces from HF API", e)
            return@withContext emptyList()
        }
    }

    /**
     * Query a Gradio or direct REST space endpoint with keep-alive
     */
    suspend fun querySpaceEndpoint(
        spaceId: String,
        payloadData: List<Any>,
        apiName: String = "predict"
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseEndpoint = HfSpace.toSanitizedSpaceEndpoint(spaceId)
        val directUrl = "$baseEndpoint/api/$apiName"

        try {
            val jsonObject = JSONObject()
            val array = JSONArray()
            payloadData.forEach { array.put(it) }
            jsonObject.put("data", array)

            val requestBody = jsonObject.toString().toRequestBody(JSON_MEDIA)
            val requestBuilder = Request.Builder()
                .url(directUrl)
                .post(requestBody)

            tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
                requestBuilder.header("Authorization", "Bearer $token")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    return@withContext Result.success(bodyString)
                } else {
                    val errorMsg = "HTTP ${response.code}: ${response.message}"
                    Log.w(TAG, "Direct space query unsuccessful: $errorMsg")
                    return@withContext Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception querying space direct endpoint, will use agent synthesis fallback: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Curated list of top Spaces for out-of-the-box operation
     */
    fun getCuratedSpaces(): List<HfSpace> {
        return listOf(
            HfSpace(
                id = "deepseek-ai/DeepSeek-R1",
                author = "deepseek-ai",
                name = "DeepSeek-R1",
                title = "DeepSeek R1 Reasoning Space",
                description = "Open reasoning model with unprecedented chain-of-thought mathematical & logical problem solving.",
                sdk = "gradio",
                category = "Reasoning",
                likes = 18450,
                endpointUrl = "https://deepseek-ai-deepseek-r1.hf.space",
                isFavorite = true,
                isCurated = true,
                demoPrompt = "Solve this step-by-step: If 5 machines make 5 widgets in 5 minutes, how long do 100 machines need to make 100 widgets?"
            ),
            HfSpace(
                id = "Qwen/Qwen2.5-Coder-32B-Instruct",
                author = "Qwen",
                name = "Qwen2.5-Coder-32B",
                title = "Qwen2.5 Coder 32B Studio",
                description = "State-of-the-art code generation, debugging, algorithmic optimization, and full-stack architecture.",
                sdk = "gradio",
                category = "Code",
                likes = 12900,
                endpointUrl = "https://qwen-qwen2-5-coder-32b-instruct.hf.space",
                isFavorite = true,
                isCurated = true,
                demoPrompt = "Write an optimized Python script that monitors API latencies with exponential backoff and jitter."
            ),
            HfSpace(
                id = "black-forest-labs/FLUX.1-schnell",
                author = "black-forest-labs",
                name = "FLUX.1-schnell",
                title = "FLUX.1 High-Speed Diffusion",
                description = "Next-generation 12B parameter rectified flow transformer for photorealistic text-to-image synthesis.",
                sdk = "gradio",
                category = "Image",
                likes = 24300,
                endpointUrl = "https://black-forest-labs-flux-1-schnell.hf.space",
                isFavorite = true,
                isCurated = true,
                demoPrompt = "Cybernetic autonomous robotic explorer examining glowing biometric crystal flowers on neon exoplanet, 8k cinematic lighting"
            ),
            HfSpace(
                id = "smolagents/agents-market",
                author = "smolagents",
                name = "agents-market",
                title = "HuggingFace Smolagents Market",
                description = "Multi-tool autonomous agent runtime utilizing web search, code interpretation, and space calling.",
                sdk = "gradio",
                category = "Agent",
                likes = 6720,
                endpointUrl = "https://smolagents-agents-market.hf.space",
                isFavorite = true,
                isCurated = true,
                demoPrompt = "Find the latest research papers on speculative decoding and compile an executive summary."
            ),
            HfSpace(
                id = "meta-llama/Llama-3.2-3B-Instruct",
                author = "meta-llama",
                name = "Llama-3.2-3B-Instruct",
                title = "Llama 3.2 High-Efficiency Assistant",
                description = "Ultra-responsive lightweight model ideal for quick summaries, structured JSON extraction, and translation.",
                sdk = "gradio",
                category = "General",
                likes = 8900,
                endpointUrl = "https://meta-llama-llama-3-2-3b-instruct.hf.space",
                isFavorite = false,
                isCurated = true,
                demoPrompt = "Draft a succinct project proposal for an on-device local AI task runner."
            ),
            HfSpace(
                id = "openai/whisper-large-v3",
                author = "openai",
                name = "whisper-large-v3",
                title = "Whisper Large V3 Speech Transcriber",
                description = "Multilingual speech recognition, acoustic translation, and audio timestamping space.",
                sdk = "gradio",
                category = "Audio",
                likes = 9430,
                endpointUrl = "https://openai-whisper-large-v3.hf.space",
                isFavorite = false,
                isCurated = true,
                demoPrompt = "Transcribe and analyze speech patterns."
            )
        )
    }
}
