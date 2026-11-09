package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.HfSpace
import com.example.ui.SpaceWebPolicy

/** Runs the author's actual app without injecting token headers or a JavaScript bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OriginalSpaceView(
    space: HfSpace,
    visible: Boolean,
    onDownload: (url: String, filename: String, mimeType: String?) -> Unit,
    onExternalLink: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val download by rememberUpdatedState(onDownload)
    val externalLink by rememberUpdatedState(onExternalLink)
    val isVisible by rememberUpdatedState(visible)
    var disposed by remember(space.id) { mutableStateOf(false) }
    var browser by remember(space.id) { mutableStateOf<WebView?>(null) }
    var loading by remember(space.id) { mutableStateOf(true) }
    var error by remember(space.id) { mutableStateOf<String?>(null) }
    var fileCallback by remember(space.id) { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var permissionRequest by remember(space.id) { mutableStateOf<PermissionRequest?>(null) }
    var permissionDialog by remember(space.id) { mutableStateOf(false) }
    var showSettings by remember(space.id) { mutableStateOf(false) }
    var fullscreenView by remember(space.id) { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember(space.id) { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.onReceiveValue(uri?.takeIf { it.scheme == "content" }?.let { arrayOf(it) })
        fileCallback = null
    }
    val multiplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.filter { it.scheme == "content" }.takeIf { it.isNotEmpty() }?.toTypedArray())
        fileCallback = null
    }
    fun permittedResources(request: PermissionRequest): Array<String> = request.resources.filter {
        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
    }.toTypedArray()
    fun androidPermission(resource: String): String = if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO
    fun finishPermissionRequest() {
        val request = permissionRequest ?: return
        val resources = permittedResources(request)
        val granted = !disposed && isVisible && resources.isNotEmpty() && SpaceWebPolicy.sameOrigin(request.origin.toString(), space.directEmbedUrl) &&
            resources.all { ContextCompat.checkSelfPermission(context, androidPermission(it)) == PackageManager.PERMISSION_GRANTED }
        if (granted) request.grant(resources) else {
            request.deny()
            error = "Camera or microphone access was not allowed. File uploads still work."
            val activity = context.findActivity()
            showSettings = activity != null && resources.any {
                ContextCompat.checkSelfPermission(context, androidPermission(it)) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, androidPermission(it))
            }
        }
        permissionRequest = null
        permissionDialog = false
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        finishPermissionRequest()
    }

    DisposableEffect(space.id, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> browser?.onResume()
                Lifecycle.Event.ON_PAUSE -> browser?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            permissionRequest?.deny()
            permissionRequest = null
            fullscreenCallback?.onCustomViewHidden()
            browser?.apply { stopLoading(); webChromeClient = null; destroy() }
            browser = null
        }
    }

    Column(modifier) {
        if (visible && loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (visible && error != null) {
            Text(error.orEmpty(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = { error = null; browser?.reload() }) { Text("Retry") }
                TextButton(onClick = { externalLink(space.hfWebUrl) }) { Text("Open in browser") }
                if (showSettings) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("App settings") }
            }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    browser = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = true
                    // The Hub page embeds its Space on a separate hf.space origin.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val url = request.url.toString()
                            if (SpaceWebPolicy.canNavigateInside(url, space.directEmbedUrl)) return false
                            if (request.hasGesture() && SpaceWebPolicy.isWebLink(url)) externalLink(url)
                            return true
                        }
                        override fun onPageFinished(view: WebView, url: String) { loading = false }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                            if (request.isForMainFrame) {
                                loading = false
                                error = "Could not load this Space. Check your connection or open it in a browser."
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = callback
                            val types = params.acceptTypes.flatMap { it.split(',') }.mapNotNull { raw ->
                                val type = raw.trim()
                                if (type.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1))
                                else type.takeIf { it.matches(Regex("[a-zA-Z0-9*.+-]+/[a-zA-Z0-9*.+-]+")) }
                            }.ifEmpty { listOf("*/*") }.toTypedArray()
                            try {
                                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) multiplePicker.launch(types)
                                else singlePicker.launch(types)
                            } catch (_: RuntimeException) {
                                fileCallback?.onReceiveValue(null)
                                fileCallback = null
                                error = "No file picker is available. Try opening the Space in a browser."
                            }
                            return true
                        }
                        override fun onPermissionRequest(request: PermissionRequest) {
                            post {
                                if (disposed || !isVisible || !SpaceWebPolicy.sameOrigin(request.origin.toString(), space.directEmbedUrl) || permittedResources(request).isEmpty()) {
                                    request.deny()
                                } else {
                                    permissionRequest?.deny()
                                    permissionRequest = request
                                    permissionDialog = true
                                }
                            }
                        }
                        override fun onPermissionRequestCanceled(request: PermissionRequest) {
                            if (permissionRequest == request) { permissionRequest = null; permissionDialog = false }
                        }
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                            fullscreenView = view
                            fullscreenCallback = callback
                        }
                        override fun onHideCustomView() {
                            fullscreenView = null
                            fullscreenCallback?.onCustomViewHidden()
                            fullscreenCallback = null
                        }
                    }
                    setDownloadListener { url, _, disposition, mime, _ ->
                        if (SpaceWebPolicy.isWebLink(url)) download(url, URLUtil.guessFileName(url, disposition, mime), mime)
                        else error = "This download needs a browser. Use Open in browser."
                    }
                    loadUrl(space.hfWebUrl)
                }
            },
            update = { it.visibility = if (visible) View.VISIBLE else View.GONE },
            modifier = if (visible) Modifier.fillMaxSize() else Modifier.size(0.dp)
        )
    }
    if (permissionDialog && permissionRequest != null) {
        AlertDialog(
            onDismissRequest = { permissionRequest?.deny(); permissionRequest = null; permissionDialog = false },
            title = { Text("Allow capture for this Space?") },
            text = { Text("${space.title} requested camera or microphone access for its capture controls. You can use file uploads without granting access.") },
            confirmButton = { TextButton(onClick = {
                permissionDialog = false
                val request = permissionRequest ?: return@TextButton
                val missing = permittedResources(request).map(::androidPermission).distinct().filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) finishPermissionRequest() else permissions.launch(missing.toTypedArray())
            }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { permissionRequest?.deny(); permissionRequest = null; permissionDialog = false }) { Text("Not now") } }
        )
    }
    fullscreenView?.let { fullView ->
        Dialog(onDismissRequest = {
            fullscreenView = null; fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
        }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            AndroidView(factory = { ctx ->
                (fullView.parent as? ViewGroup)?.removeView(fullView)
                FrameLayout(ctx).apply { addView(fullView, ViewGroup.LayoutParams(-1, -1)) }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
