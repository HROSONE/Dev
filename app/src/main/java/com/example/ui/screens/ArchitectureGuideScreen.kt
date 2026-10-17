package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
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
        "1. Arquitetura",
        "2. Agent Loop",
        "3. Backend Python",
        "4. Frontend React"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Header
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
                    text = "Guia de Arquitetura & Código",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Especificação para Agentes Autônomos de Código (Cursor / Windsurf)",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        // Tabs
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

        // Notice pill when copied
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
            when (selectedTab) {
                0 -> {
                    item {
                        ArchitectureOverviewSection(
                            onCopy = { code ->
                                clipboardManager.setText(AnnotatedString(code))
                                copiedNotice = "Copiado para a área de transferência!"
                            }
                        )
                    }
                }
                1 -> {
                    item {
                        AgentLoopSection(
                            onCopy = { code ->
                                clipboardManager.setText(AnnotatedString(code))
                                copiedNotice = "Ciclo de vida do agente copiado!"
                            }
                        )
                    }
                }
                2 -> {
                    item {
                        PythonBackendSection(
                            onCopy = { code ->
                                clipboardManager.setText(AnnotatedString(code))
                                copiedNotice = "Código Python backend copiado!"
                            }
                        )
                    }
                }
                3 -> {
                    item {
                        FrontendReactSection(
                            onCopy = { code ->
                                clipboardManager.setText(AnnotatedString(code))
                                copiedNotice = "Código React/Next.js copiado!"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ArchitectureOverviewSection(onCopy: (String) -> Unit) {
    val overviewDoc = """
# Arquitetura de Solução: DevAgent (Estilo Cursor / Windsurf)

1. Frontend (Next.js 15+ / React 19):
   - Server-Sent Events (SSE) para streaming contínuo de pensamento e logs.
   - Monaco Editor / CodeMirror para syntax highlighting e visualização de diffs.
   - State machine gerenciando etapas: IDLE -> THINKING -> TOOL_EXEC -> FINISHED.

2. Backend & Agent Orchestrator (Python FastAPI ou Node.js Fastify):
   - Gemini 2.5 Flash / 3.1 Pro com Suporte a Function Calling nativo.
   - Padrão ReAct (Thought -> Action -> Observation -> Response).
   - Rate limiting, telemetria (OpenTelemetry) e fila assíncrona (Celery / Redis / Temporal).

3. Sandbox de Execução Segura:
   - Docker em modo rootless + gVisor (runsc) ou Firecracker MicroVMs.
   - Sem permissão de rede interna (evita SSRF e vazamento de chaves).
   - Isolamento via cgroups v2: 2 CPUs, 2GB RAM, 30s timeout por comando.

4. Conectores Externos:
   - GitHub REST & GraphQL API (autenticação via GitHub App ou Personal Access Token).
   - Git CLI isolado dentro do volume de trabalho temporário.
    """.trimIndent()

    CodeBlockCard(
        title = "Visão Geral dos Componentes e Tecnologias",
        language = "markdown",
        code = overviewDoc,
        onCopy = { onCopy(overviewDoc) }
    )
}

@Composable
fun AgentLoopSection(onCopy: (String) -> Unit) {
    val loopDoc = """
## Ciclo de Vida do Agent Loop (ReAct com CoT)

1. INGESTÃO & CONTEXTO:
   - Usuário envia o prompt + branch ativo + arquivos abertos no editor.
   - Coletor de contexto injeta árvores de arquivos relevantes.

2. PLANEJAMENTO (Chain-of-Thought):
   - O modelo gera etapas lógicas antes de acionar ferramentas.
   - Exemplo: "1. Analisar rotas -> 2. Inspecionar middleware -> 3. Rodar testes".

3. EXECUÇÃO DE FERRAMENTAS (Tool Calling):
   - O modelo retorna `functionCall: { name: 'read_github_repo', args: {...} }`.
   - Backend intercepta e despacha a requisição para a Sandbox Docker ou GitHub API.
   - A resposta da ferramenta é empacotada em `functionResponse` e devolvida ao Gemini.

4. AUTO-REFLEXÃO & AUTO-CORREÇÃO:
   - Se o comando de teste (`pytest`) falhar (Exit Code != 0), o agente inspeciona o stderr, ajusta o patch de código e tenta novamente até convergir (máximo 3 tentativas).

5. SÍNTESE FINAL:
   - Geração de sumário explicativo e criação de Pull Request / Patch Git.
    """.trimIndent()

    CodeBlockCard(
        title = "Estrutura do Ciclo de Vida do Agente",
        language = "markdown",
        code = loopDoc,
        onCopy = { onCopy(loopDoc) }
    )
}

@Composable
fun PythonBackendSection(onCopy: (String) -> Unit) {
    val pythonCode = """
# backend/agent_service.py
import os
import subprocess
import requests
from google import genai
from google.genai import types

# 1. Definição das Ferramentas (Tools Definition)
def read_github_repo(owner: str, repo: str, path: str = "") -> str:
    \"\"\"Lê arquivos ou diretórios de um repositório GitHub via API.\"\"\"
    url = f"https://api.github.com/repos/{owner}/{repo}/contents/{path}"
    headers = {"Authorization": f"Bearer {os.getenv('GITHUB_TOKEN')}"}
    res = requests.get(url, headers=headers)
    if res.status_code == 200:
        data = res.json()
        if isinstance(data, list):
            return "\n".join([f"[{item['type']}] {item['path']}" for item in data])
        return data.get("content", "Arquivo vazio")
    return f"Erro ao acessar GitHub: HTTP {res.status_code}"

def run_terminal_command(command: str) -> str:
    \"\"\"Executa comandos de terminal em um container Docker isolado.\"\"\"
    # Boas práticas de segurança: timeout estrito e ambiente isolado
    docker_cmd = [
        "docker", "exec", "--user", "sandboxuser",
        "-w", "/workspace", "sandbox_container",
        "bash", "-c", command
    ]
    try:
        proc = subprocess.run(
            docker_cmd, capture_output=True, text=True, timeout=30
        )
        return f"Exit Code {proc.returncode}\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
    except subprocess.TimeoutExpired:
        return "Erro: Comando excedeu o timeout de 30 segundos (SIGKILL)."

# 2. Inicialização do Cliente Gemini e Agent Loop
client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"))

tools = [read_github_repo, run_terminal_command]

async def run_autonomous_agent(user_prompt: str, repo: str):
    config = types.GenerateContentConfig(
        tools=tools,
        temperature=0.2,
        system_instruction=(
            "Você é um engenheiro de software autônomo. Antes de alterar arquivos, "
            "sempre inspecione o repositório, planeje os passos e execute os testes."
        )
    )

    chat = client.chats.create(model="gemini-2.5-flash", config=config)
    response = chat.send_message(user_prompt)

    # Processa as chamadas de ferramentas iterativamente
    while response.function_calls:
        for tool_call in response.function_calls:
            print(f"Executando ferramenta: {tool_call.name}")
            # O SDK do Gemini com tools mapeadas em Python executa e itera automaticamente!
        response = chat.send_message("Continue com os próximos passos planejados.")

    return response.text
    """.trimIndent()

    CodeBlockCard(
        title = "Backend Python com Gemini Function Calling",
        language = "python",
        code = pythonCode,
        onCopy = { onCopy(pythonCode) }
    )
}

@Composable
fun FrontendReactSection(onCopy: (String) -> Unit) {
    val reactCode = """
// frontend/components/AgentChat.tsx
import React, { useState } from 'react';

interface ToolExecution {
  id: string;
  name: string;
  status: 'running' | 'success' | 'failed';
  command?: string;
  output?: string;
}

export function AgentChat() {
  const [prompt, setPrompt] = useState('');
  const [isThinking, setIsThinking] = useState(false);
  const [thinkingSeconds, setThinkingSeconds] = useState(0);
  const [activeTools, setActiveTools] = useState<ToolExecution[]>([]);

  const handleSendPrompt = async () => {
    setIsThinking(true);
    // Inicia SSE (Server-Sent Events) para receber chunks e status de ferramentas
    const eventSource = new EventSource('/api/agent/stream?q=' + encodeURIComponent(prompt));

    eventSource.onmessage = (event) => {
      const data = JSON.parse(event.data);
      if (data.type === 'THINKING_TICK') {
        setThinkingSeconds(data.seconds);
      } else if (data.type === 'TOOL_CALL_STARTED') {
        setActiveTools(prev => [...prev, data.tool]);
      } else if (data.type === 'TOOL_CALL_FINISHED') {
        setActiveTools(prev => prev.map(t => t.id === data.tool.id ? data.tool : t));
      } else if (data.type === 'DONE') {
        setIsThinking(false);
        eventSource.close();
      }
    };
  };

  return (
    <div className="flex flex-col h-screen bg-[#090D12] text-white">
      {/* Indicadores de Status em Tempo Real */}
      <div className="flex items-center gap-3 p-3 bg-[#161B22] border-b border-[#30363D]">
        {isThinking && (
          <span className="text-purple-400 animate-pulse text-xs font-semibold">
            {"Pensando por " + thinkingSeconds.toFixed(1) + "s..."}
          </span>
        )}
        <span className="text-green-400 text-xs bg-green-950 px-2 py-1 rounded">
          🐙 GitHub Conectado
        </span>
        <span className="text-cyan-400 text-xs bg-cyan-950 px-2 py-1 rounded">
          🐳 Sandbox Docker Ativa
        </span>
      </div>

      {/* Cards de Execução das Ferramentas */}
      <div className="flex-1 overflow-y-auto p-4 space-y-3">
        {activeTools.map(tool => (
          <div key={tool.id} className="p-3 bg-[#0D1117] border border-[#30363D] rounded-lg">
            <div className="flex justify-between items-center text-xs">
              <span className="font-mono text-cyan-400">{tool.name}</span>
              <span className={tool.status === 'success' ? 'text-green-400' : 'text-yellow-400'}>
                {tool.status}
              </span>
            </div>
            {tool.output && (
              <pre className="mt-2 p-2 bg-black text-xs font-mono text-gray-300 rounded overflow-x-auto">
                {tool.output}
              </pre>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
    """.trimIndent()

    CodeBlockCard(
        title = "Frontend Next.js/React com Streaming de Estado",
        language = "typescript",
        code = reactCode,
        onCopy = { onCopy(reactCode) }
    )
}

@Composable
fun CodeBlockCard(
    title: String,
    language: String,
    code: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(10.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
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
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copiar Código",
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
