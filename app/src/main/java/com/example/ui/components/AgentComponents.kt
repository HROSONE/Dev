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
        modifier = modifier.fillMaxWidth().background(DarkSurface).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DarkSurfaceVariant).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AgentMode.values().forEach { mode ->
                val isSelected = mode == selectedMode
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AccentCyan.copy(alpha = 0.2f) else Color.Transparent)
                        .border(1.dp, if (isSelected) AccentCyan else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onModeSelected(mode) }.padding(vertical = 8.dp)
                        .testTag("mode_tab_${mode.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        val icon = when (mode) {
                            AgentMode.ASK -> Icons.AutoMirrored.Filled.HelpOutline
                            AgentMode.IMAGINE -> Icons.Default.Lightbulb
                            AgentMode.BUILD -> Icons.Default.Build
                        }
                        Icon(icon, mode.iconDescription, tint = if (isSelected) AccentCyan else TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(mode.label, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) AccentCyan else TextSecondary)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(2.dp)) {
                ReasoningSpeed.values().forEach { speed ->
                    val isSelected = speed == selectedSpeed
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) AccentPurple.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { onSpeedSelected(speed) }.padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("speed_toggle_${speed.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(speed.label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) AccentPurple else TextSecondary)
                    }
                }
            }
            Row(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).clickable { onSelectRepoClick() }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Code, "GitHub", tint = AccentGreen, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(activeRepo.ifBlank { "Nenhum repositório conectado" }, fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LiveStatusIndicator(isThinking: Boolean, thinkingSeconds: Double, activeRepo: String, commandsExecuted: Int, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "alpha")
    Row(
        modifier = modifier.fillMaxWidth().background(DarkSurfaceVariant.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isThinking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(AccentPurple.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(AccentPurple.copy(alpha = alpha)))
                Spacer(Modifier.width(6.dp))
                Text("Pensando por ${String.format("%.1f", thinkingSeconds)}s", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AccentPurple)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background((if (activeRepo.isBlank()) AccentYellow else AccentGreen).copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (activeRepo.isBlank()) AccentYellow else AccentGreen))
            Spacer(Modifier.width(5.dp))
            Text(if (activeRepo.isBlank()) "GitHub desconectado" else "GitHub conectado", fontSize = 11.sp, color = if (activeRepo.isBlank()) AccentYellow else AccentGreen, fontWeight = FontWeight.Medium)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(AccentCyan.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Icon(Icons.Default.Terminal, null, tint = AccentCyan, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(4.dp))
            Text("Workspace local", fontSize = 11.sp, color = AccentCyan)
        }
        if (commandsExecuted > 0) Text("• $commandsExecuted cmds", fontSize = 11.sp, color = TextSecondary)
    }
}

@Composable
fun ThoughtProcessCard(thoughtSteps: List<String>, isThinking: Boolean, modifier: Modifier = Modifier) {
    var isExpanded by remember { mutableStateOf(true) }
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(10.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Psychology, "CoT", tint = AccentPurple, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isThinking) "Planejando…" else "Raciocínio (CoT)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPurple)
                }
                Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier.padding(top = 8.dp)) {
                    thoughtSteps.forEach { step ->
                        Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(step, fontSize = 12.sp, color = TextPrimary, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolExecutionCard(tool: ToolCallInfo, onToggleExpand: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
    ) {
        Column(modifier.padding(10.dp)) {
            Row(modifier.fillMaxWidth().clickable { onToggleExpand() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val icon = when (tool.type) {
                        ToolType.LIST_FILES -> Icons.Default.FolderOpen
                        ToolType.READ_FILE -> Icons.Default.Description
                        ToolType.WRITE_FILE -> Icons.Default.Edit
                        ToolType.RUN_COMMAND -> Icons.Default.Terminal
                        ToolType.READ_GITHUB_REPO -> Icons.Default.CloudDownload
                        ToolType.COMMIT_GITHUB -> Icons.Default.Upload
                    }
                    val iconColor = when (tool.type) {
                        ToolType.LIST_FILES -> AccentBlue
                        ToolType.READ_FILE -> AccentCyan
                        ToolType.WRITE_FILE -> AccentYellow
                        ToolType.RUN_COMMAND -> AccentGreen
                        ToolType.READ_GITHUB_REPO -> AccentPurple
                        ToolType.COMMIT_GITHUB -> AccentGreen
                    }
                    Icon(icon, tool.title, tint = iconColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(tool.type.displayName, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = iconColor)
                        Text(tool.detail, fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (tool.status) {
                        ExecutionStatus.RUNNING -> CircularProgressIndicator(modifier.size(14.dp), strokeWidth = 2.dp, color = AccentCyan)
                        ExecutionStatus.SUCCESS -> {
                            Text("${tool.durationMs}ms", fontSize = 10.sp, color = TextMuted, modifier = Modifier.padding(end = 4.dp))
                            Icon(Icons.Default.Check, "OK", tint = AccentGreen, modifier = Modifier.size(16.dp))
                        }
                        ExecutionStatus.FAILED -> Icon(Icons.Default.Close, "Erro", tint = AccentRed, modifier = Modifier.size(16.dp))
                        ExecutionStatus.PENDING -> Text("Pendente", fontSize = 10.sp, color = TextMuted)
                        ExecutionStatus.AWAITING_CONFIRMATION -> Text("Aguardando", fontSize = 10.sp, color = AccentYellow)
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(if (tool.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
            AnimatedVisibility(visible = tool.isExpanded) {
                Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (tool.diffLines.isNotEmpty()) DiffView(lines = tool.diffLines)
                    else if (tool.output.isNotBlank()) TerminalConsoleBox(text = tool.output)
                }
            }
        }
    }
}

@Composable
fun TerminalConsoleBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D1117)).border(1.dp, DarkBorder, RoundedCornerShape(6.dp)).padding(8.dp)
    ) {
        Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF7EE787), lineHeight = 15.sp)
    }
}

@Composable
fun DiffView(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D1117)).border(1.dp, DarkBorder, RoundedCornerShape(6.dp)).padding(6.dp)
    ) {
        lines.forEach { line ->
            val bg = when (line.type) {
                DiffLineType.ADDED -> Color(0xFF1B4332)
                DiffLineType.REMOVED -> Color(0xFF3D0C11)
                DiffLineType.HEADER -> Color(0xFF161B22)
                DiffLineType.CONTEXT -> Color.Transparent
            }
            val fg = when (line.type) {
                DiffLineType.ADDED -> Color(0xFF7EE787)
                DiffLineType.REMOVED -> Color(0xFFFF7B72)
                DiffLineType.HEADER -> AccentCyan
                DiffLineType.CONTEXT -> TextSecondary
            }
            Text(line.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = fg, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp, vertical = 1.dp))
        }
    }
}
