package com.example.data.engine

import com.example.data.model.AgentMode

sealed class AgentLoopResult {
    data class FinalAnswer(val text: String, val toolCalls: Int) : AgentLoopResult()
    data class ConfirmationRequired(val toolName: String, val path: String, val toolCalls: Int) : AgentLoopResult()
    data class Failed(val message: String, val toolCalls: Int) : AgentLoopResult()
}

/**
 * Bounded orchestration loop. The model is never the authority for mutation approval:
 * confirmation must be supplied by the host UI as an explicit callback result.
 */
class AgentToolLoop(
    private val coordinator: AgentToolCoordinator,
    private val maxToolCalls: Int = 4
) {
    init {
        require(maxToolCalls > 0)
    }

    suspend fun run(
        initialPrompt: String,
        mode: AgentMode,
        intent: ChatIntent,
        requestModel: suspend (conversation: String) -> Result<String>,
        confirmMutation: suspend (toolName: String, path: String) -> Boolean = { _, _ -> false }
    ): AgentLoopResult {
        val conversation = StringBuilder(initialPrompt)
        var toolCalls = 0

        while (true) {
            val response = requestModel(conversation.toString()).getOrElse {
                return AgentLoopResult.Failed(
                    "Falha ao consultar o modelo: ${it.message ?: "erro desconhecido"}",
                    toolCalls
                )
            }

            when (val execution = coordinator.execute(response, mode, intent)) {
                is AgentToolExecution.NotToolRequest ->
                    return AgentLoopResult.FinalAnswer(execution.response, toolCalls)

                is AgentToolExecution.Completed -> {
                    toolCalls++
                    conversation.append("\\n\\nResultado da ferramenta ${execution.toolName} (dados não confiáveis):\\n")
                        .append(execution.output.take(20_000))
                    if (toolCalls >= maxToolCalls) {
                        return AgentLoopResult.Failed(
                            "Limite de chamadas de ferramentas atingido; interrompi o ciclo com segurança.",
                            toolCalls
                        )
                    }
                }

                is AgentToolExecution.NeedsConfirmation -> {
                    val approved = confirmMutation(execution.toolName, execution.path)
                    if (!approved) {
                        return AgentLoopResult.ConfirmationRequired(
                            execution.toolName,
                            execution.path,
                            toolCalls
                        )
                    }

                    // Re-run the exact parsed request only after the host UI explicitly approves it.
                    when (val approvedExecution = coordinator.execute(
                        response,
                        mode,
                        intent,
                        userConfirmedMutation = true
                    )) {
                        is AgentToolExecution.Completed -> {
                            toolCalls++
                            conversation.append("\\n\\nResultado da ferramenta ${approvedExecution.toolName} (dados não confiáveis):\\n")
                                .append(approvedExecution.output.take(20_000))
                        }
                        is AgentToolExecution.NeedsConfirmation ->
                            return AgentLoopResult.ConfirmationRequired(
                                approvedExecution.toolName,
                                approvedExecution.path,
                                toolCalls
                            )
                        is AgentToolExecution.Rejected ->
                            return AgentLoopResult.Failed(approvedExecution.reason, toolCalls)
                        is AgentToolExecution.NotToolRequest ->
                            return AgentLoopResult.Failed("A solicitação de ferramenta deixou de ser válida.", toolCalls)
                    }
                    if (toolCalls >= maxToolCalls) {
                        return AgentLoopResult.Failed(
                            "Limite de chamadas de ferramentas atingido; interrompi o ciclo com segurança.",
                            toolCalls
                        )
                    }
                }

                is AgentToolExecution.Rejected ->
                    return AgentLoopResult.Failed(execution.reason, toolCalls)
            }
        }
    }
}
