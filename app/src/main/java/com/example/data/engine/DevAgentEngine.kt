package com.example.data.engine

import com.example.data.api.GeminiClient
import com.example.data.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

sealed class AgentExecutionEvent {
    data class ThinkingTick(val seconds: Double) : AgentExecutionEvent()
    data class ThoughtStepAdded(val step: String) : AgentExecutionEvent()
    data class ToolCallStarted(val toolCall: ToolCallInfo) : AgentExecutionEvent()
    data class ToolCallUpdated(val toolCall: ToolCallInfo) : AgentExecutionEvent()
    data class FinalResponseChunk(val text: String, val metrics: AgentMetrics) : AgentExecutionEvent()
    data class Error(val message: String) : AgentExecutionEvent()
}

class DevAgentEngine {

    fun executeTask(
        userPrompt: String,
        mode: AgentMode,
        speed: ReasoningSpeed,
        repositoryName: String = "octocat/modern-auth-service"
    ): Flow<AgentExecutionEvent> = flow {
        val startTime = System.currentTimeMillis()
        var currentSeconds = 0.0

        // 1. Initial Thinking Phase
        val baseDelay = if (speed == ReasoningSpeed.DEEP_THINKING) 800L else 400L

        emit(AgentExecutionEvent.ThinkingTick(0.2))
        delay(baseDelay)
        currentSeconds += 0.5
        emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

        if (mode == AgentMode.BUILD) {
            // Planning CoT
            emit(AgentExecutionEvent.ThoughtStepAdded("1. Analisando o prompt do usuário e o escopo de execução em: $repositoryName"))
            delay(baseDelay)
            currentSeconds += 0.6
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

            emit(AgentExecutionEvent.ThoughtStepAdded("2. Mapeando dependências do projeto e estruturação de pastas para modificação"))
            delay(baseDelay)
            currentSeconds += 0.7
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

            // Tool 1: read_github_repo
            val tool1 = ToolCallInfo(
                id = UUID.randomUUID().toString(),
                type = ToolType.READ_GITHUB_REPO,
                title = "Inspecionando repositório GitHub",
                detail = "GET /repos/$repositoryName/contents/src",
                status = ExecutionStatus.RUNNING
            )
            emit(AgentExecutionEvent.ToolCallStarted(tool1))
            delay(baseDelay + 300)
            currentSeconds += 0.8
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

            tool1.status = ExecutionStatus.SUCCESS
            tool1.durationMs = 380
            tool1.output = """
                [GitHub API Tree]
                ├── src/
                │   ├── api/routes/auth.py (142 lines)
                │   ├── core/security.py (98 lines)
                │   └── middleware/rate_limit.py (MISSING)
                ├── tests/
                │   └── test_auth.py (65 lines)
                └── pyproject.toml (FastAPI 0.115, Pydantic v2)
            """.trimIndent()
            emit(AgentExecutionEvent.ToolCallUpdated(tool1))

            emit(AgentExecutionEvent.ThoughtStepAdded("3. Estrutura identificada: criando plano de patches e verificando sandbox"))
            delay(baseDelay)
            currentSeconds += 0.6
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

            // Tool 2: run_terminal_command
            val tool2 = ToolCallInfo(
                id = UUID.randomUUID().toString(),
                type = ToolType.RUN_TERMINAL_COMMAND,
                title = "Executando verificação de ambiente isolado",
                detail = "docker exec sandbox poetry run pytest --collect-only",
                status = ExecutionStatus.RUNNING
            )
            emit(AgentExecutionEvent.ToolCallStarted(tool2))
            delay(baseDelay + 400)
            currentSeconds += 0.9
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

            tool2.status = ExecutionStatus.SUCCESS
            tool2.durationMs = 620
            tool2.output = """
                ========================= test session starts ==========================
                platform linux -- Python 3.12.3, pytest-8.3.2, pluggy-1.5.0
                rootdir: /workspace/modern-auth-service
                collected 4 items in 0.08s
                Status: Exit Code 0 (Sandbox isolada pronta para patches)
            """.trimIndent()
            emit(AgentExecutionEvent.ToolCallUpdated(tool2))

            // Tool 3: apply_diff_patch
            emit(AgentExecutionEvent.ThoughtStepAdded("4. Gerando e aplicando patch de código (Adição de middleware e schemas)"))
            val tool3 = ToolCallInfo(
                id = UUID.randomUUID().toString(),
                type = ToolType.APPLY_DIFF_PATCH,
                title = "Aplicando patch de código unificado",
                detail = "git apply --index patch_auth_v2.diff",
                status = ExecutionStatus.RUNNING
            )
            emit(AgentExecutionEvent.ToolCallStarted(tool3))
            delay(baseDelay + 500)
            currentSeconds += 1.1
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

            tool3.status = ExecutionStatus.SUCCESS
            tool3.durationMs = 210
            tool3.output = "Patch aplicado com sucesso: 1 arquivo adicionado (+38 linhas), 1 arquivo modificado (+14/-3 linhas)."
            tool3.diffLines = listOf(
                DiffLine(DiffLineType.HEADER, "diff --git a/src/middleware/rate_limit.py b/src/middleware/rate_limit.py"),
                DiffLine(DiffLineType.ADDED, "+from fastapi import Request, HTTPException, status", null, 1),
                DiffLine(DiffLineType.ADDED, "+import time", null, 2),
                DiffLine(DiffLineType.ADDED, "+from collections import defaultdict", null, 3),
                DiffLine(DiffLineType.ADDED, "+", null, 4),
                DiffLine(DiffLineType.ADDED, "+class InMemoryRateLimiter:", null, 5),
                DiffLine(DiffLineType.ADDED, "+    def __init__(self, requests_per_minute: int = 60):", null, 6),
                DiffLine(DiffLineType.ADDED, "+        self.rate = requests_per_minute", null, 7),
                DiffLine(DiffLineType.ADDED, "+        self.history = defaultdict(list)", null, 8),
                DiffLine(DiffLineType.CONTEXT, " ", null, 9),
                DiffLine(DiffLineType.HEADER, "diff --git a/src/api/routes/auth.py b/src/api/routes/auth.py"),
                DiffLine(DiffLineType.REMOVED, "-@router.post('/login')", 45, null),
                DiffLine(DiffLineType.ADDED, "+@router.post('/login', dependencies=[Depends(rate_limiter)])", null, 45),
                DiffLine(DiffLineType.CONTEXT, " async fun login(form_data: OAuth2PasswordRequestForm):", 46, 46)
            )
            emit(AgentExecutionEvent.ToolCallUpdated(tool3))

            // Tool 4: run_test_suite
            emit(AgentExecutionEvent.ThoughtStepAdded("5. Executando bateria de testes no container Docker para validação"))
            val tool4 = ToolCallInfo(
                id = UUID.randomUUID().toString(),
                type = ToolType.RUN_TESTS,
                title = "Executando suite de testes automatizados",
                detail = "pytest -v tests/test_auth.py",
                status = ExecutionStatus.RUNNING
            )
            emit(AgentExecutionEvent.ToolCallStarted(tool4))
            delay(baseDelay + 400)
            currentSeconds += 0.8
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

            tool4.status = ExecutionStatus.SUCCESS
            tool4.durationMs = 940
            tool4.output = """
                tests/test_auth.py::test_login_success PASSED               [ 25%]
                tests/test_auth.py::test_login_invalid_credentials PASSED   [ 50%]
                tests/test_auth.py::test_jwt_expiration PASSED             [ 75%]
                tests/test_auth.py::test_rate_limiter_blocks_abusive_requests PASSED [100%]
                
                ========================= 4 passed in 0.82s ==========================
            """.trimIndent()
            emit(AgentExecutionEvent.ToolCallUpdated(tool4))

            emit(AgentExecutionEvent.ThoughtStepAdded("6. Todas as ferramentas executadas com sucesso. Sintetizando resposta."))
        } else if (mode == AgentMode.IMAGINE) {
            emit(AgentExecutionEvent.ThoughtStepAdded("1. Decompondo requisitos de arquitetura e modelo de dados"))
            delay(baseDelay)
            currentSeconds += 0.8
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

            emit(AgentExecutionEvent.ThoughtStepAdded("2. Formulando padrões de projeto (Clean Architecture, Repositories, UseCases)"))
            delay(baseDelay)
            currentSeconds += 0.7
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))

