package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AgentModeSelector
import com.example.ui.components.DiffView
import com.example.ui.screens.AgentChatScreen
import com.example.ui.screens.ArchitectureGuideScreen
import com.example.ui.screens.SandboxWorkspaceScreen
import com.example.ui.theme.*
import com.example.ui.viewmodel.DevAgentViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: DevAgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                DevAgentApp(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevAgentApp(viewModel: DevAgentViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showRepoDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var repoInputText by remember { mutableStateOf(uiState.activeRepo) }
    var githubTokenText by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKeyPlain by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.repositoryStatus) {
        if (uiState.repositoryStatus.startsWith("Conectado")) {
            showRepoDialog = false
            githubTokenText = ""
        }
    }

    // Diálogo Configurações — colar API key
    if (showSettingsDialog) {
        LaunchedEffect(Unit) {
            apiKeyInput = viewModel.getStoredApiKey()
        }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Text("Configurações", fontSize = 16.sp, color = TextPrimary)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Gemini API key",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Cole a chave de https://aistudio.google.com/apikey. Fica salva só neste aparelho.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API key") },
                        placeholder = { Text("AIza…") },
                        singleLine = true,
                        visualTransformation = if (showApiKeyPlain) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showApiKeyPlain = !showApiKeyPlain }) {
                                Icon(
                                    imageVector = if (showApiKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKeyPlain) "Ocultar" else "Mostrar",
                                    tint = TextSecondary
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_gemini_api_key"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (uiState.hasGeminiKey) AccentGreen else AccentYellow,
                                    shape = MaterialTheme.shapes.small
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.hasGeminiKey) "Chave configurada" else "Nenhuma chave",
                            fontSize = 12.sp,
                            color = if (uiState.hasGeminiKey) AccentGreen else AccentYellow
                        )
                    }
                    if (uiState.apiKeyStatus.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(uiState.apiKeyStatus, fontSize = 11.sp, color = AccentCyan)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveGeminiApiKey(apiKeyInput)
                        showSettingsDialog = false
                    },
                    modifier = Modifier.testTag("settings_save_api_key")
                ) {
                    Text("Salvar", color = AccentGreen)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        apiKeyInput = ""
                        viewModel.saveGeminiApiKey("")
                    }) {
                        Text("Limpar", color = AccentRed)
                    }
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Fechar", color = TextSecondary)
                    }
                }
            },
            containerColor = DarkSurfaceVariant
        )
    }

    uiState.pendingMutation?.let { mutation ->
        AlertDialog(
            onDismissRequest = { viewModel.respondToMutation(false) },
            title = { Text("Confirmar gravação", fontSize = 16.sp, color = TextPrimary) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Arquivo:", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = mutation.path,
                        fontSize = 13.sp,
                        color = AccentCyan,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (mutation.diffLines.isNotEmpty()) {
                        Text("Diff:", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            DiffView(lines = mutation.diffLines)
                        }
                    } else {
                        Text("Prévia:", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = DarkSurface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                        ) {
                            Text(
                                text = mutation.contentPreview.ifBlank { "(vazio)" },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Grava só no workspace local. Use a aba Commit para enviar ao GitHub.",
                        fontSize = 11.sp,
                        color = AccentYellow
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.respondToMutation(true) },
                    modifier = Modifier.testTag("confirm_mutation_approve")
                ) { Text("Aprovar e gravar", color = AccentGreen) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.respondToMutation(false) },
                    modifier = Modifier.testTag("confirm_mutation_reject")
                ) { Text("Recusar", color = AccentRed) }
            },
            containerColor = DarkSurfaceVariant
        )
    }

    if (showRepoDialog) {
        AlertDialog(
            onDismissRequest = { showRepoDialog = false },
            title = { Text("Conectar Repositório GitHub", fontSize = 16.sp, color = TextPrimary) },
            text = {
                Column {
                    Text(
                        text = "owner/repo — token opcional (leitura ou contents:write para commit)",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repoInputText,
                        onValueChange = { repoInputText = it },
                        label = { Text("proprietário/repositório") },
                        singleLine = true,
                        enabled = !uiState.isConnectingRepo,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = githubTokenText,
                        onValueChange = { githubTokenText = it },
                        label = { Text("Token GitHub (opcional)") },
                        placeholder = { Text("ghp_… / github_pat_…") },
                        singleLine = true,
                        enabled = !uiState.isConnectingRepo,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder
                        )
                    )
                    Text(
                        text = "Token fica só na memória desta sessão. contents:write habilita commit.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    if (uiState.repositoryStatus.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = uiState.repositoryStatus,
                            fontSize = 11.sp,
                            color = if (uiState.repositoryStatus.startsWith("Conectado")) AccentGreen else TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !uiState.isConnectingRepo && repoInputText.isNotBlank(),
                    onClick = {
                        val tokenForRequest = githubTokenText
                        githubTokenText = ""
                        viewModel.connectRepository(repoInputText, tokenForRequest)
                    }
                ) {
                    Text(if (uiState.isConnectingRepo) "Conectando…" else "Conectar", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRepoDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurfaceVariant
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        topBar = {
            Column {
                // Barra com ícone de configurações
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!uiState.hasGeminiKey) {
                        Text(
                            text = "API key",
                            fontSize = 11.sp,
                            color = AccentYellow,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.testTag("open_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configurações",
                            tint = if (uiState.hasGeminiKey) AccentCyan else AccentYellow
                        )
                    }
                }
                AgentModeSelector(
                    selectedMode = uiState.selectedMode,
                    onModeSelected = { viewModel.setMode(it) },
                    selectedSpeed = uiState.selectedSpeed,
                    onSpeedSelected = { viewModel.setSpeed(it) },
                    activeRepo = uiState.activeRepo,
                    onSelectRepoClick = {
                        repoInputText = uiState.activeRepo
                        showRepoDialog = true
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = AccentCyan,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = uiState.activeTab == 0,
                    onClick = { viewModel.setTab(0) },
                    icon = { Icon(Icons.Default.SmartToy, contentDescription = "Agente") },
                    label = { Text("Agente", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentCyan,
                        selectedTextColor = AccentCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = AccentCyan.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_agent_chat")
                )
                NavigationBarItem(
                    selected = uiState.activeTab == 1,
                    onClick = { viewModel.setTab(1) },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Sandbox") },
                    label = { Text("Sandbox", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentCyan,
                        selectedTextColor = AccentCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = AccentCyan.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_sandbox_workspace")
                )
                NavigationBarItem(
                    selected = uiState.activeTab == 2,
                    onClick = { viewModel.setTab(2) },
                    icon = { Icon(Icons.Default.Code, contentDescription = "Arquitetura") },
                    label = { Text("Arquitetura", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentCyan,
                        selectedTextColor = AccentCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = AccentCyan.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_architecture_guide")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkBackground)
        ) {
            when (uiState.activeTab) {
                0 -> AgentChatScreen(
                    messages = uiState.messages,
                    isThinking = uiState.isThinking,
                    thinkingSeconds = uiState.thinkingSeconds,
                    activeRepo = uiState.activeRepo,
                    commandsExecuted = uiState.commandsExecuted,
                    currentPrompt = uiState.currentPrompt,
                    onPromptChange = { viewModel.setPrompt(it) },
                    onSubmitPrompt = { viewModel.submitPrompt() },
                    onStopExecution = { viewModel.stopExecution() },
                    onToggleToolExpand = { msgId, toolId -> viewModel.toggleToolExpand(msgId, toolId) },
                    onQuickPromptSelected = { prompt ->
                        viewModel.setPrompt(prompt)
                        viewModel.submitPrompt()
                    }
                )
                1 -> SandboxWorkspaceScreen(
                    activeRepo = uiState.activeRepo,
                    files = uiState.repoFiles,
                    selectedFile = uiState.selectedFile,
                    onSelectFile = { viewModel.selectFile(it) },
                    terminalLogs = uiState.terminalLogs,
                    onRunCommand = { viewModel.runTerminalCommand(it) },
                    hasWriteToken = uiState.hasWriteToken,
                    isCommitting = uiState.isCommitting,
                    lastCommitStatus = uiState.lastCommitStatus,
                    onCommitFile = { viewModel.commitFileToGitHub(it) }
                )
                2 -> ArchitectureGuideScreen()
            }
        }
    }
}
