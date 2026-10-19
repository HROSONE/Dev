package com.example.data.model

enum class AgentMode(val label: String, val subtitle: String, val iconDescription: String) {
    ASK("Ask", "Análise de código, dúvidas e debug", "Modo Pergunta"),
    IMAGINE("Plan", "Arquitetura, especificação e design", "Modo Arquitetura"),
    BUILD("Build", "Geração e edição autônoma de código", "Modo Construtor")
}

enum class ReasoningSpeed(val label: String, val modelName: String, val badge: String) {
    FAST("⚡ Fast · Flash", "gemini-3.8-flash", "Flash"),
    DEEP_THINKING("🧠 Deep · CoT", "gemini-3.8-flash", "Deep CoT")
}

enum class LlmProvider(val label: String) {
    GEMINI("Gemini 3.8"),
    OPENROUTER("OpenRouter"),
    QWEN_CODER("Qwen 2.5 Coder")
    ;

    companion object {
        // Free e bom para código — sem cobrança na OpenRouter
        const val DEFAULT_OPENROUTER_MODEL = "qwen/qwen-2.5-coder-32b-instruct:free"

        val openRouterPresets: List<OpenRouterModelPreset> = listOf(
            OpenRouterModelPreset("Qwen 2.5 Coder 32B (free)", "qwen/qwen-2.5-coder-32b-instruct:free", free = true),
            OpenRouterModelPreset("Qwen 3.8 27B (free)", "qwen/qwen3.8-27b:free", free = true),
            OpenRouterModelPreset("Qwen3 32B (free)", "qwen/qwen3-32b:free", free = true),
            OpenRouterModelPreset("Qwen3 Coder (free)", "qwen/qwen3-coder:free", free = true),
            OpenRouterModelPreset("Qwen 2.5 Coder 32B", "qwen/qwen-2.5-coder-32b-instruct", free = false),
            OpenRouterModelPreset("DeepSeek V4.1 Flash", "deepseek/deepseek-v4.1-flash", free = false),
            OpenRouterModelPreset("OpenRouter free auto", "openrouter/free", free = true)
        )
    }
}

enum class QwenModelVariant(
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    val approxBytes: Long,
    val hfModelId: String
) {
    QWEN_0_5B(
        label = "Qwen 2.5 Coder 0.5B Instruct (Q4_K_M · ~398 MB)",
        fileName = "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
        approxBytes = 398_000_000L,
        hfModelId = "Qwen/Qwen2.5-Coder-1.5B-Instruct"
    ),
    QWEN_1_5B(
        label = "Qwen 2.5 Coder 1.5B Instruct (Q4_K_M · ~1.1 GB)",
        fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        approxBytes = 1_120_000_000L,
        hfModelId = "Qwen/Qwen2.5-Coder-1.5B-Instruct"
    ),
    QWEN_3B(
        label = "Qwen 2.5 Coder 3B Instruct (Q4_K_M · ~2.0 GB)",
        fileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        approxBytes = 2_050_000_000L,
        hfModelId = "Qwen/Qwen2.5-Coder-7B-Instruct"
    ),
    QWEN_7B(
        label = "Qwen 2.5 Coder 7B Instruct (Q4_K_M · ~4.7 GB)",
        fileName = "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
        approxBytes = 4_700_000_000L,
        hfModelId = "Qwen/Qwen2.5-Coder-7B-Instruct"
    )
}

enum class ProjectTemplate(val label: String, val description: String) {
    BLANK("Projeto em Branco", "README.md e .gitignore prontos para começar"),
    ANDROID_COMPOSE("Android MVVM + Compose", "Arquitetura completa: Model, ViewModel, UI Compose e Gradle Kotlin DSL"),
    PYTHON_APP("Python CLI / Automação", "Estrutura modular src/, main.py, testes, requirements.txt"),
    WEB_APP("Web App Moderno (SPA)", "HTML5 semântico, CSS variáveis dark mode e arquitetura JS modular"),
    NODE_TS_API("API Node.js + TypeScript", "Servidor REST tipado com package.json, tsconfig.json e rotas")
}

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val activeRepo: String,
    val messageCount: Int
)

data class OpenRouterModelPreset(
    val label: String,
    val id: String,
    val free: Boolean
)

enum class ToolType(val displayName: String, val category: String) {
    LIST_FILES("list_files", "Workspace"),
    READ_FILE("read_file", "Workspace"),
    READ_FILE_LINES("read_file_lines", "Harness"),
    SEARCH_FILES("search_files", "Workspace"),
    FIND_SYMBOL("find_symbol", "Harness"),
    EDIT_FILE("edit_file", "Workspace"),
    PATCH_LINES("patch_lines", "Harness"),
    WRITE_FILE("write_file", "Workspace"),
    DELETE_FILE("delete_file", "Workspace"),
    VERIFY_SYNTAX("verify_syntax", "Harness"),
    RUN_COMMAND("run_command", "Sandbox"),
    READ_GITHUB_REPO("read_github_repo", "GitHub API"),
    COMMIT_GITHUB("commit_github", "GitHub API")
}

enum class ExecutionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    AWAITING_CONFIRMATION
}

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null
)

enum class DiffLineType {
    CONTEXT, ADDED, REMOVED, HEADER
}

data class ToolCallInfo(
    val id: String,
    val type: ToolType,
    val title: String,
    val detail: String,
    var status: ExecutionStatus = ExecutionStatus.PENDING,
    var durationMs: Long = 0,
    var output: String = "",
    var diffLines: List<DiffLine> = emptyList(),
    var isExpanded: Boolean = false
)

data class AgentMetrics(
    val thinkingSeconds: Double = 0.0,
    val commandsExecuted: Int = 0,
    val filesInspected: Int = 0,
    val linesChanged: Int = 0,
    val sandboxLatencyMs: Long = 0
)

enum class MessageRole {
    USER, AGENT, SYSTEM
}

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val mode: AgentMode = AgentMode.BUILD,
    val speed: ReasoningSpeed = ReasoningSpeed.DEEP_THINKING,
    val isThinking: Boolean = false,
    val thinkingSeconds: Double = 0.0,
    val thoughtSteps: List<String> = emptyList(),
    val toolCalls: List<ToolCallInfo> = emptyList(),
    val metrics: AgentMetrics? = null
)

data class RepoFile(
    val path: String,
    val language: String,
    val content: String,
    val isModified: Boolean = false
)

data class PendingMutation(
    val toolName: String,
    val path: String,
    val contentPreview: String,
    val messageId: String,
    val diffLines: List<DiffLine> = emptyList()
)
