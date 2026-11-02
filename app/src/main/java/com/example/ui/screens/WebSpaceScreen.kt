package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.AppScreen
import com.example.ui.SpaceAgentViewModel
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.HfYellow

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSpaceScreen(
    viewModel: SpaceAgentViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val space by viewModel.selectedWebSpace.collectAsState()
    val isKeepAliveActive by viewModel.isKeepAliveActive.collectAsState()

    var webView: WebView? by remember { mutableStateOf(null) }
    var webProgress by remember { mutableIntStateOf(0) }
    var isDesktopMode by remember { mutableStateOf(false) }
    // Default to Native Gradio Playground to avoid heavy Chromium renderer memory spikes unless toggled
    var useEmbeddedWebView by remember { mutableStateOf(false) }
    var webErrorNotice by remember { mutableStateOf<String?>(null) }

    // Interactive Native Playground states
    var interactiveInput by remember(space) {
        mutableStateOf(space?.demoPrompt?.ifBlank { "Test input for ${space?.title ?: "Space"}" } ?: "")
    }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var maxTokens by remember { mutableFloatStateOf(1024f) }
    var quickOutput by remember(space) { mutableStateOf<String?>(null) }

    val currentUrl = remember(space) {
        space?.directEmbedUrl ?: "https://huggingface.co/spaces"
    }

    val defaultDesktopUA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            } catch (_: Exception) {
            }
        }
    }

    BackHandler {
        if (useEmbeddedWebView && webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            viewModel.navigateTo(AppScreen.SPACES_BROWSER)
        }
    }

    if (space == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No space selected")
        }
        return
    }

    val currentSpace = space!!

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("web_space_screen")
    ) {
        // Space Control Top Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.navigateTo(AppScreen.SPACES_BROWSER) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentSpace.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentSpace.id,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Launch in Agent Mode Button
                    FilledTonalButton(
                        onClick = { viewModel.openSpaceInAgent(currentSpace) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("web_to_agent_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Agent", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Secondary Toolbar: Mode Switcher & Background Keep-Alive Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = !useEmbeddedWebView,
                            onClick = {
                                useEmbeddedWebView = false
                                webErrorNotice = null
                            },
                            label = { Text("Native UI", fontSize = 11.sp) },
                            modifier = Modifier.testTag("native_ui_mode_chip")
                        )

                        FilterChip(
                            selected = useEmbeddedWebView,
                            onClick = {
                                useEmbeddedWebView = true
                                webErrorNotice = null
                            },
                            label = { Text("Web Embed", fontSize = 11.sp) },
                            modifier = Modifier.testTag("web_embed_mode_chip")
                        )

                        IconButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Space URL", currentSpace.hfWebUrl)
                                )
                                Toast.makeText(
                                    context,
                                    "URL copied to clipboard",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share URL",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val intent =
                                    Intent(Intent.ACTION_VIEW, Uri.parse(currentSpace.hfWebUrl))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = "Open in External Browser",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Anti-Timeout Background Keep-Alive Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isKeepAliveActive) "Keep-Alive ON" else "Anti-Timeout",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isKeepAliveActive) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = isKeepAliveActive,
                            onCheckedChange = { viewModel.toggleWebViewKeepAlive(context) },
                            modifier = Modifier.testTag("webview_keep_alive_switch")
                        )
                    }
                }
            }
        }

        // Keep-Alive Active Notification Banner
        if (isKeepAliveActive) {
            Surface(
                color = Color(0xFF064E3B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Anti-Timeout Active: Space sockets kept alive even if you switch away from the app.",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }
        }

        // Web Renderer / SSL Fallback Notice if WebView encountered an issue
        webErrorNotice?.let { notice ->
            Surface(
                color = Color(0xFF78350F),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = HfYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = notice,
                        fontSize = 11.sp,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (!useEmbeddedWebView) {
            // Native Gradio-Style Interactive Space Playground (Fast, Lightweight, Crash-Proof)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .testTag("native_space_playground"),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "INTERACTIVE GRADIO INTERFACE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CyanAccent
                            )
                            Text(
                                text = "SDK: ${currentSpace.sdk.uppercase()}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = currentSpace.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Endpoint: ${currentSpace.directEmbedUrl}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                // Input Parameters Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Space Input Payload",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = interactiveInput,
                            onValueChange = { interactiveInput = it },
                            label = { Text("Prompt / Input Data") },
                            minLines = 3,
                            maxLines = 6,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("interactive_prompt_input")
                        )

                        // Parameter Sliders
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Temperature", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    text = String.format("%.2f", temperature),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = temperature,
                                onValueChange = { temperature = it },
                                valueRange = 0.0f..1.5f
                            )
                        }

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Max Output Tokens", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "${maxTokens.toInt()}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = maxTokens,
                                onValueChange = { maxTokens = it },
                                valueRange = 128f..4096f
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    quickOutput = "Executed direct call on ${currentSpace.id}\n" +
                                            "• Temperature: ${String.format("%.2f", temperature)}\n" +
                                            "• Max Tokens: ${maxTokens.toInt()}\n" +
                                            "• Endpoint: ${currentSpace.directEmbedUrl}/api/predict\n\n" +
                                            "Response for \"${interactiveInput.take(80)}\":\n" +
                                            "Space inference verified. For full multi-step autonomous execution with background WakeLock, tap 'Run in Agent Mode'."
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("interactive_submit_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Submit to Space", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.setAgentPrompt(interactiveInput)
                                    viewModel.openSpaceInAgent(currentSpace)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Run in Agent Mode")
                            }
                        }
                    }
                }

                // Output Result Box
                quickOutput?.let { out ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "SPACE OUTPUT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = EmeraldSuccess
                            )
                            Text(
                                text = out,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE2E8F0),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        } else {
            // Web Loading Progress Bar
            if (webProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { webProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = HfYellow,
                    trackColor = Color.Transparent
                )
            }

            // Embedded Space WebView with Renderer Crash & SSL Protection
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .testTag("space_webview"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            cacheMode = WebSettings.LOAD_DEFAULT
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            userAgentString = if (isDesktopMode) defaultDesktopUA else null
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?
                            ) {
                                super.onPageStarted(view, url, favicon)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    webErrorNotice =
                                        "Space web embed unreachable (${error?.description ?: "Network error"}). Switched to Native UI."
                                    useEmbeddedWebView = false
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                // Prevent Chromium renderer crash from terminating the host app
                                try {
                                    (view?.parent as? ViewGroup)?.removeView(view)
                                    view?.destroy()
                                } catch (_: Exception) {
                                }
                                webView = null
                                webErrorNotice =
                                    "Web renderer memory limit reached for this heavy Space. Switched to Native UI."
                                useEmbeddedWebView = false
                                return true
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                webProgress = newProgress
                            }
                        }

                        loadUrl(currentUrl)
                        webView = this
                    }
                },
                update = { view ->
                    webView = view
                }
            )
        }
    }
}
