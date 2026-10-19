package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.ui.theme.*

/**
 * Barra superior enxuta: modo + toggle autônomo (em BUILD) + histórico + projeto/repo + velocidade.
 */
@Composable
fun AgentModeSelector(
    selectedMode: AgentMode,
    onModeSelected: (AgentMode) -> Unit,
    selectedSpeed: ReasoningSpeed,
    onSpeedSelected: (ReasoningSpeed) -> Unit,
    activeRepo: String,
    onSelectRepoClick: () -> Unit,
    autoApproveEdits: Boolean = true,
    onToggleAutoApprove: () -> Unit = {},
    onOpenHistoryClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkSurface)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            AgentMode.entries.forEach { mode ->
                val selected = mode == selectedMode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (selected) AccentCyan.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onModeSelected(mode) }
                        .padding(vertical = 7.dp)
                        .testTag("mode_tab_${mode.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mode.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) AccentCyan else TextSecondary
                    )
                }
            }
        }

        if (selectedMode == AgentMode.BUILD) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (autoApproveEdits) AccentGreen.copy(alpha = 0.18f)
                        else AccentYellow.copy(alpha = 0.18f)
                    )
                    .clickable { onToggleAutoApprove() }
                    .padding(horizontal = 7.dp, vertical = 6.dp)
                    .testTag("toggle_auto_approve"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (autoApproveEdits) "⚡ Auto" else "🔒 Diff",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (autoApproveEdits) AccentGreen else AccentYellow
                )
            }
        }

        IconButton(
            onClick = onOpenHistoryClick,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(DarkSurface)
                .testTag("open_history_button")
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = "Histórico de Conversas",
                tint = AccentCyan,
                modifier = Modifier.size(16.dp)
            )
        }

        IconButton(
            onClick = onSelectRepoClick,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(DarkSurface)
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = "Projeto / Repositório",
                tint = if (activeRepo.isNotBlank()) AccentGreen else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }

        IconButton(
            onClick = {
                val next = if (selectedSpeed == ReasoningSpeed.FAST) ReasoningSpeed.DEEP_THINKING else ReasoningSpeed.FAST
                onSpeedSelected(next)
            },
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(DarkSurface)
        ) {
            Text(
                if (selectedSpeed == ReasoningSpeed.FAST) "⚡" else "🧠",
                fontSize = 14.sp
            )
        }
    }
}

/** Indicador ao vivo enquanto o agente pensa. */
@Composable
fun LiveStatusIndicator(
    isThinking: Boolean,
    thinkingSeconds: Double,
    activeRepo: String,
    commandsExecuted: Int,
    modifier: Modifier = Modifier
) {
    if (!isThinking) return
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AccentCyan.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(AccentCyan.copy(alpha = alpha)))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Pensando…",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AccentCyan
            )
            Text(
                buildString {
                    append(String.format("%.1fs", thinkingSeconds))
                    if (commandsExecuted > 0) append(" · $commandsExecuted ferramentas")
                    if (activeRepo.isNotBlank()) append(" · $activeRepo")
                },
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

/** Raciocínio ao vivo: expandido enquanto pensa. */
@Composable
fun ThoughtProcessCard(
    thoughtSteps: List<String>,
    isThinking: Boolean,
    modifier: Modifier = Modifier
) {
    val steps = if (thoughtSteps.isEmpty() && isThinking) {
        listOf("Analisando sua mensagem…")
    } else {
        thoughtSteps
    }
    if (steps.isEmpty()) return

    var expanded by remember(isThinking) { mutableStateOf(true) }
    LaunchedEffect(isThinking) {
        if (isThinking) expanded = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isThinking) {
                val pulse = rememberInfiniteTransition(label = "think")
                val a by pulse.animateFloat(
                    0.4f, 1f,
                    infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
                    label = "a"
                )
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(AccentCyan.copy(alpha = a))
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (isThinking) "Raciocínio em tempo real" else "Raciocínio (${steps.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isThinking) AccentCyan else TextSecondary
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Recolher" else "Expandir",
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                steps.forEachIndexed { index, step ->
                    val isLatest = index == steps.lastIndex && isThinking
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "→",
                            fontSize = 12.sp,
                            color = if (isLatest) AccentCyan else TextMuted,
                            modifier = Modifier.padding(end = 6.dp, top = 1.dp)
                        )
                        Text(
                            step,
                            fontSize = 13.sp,
                            color = if (isLatest) TextPrimary else TextSecondary,
                            lineHeight = 18.sp,
                            fontWeight = if (isLatest) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

/** Tool card compacto, exibe diff colorido quando expandido. */
@Composable
fun ToolExecutionCard(
    tool: ToolCallInfo,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface.copy(alpha = 0.6f))
            .clickable { onToggleExpand() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val statusColor = when (tool.status) {
                ExecutionStatus.RUNNING -> AccentCyan
                ExecutionStatus.SUCCESS -> AccentGreen
                ExecutionStatus.FAILED -> AccentRed
                ExecutionStatus.AWAITING_CONFIRMATION -> AccentYellow
                ExecutionStatus.PENDING -> TextMuted
            }
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Code,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    tool.type.displayName,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
                if (tool.detail.isNotBlank()) {
                    Text(
                        "  ${tool.detail}",
                        fontSize = 12.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (tool.durationMs > 0) {
                Text("${tool.durationMs}ms", fontSize = 10.sp, color = TextMuted)
            }
        }
        AnimatedVisibility(visible = tool.isExpanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (tool.diffLines.isNotEmpty()) {
                    DiffView(lines = tool.diffLines)
                }
                if (tool.output.isNotBlank()) {
                    if (tool.diffLines.isNotEmpty()) Spacer(Modifier.height(6.dp))
                    TerminalConsoleBox(text = tool.output)
                }
            }
        }
    }
}

@Composable
fun TerminalConsoleBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D1117))
            .padding(8.dp)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF7EE787),
            lineHeight = 15.sp
        )
    }
}

@Composable
fun DiffView(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D1117))
            .padding(6.dp)
    ) {
        lines.forEach { line ->
            val fg = when (line.type) {
                DiffLineType.ADDED -> Color(0xFF7EE787)
                DiffLineType.REMOVED -> Color(0xFFFF7B72)
                DiffLineType.HEADER -> AccentCyan
                DiffLineType.CONTEXT -> TextSecondary
            }
            Text(
                line.text,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = fg,
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}
