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
                    text = "Android · Kotlin · Agente Autônomo Estilo Claude Code",
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
# Stack implementada (Agente Autônomo Estilo Claude Code)

• App Android nativo (Kotlin + Jetpack Compose)
• Modelos: Gemini 3.8 Flash + OpenRouter (Claude, DeepSeek, Qwen, etc.)
• GitHub REST + Git Data API: leitura de até 120 arquivos + commit atômico multi-arquivo
• Workspace local editável em filesDir/devagent-workspace + Editor de Código integrado
• Tools: list_files, search_files, read_file, edit_file (cirúrgico), write_file, delete_file, run_command
• Comandos do terminal: pwd, ls, cat, grep, find, head, tail, wc, tree

Camadas:
  UI (Compose) → ViewModel → DevAgentEngine → AgentToolLoop (até 18 passos)
       → AgentToolCoordinator → WorkspaceFileTools / WorkspaceCommandRunner
       → LlmGateway (Gemini / OpenRouter) / GitHubRepositoryClient
""".trimIndent()

private val AGENT_LOOP = """
# Ciclo autônomo do agente (Claude Code Style)

1. Mantém histórico multi-turn e índice completo da árvore de arquivos do workspace
2. Extrai raciocínio + JSON de ferramenta (mesmo dentro de blocos ```json ou após explicação)
3. Pesquisa com search_files / lê com read_file e aplica mudanças cirúrgicas com edit_file
4. Modo ⚡ Auto (padrão em Construir): executa edições encadeadas autonomamente e anexa o diff colorido em cada card
5. Modo 🔒 Diff: abre diálogo de aprovação humana antes de cada gravação
6. Auto-recuperação: se um caminho ou old_text falhar, devolve o erro ao modelo para ele corrigir e continuar
7. Aba Commit envia todos os arquivos alterados em um único commit Git atômico (Git Trees API)
""".trimIndent()

private val SECURITY = """
# Segurança implementada

• Path canonical — bloqueia path traversal (../) e caminhos absolutos
• Sem shell arbitrário: comandos restritos ao workspace (pwd, ls, cat, grep, find, head, tail, wc, tree)
• Sem pipes, ;, |, $, ``
• Token GitHub mantido apenas em memória de sessão
• Arquivos sensíveis (.env, keys, keystores) ignorados na leitura remota
• Modos ASK e IMAGINE bloqueiam qualquer mutação na camada de política (ToolExecutionPolicy)
""".trimIndent()

private val LIMITS = """
# Limites honestos do ambiente local no Android

• Não compila APK / Gradle diretamente dentro do celular (o push para o GitHub dispara o workflow Build APK no GitHub Actions, e o app baixa e instala a nova release automaticamente)
• Repositórios com mais de 120 arquivos priorizam código-fonte principal (src/main, src/test, configs)
• Branch protegida / token sem contents:write → commit falha com mensagem clara
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
