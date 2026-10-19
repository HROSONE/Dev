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
        "1. SWE-Harness",
        "2. Agent Loop 2.0",
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
                    text = "Arquitetura SWE-Agent Harness do DevAgent",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "RepoMap · SyntaxGuard · Batch Tools · Edição Cirúrgica Tolerante",
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
# SWE-Agent Harness 2.0 (Foco Máximo em Geração de Código)

• RepoSymbolIndexer (RepoMap Automático):
  Indexa classes, interfaces, objetos, funções e rotas com número de linha em Kotlin, Java, Python, TS/JS, Go e Rust e injeta o mapa estrutural diretamente no prompt do sistema.
• 11 Ferramentas de Engenharia de Precisão:
  - find_symbol: localiza declarações de classes/funções com linha exata
  - read_file_lines: lê janelas de código numeradas ("  42 | codigo") economizando contexto
  - edit_file: substituição cirúrgica tolerante a diferenças de indentação e CRLF
  - patch_lines: substitui intervalos exatos de linhas [start_line..end_line]
  - verify_syntax: validador estático SyntaxGuard (chaves/parênteses/strings/JSON e bloqueio de placeholders)
  - search_files, list_files, read_file, write_file, delete_file, run_command
• Modelos Suportados:
  - Gemini 3.8 Flash, Qwen 2.5 Coder (Motor Nativo GGUF llama-server + Fallback Cloud) e OpenRouter.
""".trimIndent()

private val AGENT_LOOP = """
# Ciclo Autônomo 2.0 (24 Passos + Batch Execution + SyntaxGuard)

1. Protocolo THINK → LOCATE → ACT → VERIFY para espremer o máximo do LLM.
2. Execução Multi-Tool em Lote (Batch):
   O modelo pode emitir até 4 ferramentas num único turno (ex.: ler vários arquivos ou criar múltiplos módulos de um projeto do zero).
3. Auto-Verificação Pós-Mutação (SyntaxGuard):
   Toda chamada de edit_file, patch_lines ou write_file roda automaticamente o SyntaxVerifier. Se houver chave desbalanceada ou placeholder proibido ("// ... existing code"), o erro exato e a linha são devolvidos ao modelo no mesmo turno para autocorreção imediata.
4. Compactação de Janela Deslizante (Context Compaction):
   Preserva os passos recentes completos e resume leituras antigas para nunca estourar a janela de tokens em refatorações longas.
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
