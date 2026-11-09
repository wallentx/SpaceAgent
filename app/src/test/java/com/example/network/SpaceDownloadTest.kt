package com.example.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SpaceDownloadTest {
    @Test fun `downloads save actual bytes and restrict credentials to the selected Space`() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request().url.host to chain.request().header("Authorization")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(byteArrayOf(1, 2, 3).toResponseBody()).build()
        }.build()
        val client = HfApiClient(client = http, tokenProvider = { "hf_test_only" })
        val first = ByteArrayOutputStream()
        assertTrue(client.saveResultFile("https://space.test/a.png", "https://space.test") { first }.isSuccess)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.toByteArray())
        assertTrue(client.saveResultFile("https://files.example/a.png", "https://space.test") { ByteArrayOutputStream() }.isSuccess)
        assertEquals(listOf("space.test" to "Bearer hf_test_only", "files.example" to null), seen)
    }

    @Test fun `failed or insecure downloads never open the destination`() = runTest {
        var opened = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Missing")
                .body("missing".toResponseBody()).build()
        }.build()
        val client = HfApiClient(client = http, tokenProvider = { null })
        assertTrue(client.saveResultFile("https://space.test/missing", "https://space.test") {
            opened = true; ByteArrayOutputStream()
        }.isFailure)
        assertTrue(client.saveResultFile("file:///private/token", "https://space.test") {
            opened = true; ByteArrayOutputStream()
        }.isFailure)
        assertFalse(opened)
    }
}
