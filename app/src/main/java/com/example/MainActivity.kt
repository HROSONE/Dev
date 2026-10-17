package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AgentModeSelector
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
    var repoInputText by remember { mutableStateOf(uiState.activeRepo) }
    var githubTokenText by remember { mutableStateOf("") }

    if (showRepoDialog) {
        AlertDialog(
            onDismissRequest = { showRepoDialog = false },
            title = { Text("Conectar Repositório GitHub", fontSize = 16.sp, color = TextPrimary) },
            text = {
                Column {
                    Text(
                        text = "Informe o repositório para o conector GitHub do agente:",
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
                        label = { Text("Token GitHub (opcional para repositórios públicos)") },
                        placeholder = { Text("ghp_… ou github_pat_…") },
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
                        text = "Para repositórios privados, use um token com somente permissão de leitura de conteúdo. O token é usado em memória nesta sessão e não é salvo pelo app.",
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
                        viewModel.connectRepository(repoInputText, githubTokenText)
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
                    icon = {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "Agente"
                        )
                    },
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
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Sandbox"
                        )
                    },
                    label = { Text("Sandbox & Repo", fontSize = 11.sp) },
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
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Arquitetura"
                        )
                    },
                    label = { Text("Arquitetura & Código", fontSize = 11.sp) },
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
                0 -> {
                    AgentChatScreen(
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
                }

                1 -> {
                    SandboxWorkspaceScreen(
                        activeRepo = uiState.activeRepo,
                        files = uiState.repoFiles,
                        selectedFile = uiState.selectedFile,
                        onSelectFile = { viewModel.selectFile(it) }
                    )
                }

                2 -> {
                    ArchitectureGuideScreen()
                }
            }
        }
    }
}
