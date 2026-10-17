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
import com.example.data.model.LlmProvider
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

    var settingsProvider by remember { mutableStateOf(LlmProvider.GEMINI) }
    var geminiKeyInput by remember { mutableStateOf("") }
    var openRouterKeyInput by remember { mutableStateOf("") }
    var selectedOrModel by remember { mutableStateOf(LlmProvider.DEFAULT_OPENROUTER_MODEL) }
    var showKeysPlain by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.repositoryStatus) {
        if (uiState.repositoryStatus.startsWith("Conectado")) {
            showRepoDialog = false
            githubTokenText = ""
        }
    }

    if (showSettingsDialog) {
        LaunchedEffect(Unit) {
            settingsProvider = uiState.llmProvider
            geminiKeyInput = viewModel.getStoredGeminiKey()
            openRouterKeyInput = viewModel.getStoredOpenRouterKey()
            selectedOrModel = uiState.openRouterModelId
        }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Configurações", fontSize = 16.sp, color = TextPrimary) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Provedor LLM", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LlmProvider.entries.forEach { p ->
                            FilterChip(
                                selected = settingsProvider == p,
                                onClick = { settingsProvider = p },
                                label = { Text(p.label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan.copy(alpha = 0.25f),
                                    selectedLabelColor = AccentCyan
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (settingsProvider == LlmProvider.GEMINI) {
                        Text("Gemini API key", fontSize = 12.sp, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = geminiKeyInput,
                            onValueChange = { geminiKeyInput = it },
                            label = { Text("AIza…") },
                            singleLine = true,
                            visualTransformation = if (showKeysPlain) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("settings_gemini_api_key"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = DarkBorder
                            )
                        )
                    } else {
                        Text("OpenRouter API key", fontSize = 12.sp, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = openRouterKeyInput,
                            onValueChange = { openRouterKeyInput = it },
                            label = { Text("sk-or-…") },
                            singleLine = true,
                            visualTransformation = if (showKeysPlain) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("settings_openrouter_api_key"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = DarkBorder
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Modelo", fontSize = 12.sp, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        LlmProvider.openRouterPresets.forEach { preset ->
                            FilterChip(
                                selected = selectedOrModel == preset.id,
                                onClick = { selectedOrModel = preset.id },
                                label = {
                                    Text(
                                        if (preset.free) "${preset.label} · free" else preset.label,
                                        fontSize = 11.sp
                                    )
                                },
                                modifier = Modifier.padding(bottom = 4.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentGreen.copy(alpha = 0.2f),
                                    selectedLabelColor = AccentGreen
                                )
                            )
                        }
                        OutlinedTextField(
                            value = selectedOrModel,
                            onValueChange = { selectedOrModel = it },
                            label = { Text("ID custom") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = DarkBorder
                            )
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showKeysPlain, onCheckedChange = { showKeysPlain = it })
                        Text("Mostrar chaves", fontSize = 12.sp, color = TextSecondary)
                    }
                    if (uiState.apiKeyStatus.isNotBlank()) {
                        Text(uiState.apiKeyStatus, fontSize = 11.sp, color = AccentCyan)
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = DarkBorder)
                    Spacer(Modifier.height(10.dp))
                    Text("Atualização", fontSize = 13.sp, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        uiState.updateStatus.ifBlank { "Releases em HROSONE/Dev" },
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { viewModel.checkForAppUpdate() },
                            enabled = !uiState.isCheckingUpdate
                        ) {
                            Text(if (uiState.isCheckingUpdate) "…" else "Verificar", color = AccentCyan)
                        }
                        if (uiState.updateAvailable != null) {
                            TextButton(
                                onClick = { viewModel.downloadAndInstallUpdate() },
                                enabled = !uiState.isDownloadingUpdate
                            ) {
                                Text(
                                    if (uiState.isDownloadingUpdate) "Baixando…" else "Instalar",
                                    color = AccentGreen
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveLlmSettings(
                            provider = settingsProvider,
                            geminiKey = geminiKeyInput,
                            openRouterKey = openRouterKeyInput,
                            openRouterModelId = selectedOrModel
                        )
                        showSettingsDialog = false
                    },
                    modifier = Modifier.testTag("settings_save_api_key")
                ) { Text("Salvar", color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Fechar", color = TextSecondary)
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
                Column(Modifier.fillMaxWidth()) {
                    Text(mutation.path, fontSize = 13.sp, color = AccentCyan, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    if (mutation.diffLines.isNotEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            DiffView(lines = mutation.diffLines)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.respondToMutation(true) }) {
                    Text("Aprovar", color = AccentGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.respondToMutation(false) }) {
                    Text("Recusar", color = AccentRed)
                }
            },
            containerColor = DarkSurfaceVariant
        )
    }

    if (showRepoDialog) {
        AlertDialog(
            onDismissRequest = { showRepoDialog = false },
            title = { Text("Repositório", fontSize = 16.sp, color = TextPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = repoInputText,
                        onValueChange = { repoInputText = it },
                        label = { Text("owner/repo") },
                        singleLine = true,
                        enabled = !uiState.isConnectingRepo,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = githubTokenText,
                        onValueChange = { githubTokenText = it },
                        label = { Text("Token (opcional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !uiState.isConnectingRepo,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !uiState.isConnectingRepo && repoInputText.isNotBlank(),
                    onClick = {
                        val t = githubTokenText
                        githubTokenText = ""
                        viewModel.connectRepository(repoInputText, t)
                    }
                ) { Text(if (uiState.isConnectingRepo) "…" else "Conectar", color = AccentCyan) }
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(DarkBackground)
                    .statusBarsPadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AgentModeSelector(
                        selectedMode = uiState.selectedMode,
                        onModeSelected = { viewModel.setMode(it) },
                        selectedSpeed = uiState.selectedSpeed,
                        onSpeedSelected = { viewModel.setSpeed(it) },
                        activeRepo = uiState.activeRepo,
                        onSelectRepoClick = {
                            repoInputText = uiState.activeRepo
                            showRepoDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.testTag("open_settings_button")
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            "Configurações",
                            tint = if (uiState.hasLlmKey) TextSecondary else AccentYellow,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                val showBanner = uiState.updateAvailable != null && !uiState.updateBannerDismissed
                if (showBanner) {
                    val upd = uiState.updateAvailable!!
                    Surface(
                        color = AccentCyan.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Nova versão ${upd.versionName}",
                                    fontSize = 13.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    when {
                                        uiState.isDownloadingUpdate ->
                                            "Baixando… ${(uiState.updateDownloadProgress * 100).toInt()}%"
                                        uiState.updateApkReady -> "Pronta para instalar"
                                        else -> "Atualização disponível"
                                    },
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                if (uiState.isDownloadingUpdate) {
                                    LinearProgressIndicator(
                                        progress = { uiState.updateDownloadProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        color = AccentCyan,
                                        trackColor = DarkBorder
                                    )
                                }
                            }
                            TextButton(
                                onClick = { viewModel.downloadAndInstallUpdate() },
                                enabled = !uiState.isCheckingUpdate
                            ) {
                                Text(
                                    if (uiState.updateApkReady || !uiState.isDownloadingUpdate)
                                        "Instalar" else "…",
                                    color = AccentGreen,
                                    fontSize = 13.sp
                                )
                            }
                            IconButton(onClick = { viewModel.dismissUpdateBanner() }) {
                                Icon(
                                    Icons.Default.Close,
                                    "Fechar",
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = DarkBackground,
                contentColor = AccentCyan,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = uiState.activeTab == 0,
                    onClick = { viewModel.setTab(0) },
                    icon = { Icon(Icons.Default.ChatBubbleOutline, "Chat") },
                    label = { Text("Chat", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentCyan,
                        selectedTextColor = AccentCyan,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = AccentCyan.copy(alpha = 0.12f)
                    )
                )
                NavigationBarItem(
                    selected = uiState.activeTab == 1,
                    onClick = { viewModel.setTab(1) },
                    icon = { Icon(Icons.Default.FolderOpen, "Arquivos") },
                    label = { Text("Arquivos", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentCyan,
                        selectedTextColor = AccentCyan,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = AccentCyan.copy(alpha = 0.12f)
                    )
                )
                NavigationBarItem(
                    selected = uiState.activeTab == 2,
                    onClick = { viewModel.setTab(2) },
                    icon = { Icon(Icons.Default.Info, "Sobre") },
                    label = { Text("Sobre", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentCyan,
                        selectedTextColor = AccentCyan,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = AccentCyan.copy(alpha = 0.12f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            Modifier
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
                    onQuickPromptSelected = { p ->
                        viewModel.setPrompt(p)
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
