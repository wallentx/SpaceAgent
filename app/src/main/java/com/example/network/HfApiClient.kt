package com.example.network

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.model.HfSpace
import com.example.model.SpaceCategoryFilter
import com.example.model.SpaceEndpointSpec
import com.example.model.SpaceInputType
import com.example.model.SpaceInputValue
import com.example.model.SpaceOutputSpec
import com.example.model.SpaceParameterSpec
import com.example.model.SpaceRuntimeFilter
import com.example.model.SpaceSchema
import com.example.model.SpaceSdkFilter
import com.example.model.SpaceSortOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HfApiClient(
    private val client: OkHttpClient = createDefaultClient(),
    private val hubApiBase: String = HF_API_BASE,
    private val spaceEndpointResolver: (String) -> String = { HfSpace.toSanitizedSpaceEndpoint(it) },
    private val tokenProvider: () -> String?
) {

    companion object {
        private const val TAG = "HfApiClient"
        private const val HF_API_BASE = "https://huggingface.co/api"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun createDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
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
        val effectiveSearch = buildSearchQuery(query, categoryFilter)

        val urlBuilder = StringBuilder("$hubApiBase/spaces?full=true&limit=$limit&sort=${sortOption.apiSortKey}&direction=-1")
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
            executeRequest(requestBuilder.build()).use { response ->
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

                    if (sdkFilter != SpaceSdkFilter.ALL && !space.sdk.equals(sdkFilter.sdkKey, ignoreCase = true)) {
                        continue
                    }

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching live spaces from HF API", e)
            return@withContext Result.failure(e)
        }
    }

    internal fun buildSearchQuery(query: String, categoryFilter: SpaceCategoryFilter): String =
        listOfNotNull(
            query.trim().takeIf { it.isNotEmpty() },
            categoryFilter.id.takeIf { categoryFilter != SpaceCategoryFilter.ALL }
        ).joinToString(" ")

    /**
     * Fetches the 1:1 input/output component schema for a Hugging Face Space
     * by inspecting /gradio_api/info, /info, and /config on the Space host.
     */
    suspend fun fetchSpaceSchema(space: HfSpace): SpaceSchema = withContext(Dispatchers.IO) {
        val baseHost = spaceEndpointResolver(space.id).trimEnd('/')
        val candidateUrls = listOf(
            "$baseHost/gradio_api/info",
            "$baseHost/info",
            "$baseHost/config"
        )

        for (url in candidateUrls) {
            try {
                val reqBuilder = Request.Builder().url(url).get()
                tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                executeRequest(reqBuilder.build()).use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            val json = JSONObject(body)
                            if (url.endsWith("/info")) {
                                val endpoints = parseGradioInfoJson(json)
                                if (endpoints.isNotEmpty()) {
                                    return@withContext SpaceSchema(
                                        spaceId = space.id,
                                        endpoints = endpoints,
                                        isLiveFetched = true,
                                        schemaSourceNote = "1:1 Live Gradio Schema (${endpoints.size} endpoint${if (endpoints.size == 1) "" else "s"})"
                                    )
                                }
                            } else if (url.endsWith("/config")) {
                                val endpoints = parseGradioConfigJson(json)
                                if (endpoints.isNotEmpty()) {
                                    return@withContext SpaceSchema(
                                        spaceId = space.id,
                                        endpoints = endpoints,
                                        isLiveFetched = true,
                                        schemaSourceNote = "1:1 Live Space Config (${endpoints.size} endpoint${if (endpoints.size == 1) "" else "s"})"
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Schema probe failed for $url: ${e.message}")
            }
        }

        return@withContext unavailableSchema(space)
    }

    internal fun parseGradioInfoJson(root: JSONObject): List<SpaceEndpointSpec> {
        val named = root.optJSONObject("named_endpoints") ?: return emptyList()
        val endpoints = mutableListOf<SpaceEndpointSpec>()
        val keys = named.keys()

        while (keys.hasNext()) {
            val rawKey = keys.next()
            val epObj = named.optJSONObject(rawKey) ?: continue
            val paramsArr = epObj.optJSONArray("parameters") ?: continue
            val params = mutableListOf<SpaceParameterSpec>()
            for (i in 0 until paramsArr.length()) {
                val pObj = paramsArr.optJSONObject(i) ?: continue
                val label = pObj.optString("label").ifBlank {
                    pObj.optString("parameter_name").ifBlank { "Input ${i + 1}" }
                }
                val paramName = pObj.optString("parameter_name").ifBlank { "param_$i" }
                val component = pObj.optString("component").ifBlank { "Textbox" }
                val hasDefault = pObj.optBoolean("parameter_has_default", false)
                val defaultRaw = pObj.opt("parameter_default")
                val defaultStr = if (defaultRaw != null && defaultRaw != JSONObject.NULL) defaultRaw.toString() else ""

                val typeObj = pObj.optJSONObject("type")
                val pythonTypeObj = pObj.optJSONObject("python_type")
                val desc = typeObj?.optString("description") ?: pythonTypeObj?.optString("description") ?: ""

                // Extract choices if enum or Literal
                val choices = mutableListOf<String>()
                val enumArr = typeObj?.optJSONArray("enum")
                if (enumArr != null) {
                    for (c in 0 until enumArr.length()) {
                        choices.add(enumArr.optString(c))
                    }
                } else {
                    val pyType = pythonTypeObj?.optString("type") ?: ""
                    if (pyType.startsWith("Literal[")) {
                        val inner = pyType.removePrefix("Literal[").removeSuffix("]")
                        inner.split(",").map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }.forEach {
                            choices.add(it)
                        }
                    }
                }

                // Extract min/max for sliders/numbers
                var minVal = typeObj?.nullableFloat("minimum")
                var maxVal = typeObj?.nullableFloat("maximum")
                if (minVal == null || maxVal == null) {
                    val pyDesc = pythonTypeObj?.optString("description") ?: ""
                    val rangeRegex = Regex("between\\s+(-?[0-9.]+)\\s+and\\s+(-?[0-9.]+)", RegexOption.IGNORE_CASE)
                    val match = rangeRegex.find(pyDesc)
                    if (match != null) {
                        if (minVal == null) minVal = match.groupValues[1].toFloatOrNull()
                        if (maxVal == null) maxVal = match.groupValues[2].toFloatOrNull()
                    }
                }
                val stepVal = typeObj?.nullableFloat("multipleOf")

                val inputType = classifyInputType(
                    component = component,
                    label = label,
                    paramName = paramName,
                    jsonType = typeObj?.optString("type") ?: "",
                    pythonType = pythonTypeObj?.optString("type") ?: "",
                    choices = choices
                )

                params.add(
                    SpaceParameterSpec(
                        index = i,
                        name = paramName,
                        label = label,
                        componentName = component,
                        inputType = inputType,
                        isRequired = !hasDefault,
                        defaultValue = defaultStr,
                        defaultIsNull = hasDefault && (defaultRaw == null || defaultRaw == JSONObject.NULL),
                        min = minVal,
                        max = maxVal,
                        step = stepVal,
                        choices = choices,
                        description = desc
                    )
                )
            }

            val returnsArr = epObj.optJSONArray("returns")
            val outputs = mutableListOf<SpaceOutputSpec>()
            if (returnsArr != null) {
                for (r in 0 until returnsArr.length()) {
                    val rObj = returnsArr.optJSONObject(r) ?: continue
                    outputs.add(
                        SpaceOutputSpec(
                            label = rObj.optString("label", "Output ${r + 1}"),
                            componentName = rObj.optString("component", "Textbox")
                        )
                    )
                }
            }

            val cleanApiName = rawKey.trimStart('/')
            if (cleanApiName.isBlank()) continue
            endpoints.add(
                SpaceEndpointSpec(
                    apiName = cleanApiName,
                    parameters = params,
                    outputs = outputs
                )
            )
        }

        return endpoints
    }

    internal fun parseGradioConfigJson(root: JSONObject): List<SpaceEndpointSpec> {
        val componentsArr = root.optJSONArray("components") ?: return emptyList()
        val dependenciesArr = root.optJSONArray("dependencies") ?: return emptyList()

        val compMap = mutableMapOf<Int, JSONObject>()
        for (i in 0 until componentsArr.length()) {
            val c = componentsArr.optJSONObject(i) ?: continue
            val id = c.optInt("id", -1)
            if (id != -1) compMap[id] = c
        }

        val endpoints = mutableListOf<SpaceEndpointSpec>()
        for (d in 0 until dependenciesArr.length()) {
            val dep = dependenciesArr.optJSONObject(d) ?: continue
            val inputsArr = dep.optJSONArray("inputs") ?: continue
            val apiName = dep.optString("api_name")
                .takeIf { it.isNotBlank() && it != "null" && it != "false" }
                ?: continue

            // Skip purely internal state/UI toggle callbacks with no real user inputs
            val params = mutableListOf<SpaceParameterSpec>()
            for (idx in 0 until inputsArr.length()) {
                val compId = inputsArr.optInt(idx, -1)
                val comp = compMap[compId] ?: continue
                val typeStr = comp.optString("type", "textbox")
                if (typeStr.equals("state", ignoreCase = true)) continue

                val props = comp.optJSONObject("props")
                val label = props?.optString("label")?.takeIf { it.isNotBlank() }
                    ?: props?.optString("placeholder")?.takeIf { it.isNotBlank() }
                    ?: "$typeStr #${idx + 1}"

                val defaultVal = props?.opt("value")?.takeIf { it != JSONObject.NULL }?.toString() ?: ""
                val minVal = props?.nullableFloat("minimum")
                val maxVal = props?.nullableFloat("maximum")
                val stepVal = props?.nullableFloat("step")

                val choices = mutableListOf<String>()
                val choicesArr = props?.optJSONArray("choices")
                if (choicesArr != null) {
                    for (c in 0 until choicesArr.length()) {
                        val item = choicesArr.opt(c)
                        when (item) {
                            is JSONArray -> if (item.length() > 0) {
                                choices.add(item.optString(if (item.length() > 1) 1 else 0))
                            }
                            is String -> choices.add(item)
                            else -> if (item != null) choices.add(item.toString())
                        }
                    }
                }

                val inputType = classifyInputType(
                    component = typeStr,
                    label = label,
                    paramName = "param_${params.size}",
                    jsonType = "",
                    pythonType = "",
                    choices = choices
                )

                params.add(
                    SpaceParameterSpec(
                        index = params.size,
                        name = "param_${params.size}",
                        label = label,
                        componentName = typeStr.replaceFirstChar { it.uppercase() },
                        inputType = inputType,
                        isRequired = props?.has("value") != true,
                        defaultValue = defaultVal,
                        defaultIsNull = props?.has("value") == true && props.isNull("value"),
                        min = minVal,
                        max = maxVal,
                        step = stepVal,
                        choices = choices,
                        description = props?.optString("info") ?: ""
                    )
                )
            }

            if (inputsArr.length() == 0 || params.isNotEmpty()) {
                val outputsArr = dep.optJSONArray("outputs")
                val outputs = mutableListOf<SpaceOutputSpec>()
                if (outputsArr != null) {
                    for (o in 0 until outputsArr.length()) {
                        val outComp = compMap[outputsArr.optInt(o, -1)]
                        val outType = outComp?.optString("type", "output") ?: "output"
                        val outLabel = outComp?.optJSONObject("props")?.optString("label") ?: "Output ${o + 1}"
                        outputs.add(SpaceOutputSpec(outLabel, outType))
                    }
                }
                endpoints.add(
                    SpaceEndpointSpec(
                        apiName = apiName.trimStart('/'),
                        parameters = params,
                        outputs = outputs
                    )
                )
            }
        }

        return endpoints
    }

    private fun JSONObject.nullableFloat(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN)
            .takeIf { it.isFinite() }
            ?.toFloat()
            ?.takeIf { it.isFinite() }
    }

    private fun classifyInputType(
        component: String,
        label: String,
        paramName: String,
        jsonType: String,
        pythonType: String,
        choices: List<String>
    ): SpaceInputType {
        val c = component.lowercase()
        val l = "$label $paramName".lowercase()
        val py = pythonType.lowercase()

        return when {
            c.contains("video") || (py.contains("filepath") && l.contains("video")) -> SpaceInputType.VIDEO
            c.contains("image") || c.contains("sketch") || (py.contains("filepath") && (l.contains("image") || l.contains("photo") || l.contains("mask"))) -> SpaceInputType.IMAGE
            c.contains("audio") || c.contains("microphone") || (py.contains("filepath") && (l.contains("audio") || l.contains("voice") || l.contains("wav") || l.contains("mp3"))) -> SpaceInputType.AUDIO
            c.contains("file") || c.contains("upload") || c.contains("model3d") || py.contains("filepath") -> SpaceInputType.FILE
            c.contains("slider") -> SpaceInputType.SLIDER
            c.contains("checkbox") && !c.contains("group") -> SpaceInputType.BOOLEAN
            jsonType == "boolean" || py == "bool" -> SpaceInputType.BOOLEAN
            c.contains("dropdown") || c.contains("radio") || choices.isNotEmpty() -> SpaceInputType.DROPDOWN
            c.contains("number") || jsonType == "number" || jsonType == "integer" || py == "float" || py == "int" -> SpaceInputType.NUMBER
            else -> SpaceInputType.TEXT
        }
    }

    private fun unavailableSchema(space: HfSpace) = SpaceSchema(
        spaceId = space.id,
        endpoints = emptyList(),
        isLiveFetched = false,
        schemaSourceNote = "No verified callable Gradio endpoint is available. Wake the Space and retry, or open its web UI."
    )

    /**
     * Kept for source compatibility with older callers. Metadata cannot establish a callable API,
     * so inferred fields are never exposed as executable endpoints.
     */
    @Deprecated("Use fetchSpaceSchema; unverified metadata is not an executable API contract")
    fun buildInferredSpaceSchema(space: HfSpace): SpaceSchema {
        val text = "${space.id} ${space.title} ${space.description} ${space.category}".lowercase()

        val parameters = when {
            text.contains("stabiliz") || (text.contains("video") && (text.contains("enhance") || text.contains("upscal") || text.contains("interpolat") || text.contains("edit") || !text.contains("text-to-video"))) -> {
                listOf(
                    SpaceParameterSpec(
                        index = 0,
                        name = "input_video",
                        label = "Input Video File",
                        componentName = "Video",
                        inputType = SpaceInputType.VIDEO,
                        isRequired = true,
                        description = "Upload the video file (.mp4, .mov, .webm) to process"
                    ),
                    SpaceParameterSpec(
                        index = 1,
                        name = "smoothing_radius",
                        label = "Smoothing / Stabilization Strength",
                        componentName = "Slider",
                        inputType = SpaceInputType.SLIDER,
                        defaultValue = "30",
                        min = 1f,
                        max = 100f,
                        step = 1f,
                        description = "Higher values produce smoother camera trajectories"
                    ),
                    SpaceParameterSpec(
                        index = 2,
                        name = "border_mode",
                        label = "Border / Crop Mode",
                        componentName = "Dropdown",
                        inputType = SpaceInputType.DROPDOWN,
                        defaultValue = "replicate",
                        choices = listOf("replicate", "reflect", "black", "crop_borders"),
                        description = "How frame edges are handled during stabilization"
                    ),
                    SpaceParameterSpec(
                        index = 3,
                        name = "additional_prompt",
                        label = "Optional Processing Notes / Parameters",
                        componentName = "Textbox",
                        inputType = SpaceInputType.TEXT,
                        isRequired = false,
                        defaultValue = ""
                    )
                )
            }

            text.contains("video") -> {
                listOf(
                    SpaceParameterSpec(
                        index = 0,
                        name = "prompt",
                        label = "Video Generation Prompt",
                        componentName = "Textbox",
                        inputType = SpaceInputType.TEXT,
                        isRequired = true,
                        defaultValue = space.demoPrompt
                    ),
                    SpaceParameterSpec(
                        index = 1,
                        name = "reference_image",
                        label = "Reference Image / Starting Frame (Optional)",
                        componentName = "Image",
                        inputType = SpaceInputType.IMAGE,
                        isRequired = false,
                        description = "Upload an image for Image-to-Video synthesis"
                    ),
                    SpaceParameterSpec(
                        index = 2,
                        name = "input_video",
                        label = "Reference Video (Optional)",
                        componentName = "Video",
                        inputType = SpaceInputType.VIDEO,
                        isRequired = false,
                        description = "Upload a source video for Video-to-Video transformation"
                    ),
                    SpaceParameterSpec(
                        index = 3,
                        name = "duration_seconds",
                        label = "Duration (Seconds)",
                        componentName = "Slider",
                        inputType = SpaceInputType.SLIDER,
                        defaultValue = "5",
                        min = 1f,
                        max = 15f,
                        step = 1f
                    ),
                    SpaceParameterSpec(
                        index = 4,
                        name = "guidance_scale",
                        label = "Motion / Guidance Scale",
                        componentName = "Slider",
                        inputType = SpaceInputType.SLIDER,
                        defaultValue = "6.5",
                        min = 1f,
                        max = 15f,
                        step = 0.5f
                    )
                )
            }

            text.contains("edit") || text.contains("inpaint") || text.contains("upscal") || text.contains("remove") || text.contains("background") || text.contains("try-on") || text.contains("restore") -> {
                listOf(
                    SpaceParameterSpec(
                        index = 0,
                        name = "input_image",
                        label = "Input Image",
                        componentName = "Image",
                        inputType = SpaceInputType.IMAGE,
                        isRequired = true,
                        description = "Upload the source image to edit, upscale, or segment"
                    ),
                    SpaceParameterSpec(
                        index = 1,
                        name = "prompt",
                        label = "Edit Instruction / Prompt",
                        componentName = "Textbox",
                        inputType = SpaceInputType.TEXT,
                        isRequired = false,
                        defaultValue = "Enhance details and preserve natural lighting"
                    ),
                    SpaceParameterSpec(
                        index = 2,
                        name = "strength",
                        label = "Denoising / Edit Strength",
                        componentName = "Slider",
                        inputType = SpaceInputType.SLIDER,
                        defaultValue = "0.75",
                        min = 0f,
                        max = 1f,
                        step = 0.05f
                    )
                )
            }

            text.contains("audio") || text.contains("speech") || text.contains("whisper") || text.contains("tts") || text.contains("voice") || text.contains("music") -> {
                listOf(
                    SpaceParameterSpec(
                        index = 0,
                        name = "audio_input",
                        label = "Input Audio / Reference Voice File",
                        componentName = "Audio",
                        inputType = SpaceInputType.AUDIO,
                        isRequired = false,
                        description = "Upload an audio file (.wav, .mp3, .m4a, .ogg)"
                    ),
                    SpaceParameterSpec(
                        index = 1,
                        name = "text_prompt",
                        label = "Text / Transcript / Prompt",
                        componentName = "Textbox",
                        inputType = SpaceInputType.TEXT,
                        isRequired = false,
                        defaultValue = space.demoPrompt
                    ),
                    SpaceParameterSpec(
                        index = 2,
                        name = "task_mode",
                        label = "Audio Task Mode",
                        componentName = "Dropdown",
                        inputType = SpaceInputType.DROPDOWN,
                        defaultValue = "transcribe",
                        choices = listOf("transcribe", "translate", "synthesize_speech", "enhance_audio")
                    )
                )
            }

            text.contains("3d") || text.contains("mesh") || text.contains("trellis") -> {
                listOf(
                    SpaceParameterSpec(
                        index = 0,
                        name = "input_image",
                        label = "Reference Image for 3D Reconstruction",
                        componentName = "Image",
                        inputType = SpaceInputType.IMAGE,
                        isRequired = true,
                        description = "Upload an object image to convert into a 3D mesh"
                    ),
                    SpaceParameterSpec(
                        index = 1,
                        name = "prompt",
                        label = "Text Prompt (Optional)",
                        componentName = "Textbox",
                        inputType = SpaceInputType.TEXT,
                        isRequired = false,
                        defaultValue = ""
                    ),
                    SpaceParameterSpec(
                        index = 2,
                        name = "remove_bg",
                        label = "Automatically Remove Background",
                        componentName = "Checkbox",
                        inputType = SpaceInputType.BOOLEAN,
                        defaultValue = "true"
                    )
                )
            }

            text.contains("flux") || text.contains("image") || text.contains("diffus") || text.contains("sdxl") -> {
                listOf(
                    SpaceParameterSpec(
                        index = 0,
                        name = "prompt",
                        label = "Prompt",
                        componentName = "Textbox",
                        inputType = SpaceInputType.TEXT,
                        isRequired = true,
                        defaultValue = space.demoPrompt
                    ),
                    SpaceParameterSpec(
                        index = 1,
                        name = "input_image",
                        label = "Init / Reference Image (Optional)",
                        componentName = "Image",
                        inputType = SpaceInputType.IMAGE,
                        isRequired = false
                    ),
                    SpaceParameterSpec(
                        index = 2,
                        name = "guidance_scale",
                        label = "Guidance Scale (CFG)",
                        componentName = "Slider",
                        inputType = SpaceInputType.SLIDER,
                        defaultValue = "3.5",
                        min = 1f,
                        max = 15f,
                        step = 0.5f
                    ),
                    SpaceParameterSpec(
                        index = 3,
                        name = "num_inference_steps",
                        label = "Inference Steps",
                        componentName = "Slider",
                        inputType = SpaceInputType.SLIDER,
                        defaultValue = "28",
                        min = 1f,
                        max = 50f,
                        step = 1f
                    )
                )
            }

            else -> {
                listOf(
                    SpaceParameterSpec(
                        index = 0,
                        name = "prompt",
                        label = "Input Prompt / Query",
                        componentName = "Textbox",
                        inputType = SpaceInputType.TEXT,
                        isRequired = true,
                        defaultValue = space.demoPrompt
                    ),
                    SpaceParameterSpec(
                        index = 1,
                        name = "attachment",
                        label = "Input File / Media Attachment (Optional)",
                        componentName = "File",
                        inputType = SpaceInputType.FILE,
                        isRequired = false,
                        description = "Attach any file, image, video, or document for this Space"
                    ),
                    SpaceParameterSpec(
                        index = 2,
                        name = "temperature",
                        label = "Temperature",
                        componentName = "Slider",
                        inputType = SpaceInputType.SLIDER,
                        defaultValue = "0.7",
                        min = 0f,
                        max = 1.5f,
                        step = 0.05f
                    ),
                    SpaceParameterSpec(
                        index = 3,
                        name = "max_tokens",
                        label = "Max Output Tokens",
                        componentName = "Slider",
                        inputType = SpaceInputType.SLIDER,
                        defaultValue = "2048",
                        min = 128f,
                        max = 8192f,
                        step = 128f
                    )
                )
            }
        }

        @Suppress("UNUSED_VARIABLE")
        val ignoredUnverifiedParameters = parameters
        return unavailableSchema(space)
    }

    /**
     * Uploads a local file/video/image/audio Uri to a Gradio Space's /gradio_api/upload or /upload endpoint
     * and returns the Gradio FileData JSON object.
     */
    suspend fun uploadFileToSpace(
        context: Context,
        spaceId: String,
        fileUri: Uri,
        fileName: String,
        mimeType: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val baseHost = spaceEndpointResolver(spaceId).trimEnd('/')
        val uploadUrls = listOf(
            "$baseHost/gradio_api/upload",
            "$baseHost/upload"
        )

        val streamRequestBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    sink.writeAll(inputStream.source())
                } ?: throw IllegalStateException("Could not open stream for $fileUri")
            }
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", fileName, streamRequestBody)
            .build()

        val failures = mutableListOf<String>()
        for (uploadUrl in uploadUrls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(uploadUrl)
                    .post(multipartBody)

                tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                executeRequest(reqBuilder.build()).use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string() ?: ""
                        val arr = try {
                            JSONArray(bodyStr)
                        } catch (e: Exception) {
                            failures += "$uploadUrl returned malformed JSON"
                            return@use
                        }
                        if (arr.length() > 0) {
                            val serverPath = arr.optString(0)
                            if (serverPath.isNotBlank()) {
                                val filePrefix = if (uploadUrl.contains("/gradio_api/")) {
                                    "$baseHost/gradio_api/file=$serverPath"
                                } else {
                                    "$baseHost/file=$serverPath"
                                }
                                val fileData = JSONObject().apply {
                                    put("path", serverPath)
                                    put("url", filePrefix)
                                    put("orig_name", fileName)
                                    put("mime_type", mimeType)
                                    put("meta", JSONObject().put("_type", "gradio.FileData"))
                                }
                                return@withContext Result.success(fileData)
                            }
                        }
                        failures += "$uploadUrl returned no uploaded file path"
                    } else {
                        failures += "$uploadUrl returned HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(300)}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Upload attempt to $uploadUrl failed: ${e.message}")
                failures += "$uploadUrl failed: ${e.message ?: e::class.java.simpleName}"
            }
        }

        return@withContext Result.failure(
            IOException("Upload failed for $fileName. ${failures.joinToString("; ")}")
        )
    }

    /**
     * Executes a Space endpoint with the full 1:1 list of SpaceInputValue items,
     * automatically uploading any attached Video / Image / Audio / File inputs first.
     */
    suspend fun executeSpaceWithInputs(
        context: Context?,
        spaceId: String,
        apiName: String = "predict",
        inputs: List<SpaceInputValue>,
        onProgressLog: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val dataPayload = mutableListOf<Any?>()

        for (input in inputs.sortedBy { it.paramIndex }) {
            if (input.isNull) {
                dataPayload.add(null)
                continue
            }
            when (input.inputType) {
                SpaceInputType.IMAGE, SpaceInputType.VIDEO, SpaceInputType.AUDIO, SpaceInputType.FILE -> {
                    val uriStr = input.fileUri
                    if (!uriStr.isNullOrBlank() && context != null) {
                        val fName = input.fileName ?: "upload_file"
                        val mime = input.mimeType ?: "application/octet-stream"
                        onProgressLog("📤 Uploading ${input.inputType.displayName} '$fName' to $spaceId...")
                        val uploadRes = uploadFileToSpace(
                            context = context,
                            spaceId = spaceId,
                            fileUri = Uri.parse(uriStr),
                            fileName = fName,
                            mimeType = mime
                        )
                        val fileDataObj = uploadRes.getOrElse { error ->
                            val message = error.message ?: "unknown upload error"
                            onProgressLog("❌ Upload failed for '$fName': $message")
                            return@withContext Result.failure(error)
                        }
                        onProgressLog("✅ Uploaded '$fName' -> ${fileDataObj.optString("path")}")
                        dataPayload.add(fileDataObj)
                    } else if (!uriStr.isNullOrBlank()) {
                        return@withContext Result.failure(
                            IllegalArgumentException("Cannot upload ${input.paramLabel} without an Android context")
                        )
                    } else {
                        dataPayload.add(null)
                    }
                }
                SpaceInputType.SLIDER, SpaceInputType.NUMBER -> {
                    val number = input.numberValue
                    if (number == null || !number.isFinite()) {
                        return@withContext Result.failure(
                            IllegalArgumentException("Enter a valid number for ${input.paramLabel}")
                        )
                    }
                    // Retain typed 64-bit integer seeds; Float and toInt() both lose data.
                    dataPayload.add(input.textValue.toLongOrNull() ?: number)
                }
                SpaceInputType.BOOLEAN -> {
                    dataPayload.add(input.booleanValue)
                }
                SpaceInputType.DROPDOWN, SpaceInputType.TEXT -> {
                    dataPayload.add(input.textValue)
                }
            }
        }

        return@withContext querySpaceEndpointWithAnyPayload(spaceId, dataPayload, apiName, onProgressLog)
    }

    private suspend fun executeRequest(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, cancelledResponse, _ ->
                        cancelledResponse.close()
                    }
                }
            })
        }

    private suspend fun querySpaceEndpointWithAnyPayload(
        spaceId: String,
        payloadData: List<Any?>,
        apiName: String = "predict",
        onProgressLog: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseEndpoint = spaceEndpointResolver(spaceId).trimEnd('/')
        val cleanApi = apiName.trimStart('/').ifBlank { "predict" }

        val jsonObject = JSONObject()
        val array = JSONArray()
        payloadData.forEach { item ->
            array.put(item ?: JSONObject.NULL)
        }
        jsonObject.put("data", array)
        val requestBodyString = jsonObject.toString()

        // 1. Try Gradio 4/5 SSE Queue Call (/gradio_api/call/<apiName> or /call/<apiName>)
        val queuePrefixes = listOf("$baseEndpoint/gradio_api/call/$cleanApi", "$baseEndpoint/call/$cleanApi")
        val failures = mutableListOf<String>()
        for (queueUrl in queuePrefixes) {
            try {
                val postReq = Request.Builder()
                    .url(queueUrl)
                    .post(requestBodyString.toRequestBody(JSON_MEDIA))
                    .apply {
                        tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                    }
                    .build()

                val eventId = executeRequest(postReq).use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        JSONObject(body).optString("event_id").takeIf { it.isNotBlank() && it != "null" }
                            ?: return@withContext Result.failure(IOException("Space queue accepted the request without an event_id"))
                    } else {
                        val failure = "$queueUrl returned HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(300)}"
                        if (resp.code != 404 && resp.code != 405) {
                            return@withContext Result.failure(IOException(failure))
                        }
                        failures += failure
                        null
                    }
                }

                if (eventId != null) {
                    onProgressLog("🔄 Queued on Space (event_id: $eventId). Streaming SSE status...")
                    val sseReq = Request.Builder()
                        .url("$queueUrl/$eventId")
                        .get()
                        .apply {
                            tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                        }
                        .build()

                    executeRequest(sseReq).use { sseResp ->
                        if (sseResp.isSuccessful) {
                            val sseBody = sseResp.body?.string() ?: ""
                            return@withContext parseSseResult(sseBody)
                        } else {
                            return@withContext Result.failure(IOException(
                                "$queueUrl/$eventId returned HTTP ${sseResp.code}: ${sseResp.body?.string().orEmpty().take(300)}"
                            ))
                        }
                    }
                } else {
                    failures += "$queueUrl did not return an event_id"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A submitted request may already be running; retrying can execute it twice.
                return@withContext Result.failure(IOException("Space queue request failed", e))
            }
        }

        // 2. Try Direct REST Predict (/api/<cleanApi> or /run/<cleanApi>)
        val directUrls = listOf("$baseEndpoint/api/$cleanApi", "$baseEndpoint/run/$cleanApi")
        for (directUrl in directUrls) {
            try {
                val requestBuilder = Request.Builder()
                    .url(directUrl)
                    .post(requestBodyString.toRequestBody(JSON_MEDIA))

                tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                executeRequest(requestBuilder.build()).use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string() ?: ""
                        return@withContext Result.success(bodyString)
                    } else {
                        val failure = "$directUrl returned HTTP ${response.code}: ${response.body?.string().orEmpty().take(300)}"
                        if (response.code != 404 && response.code != 405) {
                            return@withContext Result.failure(IOException(failure))
                        }
                        failures += failure
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext Result.failure(IOException("Space prediction request failed", e))
            }
        }

        return@withContext Result.failure(
            IOException("Space endpoint /$cleanApi failed. ${failures.joinToString("; ")}")
        )
    }

    internal fun parseSseResult(body: String): Result<String> {
        var currentEvent = ""
        val dataLines = mutableListOf<String>()
        for (line in body.lineSequence() + sequenceOf("")) {
            when {
                line.isBlank() -> {
                    val data = dataLines.joinToString("\n")
                    if (currentEvent.equals("error", ignoreCase = true)) {
                        return Result.failure(IOException("Gradio queue error: ${data.ifBlank { "Unknown error" }}"))
                    }
                    if (currentEvent.equals("complete", ignoreCase = true)) {
                        if (data.isBlank()) return Result.failure(IOException("Gradio queue completed without result data"))
                        return try {
                            val parsed = org.json.JSONTokener(data).nextValue()
                            Result.success(JSONObject().put("data", parsed).toString())
                        } catch (e: Exception) {
                            Result.failure(IOException("Gradio queue returned malformed result data", e))
                        }
                    }
                    currentEvent = ""
                    dataLines.clear()
                }
                line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
        return Result.failure(IOException("Gradio queue ended before a completion event"))
    }

    /**
     * Query a Gradio or direct REST space endpoint with keep-alive
     */
    suspend fun querySpaceEndpoint(
        spaceId: String,
        payloadData: List<Any>,
        apiName: String = "predict"
    ): Result<String> = querySpaceEndpointWithAnyPayload(spaceId, payloadData, apiName)

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

        val tagsList = mutableListOf<String>()
        val tagsArray = item.optJSONArray("tags")
        if (tagsArray != null) {
            for (t in 0 until tagsArray.length()) {
                val tag = tagsArray.optString(t)
                if (tag.isNotBlank()) tagsList.add(tag)
            }
        }

        val isZeroGpu = tagsList.any { it.equals("ZeroGPU", ignoreCase = true) }

        val runtimeObj = item.optJSONObject("runtime")
        val runtimeStage = runtimeObj?.optString("stage")?.takeIf { it.isNotBlank() } ?: "RUNNING"
        val hardwareObj = runtimeObj?.optJSONObject("hardware")
        val hardwareTier = hardwareObj?.optString("current")?.takeIf { it.isNotBlank() }
            ?: hardwareObj?.optString("requested")?.takeIf { it.isNotBlank() }
            ?: if (isZeroGpu) "zero-a10g" else "cpu-basic"

        val combinedText = "$id $rawTitle ${shortDesc ?: ""} ${tagsList.joinToString(" ")}".lowercase()
        val category = when {
            combinedText.contains("video") || combinedText.contains("wan") || combinedText.contains("sora") || combinedText.contains("stabiliz") -> "Video Generation"
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

    suspend fun searchSpaces(query: String = "", limit: Int = 30): List<HfSpace> {
        return fetchLiveSpaces(query = query, limit = limit).getOrDefault(emptyList())
    }

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
