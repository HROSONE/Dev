package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun AgentChatScreen(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    thinkingSeconds: Double,
    activeRepo: String,
    commandsExecuted: Int,
    currentPrompt: String,
    onPromptChange: (String) -> Unit,
    onSubmitPrompt: () -> Unit,
    onStopExecution: () -> Unit,
    onToggleToolExpand: (messageId: String, toolId: String) -> Unit,
    onQuickPromptSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(messages.size, isThinking, messages.lastOrNull()?.toolCalls?.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        LiveStatusIndicator(
            isThinking = isThinking,
            thinkingSeconds = thinkingSeconds,
            activeRepo = activeRepo,
            commandsExecuted = commandsExecuted
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyAgentState(
                    activeRepo = activeRepo,
                    onSelectPreset = onQuickPromptSelected,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            onToggleToolExpand = { toolId ->
                                onToggleToolExpand(message.id, toolId)
                            },
                            onCopyText = { text ->
                                clipboard.setText(AnnotatedString(text))
                            }
                        )
                    }
                }
            }
        }

        Surface(
            color = DarkBackground,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = currentPrompt,
                    onValueChange = onPromptChange,
                    placeholder = {
                        Text(
                            "Descreva o app para criar do zero, bug para corrigir ou refatoração…",
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("agent_prompt_input"),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentCyan
                    ),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (currentPrompt.isNotBlank() && !isThinking) onSubmitPrompt()
                        }
                    )
                )
                Spacer(Modifier.width(8.dp))
                val canSend = currentPrompt.isNotBlank() && !isThinking
                IconButton(
                    onClick = {
                        if (isThinking) onStopExecution() else onSubmitPrompt()
                    },
                    enabled = isThinking || canSend,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isThinking -> AccentRed.copy(alpha = 0.2f)
                                canSend -> AccentCyan
                                else -> DarkSurface
                            }
                        )
                        .testTag(if (isThinking) "stop_execution_button" else "submit_prompt_button")
                ) {
                    Icon(
                        if (isThinking) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isThinking) "Parar" else "Enviar",
                        tint = when {
                            isThinking -> AccentRed
                            canSend -> DarkBackground
                            else -> TextMuted
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    onToggleToolExpand: (String) -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.role == MessageRole.USER) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 330.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(AccentCyan.copy(alpha = 0.16f))
                    .border(0.5.dp, AccentCyan.copy(alpha = 0.35f), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    message.text,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 20.sp
                )
            }
        }
    } else if (message.role == MessageRole.SYSTEM) {
        Text(
            message.text,
            fontSize = 12.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            if (message.thoughtSteps.isNotEmpty()) {
                ThoughtProcessCard(
                    thoughtSteps = message.thoughtSteps,
                    isThinking = message.isThinking
                )
            }
            if (message.toolCalls.isNotEmpty()) {
                message.toolCalls.forEach { tool ->
                    ToolExecutionCard(
                        tool = tool.copy(isExpanded = tool.isExpanded),
                        onToggleExpand = { onToggleToolExpand(tool.id) }
                    )
                }
            }
            if (message.text.isNotBlank()) {
                CodeAwareMessageContent(
                    rawText = message.text,
                    onCopyText = onCopyText
                )
            } else if (message.isThinking && message.toolCalls.isEmpty()) {
                Text(
                    "Analisando arquitetura e planejando passos…",
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }
        }
    }
}

private sealed interface MessageSegment {
    data class Prose(val text: String) : MessageSegment
    data class CodeBlock(val language: String, val code: String) : MessageSegment
}

private val CODE_FENCE_REGEX = Regex("```([a-zA-Z0-9_+.-]*)\\s*\\n([\\s\\S]*?)```")

private fun parseMessageSegments(rawText: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    var lastIndex = 0
    for (match in CODE_FENCE_REGEX.findAll(rawText)) {
        if (match.range.first > lastIndex) {
            val prose = rawText.substring(lastIndex, match.range.first).trim()
            if (prose.isNotEmpty()) segments += MessageSegment.Prose(prose)
        }
        val lang = match.groupValues[1].trim().ifEmpty { "code" }
        val code = match.groupValues[2].trimEnd()
        segments += MessageSegment.CodeBlock(lang, code)
        lastIndex = match.range.last + 1
    }
    if (lastIndex < rawText.length) {
        val tail = rawText.substring(lastIndex).trim()
        if (tail.isNotEmpty()) segments += MessageSegment.Prose(tail)
    }
    return if (segments.isEmpty()) listOf(MessageSegment.Prose(rawText)) else segments
}

