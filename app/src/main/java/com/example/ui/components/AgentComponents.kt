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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Mode Tabs (Pergunte, Imagine, Construir)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceVariant)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AgentMode.values().forEach { mode ->
                val isSelected = mode == selectedMode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AccentCyan.copy(alpha = 0.2f) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) AccentCyan else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onModeSelected(mode) }
                        .padding(vertical = 8.dp)
                        .testTag("mode_tab_${mode.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val icon = when (mode) {
                            AgentMode.ASK -> Icons.AutoMirrored.Filled.HelpOutline
                            AgentMode.IMAGINE -> Icons.Default.Lightbulb
                            AgentMode.BUILD -> Icons.Default.Build
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = mode.iconDescription,
                            tint = if (isSelected) AccentCyan else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = mode.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) AccentCyan else TextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Speed Selector & Repo Tag
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed Switch
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceVariant)
                    .padding(2.dp)
            ) {
                ReasoningSpeed.values().forEach { speed ->
                    val isSelected = speed == selectedSpeed
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) AccentPurple.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { onSpeedSelected(speed) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("speed_toggle_${speed.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = speed.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) AccentPurple else TextSecondary
                        )
                    }
                }
            }

            // Repository Badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceVariant)
                    .clickable { onSelectRepoClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = "GitHub Repo",
                    tint = AccentGreen,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = activeRepo.ifBlank { "Nenhum repositório conectado" },
                    fontSize = 11.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun LiveStatusIndicator(
    isThinking: Boolean,
    thinkingSeconds: Double,
    activeRepo: String,
    commandsExecuted: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurfaceVariant.copy(alpha = 0.8f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isThinking) {
            // Thinking timer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentPurple.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AccentPurple.copy(alpha = alpha))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Pensando por ${String.format("%.1f", thinkingSeconds)}s",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentPurple
                )
            }
        }

        // GitHub Connector Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(AccentGreen.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(AccentGreen)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "GitHub Ativo",
                fontSize = 11.sp,
                color = AccentGreen,
                fontWeight = FontWeight.Medium
            )
        }

        // Docker Sandbox Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(AccentCyan.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Docker Sandbox",
                fontSize = 11.sp,
                color = AccentCyan
            )
        }

        if (commandsExecuted > 0) {
            Text(
                text = "• $commandsExecuted comandos",
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun ThoughtProcessCard(
    thoughtSteps: List<String>,
    isThinking: Boolean,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(10.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "Raciocínio CoT",
                        tint = AccentPurple,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isThinking) "Planejando & Raciocinando..." else "Plano de Raciocínio (CoT)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentPurple
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expandir/Recolher",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    thoughtSteps.forEach { step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AccentGreen,
                                modifier = Modifier
                                    .size(14.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = step,
                                fontSize = 12.sp,
                                color = TextPrimary,
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
fun ToolExecutionCard(
    tool: ToolCallInfo,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val icon = when (tool.type) {
                        ToolType.READ_GITHUB_REPO -> Icons.Default.FolderOpen
                        ToolType.RUN_TERMINAL_COMMAND -> Icons.Default.Terminal
                        ToolType.APPLY_DIFF_PATCH -> Icons.Default.Difference
                        ToolType.RUN_TESTS -> Icons.AutoMirrored.Filled.FactCheck
                    }
                    val iconColor = when (tool.type) {
                        ToolType.READ_GITHUB_REPO -> AccentBlue
                        ToolType.RUN_TERMINAL_COMMAND -> AccentCyan
                        ToolType.APPLY_DIFF_PATCH -> AccentYellow
                        ToolType.RUN_TESTS -> AccentGreen
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = tool.title,
                        tint = iconColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = tool.type.displayName,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = iconColor
                        )
                        Text(
                            text = tool.detail,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status Badge
                    when (tool.status) {
                        ExecutionStatus.RUNNING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = AccentCyan
                            )
                        }
                        ExecutionStatus.SUCCESS -> {
                            Text(
                                text = "${tool.durationMs}ms",
                                fontSize = 10.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Sucesso",
                                tint = AccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        ExecutionStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Erro",
                                tint = AccentRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        ExecutionStatus.PENDING -> {
                            Text(text = "Pendente", fontSize = 10.sp, color = TextMuted)
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (tool.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expandir detalhes",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Expanded content
            AnimatedVisibility(visible = tool.isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    if (tool.diffLines.isNotEmpty()) {
                        DiffView(lines = tool.diffLines)
                    } else if (tool.output.isNotBlank()) {
                        TerminalConsoleBox(text = tool.output)
                    }
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
            .background(TerminalBackground)
            .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextPrimary,
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
            .background(TerminalBackground)
            .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        lines.forEach { line ->
            val bg = when (line.type) {
                DiffLineType.ADDED -> DiffAddedBg
                DiffLineType.REMOVED -> DiffRemovedBg
                DiffLineType.HEADER -> DarkSurfaceVariant
                DiffLineType.CONTEXT -> Color.Transparent
            }
            val textColor = when (line.type) {
                DiffLineType.ADDED -> DiffAddedText
                DiffLineType.REMOVED -> DiffRemovedText
                DiffLineType.HEADER -> AccentCyan
                DiffLineType.CONTEXT -> TextSecondary
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = line.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = textColor
                )
            }
        }
    }
}
