package com.example.network

import android.util.Log
import com.example.model.HfSpace
import com.example.model.SpaceCategoryFilter
import com.example.model.SpaceRuntimeFilter
import com.example.model.SpaceSdkFilter
import com.example.model.SpaceSortOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HfApiClient(private val tokenProvider: () -> String?) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
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
     * Fetch live Hugging Face Spaces directly from https://huggingface.co/api/spaces
     * supporting search query, category filter, SDK filter, runtime filter, and sort order.
     */
    suspend fun fetchLiveSpaces(
        query: String = "",
        categoryFilter: SpaceCategoryFilter = SpaceCategoryFilter.ALL,
        sdkFilter: SpaceSdkFilter = SpaceSdkFilter.ALL,
        runtimeFilter: SpaceRuntimeFilter = SpaceRuntimeFilter.ALL,
        sortOption: SpaceSortOption = SpaceSortOption.TRENDING,
        favoriteIds: Set<String> = emptySet(),
        limit: Int = 50
    ): Result<List<HfSpace>> = withContext(Dispatchers.IO) {
        val effectiveSearch = buildString {
            if (query.isNotBlank()) {
                append(query.trim())
            } else if (categoryFilter != SpaceCategoryFilter.ALL) {
                append(categoryFilter.id)
            }
        }

        val urlBuilder = StringBuilder("$HF_API_BASE/spaces?full=true&limit=$limit&sort=${sortOption.apiSortKey}&direction=-1")
        if (effectiveSearch.isNotBlank()) {
            urlBuilder.append("&search=").append(URLEncoder.encode(effectiveSearch, "UTF-8"))
        }
        if (sdkFilter != SpaceSdkFilter.ALL) {
            urlBuilder.append("&filter=").append(URLEncoder.encode(sdkFilter.sdkKey, "UTF-8"))
        }

        val requestBuilder = Request.Builder().url(urlBuilder.toString()).get()
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
            requestBuilder.header("Authorization", "Bearer $token")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "HF Hub HTTP ${response.code}"
                    Log.w(TAG, "Fetch live spaces failed: $msg")
                    return@withContext Result.failure(Exception(msg))
                }
                val body = response.body?.string() ?: return@withContext Result.success(emptyList())
                val jsonArray = JSONArray(body)
                val results = mutableListOf<HfSpace>()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val space = parseSpaceJson(item, favoriteIds) ?: continue

                    // Apply SDK filter if API returned mixed items
                    if (sdkFilter != SpaceSdkFilter.ALL && !space.sdk.equals(sdkFilter.sdkKey, ignoreCase = true)) {
                        continue
                    }

                    // Apply Runtime/Hardware filter
                    when (runtimeFilter) {
                        SpaceRuntimeFilter.RUNNING_ONLY -> {
                            if (!space.runtimeStage.contains("RUNNING", ignoreCase = true)) continue
                        }
                        SpaceRuntimeFilter.ZERO_GPU -> {
                            if (!space.isZeroGpu && !space.hardwareTier.contains("gpu", ignoreCase = true) && !space.hardwareTier.contains("a10", ignoreCase = true)) {
                                continue
                            }
                        }
                        SpaceRuntimeFilter.SAVED -> {
                            if (!space.isFavorite) continue
                        }
                        SpaceRuntimeFilter.ALL -> {}
                    }

                    results.add(space)
                }
                return@withContext Result.success(results)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching live spaces from HF API", e)
            return@withContext Result.failure(e)
        }
    }

    private fun parseSpaceJson(item: JSONObject, favoriteIds: Set<String>): HfSpace? {
        val id = item.optString("id")
        if (id.isBlank() || !id.contains("/")) return null

        val parts = id.split("/")
        val author = item.optString("author").ifBlank { parts[0] }
        val name = parts.getOrElse(1) { id }
        val likes = item.optInt("likes", 0)
        val trendingScore = item.optInt("trendingScore", 0)
        val lastModified = item.optString("lastModified", "")

        val cardData = item.optJSONObject("cardData")
        val sdk = item.optString("sdk")
            .ifBlank { cardData?.optString("sdk") ?: "gradio" }

        val rawTitle = cardData?.optString("title")?.takeIf { it.isNotBlank() }
            ?: name.replace("-", " ").replace("_", " ").replaceFirstChar { it.uppercase() }

        val emoji = cardData?.optString("emoji")?.takeIf { it.isNotBlank() } ?: "🚀"
        val shortDesc = cardData?.optString("short_description")?.takeIf { it.isNotBlank() }

        // Parse tags
        val tagsList = mutableListOf<String>()
        val tagsArray = item.optJSONArray("tags")
        if (tagsArray != null) {
            for (t in 0 until tagsArray.length()) {
                val tag = tagsArray.optString(t)
                if (tag.isNotBlank()) tagsList.add(tag)
            }
        }

        val isZeroGpu = tagsList.any { it.equals("ZeroGPU", ignoreCase = true) }

        // Parse runtime info if available
        val runtimeObj = item.optJSONObject("runtime")
        val runtimeStage = runtimeObj?.optString("stage")?.takeIf { it.isNotBlank() } ?: "RUNNING"
        val hardwareObj = runtimeObj?.optJSONObject("hardware")
        val hardwareTier = hardwareObj?.optString("current")?.takeIf { it.isNotBlank() }
            ?: hardwareObj?.optString("requested")?.takeIf { it.isNotBlank() }
            ?: if (isZeroGpu) "zero-a10g" else "cpu-basic"

        val combinedText = "$id $rawTitle ${shortDesc ?: ""} ${tagsList.joinToString(" ")}".lowercase()
        val category = when {
            combinedText.contains("video") || combinedText.contains("wan") || combinedText.contains("sora") -> "Video Generation"
            combinedText.contains("edit") || combinedText.contains("upscal") || combinedText.contains("inpaint") || combinedText.contains("remove") -> "Image Editing"
            combinedText.contains("flux") || combinedText.contains("diffus") || combinedText.contains("image") || combinedText.contains("sdxl") -> "Image Generation"
            combinedText.contains("3d") || combinedText.contains("mesh") || combinedText.contains("trellis") -> "3D Modeling"
            combinedText.contains("whisper") || combinedText.contains("audio") || combinedText.contains("tts") || combinedText.contains("speech") || combinedText.contains("voice") -> "Audio & Speech"
            combinedText.contains("code") || combinedText.contains("coder") || combinedText.contains("qwen2.5-coder") -> "Code & Dev"
            combinedText.contains("r1") || combinedText.contains("reason") || combinedText.contains("agent") || combinedText.contains("smol") -> "Agents & Reasoning"
            else -> "Text Generation"
        }

        val description = shortDesc
            ?: "Live $sdk Space by $author • ${if (isZeroGpu) "ZeroGPU accelerated" else hardwareTier}"

        return HfSpace(
            id = id,
            author = author,
            name = name,
            title = rawTitle,
            description = description,
            sdk = sdk,
            category = category,
            likes = likes,
            endpointUrl = HfSpace.toSanitizedSpaceEndpoint(id),
            isFavorite = favoriteIds.contains(id),
            isCurated = false,
            demoPrompt = "Run an autonomous agent task on $rawTitle ($id)",
            emoji = emoji,
            runtimeStage = runtimeStage,
            hardwareTier = hardwareTier,
            trendingScore = trendingScore,
            lastModified = lastModified,
            isZeroGpu = isZeroGpu
        )
    }

    /**
     * Legacy helper delegating to fetchLiveSpaces
     */
    suspend fun searchSpaces(query: String = "", limit: Int = 30): List<HfSpace> {
        return fetchLiveSpaces(query = query, limit = limit).getOrDefault(emptyList())
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
     * Fallback curated list if device is completely offline
     */
    fun getCuratedSpaces(): List<HfSpace> {
        return listOf(
            HfSpace(
                id = "deepseek-ai/DeepSeek-R1",
                author = "deepseek-ai",
                name = "DeepSeek-R1",
                title = "DeepSeek R1 Reasoning",
                description = "Open reasoning model with chain-of-thought mathematical & logical problem solving.",
                sdk = "gradio",
                category = "Agents & Reasoning",
                likes = 18450,
                endpointUrl = HfSpace.toSanitizedSpaceEndpoint("deepseek-ai/DeepSeek-R1"),
                isFavorite = true,
                isCurated = true,
                demoPrompt = "Solve step-by-step: If 5 machines make 5 widgets in 5 minutes, how long do 100 machines need to make 100 widgets?",
                emoji = "🧠",
                runtimeStage = "RUNNING",
                hardwareTier = "zero-a10g",
                trendingScore = 95,
                isZeroGpu = true
            ),
            HfSpace(
                id = "black-forest-labs/FLUX.1-schnell",
                author = "black-forest-labs",
                name = "FLUX.1-schnell",
                title = "FLUX.1 [schnell]",
                description = "12B parameter rectified flow transformer for fast photorealistic text-to-image generation.",
                sdk = "gradio",
                category = "Image Generation",
                likes = 24300,
                endpointUrl = HfSpace.toSanitizedSpaceEndpoint("black-forest-labs/FLUX.1-schnell"),
                isFavorite = true,
                isCurated = true,
                demoPrompt = "Cybernetic autonomous robotic explorer examining glowing crystal flowers on neon exoplanet, 8k",
                emoji = "⚡",
                runtimeStage = "RUNNING",
                hardwareTier = "zero-a10g",
                trendingScore = 120,
                isZeroGpu = true
            ),
            HfSpace(
                id = "Qwen/Qwen2.5-Coder-32B-Instruct",
                author = "Qwen",
                name = "Qwen2.5-Coder-32B-Instruct",
                title = "Qwen2.5 Coder 32B Studio",
                description = "Code generation, debugging, algorithmic optimization, and full-stack architecture.",
                sdk = "gradio",
                category = "Code & Dev",
                likes = 12900,
                endpointUrl = HfSpace.toSanitizedSpaceEndpoint("Qwen/Qwen2.5-Coder-32B-Instruct"),
                isFavorite = true,
                isCurated = true,
                demoPrompt = "Write an optimized Python script that monitors API latencies with exponential backoff and jitter.",
                emoji = "💻",
                runtimeStage = "RUNNING",
                hardwareTier = "zero-a10g",
                trendingScore = 88,
                isZeroGpu = true
            ),
            HfSpace(
                id = "smolagents/agents-market",
                author = "smolagents",
                name = "agents-market",
                title = "Smolagents Multi-Tool Hub",
                description = "Multi-tool autonomous agent runtime utilizing web search, code interpretation, and space calling.",
                sdk = "gradio",
                category = "Agents & Reasoning",
                likes = 6720,
                endpointUrl = HfSpace.toSanitizedSpaceEndpoint("smolagents/agents-market"),
                isFavorite = true,
                isCurated = true,
                demoPrompt = "Find the latest research papers on speculative decoding and compile an executive summary.",
                emoji = "🤖",
                runtimeStage = "RUNNING",
                hardwareTier = "cpu-upgrade",
                trendingScore = 64,
                isZeroGpu = false
            )
        )
    }
}
