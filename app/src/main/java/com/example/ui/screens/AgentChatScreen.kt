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
import androidx.compose.material.icons.filled.ContentCopy
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
                            onCopyText = { text -> clipboard.setText(AnnotatedString(text)) }
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
                        Text("Descreva o app, bug ou refatoração…", fontSize = 13.sp, color = TextMuted)
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
        Column(modifier.fillMaxWidth()) {
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
                Text(
                    message.text,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth().clickable { onCopyText(message.text) }
                )
            } else if (message.isThinking && message.toolCalls.isEmpty()) {
                Text("Pensando…", fontSize = 13.sp, color = TextMuted)
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
        Spacer(Modifier.height(8.dp))
        Text(
            if (activeRepo.isNotBlank()) "Workspace: $activeRepo" else "Crie ou evolua projetos com o agente.",
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            "Crie um HTML de landing page com CSS moderno",
            "Explique a arquitetura deste projeto"
        ).forEach { prompt ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface)
                    .clickable { onSelectPreset(prompt) }
                    .padding(12.dp)
            ) {
                Text(prompt, fontSize = 12.sp, color = TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
