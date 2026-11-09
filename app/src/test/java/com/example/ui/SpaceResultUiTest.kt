package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.model.*
import com.example.ui.components.*
import com.example.ui.theme.SpaceAgentTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpaceResultUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun imageResultHasPreviewAndSaveActionInsteadOfRawMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = SpaceResultFile("android.resource://${context.packageName}/${R.drawable.ic_launcher_foreground}",
            "upscaled_4x_image.png", ResultMediaKind.IMAGE, "image/png", 1048576)
        var saved: SpaceResultFile? = null
        compose.setContent {
            SpaceAgentTheme(darkTheme = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Real-ESRGAN AI Upscaler", style = MaterialTheme.typography.titleLarge)
                        RuntimeStatusBadge("RUNNING", true)
                        SpaceResultsSection(listOf(SpaceResultOutput("Download Upscaled Image", SpaceResultContent.File(file))),
                            onSave = { saved = it }, onOpen = {})
                    }
                }
            }
        }
        compose.onNodeWithText("Download Upscaled Image").assertIsDisplayed()
        compose.onNodeWithTag("result_image").assertIsDisplayed()
        compose.onNodeWithText("Running on ZeroGPU").assertIsDisplayed()
        compose.onNodeWithText("gradio.FileData", substring = true).assertDoesNotExist()
        compose.onNodeWithText("/tmp/gradio", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        assertEquals(file, saved)
        capture("image-result.png")
    }

    @Test fun upscaleFactorUsesRealRadioControlsAndPreservesSelection() {
        var choice = "4x"
        val spec = SpaceParameterSpec(1, "scale_factor", "Upscale Factor", "Radio", SpaceInputType.DROPDOWN,
            isRequired = false, defaultValue = "4x", choices = listOf("2x", "4x", "8x"))
        compose.setContent {
            var selected by remember { mutableStateOf(choice) }
            SpaceAgentTheme(darkTheme = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Image Upscale", style = MaterialTheme.typography.titleLarge)
                        SpaceParameterInputCard(spec, SpaceInputValue(1, "scale_factor", "Upscale Factor", SpaceInputType.DROPDOWN,
                            textValue = selected), onTextChange = { selected = it; choice = it }, onNumberChange = {},
                            onBooleanChange = {}, onNullChange = {}, onPickVisualMedia = {}, onPickDocument = {}, onClearFile = {})
                    }
                }
            }
        }
        compose.onNodeWithText("4x").assertIsSelected()
        compose.onNodeWithText("8x").performClick().assertIsSelected()
        assertEquals("8x", choice)
        compose.onNodeWithText("RADIO").assertDoesNotExist()
        capture("upscale-controls.png")
    }

    private fun capture(name: String) {
        val directory = File(checkNotNull(System.getProperty("spaceagent.screenshotDir")))
        directory.mkdirs()
        File(directory, name).outputStream().use {
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
