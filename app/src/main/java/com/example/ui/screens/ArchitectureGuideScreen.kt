package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun ArchitectureGuideScreen(modifier: Modifier = Modifier) {
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var copiedNotice by remember { mutableStateOf<String?>(null) }

    val tabs = listOf(
        "1. O que é real",
        "2. Agent Loop",
        "3. Segurança",
        "4. Limites"
    )

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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.IntegrationInstructions,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Arquitetura real do DevAgent",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Android · Kotlin · Gemini 3.8 Flash · workspace local",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurface,
            contentColor = AccentCyan,
            edgePadding = 8.dp,
            divider = { HorizontalDivider(color = DarkBorder, thickness = 1.dp) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 12.sp) }
                )
            }
        }

        copiedNotice?.let { notice ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AccentGreen.copy(alpha = 0.2f))
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = notice, fontSize = 11.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                val text = when (selectedTab) {
                    0 -> REAL_STACK
                    1 -> AGENT_LOOP
                    2 -> SECURITY
                    else -> LIMITS
                }
                CodeBlockCard(
                    title = tabs[selectedTab],
                    code = text,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(text))
                        copiedNotice = "Copiado!"
                    }
                )
            }
        }
    }
}

private val REAL_STACK = """
# Stack implementada (não é Next.js / Docker)

• App Android nativo (Kotlin + Jetpack Compose)
• Modelo: gemini-3.8-flash via Generative Language API
• GitHub REST: leitura de árvore + contents; commit opcional (Contents API)
• Workspace isolado em filesDir/devagent-workspace
• Tools: list_files, read_file, write_file, run_command (pwd|ls|cat)

Camadas:
  UI (Compose) → ViewModel → DevAgentEngine → AgentToolLoop
       → AgentToolCoordinator → WorkspaceFileTools / WorkspaceCommandRunner
       → GeminiClient / GitHubRepositoryClient
""".trimIndent()

private val AGENT_LOOP = """
# Ciclo real do agente

1. Classifica intenção (ChatIntentClassifier) — routing, não autorização
2. Monta system prompt + contexto do repo (até ~30 arquivos)
3. Chama Gemini; se a resposta for JSON de tool, executa no workspace
4. write_file exige: modo BUILD + IMPLEMENTATION_REQUEST + confirmação na UI
5. Após aprovação, grava no workspace local
6. Commit no GitHub é manual (botão) e só com token contents:write

Limite: até 6 tool calls por turno.
""".trimIndent()

private val SECURITY = """
# Segurança implementada

• Path canonical — bloqueia traversal e caminhos absolutos
• Sem shell: ProcessBuilder só com pwd, ls, cat
• Sem pipes, ;, |, $, ``
• Token GitHub só em memória de sessão
• Arquivos sensíveis (.env, keys, keystores) ignorados na leitura
• Mutação só com confirmação humana explícita
• Não há Docker, gVisor nem micro-VM neste app
""".trimIndent()

private val LIMITS = """
# Limites honestos

• Não executa Gradle/pytest/npm do projeto alvo
• Terminal só pwd / ls / cat
• Contexto limitado (~30 arquivos prioritários)
• Commit é arquivo a arquivo (Contents API), não git push multi-file
• Branch protegida / token sem write → commit falha com erro claro
• Histórico de chat não persiste entre sessões
""".trimIndent()

@Composable
fun CodeBlockCard(
    title: String,
    code: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(10.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copiar",
                        tint = AccentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TerminalBackground)
                    .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextPrimary,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
