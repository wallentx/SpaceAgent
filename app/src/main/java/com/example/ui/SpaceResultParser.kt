package com.example.ui

import com.example.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Interpret Gradio's returned values using their output component, never their server paths as display text. */
internal object SpaceResultParser {
    fun parse(raw: String, space: HfSpace, endpoint: SpaceEndpointSpec): SpaceResult {
        val root = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        if (root is JSONObject && !root.isNull("error")) {
            throw IllegalStateException(root.optString("error").ifBlank { "The Space could not complete this request." })
        }
        val data = if (root is JSONObject && root.has("data")) root.opt("data") else root ?: raw
        val values = if (data is JSONArray) (0 until data.length()).map { data.opt(it) } else listOf(data)
        val outputs = values.mapIndexed { index, value ->
            val spec = endpoint.outputs.getOrNull(index)
            SpaceResultOutput(spec?.label?.takeIf { it.isNotBlank() } ?: "Result ${index + 1}",
                content(value, spec?.componentName.orEmpty(), space.directEmbedUrl))
        }
        return SpaceResult(space.id, endpoint.apiName, outputs, raw)
    }

    private fun content(value: Any?, component: String, baseUrl: String): SpaceResultContent {
        if (value == null || value == JSONObject.NULL) return SpaceResultContent.Text("No result was returned.")
        if (value is JSONObject) {
            file(value, component, baseUrl)?.let { return SpaceResultContent.File(it) }
            val headers = value.optJSONArray("headers")
            val rows = value.optJSONArray("data")
            if (headers != null && rows != null) {
                return SpaceResultContent.Table(
                    (0 until headers.length()).map { headers.opt(it).toString() },
                    (0 until rows.length()).map { index ->
                        val row = rows.optJSONArray(index)
                        if (row == null) listOf(rows.opt(index).toString())
                        else (0 until row.length()).map { row.opt(it).takeUnless { it == JSONObject.NULL }?.toString().orEmpty() }
                    }
                )
            }
            for (key in listOf("video", "audio", "image")) {
                if (!value.isNull(key)) {
                    val media = content(value.opt(key), key, baseUrl)
                    val caption = value.stringOrNull("caption")
                    return if (caption == null) media else SpaceResultContent.Group(listOf(media, SpaceResultContent.Text(caption)))
                }
            }
            return SpaceResultContent.Json(value.toString(2))
        }
        if (value is JSONArray) {
            return SpaceResultContent.Group((0 until value.length()).map { content(value.opt(it), component, baseUrl) })
        }
        if (value is String && component.lowercase() in listOf("image", "video", "audio", "file", "gallery")) {
            file(JSONObject().put("url", value), component, baseUrl)?.let { return SpaceResultContent.File(it) }
        }
        return SpaceResultContent.Text(value.toString())
    }

    private fun file(value: JSONObject, component: String, baseUrl: String): SpaceResultFile? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val rawUrl = value.stringOrNull("url")
        val path = value.stringOrNull("path")
        if (rawUrl == null && path == null) return null
        val resolved = when {
            rawUrl != null -> base.resolve(rawUrl)
            path!!.startsWith("https://") -> path.toHttpUrlOrNull()
            else -> base.newBuilder().addPathSegments("gradio_api/file=$path").build()
        }?.takeIf { it.isHttps } ?: return null
        val name = value.stringOrNull("orig_name")?.substringAfterLast('/')?.substringAfterLast('\\')
            ?: path?.substringAfterLast('/')
            ?: resolved.pathSegments.lastOrNull()?.substringAfterLast('=')
            ?: "result"
        val mime = value.stringOrNull("mime_type")
        val extension = name.substringAfterLast('.', "").lowercase()
        val kind = when {
            component.equals("video", true) || mime?.startsWith("video/") == true || extension in listOf("mp4", "webm", "mov", "m4v") -> ResultMediaKind.VIDEO
            component.equals("audio", true) || mime?.startsWith("audio/") == true || extension in listOf("mp3", "wav", "ogg", "flac", "m4a") -> ResultMediaKind.AUDIO
            component.equals("image", true) || mime?.startsWith("image/") == true || extension in listOf("png", "jpg", "jpeg", "webp", "gif", "avif", "bmp") -> ResultMediaKind.IMAGE
            else -> ResultMediaKind.FILE
        }
        return SpaceResultFile(resolved.toString(), name.ifBlank { "result" }, kind, mime,
            value.optLong("size", -1).takeIf { it >= 0 })
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
}
