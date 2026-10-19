package com.example.data.engine

import com.example.data.model.AgentMode

/**
 * Local policy gate for future tool execution.
 *
 * Chat intent is only a routing signal; it is not, by itself, authorization.
 * Every tool adapter must call this policy immediately before executing an action.
 */
enum class ToolRisk {
    READ_ONLY,
    MUTATING,
    UNKNOWN
}

enum class ToolPolicyDecision {
    ALLOW,
    REQUIRE_CONFIRMATION,
    DENY
}

object ToolExecutionPolicy {

    fun evaluate(
        mode: AgentMode,
        intent: ChatIntent,
        risk: ToolRisk,
        userConfirmedMutation: Boolean = false
    ): ToolPolicyDecision {
        if (risk == ToolRisk.UNKNOWN) return ToolPolicyDecision.DENY

        // Leitura dentro do workspace isolado é sempre segura: o agente precisa de contexto
        // real (como o Claude Code) mesmo em perguntas ou mensagens ambíguas.
        if (risk == ToolRisk.READ_ONLY) return ToolPolicyDecision.ALLOW

        // Mutations need all three: BUILD mode, an explicit implementation request,
        // and a separate confirmation. Classifier output alone must never authorize it.
        if (mode != AgentMode.BUILD || intent != ChatIntent.IMPLEMENTATION_REQUEST) {
            return ToolPolicyDecision.DENY
        }

        return if (userConfirmedMutation) {
            ToolPolicyDecision.ALLOW
        } else {
            ToolPolicyDecision.REQUIRE_CONFIRMATION
        }
    }
}
