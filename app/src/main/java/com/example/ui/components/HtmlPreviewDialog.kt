package com.example.ui.components

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.engine.WorkspaceCodeAutoSaver
import com.example.ui.theme.*
import java.io.File

/**
 * Modal de Preview Interativo em Tempo Real (Botão ▶ Play) para códigos HTML / Web Apps.
 * Renderiza o `index.html` inteiro com suporte completo a CSS, JavaScript, DOMStorage,
 * resolução de arquivos locais do workspace e console JS integrado.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPreviewDialog(
    title: String,
    rawHtmlCode: String,
    workspaceRoot: File? = null,
    onOpenInWorkspace: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var showConsole by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<String>() }

    val preparedHtml = remember(rawHtmlCode, workspaceRoot, reloadKey) {
        val extraCss = mutableListOf<String>()
        val extraJs = mutableListOf<String>()
        if (workspaceRoot != null && workspaceRoot.exists()) {
            val cssFile = File(workspaceRoot, "styles.css")
            if (cssFile.isFile && rawHtmlCode.contains("styles.css")) {
                runCatching { extraCss += cssFile.readText(Charsets.UTF_8) }
            }
            val jsFile = File(workspaceRoot, "app.js")
            if (jsFile.isFile && rawHtmlCode.contains("app.js")) {
                runCatching { extraJs += jsFile.readText(Charsets.UTF_8) }
            }
        }
        WorkspaceCodeAutoSaver.ensureCompleteHtmlDocument(
            rawHtml = rawHtmlCode,
            extraCss = extraCss,
            extraJs = extraJs
        )
    }

    val baseUrl = remember(workspaceRoot) {
        if (workspaceRoot != null && workspaceRoot.exists()) {
            "file://${workspaceRoot.canonicalFile.absolutePath}/"
        } else {
            "https://localhost/"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, DarkBorder, RoundedCornerShape(16.dp)),
            color = DarkBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Barra superior do Preview
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Preview ao Vivo · ${title.ifBlank { "index.html" }}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1
                            )
                            Text(
                                text = "HTML5 + CSS3 + JS (Documento Completo)",
                                fontSize = 10.sp,
                                color = AccentCyan
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (onOpenInWorkspace != null) {
                            TextButton(
                                onClick = {
                                    onOpenInWorkspace()
                                    onDismiss()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "Abrir no Workspace",
                                    tint = AccentCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Workspace", fontSize = 11.sp, color = AccentCyan)
                            }
                        }

                        TextButton(
                            onClick = { showConsole = !showConsole },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Console JS",
                                tint = if (consoleLogs.any { it.startsWith("[ERRO") }) AccentRed else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Console (${consoleLogs.size})",
                                fontSize = 11.sp,
                                color = if (showConsole) AccentCyan else TextSecondary
                            )
                        }

                        IconButton(
                            onClick = {
                                consoleLogs.clear()
                                reloadKey++
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Recarregar Preview",
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Fechar Preview",
                                tint = AccentRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                // WebView renderizando o HTML completo
                key(reloadKey) {
                    AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.White),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean = false
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                                        if (cm != null) {
                                            val tag = when (cm.messageLevel()) {
                                                ConsoleMessage.MessageLevel.ERROR -> "[ERRO L${cm.lineNumber()}]"
                                                ConsoleMessage.MessageLevel.WARNING -> "[AVISO L${cm.lineNumber()}]"
                                                else -> "[LOG L${cm.lineNumber()}]"
                                            }
                                            consoleLogs.add("$tag ${cm.message()}")
                                        }
                                        return true
                                    }
                                }
                                loadDataWithBaseURL(
                                    baseUrl,
                                    preparedHtml,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        },
                        update = { webView ->
                            webView.loadDataWithBaseURL(
                                baseUrl,
                                preparedHtml,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    )
                }

                // Console JS expansível
                AnimatedVisibility(visible = showConsole) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(Color(0xFF0D1117))
                            .border(1.dp, DarkBorder)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "Console JavaScript do Preview",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan
                        )
                        Spacer(Modifier.height(4.dp))
                        if (consoleLogs.isEmpty()) {
                            Text(
                                text = "Nenhum log ou erro emitido pelo JavaScript.",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(consoleLogs) { log ->
                                    Text(
                                        text = log,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (log.startsWith("[ERRO")) AccentRed else Color(0xFF7EE787)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
