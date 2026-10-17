package com.example.data.engine

import com.example.data.model.AgentMode

/**
 * Bridges untrusted model output to the allowlisted workspace tools.
 *
 * This coordinator is deliberately side-effect-free unless a request parses and the
 * workspace adapter's policy gate allows it. The caller must obtain user confirmation
 * outside the model response and pass it explicitly for mutations.
 */
sealed class AgentToolExecution {
    data class NotToolRequest(val response: String) : AgentToolExecution()
    data class Completed(val toolName: String, val output: String) : AgentToolExecution()
    data class NeedsConfirmation(val toolName: String, val path: String) : AgentToolExecution()
    data class Rejected(val reason: String) : AgentToolExecution()
}

class AgentToolCoordinator(
    private val workspaceTools: WorkspaceFileTools,
    private val maxListedFiles: Int = 200,
    private val maxOutputChars: Int = 20_000
) {
    init {
        require(maxListedFiles > 0)
        require(maxOutputChars > 0)
    }

    fun execute(
        modelResponse: String,
        mode: AgentMode,
        intent: ChatIntent,
        userConfirmedMutation: Boolean = false
    ): AgentToolExecution {
        val request = AgentToolRequestParser.parse(modelResponse)
            ?: return AgentToolExecution.NotToolRequest(modelResponse)

        return when (request) {
            is AgentToolRequest.ListFiles -> {
                when (val result = workspaceTools.listFiles(request.path, mode, intent)) {
                    is ToolDispatchResult.Executed -> {
                        val files = result.value
                        val shown = files.take(maxListedFiles)
                        val suffix = if (files.size > shown.size) {
                            "\n… e mais ${files.size - shown.size} arquivos."
                        } else ""
                        AgentToolExecution.Completed(
                            toolName = "list_files",
                            output = (shown.joinToString("\n") + suffix).take(maxOutputChars)
                        )
                    }
                    ToolDispatchResult.ConfirmationRequired ->
                        AgentToolExecution.Rejected("A política exige confirmação para esta operação.")
                    ToolDispatchResult.Denied ->
                        AgentToolExecution.Rejected("A política de segurança negou a leitura solicitada.")
                }
            }
            is AgentToolRequest.ReadFile -> {
                when (val result = workspaceTools.readFile(request.path, mode, intent)) {
                    is ToolDispatchResult.Executed -> AgentToolExecution.Completed(
                        toolName = "read_file",
                        output = result.value.take(maxOutputChars) +
                            if (result.value.length > maxOutputChars) "\n[Conteúdo truncado pelo limite de saída.]" else ""
                    )
                    ToolDispatchResult.ConfirmationRequired ->
                        AgentToolExecution.Rejected("A política exige confirmação para esta operação.")
                    ToolDispatchResult.Denied ->
                        AgentToolExecution.Rejected("A política de segurança negou a leitura solicitada.")
                }
            }
            is AgentToolRequest.WriteFile -> {
                when (val result = workspaceTools.writeFile(
                    relativePath = request.path,
                    content = request.content,
                    mode = mode,
                    intent = intent,
                    userConfirmedMutation = userConfirmedMutation
                )) {
                    is ToolDispatchResult.Executed ->
                        AgentToolExecution.Completed("write_file", result.value)
                    ToolDispatchResult.ConfirmationRequired ->
                        AgentToolExecution.NeedsConfirmation("write_file", request.path)
                    ToolDispatchResult.Denied ->
                        AgentToolExecution.Rejected("A política de segurança negou a alteração solicitada.")
                }
            }
        }
    }
}
