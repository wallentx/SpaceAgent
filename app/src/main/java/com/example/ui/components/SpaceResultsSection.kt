package com.example.ui.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun SpaceResultsSection(
    outputs: List<SpaceResultOutput>,
    onSave: (SpaceResultFile) -> Unit,
    onOpen: (SpaceResultFile) -> Unit,
    isSaving: Boolean = false,
    spaceOrigin: String = "",
    token: String = ""
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("space_results")) {
        outputs.forEach { output ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(output.label, style = MaterialTheme.typography.titleMedium)
                    ResultContentView(output.content, onSave, onOpen, isSaving, spaceOrigin, token)
                }
            }
        }
    }
}

@Composable
private fun ResultContentView(
    content: SpaceResultContent,
    onSave: (SpaceResultFile) -> Unit,
    onOpen: (SpaceResultFile) -> Unit,
    isSaving: Boolean,
    spaceOrigin: String,
    token: String
) {
    when (content) {
        SpaceResultContent.Empty -> Text("Your result will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        is SpaceResultContent.Text -> SpaceOutputView(content.value)
        is SpaceResultContent.Json -> CodeBlockView(content.value, title = "Data")
        is SpaceResultContent.Group -> content.items.forEach {
            ResultContentView(it, onSave, onOpen, isSaving, spaceOrigin, token)
        }
        is SpaceResultContent.Table -> ResultTable(content)
        is SpaceResultContent.File -> {
            val file = content.file
            val context = LocalContext.current
            val origin = spaceOrigin.toHttpUrlOrNull()
            val address = file.url.toHttpUrlOrNull()
            val headers = if (token.isNotBlank() && address?.isHttps == true && origin?.isHttps == true &&
                address.host == origin.host && address.port == origin.port) mapOf("Authorization" to "Bearer $token") else emptyMap()
            if (file.kind == ResultMediaKind.IMAGE) {
                var previewFailed by remember(file.url) { mutableStateOf(false) }
                AsyncImage(
                    model = ImageRequest.Builder(context).data(file.url).apply {
                        headers.forEach { (name, value) -> addHeader(name, value) }
                    }.build(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    onError = { previewFailed = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 380.dp).testTag("result_image")
                )
                if (previewFailed) Text("Preview unavailable. You can still save or open the file.")
            } else if (file.kind == ResultMediaKind.VIDEO || file.kind == ResultMediaKind.AUDIO) {
                ResultMediaPlayer(file, headers)
            } else Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            file.sizeBytes?.let { Text(android.text.format.Formatter.formatShortFileSize(context, it), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onSave(file) }, enabled = !isSaving, modifier = Modifier.testTag("save_result")) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
                OutlinedButton(onClick = { onOpen(file) }) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun ResultMediaPlayer(file: SpaceResultFile, headers: Map<String, String>) {
    var started by remember(file.url) { mutableStateOf(false) }
    var error by remember(file.url) { mutableStateOf(false) }
    var player by remember(file.url) { mutableStateOf<VideoView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(file.url, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.stopPlayback()
        }
    }
    if (!started) {
        OutlinedButton(onClick = { started = true }) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text(if (file.kind == ResultMediaKind.AUDIO) "Play audio" else "Play video")
        }
    } else if (error) {
        Text("This media cannot be previewed here. Save it or open it in another app.")
    } else {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    player = this
                    val controls = MediaController(context)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setVideoURI(Uri.parse(file.url), headers)
                    setOnPreparedListener { start(); controls.show() }
                    setOnErrorListener { _, _, _ -> error = true; true }
                }
            },
            modifier = Modifier.fillMaxWidth().height(if (file.kind == ResultMediaKind.AUDIO) 96.dp else 240.dp)
        )
    }
}

@Composable
private fun ResultTable(table: SpaceResultContent.Table) {
    var page by remember(table) { mutableIntStateOf(0) }
    val pageSize = 20
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row { table.headers.forEach { Text(it, Modifier.width(160.dp).padding(8.dp), style = MaterialTheme.typography.titleSmall) } }
        table.rows.drop(page * pageSize).take(pageSize).forEach { row ->
            Row { row.forEach { Text(it, Modifier.width(160.dp).padding(8.dp)) } }
        }
    }
    if (table.rows.size > pageSize) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
            Text("${page * pageSize + 1}–${minOf((page + 1) * pageSize, table.rows.size)} of ${table.rows.size}")
            TextButton(onClick = { page++ }, enabled = (page + 1) * pageSize < table.rows.size) { Text("Next") }
        }
    }
}
