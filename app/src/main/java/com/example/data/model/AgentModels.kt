package com.example.data.model

enum class AgentMode(val label: String, val subtitle: String, val iconDescription: String) {
    ASK("Pergunte", "Consultas rápidas e explicações", "Modo Pergunta"),
    IMAGINE("Imagine", "Arquitetura e especificações", "Modo Especificação"),
    BUILD("Construir", "Agente autônomo com execução", "Modo Construtor")
}

enum class ReasoningSpeed(val label: String, val modelName: String, val badge: String) {
    FAST("⚡ Rápido", "gemini-3.5-flash", "Flash Engine"),
    DEEP_THINKING("🧠 Raciocínio Profundo", "gemini-3.1-pro-preview", "CoT Thinking")
}

enum class ToolType(val displayName: String, val category: String) {
    READ_GITHUB_REPO("read_github_repo", "GitHub API"),
    RUN_TERMINAL_COMMAND("run_terminal_command", "Docker Sandbox"),
    APPLY_DIFF_PATCH("apply_diff_patch", "Git Engine"),
    RUN_TESTS("run_test_suite", "Test Runner")
}

enum class ExecutionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
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
