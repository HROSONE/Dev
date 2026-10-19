package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontStyle
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

/** Indicador ao vivo enquanto o agente trabalha: mostra a atividade atual, tempo e ferramentas. */
@Composable
fun LiveStatusIndicator(
    isThinking: Boolean,
    thinkingSeconds: Double,
    activeRepo: String,
    commandsExecuted: Int,
    modifier: Modifier = Modifier,
    activity: String = ""
) {
    if (!isThinking) return
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Column(modifier = modifier.fillMaxWidth().background(AccentCyan.copy(alpha = 0.08f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(AccentCyan.copy(alpha = alpha)))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    activity.ifBlank { "Pensando…" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(String.format(java.util.Locale.US, "%.1fs", thinkingSeconds))
                        if (commandsExecuted > 0) append(" · $commandsExecuted ferramentas")
                        if (activeRepo.isNotBlank()) append(" · $activeRepo")
                    },
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = AccentCyan,
            trackColor = Color.Transparent
        )
    }
}

/** Ponto pulsante usado em itens ao vivo. */
@Composable
private fun LiveDot(color: Color = AccentCyan, size: Int = 7) {
    val pulse = rememberInfiniteTransition(label = "dot")
    val a by pulse.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    Box(Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = a)))
}

/**
 * Pensamento do modelo transmitido ao vivo (token a token). Enquanto está ao vivo fica aberto e
 * mostra as últimas linhas; ao terminar recolhe para um resumo clicável.
 */
@Composable
fun LiveReasoningBlock(
    text: String,
    isLive: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val showBody = isLive || expanded
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AccentPurple.copy(alpha = 0.07f))
            .clickable(enabled = !isLive) { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLive) {
                LiveDot(AccentPurple)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Default.Psychology, null, tint = AccentPurple, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (isLive) "Pensando…" else "Pensamento · ${text.length} caracteres",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AccentPurple
            )
            Spacer(Modifier.weight(1f))
            if (!isLive) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Recolher" else "Expandir",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        AnimatedVisibility(visible = showBody) {
            val body = if (isLive) text.lines().takeLast(10).joinToString("\n") else text
            Text(
                body + if (isLive) " ▍" else "",
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontStyle = FontStyle.Italic,
                color = TextSecondary
            )
        }
    }
}

/** Texto que o agente escreve entre ferramentas ("Vou ler…"), estilo Claude Code. */
@Composable
fun NarrationBlock(text: String, isLive: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text("●", fontSize = 11.sp, color = TextPrimary, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
        Text(
            text + if (isLive) " ▍" else "",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = TextPrimary
        )
    }
}

/** Linha de status do harness. */
@Composable
fun StatusLine(text: String, isLive: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLive) {
            LiveDot(AccentCyan, size = 6)
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(TextMuted.copy(alpha = 0.6f)))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 12.sp,
            color = if (isLive) AccentCyan else TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Raciocínio legado (mensagens salvas antes da linha do tempo). */
@Composable
fun ThoughtProcessCard(
    thoughtSteps: List<String>,
    isThinking: Boolean,
    modifier: Modifier = Modifier
) {
    if (thoughtSteps.isEmpty()) return
    var expanded by remember { mutableStateOf(isThinking) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface.copy(alpha = 0.85f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text("Raciocínio (${thoughtSteps.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                thoughtSteps.forEach { step ->
                    Text("→ $step", fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
                }
            }
        }
    }
}

private data class ToolLabel(val verb: String, val argument: String)

private fun toolLabel(tool: ToolCallInfo): ToolLabel {
    val target = tool.target.ifBlank {
        tool.detail.removePrefix("path=").substringBefore(" (").substringBefore(":").trim()
    }
    return when (tool.type) {
        ToolType.READ_FILE -> ToolLabel("Read", target)
        ToolType.READ_FILE_LINES -> ToolLabel("Read", tool.detail)
        ToolType.LIST_FILES -> ToolLabel("List", target.ifBlank { "." })
        ToolType.SEARCH_FILES -> ToolLabel("Search", tool.detail.removePrefix("query="))
        ToolType.FIND_SYMBOL -> ToolLabel("Symbol", tool.detail.removePrefix("symbol="))
        ToolType.WRITE_FILE -> ToolLabel(
            if (tool.fileChange?.kind == FileChangeKind.MODIFIED) "Write" else "Create",
            target
        )
        ToolType.EDIT_FILE -> ToolLabel("Edit", target)
        ToolType.PATCH_LINES -> ToolLabel("Edit", tool.detail)
        ToolType.DELETE_FILE -> ToolLabel("Delete", target)
        ToolType.VERIFY_SYNTAX -> ToolLabel("Check", target)
        ToolType.RUN_COMMAND -> ToolLabel("Bash", tool.detail.removePrefix("cmd="))
        ToolType.READ_GITHUB_REPO -> ToolLabel("GitHub", tool.detail)
        ToolType.COMMIT_GITHUB -> ToolLabel("Commit", tool.detail)
    }
}