            emit(AgentExecutionEvent.ThoughtStepAdded("3. Estruturando diagrama de fluxo e contratos de API (OpenAPI / JSON Schema)"))
        } else { // ASK mode
            emit(AgentExecutionEvent.ThoughtStepAdded("1. Analisando a pergunta técnica e identificando melhores práticas"))
            delay(baseDelay)
            currentSeconds += 0.6
            emit(AgentExecutionEvent.ThinkingTick(currentSeconds))
        }

        // Final Response Generation
        val totalThinkingSeconds = String.format("%.1f", currentSeconds).toDoubleOrNull() ?: currentSeconds

        // Attempt real Gemini API if available
        var responseText = ""
        if (GeminiClient.isApiKeyConfigured()) {
            val systemPrompt = "Você é o DevAgent, um agente autônomo de código como Cursor ou Copilot Workspace. " +
                    "Responda à solicitação do usuário em português de forma clara, técnica e objetiva, " +
                    "incluindo explicações das ações executadas."
            val result = GeminiClient.queryGemini(userPrompt, speed.modelName, systemPrompt)
            if (result.isSuccess) {
                responseText = result.getOrNull().orEmpty()
            }
        }

        if (responseText.isBlank()) {
            responseText = buildDefaultResponse(userPrompt, mode, repositoryName)
        }

        val metrics = AgentMetrics(
            thinkingSeconds = totalThinkingSeconds,
            commandsExecuted = if (mode == AgentMode.BUILD) 3 else 0,
            filesInspected = if (mode == AgentMode.BUILD) 4 else 1,
            linesChanged = if (mode == AgentMode.BUILD) 52 else 0,
            sandboxLatencyMs = if (mode == AgentMode.BUILD) 1150 else 120
        )

        emit(AgentExecutionEvent.FinalResponseChunk(responseText, metrics))
    }

    private fun buildDefaultResponse(prompt: String, mode: AgentMode, repo: String): String {
        return when (mode) {
            AgentMode.BUILD -> """
                ### Tarefa Concluída com Sucesso 🚀
                
                O agente analisou o repositório **`$repo`**, planejou os passos de execução e realizou as alterações no ambiente sandbox isolado:
                
                1. **Inspeção de Código (`read_github_repo`):**
                   - Analisou a estrutura de `src/api/routes/auth.py` e detectou ausência de limitação de requisições.
                
                2. **Isolamento e Testes Pré-modificação (`run_terminal_command`):**
                   - O container Docker foi verificado com sucesso (`pytest --collect-only`).
                
                3. **Aplicação do Patch (`apply_diff_patch`):**
                   - Criado novo middleware `src/middleware/rate_limit.py` com algoritmo Sliding Window.
                   - Vinculada a injeção de dependência no endpoint `/login`.
                
                4. **Validação Final (`run_test_suite`):**
                   - Todos os 4 testes unitários e de integração passaram com código de saída 0.
                
                > O código está pronto para commit e criação de Pull Request.
            """.trimIndent()

            AgentMode.IMAGINE -> """
                ### Especificação Arquitetural e Design 📐
                
                **1. Visão Geral do Sistema:**
                - Arquitetura desacoplada em 4 camadas: `Presentation (Chat UI)`, `Orchestrator (Agent Loop)`, `Tool Execution Gateway (Sandbox Docker)`, e `LLM Provider (Gemini API)`.
                
                **2. Fluxo de Dados:**
                - `User Prompt` → `CoT Planner` → `Function Calling Loop` → `Tool Execution Sandbox` → `Self-Reflection / Validation` → `Code Diff Artifact`.
                
                **3. Requisitos de Segurança:**
                - Isolamento gVisor / Docker rootless com limites estritos de CPU (2 cores), memória (2GB) e sem acesso à rede privada.
            """.trimIndent()

            AgentMode.ASK -> """
                ### Análise Técnica 💡
                
                Em relação à sua solicitação:
                
                - **Padrão ReAct (Reasoning + Acting):** Essencial para agentes de código. O modelo gera uma reflexão intermediária (Thought), decide invocar uma ferramenta (Action), observa o resultado (Observation) e itera até solucionar o problema.
                - **Context Window & Truncamento:** Ao ler repositórios grandes via GitHub API, filtre arquivos binários e minimize o tamanho dos arquivos enviados ao prompt usando Abstract Syntax Trees (ASTs) ou sumários léxicos.
            """.trimIndent()
        }
    }
}
