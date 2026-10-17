package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
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

        // Input limpo — sem chips de sugestão ocupando a tela
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
                            "Mensagem…",
                            fontSize = 14.sp,
                            color = TextMuted
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("agent_prompt_input"),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        focusedBorderColor = DarkBorder,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentCyan
                    ),
                    maxLines = 5,
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
        // Bolha do usuário alinhada à direita, só o texto
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(AccentCyan.copy(alpha = 0.14f))
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
        // Resposta do agente: texto limpo, sem card pesado
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
                Text(
                    // remove markdown ## * * excessivo na leitura rápida
                    message.text
                        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
                        .replace("**", ""),
                    fontSize = 15.sp,
                    color = TextPrimary,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopyText(message.text) }
                )
            } else if (message.isThinking && message.toolCalls.isEmpty()) {
                Text(
                    "…",
                    fontSize = 15.sp,
                    color = TextMuted
                )
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
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "DevAgent",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pergunte, planeje ou peça mudanças no código.\nConecte um repo quando quiser contexto real.",
            fontSize = 14.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(28.dp))
        listOf(
            "O que você consegue fazer?",
            "Liste os arquivos do workspace",
            "Explique a estrutura do projeto"
        ).forEach { sample ->
            Text(
                sample,
                fontSize = 14.sp,
                color = AccentCyan,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectPreset(sample) }
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}
