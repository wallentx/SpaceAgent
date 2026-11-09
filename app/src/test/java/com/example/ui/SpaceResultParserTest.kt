package com.example.ui

import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpaceResultParserTest {
    private val space = HfSpace("owner/upscaler", "owner", "upscaler", "Upscaler", "", "gradio", "Image Editing", 1,
        "https://owner-upscaler.hf.space")
    private fun parse(raw: String, component: String = "File") = SpaceResultParser.parse(raw, space,
        SpaceEndpointSpec("upscale_image", emptyList(), listOf(SpaceOutputSpec("Download Upscaled Image", component))))

    @Test fun `file output with a null MIME type is rendered as an image`() {
        val result = parse("""{"data":[{"path":"/tmp/gradio/job/upscaled_4x_image.png",
            "url":"https://owner-upscaler.hf.space/gradio_api/file=/tmp/gradio/job/upscaled_4x_image.png",
            "orig_name":"upscaled_4x_image.png","mime_type":null,"size":1048576,
            "meta":{"_type":"gradio.FileData"}}]}""")
        val output = result.outputs.single()
        assertEquals("Download Upscaled Image", output.label)
        val file = (output.content as SpaceResultContent.File).file
        assertEquals(ResultMediaKind.IMAGE, file.kind)
        assertEquals("upscaled_4x_image.png", file.name)
        assertEquals(1048576L, file.sizeBytes)
        assertNull(file.mimeType)
    }

    @Test fun `relative file URLs and server paths resolve against this Space`() {
        val relative = parse("""{"data":[{"url":"/gradio_api/file=/tmp/image.png","orig_name":"image.png"}]}""")
        assertEquals("https://owner-upscaler.hf.space/gradio_api/file=/tmp/image.png",
            (relative.outputs.single().content as SpaceResultContent.File).file.url)
        val pathOnly = parse("""{"data":[{"path":"/tmp/a b.png"}]}""")
        assertTrue((pathOnly.outputs.single().content as SpaceResultContent.File).file.url.endsWith("file=/tmp/a%20b.png"))
    }

    @Test fun `video wrapper and gallery captions retain their content`() {
        val video = parse("""{"data":[{"video":{"url":"https://files.example/movie.mp4"},"subtitles":null}]}""", "Video")
        assertEquals(ResultMediaKind.VIDEO, (video.outputs.single().content as SpaceResultContent.File).file.kind)
        val gallery = parse("""{"data":[[{"image":{"url":"https://files.example/a.png"},"caption":"First image"}]]}""", "Gallery")
        val item = ((gallery.outputs.single().content as SpaceResultContent.Group).items.single() as SpaceResultContent.Group)
        assertEquals(SpaceResultContent.Text("First image"), item.items.last())
    }

    @Test fun `table output is structured instead of a JSON blob`() {
        val table = parse("""{"data":[{"headers":["Label","Score"],"data":[["cat",0.9],["dog",null]]}]}""", "Dataframe")
            .outputs.single().content as SpaceResultContent.Table
        assertEquals(listOf("Label", "Score"), table.headers)
        assertEquals(listOf("dog", ""), table.rows.last())
    }

    @Test fun `untrusted schemes never become clickable files`() {
        val output = parse("""{"data":[{"url":"javascript:alert(1)","orig_name":"x.png"}]}""")
        assertFalse(output.outputs.single().content is SpaceResultContent.File)
    }

    @Test fun `server errors are failures and null results are not pending placeholders`() {
        assertThrows(IllegalStateException::class.java) { parse("""{"error":"GPU unavailable"}""") }
        assertEquals(SpaceResultContent.Text("No result was returned."), parse("""{"data":[null]}""").outputs.single().content)
    }
}
