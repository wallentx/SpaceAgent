package com.example.network

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.model.HfSpace
import com.example.model.SpaceCategoryFilter
import com.example.model.SpaceInputType
import com.example.model.SpaceInputValue
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okio.Buffer
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HfApiClientTest {

    @Test
    fun infoParserPreservesZeroInputEndpointAndUnboundedDefault() {
        val client = HfApiClient { null }
        val schema = JSONObject(
            """
            {
              "named_endpoints": {
                "/health": {"parameters": [], "returns": []},
                "/predict": {
                  "parameters": [{
                    "label": "Seed",
                    "parameter_name": "seed",
                    "component": "Number",
                    "parameter_has_default": true,
                    "parameter_default": -1,
                    "type": {"type": "integer"}
                  }],
                  "returns": []
                }
              }
            }
            """.trimIndent()
        )

        val endpoints = client.parseGradioInfoJson(schema)

        assertEquals(listOf("health", "predict"), endpoints.map { it.apiName }.sorted())
        assertTrue(endpoints.single { it.apiName == "health" }.parameters.isEmpty())
        val seed = endpoints.single { it.apiName == "predict" }.parameters.single()
        assertEquals("-1", seed.defaultValue)
        assertNull(seed.min)
        assertNull(seed.max)
        assertNull(seed.step)
    }

    @Test
    fun configParserRejectsInternalCallbacksAndPreservesTypedValues() {
        val client = HfApiClient { null }
        val config = JSONObject(
            """
            {
              "components": [{
                "id": 1,
                "type": "number",
                "props": {"label": "Tokens", "value": 1024}
              }, {
                "id": 2,
                "type": "dropdown",
                "props": {"label": "Mode", "choices": [["Friendly", "friendly_value"]]}
              }],
              "dependencies": [
                {"api_name": false, "inputs": [1], "outputs": []},
                {"api_name": "/predict", "inputs": [1, 2], "outputs": []},
                {"api_name": "/health", "inputs": [], "outputs": []}
              ]
            }
            """.trimIndent()
        )

        val endpoints = client.parseGradioConfigJson(config)

        assertEquals(listOf("predict", "health"), endpoints.map { it.apiName })
        val parameters = endpoints.first().parameters
        assertEquals("1024", parameters[0].defaultValue)
        assertNull(parameters[0].min)
        assertNull(parameters[0].max)
        assertEquals(listOf("friendly_value"), parameters[1].choices)
        assertTrue(endpoints.last().parameters.isEmpty())
    }

    @Test
    fun searchCombinesTextAndCategory() {
        val client = HfApiClient { null }

        assertEquals(
            "upscale image-editing",
            client.buildSearchQuery("  upscale  ", SpaceCategoryFilter.IMAGE_EDIT)
        )
    }

    @Test
    fun failedUploadStopsBeforePrediction() = runTest {
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                requestedPaths += chain.request().url.encodedPath
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Server error")
                    .body("upload unavailable".toResponseBody())
                    .build()
            })
            .build()
        val client = HfApiClient(
            tokenProvider = { null },
            client = httpClient,
            spaceEndpointResolver = { "https://space.test" }
        )
        val context = ApplicationProvider.getApplicationContext<Context>()

        val result = client.executeSpaceWithInputs(
            context = context,
            spaceId = "owner/space",
            inputs = listOf(
                SpaceInputValue(
                    paramIndex = 0,
                    paramName = "file",
                    paramLabel = "File",
                    inputType = SpaceInputType.FILE,
                    fileUri = Uri.parse("content://example/input").toString(),
                    fileName = "input.bin"
                )
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Upload failed"))
        assertEquals(listOf("/gradio_api/upload", "/upload"), requestedPaths)
        assertFalse(requestedPaths.any { "/call/" in it || "/api/" in it || "/run/" in it })
    }

    @Test
    fun failedSchemaProbesReturnUnavailableInsteadOfInventedPredict() = runTest {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not found")
                    .body("missing".toResponseBody())
                    .build()
            }
            .build()
        val client = HfApiClient(
            tokenProvider = { null },
            client = httpClient,
            spaceEndpointResolver = { "https://space.test" }
        )

        val result = client.fetchSpaceSchema(testSpace())

        assertFalse(result.isLiveFetched)
        assertTrue(result.endpoints.isEmpty())
        assertTrue(result.schemaSourceNote.contains("No verified callable"))
    }

    @Test
    fun sseErrorIsNotReturnedAsSuccessfulOutput() {
        val client = HfApiClient { null }

        val error = client.parseSseResult("event: error\ndata: {\"error\":\"boom\"}\n")
        val success = client.parseSseResult("event: complete\ndata: [\"done\"]\n")

        assertTrue(error.isFailure)
        assertEquals("{\"data\":[\"done\"]}", success.getOrThrow())
    }

    @Test fun nullableDefaultsAreDistinctFromEmptyTextAndFalse() {
        val root = JSONObject("""{"named_endpoints":{"/predict":{"parameters":[
          {"parameter_name":"optional","component":"Number","parameter_has_default":true,"parameter_default":null},
          {"parameter_name":"text","component":"Textbox","parameter_has_default":true,"parameter_default":""},
          {"parameter_name":"flag","component":"Checkbox","parameter_has_default":true,"parameter_default":false}
        ]}}}""")
        val params = HfApiClient { null }.parseGradioInfoJson(root).single().parameters
        assertTrue(params[0].defaultIsNull)
        assertFalse(params[0].isRequired)
        assertFalse(params[1].defaultIsNull)
        assertFalse(params[2].defaultIsNull)
        assertEquals("false", params[2].defaultValue)
    }

    @Test fun largeIntegerAndOptionalNullSurviveSerializationAndHttpPayload() = runTest {
        var payload: JSONObject? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.method == "POST") {
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                payload = JSONObject(buffer.readUtf8())
                """{"event_id":"one"}"""
            } else "event: complete\ndata: [\"ok\"]\n\n"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val client = HfApiClient(client = http, spaceEndpointResolver = { "https://space.test" }, tokenProvider = { null })
        val inputs = listOf(
            SpaceInputValue(0, "seed", "Seed", SpaceInputType.NUMBER,
                textValue = Long.MAX_VALUE.toString(), numberValue = Long.MAX_VALUE.toDouble()),
            SpaceInputValue(1, "optional", "Optional", SpaceInputType.NUMBER, isNull = true),
            SpaceInputValue(2, "text", "Text", SpaceInputType.TEXT)
        )
        val restored = SpaceInputValue.listFromJsonString(SpaceInputValue.listToJsonString(inputs))
        assertTrue(client.executeSpaceWithInputs(null, "owner/demo", inputs = restored).isSuccess)
        val data = payload!!.getJSONArray("data")
        assertEquals(Long.MAX_VALUE, data.getLong(0))
        assertTrue(data.isNull(1))
        assertEquals("", data.getString(2))
    }

    @Test fun invalidNumbersFailWithoutAnHttpRequestAndDoNotBreakSessionJson() = runTest {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { requests++; error("Must not send invalid input") }.build()
        val client = HfApiClient(client = http, spaceEndpointResolver = { "https://space.test" }, tokenProvider = { null })
        val invalid = SpaceInputValue(0, "seed", "Seed", SpaceInputType.NUMBER, numberValue = Double.NaN)
        val restored = SpaceInputValue.listFromJsonString(SpaceInputValue.listToJsonString(listOf(invalid))).single()
        assertNull(restored.numberValue)
        assertTrue(client.executeSpaceWithInputs(null, "owner/demo", inputs = listOf(restored)).isFailure)
        assertEquals(0, requests)
    }

    @Test fun failedQueuedExecutionIsNeverSubmittedAgainToAnotherRoute() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.method + " " + request.url.encodedPath
            val body = if (request.method == "POST") """{"event_id":"failed"}"""
                else "event: error\ndata: {\"error\":\"GPU unavailable\"}\n\n"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val client = HfApiClient(client = http, spaceEndpointResolver = { "https://space.test" }, tokenProvider = { null })
        assertTrue(client.executeSpaceWithInputs(null, "owner/demo", inputs = emptyList()).isFailure)
        assertEquals(listOf("POST /gradio_api/call/predict", "GET /gradio_api/call/predict/failed"), requests)
    }

    @Test fun incompleteStreamDoesNotReturnPartialDataAsSuccess() {
        val client = HfApiClient { null }
        assertTrue(client.parseSseResult("event: generating\ndata: [\"partial\"]\n\n").isFailure)
        assertTrue(client.parseSseResult("event: error\ndata: null\n\n").isFailure)
        assertEquals("{\"data\":[\"done\"]}", client.parseSseResult("event: complete\ndata: [\ndata: \"done\"\ndata: ]\n\n").getOrThrow())
    }

    @Test fun agentExecutionKeepsZeroArgumentEndpointsEmpty() = runTest {
        var argumentCount = -1
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.method == "POST") {
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                argumentCount = JSONObject(buffer.readUtf8()).getJSONArray("data").length()
                """{"event_id":"health"}"""
            } else "event: complete\ndata: [\"healthy\"]\n\n"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val client = HfApiClient(client = http, spaceEndpointResolver = { "https://space.test" }, tokenProvider = { null })
        val engine = com.example.agent.SpaceAgentEngine(client)
        engine.executeAgentTask(goal = "", agentType = com.example.model.AgentType.GENERAL,
            space = testSpace(), apiName = "health", onStepUpdate = { _, _ -> }, onLog = {})
        assertEquals(0, argumentCount)
    }

    private fun testSpace() = HfSpace(
        id = "owner/space",
        author = "owner",
        name = "space",
        title = "Space",
        description = "",
        sdk = "gradio",
        category = "text-generation",
        likes = 0,
        endpointUrl = "https://space.test"
    )
}
