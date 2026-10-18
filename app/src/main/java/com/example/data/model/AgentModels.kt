package com.example.data.model

enum class AgentMode(val label: String, val subtitle: String, val iconDescription: String) {
    ASK("Pergunte", "Consultas rápidas e explicações", "Modo Pergunta"),
    IMAGINE("Imagine", "Arquitetura e especificações", "Modo Especificação"),
    BUILD("Construir", "Edição autônoma e código real", "Modo Construtor")
}

enum class ReasoningSpeed(val label: String, val modelName: String, val badge: String) {
    FAST("⚡ Rápido", "gemini-3.8-flash", "Flash"),
    DEEP_THINKING("🧠 Raciocínio Profundo", "gemini-3.8-flash", "Flash CoT")
}

enum class LlmProvider(val label: String) {
    GEMINI("Google Gemini"),
    OPENROUTER("OpenRouter")
    ;

    companion object {
        const val DEFAULT_OPENROUTER_MODEL = "qwen/qwen3.8-27b:free"

        val openRouterPresets: List<OpenRouterModelPreset> = listOf(
            OpenRouterModelPreset("Qwen 3.8 27B (free)", "qwen/qwen3.8-27b:free", free = true),
            OpenRouterModelPreset("DeepSeek V4.1 Flash", "deepseek/deepseek-v4.1-flash", free = false),
            OpenRouterModelPreset("DeepSeek V4 Flash", "deepseek/deepseek-v4-flash", free = false),
            OpenRouterModelPreset("Claude Sonnet 5.5", "anthropic/claude-sonnet-5.5", free = false),
            OpenRouterModelPreset("GPT Luna", "openai/gpt-luna", free = false),
            OpenRouterModelPreset("GLM 5.3 Flash", "z-ai/glm-5.3-flash", free = false),
            OpenRouterModelPreset("Nemotron 3.5 Lightning (free)", "nvidia/nemotron-3.5-lightning:free", free = true),
            OpenRouterModelPreset("OpenRouter free auto", "openrouter/free", free = true)
        )
    }
}

data class OpenRouterModelPreset(
    val label: String,
    val id: String,
    val free: Boolean
)

enum class ToolType(val displayName: String, val category: String) {
    LIST_FILES("list_files", "Workspace"),
    READ_FILE("read_file", "Workspace"),
    SEARCH_FILES("search_files", "Workspace"),
    EDIT_FILE("edit_file", "Workspace"),
    WRITE_FILE("write_file", "Workspace"),
    DELETE_FILE("delete_file", "Workspace"),
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
