package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.LlmProvider
import com.example.data.model.ProjectTemplate
import com.example.data.model.QwenModelVariant
import com.example.ui.components.AgentModeSelector
import com.example.ui.components.DiffView
import com.example.ui.screens.AgentChatScreen
import com.example.ui.screens.ArchitectureGuideScreen
import com.example.ui.screens.SandboxWorkspaceScreen
import com.example.ui.theme.*
import com.example.ui.viewmodel.DevAgentViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DevAgentApp(viewModel: DevAgentViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showRepoDialog by remember { mutableStateOf(false) }
    var repoDialogTab by remember { mutableIntStateOf(0) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    var repoInputText by remember { mutableStateOf(uiState.activeRepo) }
    var githubTokenText by remember { mutableStateOf("") }

    var localProjectName by remember { mutableStateOf("meu-app") }
    var selectedTemplate by remember { mutableStateOf(ProjectTemplate.ANDROID_COMPOSE) }

    var settingsProvider by remember { mutableStateOf(LlmProvider.GEMINI) }
    var geminiKeyInput by remember { mutableStateOf("") }
    var openRouterKeyInput by remember { mutableStateOf("") }
    var selectedOrModel by remember { mutableStateOf(LlmProvider.DEFAULT_OPENROUTER_MODEL) }
    var selectedQwenVariant by remember { mutableStateOf(QwenModelVariant.QWEN_1_5B) }
    var qwenEndpointInput by remember { mutableStateOf("") }
    var hfTokenInput by remember { mutableStateOf("") }
    var settingsGhTokenInput by remember { mutableStateOf("") }
    var showKeysPlain by remember { mutableStateOf(false) }

    val deviceFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importFilesFromDevice(uris)
            showRepoDialog = false
        }
    }

    LaunchedEffect(uiState.repositoryStatus) {
        if (uiState.repositoryStatus.startsWith("Conectado")) {
            showRepoDialog = false
            githubTokenText = ""
        }
    }

    // Diálogo de Histórico de Conversas Persistente
    if (showHistoryDialog) {
        val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Histórico de Conversas", fontSize = 16.sp, color = TextPrimary)
                    TextButton(
                        onClick = {
                            viewModel.startNewChatSession()
                            showHistoryDialog = false
                        }
                    ) {
                        Text("+ Nova Conversa", fontSize = 12.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.chatSessions.isEmpty()) {
                        Text(
                            "Nenhuma conversa salva ainda. Envie uma mensagem no chat e ela ficará salva automaticamente quando você fechar e abrir o app.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    } else {
                        uiState.chatSessions.forEach { session ->
                            val isCurrent = session.id == uiState.activeSessionId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isCurrent) AccentCyan.copy(alpha = 0.14f) else DarkSurface)
                                    .clickable {
                                        viewModel.selectChatSession(session.id)
                                        showHistoryDialog = false
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        session.title,
                                        fontSize = 13.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrent) AccentCyan else TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    val repoLabel = session.activeRepo.ifBlank { "Sem repo" }
                                    Text(
                                        "${dateFormat.format(Date(session.updatedAt))} · $repoLabel · ${session.messageCount} msg(s)",
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.deleteChatSession(session.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Excluir conversa",
                                        tint = AccentRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text("Fechar", color = TextSecondary)
                }
            },
            containerColor = DarkSurfaceVariant
        )
    }

    // Diálogo de Configurações (Gemini, OpenRouter, Qwen 2.5 Coder e Token GitHub)
    if (showSettingsDialog) {
        LaunchedEffect(Unit) {
            settingsProvider = uiState.llmProvider
            geminiKeyInput = viewModel.getStoredGeminiKey()
            openRouterKeyInput = viewModel.getStoredOpenRouterKey()
            selectedOrModel = uiState.openRouterModelId
            selectedQwenVariant = uiState.qwenVariant
            qwenEndpointInput = uiState.qwenLocalEndpoint
            hfTokenInput = viewModel.getStoredHuggingFaceKey()
            settingsGhTokenInput = viewModel.getStoredGitHubToken()
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
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
                    when (settingsProvider) {
                        LlmProvider.GEMINI -> {
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
                        }
                        LlmProvider.OPENROUTER -> {
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
                        LlmProvider.QWEN_CODER -> {
                            Text("Modelo Qwen 2.5 Coder (GGUF Local / HF)", fontSize = 12.sp, color = TextPrimary)
                            Spacer(Modifier.height(6.dp))
                            QwenModelVariant.entries.forEach { variant ->
                                FilterChip(
                                    selected = selectedQwenVariant == variant,
                                    onClick = { selectedQwenVariant = variant },
                                    label = { Text(variant.label, fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentGreen.copy(alpha = 0.2f),
                                        selectedLabelColor = AccentGreen
                                    )
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                uiState.qwenDownloadStatus,
                                fontSize = 11.sp,
                                color = if (uiState.isQwenModelDownloaded) AccentGreen else AccentCyan
                            )
                            if (uiState.isDownloadingQwenModel) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { uiState.qwenDownloadProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = AccentCyan,
                                    trackColor = DarkBorder
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!uiState.isDownloadingQwenModel) {
                                    Button(
                                        onClick = { viewModel.downloadQwenModel(selectedQwenVariant, hfTokenInput) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            if (uiState.isQwenModelDownloaded) "Verificar GGUF" else "Baixar Qwen GGUF",
                                            fontSize = 11.sp,
                                            color = DarkBackground,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    TextButton(onClick = { viewModel.cancelQwenDownload() }) {
                                        Text("Pausar", fontSize = 11.sp, color = AccentYellow)
                                    }
                                }
                                if (uiState.isQwenModelDownloaded) {
                                    TextButton(onClick = { viewModel.deleteDownloadedQwenModel(selectedQwenVariant) }) {
                                        Text("Remover", fontSize = 11.sp, color = AccentRed)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = qwenEndpointInput,
                                onValueChange = { qwenEndpointInput = it },
                                label = { Text("Endpoint Local (llama-server / Ollama)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = DarkBorder
                                )
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = hfTokenInput,
                                onValueChange = { hfTokenInput = it },
                                label = { Text("Token Hugging Face (opcional · hf_…)") },
                                singleLine = true,
                                visualTransformation = if (showKeysPlain) VisualTransformation.None else PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = DarkBorder
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Token GitHub (Repos Privados & Auto-Update)", fontSize = 12.sp, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = settingsGhTokenInput,
                        onValueChange = { settingsGhTokenInput = it },
                        label = { Text("ghp_… / github_pat_…") },
                        singleLine = true,
                        visualTransformation = if (showKeysPlain) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showKeysPlain, onCheckedChange = { showKeysPlain = it })
                        Text("Mostrar chaves", fontSize = 12.sp, color = TextSecondary)
                    }
                    if (uiState.apiKeyStatus.isNotBlank()) {
                        Text(uiState.apiKeyStatus, fontSize = 11.sp, color = AccentCyan)
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = DarkBorder)
                    Spacer(Modifier.height(10.dp))
                    Text("Atualização (HROSONE/Dev)", fontSize = 13.sp, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        uiState.updateStatus.ifBlank { "Releases em HROSONE/Dev" },
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                if (settingsGhTokenInput.isNotBlank()) {
                                    viewModel.saveLlmSettings(
                                        provider = settingsProvider,
                                        geminiKey = geminiKeyInput,
                                        openRouterKey = openRouterKeyInput,
                                        openRouterModelId = selectedOrModel,
                                        qwenVariant = selectedQwenVariant,
                                        qwenLocalEndpoint = qwenEndpointInput,
                                        huggingFaceKey = hfTokenInput,
                                        gitHubToken = settingsGhTokenInput
                                    )
                                }
                                viewModel.checkForAppUpdate()
                            },
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
                            openRouterModelId = selectedOrModel,
                            qwenVariant = selectedQwenVariant,
                            qwenLocalEndpoint = qwenEndpointInput,
                            huggingFaceKey = hfTokenInput,
                            gitHubToken = settingsGhTokenInput
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
            title = { Text("Confirmar ${mutation.toolName}", fontSize = 16.sp, color = TextPrimary) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(mutation.path, fontSize = 13.sp, color = AccentCyan, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    if (mutation.diffLines.isNotEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
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

    // Diálogo Unificado: Conectar GitHub | Criar Projeto no DevAgent | Importar do Celular
    if (showRepoDialog) {
        AlertDialog(
            onDismissRequest = { showRepoDialog = false },
            title = { Text("Projeto & Repositório", fontSize = 16.sp, color = TextPrimary) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabRow(
                        selectedTabIndex = repoDialogTab,
                        containerColor = DarkSurface,
                        contentColor = AccentCyan
                    ) {
                        Tab(
                            selected = repoDialogTab == 0,
                            onClick = { repoDialogTab = 0 },
                            text = { Text("GitHub", fontSize = 11.sp) }
                        )
                        Tab(
                            selected = repoDialogTab == 1,
                            onClick = { repoDialogTab = 1 },
                            text = { Text("Criar Local", fontSize = 11.sp) }
                        )
                        Tab(
                            selected = repoDialogTab == 2,
                            onClick = { repoDialogTab = 2 },
                            text = { Text("Do Celular", fontSize = 11.sp) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    when (repoDialogTab) {
                        0 -> {
                            Text(
                                "Conecte um repositório existente do GitHub:",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = repoInputText,
                                onValueChange = { repoInputText = it },
                                label = { Text("owner/repo") },
                                singleLine = true,
                                enabled = !uiState.isConnectingRepo,
                                modifier = Modifier.fillMaxWidth(),
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
                                label = { Text("Token GitHub (salvo em Configurações se vazio)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                enabled = !uiState.isConnectingRepo,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = DarkBorder
                                )
                            )
                        }
                        1 -> {
                            Text(
                                "Crie um repositório inteiro novo dentro do próprio DevAgent (depois você pode publicá-lo como repositório novo no GitHub na aba GitHub):",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = localProjectName,
                                onValueChange = { localProjectName = it },
                                label = { Text("Nome do projeto (ex.: meu-app)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = DarkBorder
                                )
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("Template Inicial:", fontSize = 12.sp, color = TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            ProjectTemplate.entries.forEach { tmpl ->
                                FilterChip(
                                    selected = selectedTemplate == tmpl,
                                    onClick = { selectedTemplate = tmpl },
                                    label = { Text(tmpl.label, fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentGreen.copy(alpha = 0.2f),
                                        selectedLabelColor = AccentGreen
                                    )
                                )
                            }
                        }
                        2 -> {
                            Text(
                                "Adicione arquivos avulsos (.kt, .py, .js, .json, .md) ou selecione um pacote .zip inteiro armazenado no seu celular para abrir no workspace:",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { deviceFilePicker.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                            ) {
                                Text(
                                    "Selecionar Arquivo(s) ou .ZIP do Celular",
                                    fontSize = 12.sp,
                                    color = DarkBackground,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when (repoDialogTab) {
                    0 -> TextButton(
                        enabled = !uiState.isConnectingRepo && repoInputText.isNotBlank(),
                        onClick = {
                            val t = githubTokenText
                            githubTokenText = ""
                            viewModel.connectRepository(repoInputText, t)
                        }
                    ) { Text(if (uiState.isConnectingRepo) "…" else "Conectar", color = AccentCyan) }
                    1 -> TextButton(
                        enabled = localProjectName.isNotBlank(),
                        onClick = {
                            viewModel.createLocalProject(localProjectName, selectedTemplate)
                            showRepoDialog = false
                        }
                    ) { Text("Criar Projeto", color = AccentGreen, fontWeight = FontWeight.Bold) }
                    else -> {}
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
                            repoInputText = uiState.activeRepo.removePrefix("local/")
                            showRepoDialog = true
                        },
                        autoApproveEdits = uiState.autoApproveEdits,
                        onToggleAutoApprove = { viewModel.toggleAutoApproveEdits() },
                        onOpenHistoryClick = { showHistoryDialog = true },
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
                    label = {
                        Text(
                            if (uiState.repoFiles.isNotEmpty()) "Arquivos (${uiState.repoFiles.size})" else "Arquivos",
                            fontSize = 11.sp
                        )
                    },
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
                    },
                    workspaceRoot = viewModel.workspaceDir,
                    onOpenCodeInWorkspace = { path, code ->
                        viewModel.openCodeCardInWorkspace(path, code)
                    }
                )
                1 -> SandboxWorkspaceScreen(
                    activeRepo = uiState.activeRepo,
                    isLocalOnlyProject = uiState.isLocalOnlyProject,
                    repositoryStatus = uiState.repositoryStatus,
                    files = uiState.repoFiles,
                    selectedFile = uiState.selectedFile,
                    onSelectFile = { viewModel.selectFile(it) },
                    onSaveFile = { path, content -> viewModel.saveManualFileEdit(path, content) },
                    onRevertFile = { path -> viewModel.revertFileToBaseline(path) },
                    onImportFromDeviceClick = { deviceFilePicker.launch(arrayOf("*/*")) },
                    onOpenNewProjectClick = {
                        repoDialogTab = 1
                        showRepoDialog = true
                    },
                    terminalLogs = uiState.terminalLogs,
                    onRunCommand = { viewModel.runTerminalCommand(it) },
                    hasWriteToken = uiState.hasWriteToken,
                    isCommitting = uiState.isCommitting,
                    isCreatingGitHubRepo = uiState.isCreatingGitHubRepo,
                    lastCommitStatus = uiState.lastCommitStatus,
                    onCommitFile = { viewModel.commitFileToGitHub(it) },
                    onCommitModifiedFiles = { msg -> viewModel.commitModifiedFilesToGitHub(msg) },
                    onCreateGitHubRepo = { name, desc, isPrivate, token ->
                        viewModel.createGitHubRepoFromWorkspace(name, desc, isPrivate, token)
                    },
                    workspaceRoot = viewModel.workspaceDir
                )
                2 -> ArchitectureGuideScreen()
            }
        }
    }
}