/** Detecta HTML pelo idioma da fence ou pelo conteúdo. */
private fun isHtmlCode(language: String, code: String): Boolean {
    val lang = language.lowercase().trim()
    if (lang in setOf("html", "htm", "xhtml")) return true
    val sample = code.trimStart().take(400).lowercase()
    return sample.startsWith("<!doctype html") ||
        sample.startsWith("<html") ||
        (sample.contains("<html") && sample.contains("</html>")) ||
        (sample.contains("<body") && (sample.contains("<div") || sample.contains("<p") || sample.contains("<h1")))
}

@Composable
private fun CodeAwareMessageContent(
    rawText: String,
    onCopyText: (String) -> Unit
) {
    val segments = remember(rawText) { parseMessageSegments(rawText) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is MessageSegment.Prose -> {
                    val cleanProse = segment.text
                        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
                        .replace("**", "")
                    Text(
                        text = cleanProse,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        lineHeight = 21.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCopyText(segment.text) }
                    )
                }
                is MessageSegment.CodeBlock -> {
                    HtmlAwareCodeBlock(
                        language = segment.language,
                        code = segment.code,
                        blockKey = "${index}_${segment.language}_${segment.code.hashCode()}",
                        onCopyText = onCopyText
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlAwareCodeBlock(
    language: String,
    code: String,
    blockKey: String,
    onCopyText: (String) -> Unit
) {
    val showPlay = remember(language, code) { isHtmlCode(language, code) }
    var previewOpen by remember(blockKey) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D1117))
            .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurfaceVariant)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.uppercase().ifBlank { "CODE" },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = AccentCyan
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showPlay) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { previewOpen = !previewOpen }
                            .background(
                                if (previewOpen) AccentGreen.copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("html_preview_play"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (previewOpen) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = if (previewOpen) "Fechar preview" else "Preview HTML",
                            tint = if (previewOpen) AccentGreen else AccentCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (previewOpen) "Fechar" else "Play",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (previewOpen) AccentGreen else AccentCyan
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCopyText(code) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copiar código",
                        tint = TextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Copiar",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text = code,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE6EDF3),
                lineHeight = 16.sp
            )
        }

        if (showPlay && previewOpen) {
            HorizontalDivider(color = DarkBorder, thickness = 1.dp)
            Text(
                text = "Preview",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = AccentGreen,
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp)
            )
            HtmlPreviewWebView(
                html = code,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, AccentGreen.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreviewWebView(
    html: String,
    modifier: Modifier = Modifier
) {
    val document = remember(html) { wrapHtmlDocument(html) }
    AndroidView(
        modifier = modifier.background(Color.White),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(AndroidColor.WHITE)
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                /* baseUrl = */ null,
                /* data = */ document,
                /* mimeType = */ "text/html",
                /* encoding = */ "UTF-8",
                /* historyUrl = */ null
            )
        }
    )
}

/** Garante documento HTML completo para o WebView renderizar bem. */
private fun wrapHtmlDocument(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.contains("<html", ignoreCase = true)) return trimmed
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<style>
  body { font-family: system-ui, -apple-system, sans-serif; margin: 12px; color: #111; background: #fff; }
</style>
</head>
<body>
$trimmed
</body>
</html>
""".trimIndent()
}

@Composable
fun EmptyAgentState(
    activeRepo: String = "",
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(AccentCyan.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = "SWE-AGENT HARNESS · REPOMAP · SYNTAXGUARD",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = AccentCyan
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "DevAgent Studio",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (activeRepo.isNotBlank()) {
                "Workspace ativo: $activeRepo\nCrie módulos do zero ou evolua o código existente com edição cirúrgica."
            } else {
                "Desenvolvimento autônomo do zero ao deploy.\nCrie um projeto novo ou aprimore qualquer repositório existente."
            },
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "🚀 CRIAR DO ZERO (SCAFFOLDING COMPLETO)",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = AccentGreen,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        listOf(
            "Crie do zero uma API modular completa no workspace com rotas, validação, testes e README",
            "Construa do zero um Web App interativo de Kanban com persistência no localStorage e tema dark"
        ).forEach { prompt ->
            QuickPromptCard(
                label = prompt,
                accentColor = AccentGreen,
                onClick = { onSelectPreset(prompt) }
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "🛠️ APRIMORAR PROJETO EXISTENTE",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = AccentCyan,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        listOf(
            "Mapeie os símbolos e a arquitetura deste projeto e aponte os 3 melhores pontos de evolução",
            "Inspecione o código com find_symbol e verify_syntax, encontre possíveis bugs e corrija-os"
        ).forEach { prompt ->
            QuickPromptCard(
                label = prompt,
                accentColor = AccentCyan,
                onClick = { onSelectPreset(prompt) }
            )
            Spacer(modifier.height(6.dp))
        }
    }
}

@Composable
private fun QuickPromptCard(
    label: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .border(0.5.dp, DarkBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accentColor)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = TextPrimary,
            lineHeight = 16.sp
        )
    }
}
