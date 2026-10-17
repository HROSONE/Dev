package com.example.data.engine

import com.example.data.api.GeminiClient
import com.example.data.model.AgentMetrics
import com.example.data.model.AgentMode
import com.example.data.model.ReasoningSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class AgentExecutionEvent {
    data class ThinkingTick(val seconds: Double) : AgentExecutionEvent()
    data class ThoughtStepAdded(val step: String) : AgentExecutionEvent()
    data class ToolCallStarted(val toolCall: com.example.data.model.ToolCallInfo) : AgentExecutionEvent()
    data class ToolCallUpdated(val toolCall: com.example.data.model.ToolCallInfo) : AgentExecutionEvent()
    data class FinalResponseChunk(val text: String, val metrics: AgentMetrics) : AgentExecutionEvent()
    data class Error(val message: String) : AgentExecutionEvent()
}

/**
 * Honest first-stage engine: sends the prompt to Gemini, but does not pretend that
 * repository edits, shell commands, Docker or tests were executed. Real tools will be
 * added as separate implementations and connected through an agent loop.
 */
class DevAgentEngine {

    fun executeTask(
        userPrompt: String,
        mode: AgentMode,
        speed: ReasoningSpeed,
        repositoryName: String = ""
    ): Flow<AgentExecutionEvent> = flow {
        val startedAt = System.currentTimeMillis()
        emit(AgentExecutionEvent.ThinkingTick(0.0))

        if (!GeminiClient.isApiKeyConfigured()) {
            emit(AgentExecutionEvent.Error(
                "A chave do Gemini não está configurada. Nenhuma ação foi executada. " +
                    "Configure o acesso ao modelo antes de enviar uma solicitação."
            ))
            return@flow
        }

        emit(AgentExecutionEvent.ThoughtStepAdded("Enviando solicitação ao modelo configurado"))
        val systemPrompt = buildString {
            append(
                "Você é o DevAgent, um assistente de engenharia de software. " +
                    "Responda em português, com clareza, indique incertezas e nunca invente resultados de ferramentas. "
            )
            when (mode) {
                AgentMode.ASK -> append("Neste modo, explique e responda perguntas técnicas.")
                AgentMode.IMAGINE -> append("Neste modo, proponha arquitetura, etapas e critérios de validação.")
                AgentMode.BUILD -> append(
                    "Neste estágio do aplicativo, você NÃO tem ferramentas de escrita de arquivos, terminal, " +
                        "Docker ou execução de testes conectadas. Pode analisar o pedido e propor um patch em texto, " +
                        "mas não diga que alterou um repositório, executou comandos ou passou testes. "
                )
            }
            if (repositoryName.isNotBlank()) {
                append(" O repositório selecionado pelo usuário é $repositoryName, mas isso não implica que seu conteúdo foi enviado ao modelo.")
            }
        }

        val result = GeminiClient.queryGemini(
            prompt = userPrompt,
            modelName = speed.modelName,
            systemInstruction = systemPrompt
        )
        val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
        emit(AgentExecutionEvent.ThinkingTick(elapsedSeconds))

        result.fold(
            onSuccess = { response ->
                emit(
                    AgentExecutionEvent.FinalResponseChunk(
                        text = response,
                        metrics = AgentMetrics(
                            thinkingSeconds = elapsedSeconds,
                            commandsExecuted = 0,
                            filesInspected = 0,
                            linesChanged = 0,
                            sandboxLatencyMs = 0
                        )
                    )
                )
            },
            onFailure = { error ->
                emit(
                    AgentExecutionEvent.Error(
                        "A solicitação ao Gemini falhou: ${error.message ?: "erro desconhecido"}. " +
                            "Nenhuma alteração de código foi aplicada."
                    )
                )
            }
        )
    }
}
