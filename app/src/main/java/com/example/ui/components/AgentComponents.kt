package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
 * Barra superior enxuta: só modo + repo (sem chips de status).
 */
@Composable
fun AgentModeSelector(
    selectedMode: AgentMode,
    onModeSelected: (AgentMode) -> Unit,
    selectedSpeed: ReasoningSpeed,
    onSpeedSelected: (ReasoningSpeed) -> Unit,
    activeRepo: String,
    onSelectRepoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Segmento de modo compacto
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
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) AccentCyan else TextSecondary
                    )
                }
            }
        }

        // Repo: ícone único
        IconButton(
            onClick = onSelectRepoClick,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(DarkSurface)
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = "Repositório",
                tint = if (activeRepo.isBlank()) TextMuted else AccentGreen,
                modifier = Modifier.size(18.dp)
            )
        }

        // Speed escondido num menu simples (só se BUILD/ASK precisam)
        var speedMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(
                onClick = { speedMenu = true },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(DarkSurface)
            ) {
                Icon(
                    if (selectedSpeed == ReasoningSpeed.FAST) Icons.Default.Bolt else Icons.Default.Psychology,
                    contentDescription = "Velocidade",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = speedMenu,
                onDismissRequest = { speedMenu = false },
                containerColor = DarkSurface
            ) {
                ReasoningSpeed.entries.forEach { speed ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                speed.label,
                                color = if (speed == selectedSpeed) AccentCyan else TextPrimary,
                                fontSize = 13.sp
                            )
                        },
                        onClick = {
                            onSpeedSelected(speed)
                            speedMenu = false
                        }
                    )
                }
            }
        }
    }
}

/** Indicador mínimo: só aparece enquanto pensa. */
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
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(AccentCyan.copy(alpha = alpha)))
        Spacer(Modifier.width(8.dp))
        Text(
            "Pensando… ${String.format("%.0f", thinkingSeconds)}s",
            fontSize = 12.sp,
            color = TextSecondary
        )
    }
}

/** CoT recolhido por padrão — quase invisível. */
@Composable
fun ThoughtProcessCard(
    thoughtSteps: List<String>,
    isThinking: Boolean,
    modifier: Modifier = Modifier
) {
    if (thoughtSteps.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isThinking) "pensando" else "passos",
                fontSize = 11.sp,
                color = TextMuted
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
                thoughtSteps.forEach { step ->
                    Text(
                        "· $step",
                        fontSize = 11.sp,
                        color = TextMuted,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/** Tool card compacto, fechado por padrão. */
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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                when (tool.status) {
                    ExecutionStatus.RUNNING ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = AccentCyan
                        )
                    ExecutionStatus.SUCCESS ->
                        Icon(Icons.Default.Check, null, tint = AccentGreen, modifier = Modifier.size(14.dp))
                    ExecutionStatus.FAILED ->
                        Icon(Icons.Default.Close, null, tint = AccentRed, modifier = Modifier.size(14.dp))
                    else ->
                        Icon(Icons.Default.Code, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
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
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                when {
                    tool.diffLines.isNotEmpty() -> DiffView(lines = tool.diffLines)
                    tool.output.isNotBlank() -> TerminalConsoleBox(text = tool.output)
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