/** Card de ferramenta estilo Claude Code: `Verbo(alvo)`, selo NOVO/EDITADO, +/− linhas e diff. */
@Composable
fun ToolExecutionCard(
    tool: ToolCallInfo,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = remember(tool.type, tool.target, tool.detail, tool.fileChange) { toolLabel(tool) }
    val statusColor = when (tool.status) {
        ExecutionStatus.RUNNING -> AccentCyan
        ExecutionStatus.SUCCESS -> AccentGreen
        ExecutionStatus.FAILED -> AccentRed
        ExecutionStatus.AWAITING_CONFIRMATION -> AccentYellow
        ExecutionStatus.PENDING -> TextMuted
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface.copy(alpha = 0.6f))
            .clickable { onToggleExpand() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (tool.status == ExecutionStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = AccentCyan
                )
            } else {
                Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                label.verb,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary
            )
            Text(
                "(${label.argument})",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            tool.fileChange?.let { change ->
                FileChangeBadge(change)
                Spacer(Modifier.width(6.dp))
            }
            if (tool.durationMs > 0) {
                Text(formatDuration(tool.durationMs), fontSize = 10.sp, color = TextMuted)
            }
        }
        if (tool.status == ExecutionStatus.FAILED && !tool.isExpanded && tool.output.isNotBlank()) {
            Text(
                "⎿ " + tool.output.lineSequence().firstOrNull().orEmpty(),
                fontSize = 11.sp,
                color = AccentRed.copy(alpha = 0.9f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, start = 15.dp)
            )
        }
        AnimatedVisibility(visible = tool.isExpanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (tool.diffLines.isNotEmpty()) {
                    DiffView(lines = tool.diffLines)
                } else if (tool.output.isNotBlank()) {
                    TerminalConsoleBox(
                        text = tool.output.take(4_000),
                        textColor = if (tool.status == ExecutionStatus.FAILED) DiffRemovedText else DiffAddedText
                    )
                }
            }
        }
    }
}

@Composable
fun FileChangeBadge(change: FileChangeInfo, modifier: Modifier = Modifier) {
    val color = when (change.kind) {
        FileChangeKind.CREATED -> AccentGreen
        FileChangeKind.MODIFIED -> AccentBlue
        FileChangeKind.DELETED -> AccentRed
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.18f))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(change.kind.label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
        }
        if (change.kind != FileChangeKind.DELETED) {
            Spacer(Modifier.width(5.dp))
            Text("+${change.linesAdded}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DiffAddedText)
            if (change.linesRemoved > 0) {
                Text(" −${change.linesRemoved}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DiffRemovedText)
            }
        }
    }
}

/**
 * Resumo de eficiência da execução: quantos arquivos foram criados vs. reaproveitados (editados),
 * quantos foram lidos, linhas alteradas, rodadas do modelo e tempo.
 */
@Composable
fun RunSummaryBar(metrics: AgentMetrics, modifier: Modifier = Modifier) {
    val touched = metrics.filesCreated + metrics.filesModified
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (metrics.filesCreated > 0) SummaryChip("${metrics.filesCreated} novo(s)", AccentGreen)
            if (metrics.filesModified > 0) SummaryChip("${metrics.filesModified} editado(s)", AccentBlue)
            if (metrics.filesRead > 0) SummaryChip("${metrics.filesRead} lido(s)", TextSecondary)
            if (metrics.linesAdded + metrics.linesRemoved > 0) {
                SummaryChip("+${metrics.linesAdded} −${metrics.linesRemoved}", AccentYellow)
            }
            if (metrics.failedTools > 0) SummaryChip("${metrics.failedTools} falha(s)", AccentRed)
            Text(
                buildString {
                    append(String.format(java.util.Locale.US, "%.1fs", metrics.thinkingSeconds))
                    if (metrics.commandsExecuted > 0) append(" · ${metrics.commandsExecuted} ferramentas")
                    if (metrics.modelRounds > 0) append(" · ${metrics.modelRounds} rodada(s)")
                },
                fontSize = 10.sp,
                color = TextMuted
            )
        }
        if (touched > 0) {
            val reuse = metrics.filesModified.toFloat() / touched
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reuso de arquivos", fontSize = 10.sp, color = TextMuted)
                Spacer(Modifier.width(8.dp))
                LinearProgressIndicator(
                    progress = { reuse },
                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = AccentBlue,
                    trackColor = AccentGreen.copy(alpha = 0.35f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${metrics.filesModified}/$touched existentes",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

private fun formatDuration(ms: Long): String =
    if (ms < 1000) "${ms}ms" else String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)

@Composable
fun TerminalConsoleBox(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFF7EE787)
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D1117))
            .horizontalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor,
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
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        lines.take(300).forEach { line ->
            val (fg, bg) = when (line.type) {
                DiffLineType.ADDED -> DiffAddedText to DiffAddedBg
                DiffLineType.REMOVED -> DiffRemovedText to DiffRemovedBg
                DiffLineType.HEADER -> AccentCyan to Color.Transparent
                DiffLineType.CONTEXT -> TextSecondary to Color.Transparent
            }
            val number = (line.newLineNumber ?: line.oldLineNumber)?.toString().orEmpty()
            Row(Modifier.background(bg).padding(horizontal = 6.dp)) {
                Text(
                    number.padStart(4),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    line.text,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = fg,
                    softWrap = false
                )
            }
        }
        if (lines.size > 300) {
            Text(
                "… mais ${lines.size - 300} linhas de diff",
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
