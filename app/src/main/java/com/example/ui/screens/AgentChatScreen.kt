package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

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
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager: ClipboardManager = LocalClipboardManager.current

    val quickPrompts = listOf(
        "Adicionar rate limiting JWT no login",
        "Executar suite de testes e corrigir falhas",
        "Analisar vulnerabilidades de segurança",
        "Refatorar rota de autenticação para Clean Architecture",
        "Gerar testes unitários para endpoint de refresh token"
    )

    LaunchedEffect(messages.size, isThinking, messages.lastOrNull()?.toolCalls?.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Live Status Bar
        LiveStatusIndicator(
            isThinking = isThinking,
            thinkingSeconds = thinkingSeconds,
            activeRepo = activeRepo,
            commandsExecuted = commandsExecuted
        )

        // Chat Messages Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                // Empty State / Welcome
                EmptyAgentState(
                    onSelectPreset = onQuickPromptSelected,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            onToggleToolExpand = { toolId -> onToggleToolExpand(message.id, toolId) },
                            onCopyText = { text -> clipboardManager.setText(AnnotatedString(text)) }
                        )
                    }
                }
            }
        }

        // Quick Suggestions Horizontal Row
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(quickPrompts) { prompt ->
                SuggestionChip(
                    onClick = { onQuickPromptSelected(prompt) },
                    label = {
                        Text(
                            text = prompt,
                            fontSize = 11.sp,
                            maxLines = 1,
                            color = TextPrimary
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = DarkSurfaceVariant
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = DarkBorder
                    ),
                    modifier = Modifier.testTag("quick_chip_${prompt.take(10)}")
                )
            }
        }

        // Input Bar
        Surface(
            color = DarkSurface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = currentPrompt,
                    onValueChange = onPromptChange,
                    placeholder = {
                        Text(
                            text = "Peça para planejar, alterar código ou rodar comandos...",
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("agent_prompt_input"),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (currentPrompt.isNotBlank() && !isThinking) {
                            onSubmitPrompt()
                        }
                    })
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (isThinking) {
                    IconButton(
                        onClick = onStopExecution,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(AccentRed.copy(alpha = 0.2f))
                            .border(1.dp, AccentRed, CircleShape)
                            .testTag("stop_execution_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Parar Agente",
                            tint = AccentRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSubmitPrompt,
                        enabled = currentPrompt.isNotBlank(),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (currentPrompt.isNotBlank()) AccentCyan else DarkSurfaceVariant)
                            .testTag("submit_prompt_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Enviar prompt",
                            tint = if (currentPrompt.isNotBlank()) DarkBackground else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
        // User bubble
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp))
                    .background(AccentCyan.copy(alpha = 0.15f))
                    .border(1.dp, AccentCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    lineHeight = 18.sp
                )
            }
        }
    } else {
        // Agent message
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurfaceVariant.copy(alpha = 0.4f))
                .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            // Agent header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(AccentCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "DevAgent",
                            tint = AccentCyan,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DevAgent",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• ${message.mode.label}",
                        fontSize = 11.sp,
                        color = AccentCyan
                    )
                }

                IconButton(
                    onClick = { onCopyText(message.text) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copiar",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // CoT Thought Steps if available
            if (message.thoughtSteps.isNotEmpty()) {
                ThoughtProcessCard(
                    thoughtSteps = message.thoughtSteps,
                    isThinking = message.isThinking
                )
            }

            // Tool Execution Cards if available
            if (message.toolCalls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                message.toolCalls.forEach { tool ->
                    ToolExecutionCard(
                        tool = tool,
                        onToggleExpand = { onToggleToolExpand(tool.id) }
                    )
                }
            }

            // Final Text
            if (message.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message.text,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    lineHeight = 19.sp
                )
            }

            // Metrics footer
            message.metrics?.let { metrics ->
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⚡ Tempo: ${metrics.thinkingSeconds}s | ${metrics.sandboxLatencyMs}ms sandbox",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "Δ +${metrics.linesChanged} linhas | ${metrics.commandsExecuted} cmds",
                        fontSize = 10.sp,
                        color = AccentGreen
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyAgentState(
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AccentCyan.copy(alpha = 0.15f))
                .border(1.dp, AccentCyan, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "DevAgent Studio",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Agente autônomo com suporte a GitHub API, planejamento CoT e execução isolada em Docker Sandbox.",
            fontSize = 12.sp,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val samples = listOf(
                "Adicionar rate limiting JWT no login",
                "Executar suite de testes e corrigir falhas",
                "Refatorar rota de autenticação para Clean Architecture"
            )

            samples.forEach { sample ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                        .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelectPreset(sample) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = sample,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}
