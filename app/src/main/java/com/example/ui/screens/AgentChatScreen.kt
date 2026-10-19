package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.RichMessageSegment
import com.example.data.engine.WorkspaceCodeAutoSaver
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import java.io.File

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
    workspaceRoot: File? = null,
    onOpenCodeInWorkspace: (filePath: String, code: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var previewTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(messages.size, isThinking, messages.lastOrNull()?.toolCalls?.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // Modal de Preview (Botão ▶ Play) para códigos HTML / Web
    previewTarget?.let { (fileTitle, htmlCode) ->
        HtmlPreviewDialog(
            title = fileTitle,
            rawHtmlCode = htmlCode,
            workspaceRoot = workspaceRoot,
            onOpenInWorkspace = {
                onOpenCodeInWorkspace(fileTitle.ifBlank { "index.html" }, htmlCode)
            },
            onDismiss = { previewTarget = null }
        )
    }

    Column(modifier.fillMaxSize().background(DarkBackground)) {
        LiveStatusIndicator(
            isThinking = isThinking,
            thinkingSeconds = thinkingSeconds,
            activeRepo = activeRepo,
            commandsExecuted = commandsExecuted
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                            onToggleToolExpand = { toolId -> onToggleToolExpand(message.id, toolId) },
                            onCopyText = { text -> clipboard.setText(AnnotatedString(text)) },
                            onPlayPreview = { title, code ->
                                previewTarget = title to code
                            },
                            onOpenInWorkspace = { path, code ->
                                onOpenCodeInWorkspace(path, code)
                            }
                        )
                    }
                }
            }
        }

        Surface(color = DarkBackground, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = currentPrompt,
                    onValueChange = onPromptChange,
                    placeholder = {
                        Text("Peça um index.html completo, app, correção ou refatoração…", fontSize = 13.sp, color = TextMuted)
                    },
                    modifier = Modifier.weight(1f).testTag("agent_prompt_input"),
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
                        onSend = { if (currentPrompt.isNotBlank() && !isThinking) onSubmitPrompt() }
                    )
                )
                Spacer(Modifier.width(8.dp))
                val canSend = currentPrompt.isNotBlank() && !isThinking
                IconButton(
                    onClick = { if (isThinking) onStopExecution() else onSubmitPrompt() },
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
    onPlayPreview: (title: String, code: String) -> Unit = { _, _ -> },
    onOpenInWorkspace: (path: String, code: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    if (message.role == MessageRole.USER) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .widthIn(max = 330.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(AccentCyan.copy(alpha = 0.16f))
                    .border(0.5.dp, AccentCyan.copy(alpha = 0.35f), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(message.text, fontSize = 14.sp, color = TextPrimary, lineHeight = 20.sp)
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
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (message.thoughtSteps.isNotEmpty() || message.isThinking) {
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
                SeparatedProseAndCodeContent(
                    rawText = message.text,
                    onCopyText = onCopyText,
                    onPlayPreview = onPlayPreview,
                    onOpenInWorkspace = onOpenInWorkspace
                )
            } else if (message.isThinking && message.toolCalls.isEmpty()) {
                Text("Pensando…", fontSize = 13.sp, color = TextMuted)
            }
        }
    }
}

/**
 * Renderiza o texto explicativo e os blocos de código em áreas visualmente separadas:
 * - Cada bloco de texto fica em seu próprio container limpo de leitura.
 * - Cada bloco de código fica em um card IDE exclusivo com cabeçalho (linguagem + nome do arquivo),
 *   botão ▶ Play (Preview para HTML/Web), botão Workspace e botão Copiar.
 */
@Composable
private fun SeparatedProseAndCodeContent(
    rawText: String,
    onCopyText: (String) -> Unit,
    onPlayPreview: (title: String, code: String) -> Unit,
    onOpenInWorkspace: (path: String, code: String) -> Unit
) {
    val segments = remember(rawText) { WorkspaceCodeAutoSaver.parseRichSegments(rawText) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is RichMessageSegment.Prose -> {
                    if (segment.text.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurface.copy(alpha = 0.7f))
                                .border(0.5.dp, DarkBorder.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = segment.text,
                                fontSize = 14.sp,
                                color = TextPrimary,
                                lineHeight = 21.sp
                            )
                        }
                    }
                }
                is RichMessageSegment.CodeCard -> {
                    var copied by remember(segment.code) { mutableStateOf(false) }
                    val canPreview = remember(segment.language, segment.filePath, segment.code) {
                        WorkspaceCodeAutoSaver.isPreviewableWebCode(
                            language = segment.language,
                            filePath = segment.filePath,
                            code = segment.code
                        )
                    }
                    val lineCount = remember(segment.code) { segment.code.lines().size }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0D1117))
                            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                    ) {
                        // Cabeçalho separado do código com controles (Play Preview, Workspace, Copiar)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AccentCyan.copy(alpha = 0.16f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = segment.language.uppercase(),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentCyan
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${segment.filePath} · ${lineCount}L",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Botão ▶ Play (Preview de HTML / Web)
                                if (canPreview) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AccentGreen.copy(alpha = 0.2f))
                                            .border(0.5.dp, AccentGreen.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                onPlayPreview(segment.filePath.ifBlank { "index.html" }, segment.code)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                            .testTag("play_preview_button"),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Preview do código",
                                            tint = AccentGreen,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = "Play",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentGreen
                                        )
                                    }
                                }

                                // Botão para abrir/salvar direto no Workspace
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurface)
                                        .clickable {
                                            onOpenInWorkspace(segment.filePath, segment.code)
                                        }
                                        .padding(horizontal = 7.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = "Ver no Workspace",
                                        tint = AccentCyan,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text = "Arquivo",
                                        fontSize = 10.sp,
                                        color = AccentCyan
                                    )
                                }

                                // Botão Copiar código
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurface)
                                        .clickable {
                                            onCopyText(segment.code)
                                            copied = true
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .testTag("copy_code_button"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = "Copiar código",
                                        tint = if (copied) AccentGreen else TextSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (copied) "Copiado" else "Copiar",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (copied) AccentGreen else TextSecondary
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)

                        // Área exclusiva do código (com rolagem horizontal e numeração limpa)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = segment.code,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE6EDF3),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyAgentState(
    activeRepo: String = "",
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("DevAgent Studio", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            if (activeRepo.isNotBlank()) {
                "Workspace: $activeRepo · Cada código gerado é salvo como arquivo real."
            } else {
                "Geração de código separada do texto, Preview ▶ Play para HTML e criação automática no Workspace."
            },
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            "Crie um index.html completo de calculadora moderna com CSS e JS integrados",
            "Crie um index.html de landing page interativa com modo escuro e animações",
            "Explique a arquitetura deste projeto e pontos de melhoria"
        ).forEach { prompt ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface)
                    .border(0.5.dp, DarkBorder, RoundedCornerShape(10.dp))
                    .clickable { onSelectPreset(prompt) }
                    .padding(12.dp)
            ) {
                Text(prompt, fontSize = 12.sp, color = TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
