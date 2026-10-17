package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RepoFile
import com.example.ui.components.TerminalConsoleBox
import com.example.ui.theme.*

@Composable
fun SandboxWorkspaceScreen(
    activeRepo: String,
    files: List<RepoFile>,
    selectedFile: RepoFile?,
    onSelectFile: (RepoFile) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Files, 1: Sandbox Terminal, 2: Docker Stats
    var terminalInput by remember { mutableStateOf("") }
    var terminalLogs by remember {
        mutableStateOf(
            listOf(
                "Terminal ainda não conectado a um ambiente de execução real.",
                "Nenhum comando foi executado nesta sessão."
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Workspace Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Inventory2,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Repositório & Terminal (prévia)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = activeRepo,
                        fontSize = 11.sp,
                        color = AccentGreen
                    )
                }
            }

            // Docker badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(AccentYellow)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Sandbox não conectada",
                    fontSize = 10.sp,
                    color = TextPrimary
                )
            }
        }

        // Sub Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurface,
            contentColor = AccentCyan,
            divider = { HorizontalDivider(color = DarkBorder, thickness = 1.dp) }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Arquivos (${files.size})", fontSize = 12.sp) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Terminal Interativo", fontSize = 12.sp) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Isolamento & Recursos", fontSize = 12.sp) }
            )
        }

        when (selectedTab) {
            0 -> {
                // File Explorer & Inspector
                Row(modifier = Modifier.fillMaxSize()) {
                    // File List (Left column)
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight()
                            .background(DarkSurfaceVariant)
                            .border(width = 0.5.dp, color = DarkBorder)
                    ) {
                        items(files) { file ->
                            val isSelected = file.path == selectedFile?.path
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectFile(file) }
                                    .background(if (isSelected) DarkSurfaceHover else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (file.isModified) Icons.Default.EditNote else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (file.isModified) AccentYellow else TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = file.path.substringAfterLast('/'),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AccentCyan else TextPrimary,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = file.path.substringBeforeLast('/', ""),
                                        fontSize = 9.sp,
                                        color = TextMuted,
                                        maxLines = 1
                                    )
                                }
                            }
                            HorizontalDivider(color = DarkBorder.copy(alpha = 0.4f), thickness = 0.5.dp)
                        }
                    }

                    // File Viewer (Right column)
                    Box(
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight()
                            .background(TerminalBackground)
                            .padding(8.dp)
                    ) {
                        if (selectedFile != null) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedFile.path,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AccentCyan
                                    )
                                    if (selectedFile.isModified) {
                                        Text(
                                            text = "MODIFICADO PELO AGENTE",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentYellow
                                        )
                                    }
                                }
                                TerminalConsoleBox(
                                    text = selectedFile.content,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Text(
                                text = "Selecione um arquivo ao lado para inspecionar",
                                fontSize = 12.sp,
                                color = TextMuted,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }

            1 -> {
                // Interactive Sandbox Terminal
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TerminalBackground)
                            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(terminalLogs) { log ->
                            Text(
                                text = log,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (log.startsWith("root@sandbox")) AccentCyan else TextPrimary,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = terminalInput,
                            onValueChange = { terminalInput = it },
                            placeholder = { Text("Ex: pytest, git diff, poetry show...", fontSize = 11.sp, color = TextMuted) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("terminal_input_field"),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (terminalInput.isNotBlank()) {
                                    val cmd = terminalInput.trim()
                                    val output = "Terminal real ainda não conectado. Nenhum comando foi executado."
                                    terminalLogs = terminalLogs + listOf("> $cmd", output)
                                    terminalInput = ""
                                }
                            }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurfaceVariant,
                                unfocusedContainerColor = DarkSurfaceVariant,
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Button(
                            onClick = {
                                if (terminalInput.isNotBlank()) {
                                    val cmd = terminalInput.trim()
                                    val output = executeSimulatedSandboxCommand(cmd)
                                    terminalLogs = terminalLogs + listOf("root@sandbox:/workspace# $cmd", output)
                                    terminalInput = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Executar", fontSize = 11.sp, color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            2 -> {
                // Docker & Isolation Stats
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Status do Ambiente de Execução",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    StatCard(
                        title = "Mecanismo de Isolamento",
                        value = "Não conectado",
                        description = "O app ainda não iniciou um contêiner nem verificou um mecanismo de isolamento."
                    )
                    StatCard(
                        title = "Limites de Recursos (cgroups v2)",
                        value = "Limites não aplicados",
                        description = "Os limites abaixo são metas de projeto, não configurações ativas."
                    )
                    StatCard(
                        title = "Política de Rede (Network Sandbox)",
                        value = "Rede não configurada",
                        description = "Nenhuma política de rede foi aplicada ou testada."
                    )
                    StatCard(
                        title = "Tempo Limite de Execução (Timeout)",
                        value = "Timeout não aplicado",
                        description = "Nenhum processo é iniciado pelo terminal nesta versão."
                    )
                }
            }
        }
    }
}

@Composable
fun StatCard(title: String, value: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, fontSize = 10.sp, color = TextSecondary)
        }
    }
}

