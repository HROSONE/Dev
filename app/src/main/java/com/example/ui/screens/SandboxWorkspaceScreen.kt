package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
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
    repositoryStatus: String = "",
    files: List<RepoFile>,
    selectedFile: RepoFile?,
    onSelectFile: (RepoFile) -> Unit,
    onSaveFile: (path: String, content: String) -> Unit = { _, _ -> },
    onRevertFile: (path: String) -> Unit = {},
    terminalLogs: List<String> = emptyList(),
    onRunCommand: (String) -> Unit = {},
    hasWriteToken: Boolean = false,
    isCommitting: Boolean = false,
    lastCommitStatus: String = "",
    onCommitFile: (String) -> Unit = {},
    onCommitModifiedFiles: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var terminalInput by remember { mutableStateOf("") }
    var isEditing by remember(selectedFile?.path) { mutableStateOf(false) }
    var editorContent by remember(selectedFile?.path, selectedFile?.content) {
        mutableStateOf(selectedFile?.content.orEmpty())
    }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFilePath by remember { mutableStateOf("") }
    var commitMessageInput by remember { mutableStateOf("") }

    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Novo Arquivo no Workspace", fontSize = 15.sp, color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newFilePath,
                    onValueChange = { newFilePath = it },
                    label = { Text("Ex.: app/src/main/java/com/example/Novo.kt") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkBorder
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newFilePath.isNotBlank(),
                    onClick = {
                        val path = newFilePath.trim()
                        newFilePath = ""
                        showNewFileDialog = false
                        onSaveFile(path, "")
                    }
                ) { Text("Criar", color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurfaceVariant
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
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
                        text = "Workspace & Editor Local",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = activeRepo.ifBlank { "Nenhum repo conectado" },
                        fontSize = 11.sp,
                        color = if (activeRepo.isBlank()) TextMuted else AccentGreen
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(
                    onClick = { showNewFileDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("+ Novo", fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold)
                }
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
                            .background(AccentGreen)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "grep · tree · cat",
                        fontSize = 10.sp,
                        color = TextPrimary
                    )
                }
            }
        }

        if (repositoryStatus.isNotBlank()) {
            Text(
                text = repositoryStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 11.sp,
                color = if (repositoryStatus.contains("falhas") || repositoryStatus.startsWith("Erro")) AccentYellow else TextSecondary,
                lineHeight = 15.sp
            )
        }

        val modifiedCount = files.count { it.isModified }
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
                text = { Text("Terminal", fontSize = 12.sp) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = {
                    Text(
                        if (modifiedCount > 0) "Commit ($modifiedCount)" else "Commit",
                        fontSize = 12.sp
                    )
                }
            )
        }

        when (selectedTab) {
            0 -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.38f)
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

                    Box(
                        modifier = Modifier
                            .weight(0.62f)
                            .fillMaxHeight()
                            .background(TerminalBackground)
                            .padding(8.dp)
                    ) {
                        if (selectedFile != null) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = selectedFile.path,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = AccentCyan,
                                            maxLines = 1
                                        )
                                        if (selectedFile.isModified) {
                                            Text(
                                                text = "MODIFICADO NO WORKSPACE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AccentYellow
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (selectedFile.isModified && !isEditing) {
                                            TextButton(
                                                onClick = { onRevertFile(selectedFile.path) },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("Reverter", fontSize = 10.sp, color = AccentRed)
                                            }
                                        }
                                        if (!isEditing) {
                                            TextButton(
                                                onClick = {
                                                    editorContent = selectedFile.content
                                                    isEditing = true
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("Editar", fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            TextButton(
                                                onClick = {
                                                    onSaveFile(selectedFile.path, editorContent)
                                                    isEditing = false
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("Salvar", fontSize = 11.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
                                            }
                                            TextButton(
                                                onClick = {
                                                    editorContent = selectedFile.content
                                                    isEditing = false
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("Cancelar", fontSize = 10.sp, color = TextSecondary)
                                            }
                                        }
                                    }
                                }

                                if (isEditing) {
                                    OutlinedTextField(
                                        value = editorContent,
                                        onValueChange = { editorContent = it },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("workspace_code_editor"),
                                        textStyle = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = TextPrimary,
                                            lineHeight = 16.sp
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color(0xFF0D1117),
                                            unfocusedContainerColor = Color(0xFF0D1117),
                                            focusedBorderColor = AccentCyan,
                                            unfocusedBorderColor = DarkBorder,
                                            cursorColor = AccentCyan
                                        )
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        TerminalConsoleBox(
                                            text = selectedFile.content,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Selecione um arquivo",
                                fontSize = 12.sp,
                                color = TextMuted,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }

            1 -> {
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
                                color = when {
                                    log.startsWith(">") -> AccentCyan
                                    log.startsWith("[") -> TextMuted
                                    else -> TextPrimary
                                },
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
                            placeholder = {
                                Text("tree, grep termo app/src, find, cat README.md", fontSize = 11.sp, color = TextMuted)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("terminal_input_field"),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (terminalInput.isNotBlank()) {
                                    onRunCommand(terminalInput.trim())
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
                                    onRunCommand(terminalInput.trim())
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Commit no GitHub",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (hasWriteToken) {
                            "Token de sessão presente. Você pode enviar todos os arquivos modificados em um único commit atômico."
                        } else {
                            "Conecte o repositório com um token (contents:write) para habilitar commit."
                        },
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    val modified = files.filter { it.isModified }
                    if (modified.isNotEmpty()) {
                        OutlinedTextField(
                            value = commitMessageInput,
                            onValueChange = { commitMessageInput = it },
                            label = { Text("Mensagem do commit (opcional)") },
                            placeholder = { Text("feat: atualiza ${modified.size} arquivo(s)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = DarkBorder
                            )
                        )
                        Button(
                            onClick = { onCommitModifiedFiles(commitMessageInput.trim()) },
                            enabled = hasWriteToken && !isCommitting && modified.size <= 50,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) {
                            Text(
                                if (isCommitting) "Enviando…" else "Commit atômico · ${modified.size} arquivo(s)",
                                color = DarkBackground,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (modified.size > 50) {
                            Text("O limite por commit é de 50 arquivos.", fontSize = 11.sp, color = AccentYellow)
                        }
                    }
                    if (modified.isEmpty()) {
                        Text(
                            text = "Nenhum arquivo modificado no workspace.",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    } else {
                        modified.forEach { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkSurfaceVariant)
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = file.path,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { onCommitFile(file.path) },
                                    enabled = hasWriteToken && !isCommitting,
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        if (isCommitting) "…" else "Commit",
                                        fontSize = 11.sp,
                                        color = DarkBackground
                                    )
                                }
                            }
                        }
                    }

                    if (lastCommitStatus.isNotBlank()) {
                        Text(
                            text = lastCommitStatus,
                            fontSize = 11.sp,
                            color = if (lastCommitStatus.startsWith("Commit OK")) AccentGreen else AccentYellow
                        )
                    }
                }
            }
        }
    }
}
