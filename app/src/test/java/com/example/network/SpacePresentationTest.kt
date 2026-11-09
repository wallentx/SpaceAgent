package com.example.network

import com.example.model.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpacePresentationTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/gradio/$name")!!.bufferedReader().use { it.readText() }
    private val space = HfSpace("ibyteohdear/Real-ESRGAN-AI-Upscaler", "ibyteohdear", "Real-ESRGAN-AI-Upscaler",
        "Real-ESRGAN AI Upscaler", "", "gradio", "Image Editing", 1, "https://space.test")

    @Test fun `upscaler keeps original tabs actions output labels and API parameter names`() = runTest {
        val paths = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            paths += path
            val body = fixture(if (path == "/config") "real-esrgan-config.json" else "real-esrgan-info.json")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val client = HfApiClient(client = http, spaceEndpointResolver = { "https://space.test" }, tokenProvider = { null })
        val schema = client.fetchSpaceSchema(space)
        assertEquals(listOf("/gradio_api/info", "/config"), paths)
        assertEquals(listOf("Image Upscale", "Video Upscale"), schema.endpoints.map { it.displayName })
        assertEquals(listOf("Upscale Image", "Upscale Video"), schema.endpoints.map { it.submitLabel })
        assertEquals("input_img", schema.endpoints.first().parameters.first().name)
        assertEquals("4x", schema.endpoints.first().parameters.last().defaultValue)
        assertEquals("2x", schema.endpoints.last().parameters.last().defaultValue)
        assertEquals("Radio", schema.endpoints.first().parameters.last().componentName)
        assertEquals("Download Upscaled Image", schema.endpoints.first().outputs.single().label)
        assertEquals("video", schema.endpoints.last().outputs.single().componentName)
    }

    @Test fun `ZeroGPU hardware is recognized even when tags omit ZeroGPU`() {
        val client = HfApiClient { null }
        val actual = client.parseSpaceJson(JSONObject(fixture("real-esrgan-hub.json")), emptySet())!!
        assertTrue(actual.isZeroGpu)
        assertEquals("zero-a10g", actual.hardwareTier)
        assertEquals("RUNNING", actual.runtimeStage)
        val unknown = client.parseSpaceJson(JSONObject("""{"id":"owner/space"}"""), emptySet())!!
        assertEquals("UNKNOWN", unknown.runtimeStage)
        assertEquals("", unknown.hardwareTier)
        assertFalse(unknown.description.contains("cpu-basic"))
    }

    @Test fun `Hub requests include runtime and follow only same-origin pagination`() = runTest {
        var requested = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requested = chain.request().url.toString()
            assertTrue(chain.request().url.queryParameterValues("expand").contains("runtime"))
            assertNull(chain.request().url.queryParameter("full"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Link", "<https://hub.test/api/spaces?cursor=next>; rel=\"next\"")
                .body(("[" + fixture("real-esrgan-hub.json") + "]").toResponseBody()).build()
        }.build()
        val client = HfApiClient(client = http, hubApiBase = "https://hub.test/api", tokenProvider = { null })
        val page = client.fetchSpacesPage(query = "upscaler", categoryFilter = SpaceCategoryFilter.IMAGE_EDIT).getOrThrow()
        assertTrue(requested.contains("search=upscaler"))
        assertFalse(requested.contains("image-editing"))
        assertEquals(1, page.spaces.size)
        assertEquals("https://hub.test/api/spaces?cursor=next", page.nextPageUrl)
        assertNull(client.validateNextPageUrl("https://evil.example/api/spaces?cursor=next"))
        assertNull(client.validateNextPageUrl("https://hub.test/api/users/me"))
        assertTrue(client.fetchSpacesPage(pageUrl = "https://evil.example/api/spaces").isFailure)
    }
}
