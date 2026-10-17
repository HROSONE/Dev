package com.example.data.engine

import com.example.data.model.AgentMode

/**
 * Result of asking the policy gate to dispatch a tool action.
 * This layer deliberately does not implement GitHub, filesystem, or shell tools.
 */
sealed class ToolDispatchResult<out T> {
    data class Executed<T>(val value: T) : ToolDispatchResult<T>()
    object ConfirmationRequired : ToolDispatchResult<Nothing>()
    object Denied : ToolDispatchResult<Nothing>()
}

/**
 * Single guarded entry point for future tool adapters.
 *
 * Tool implementations should be called through this dispatcher rather than
 * invoking an adapter directly. The adapter itself must still validate its inputs
 * and enforce its own sandbox/permissions.
 */
class PolicyGatedToolDispatcher {

    fun <T> dispatch(
        mode: AgentMode,
        intent: ChatIntent,
        risk: ToolRisk,
        userConfirmedMutation: Boolean = false,
        action: () -> T
    ): ToolDispatchResult<T> {
        return when (
            ToolExecutionPolicy.evaluate(
                mode = mode,
                intent = intent,
                risk = risk,
                userConfirmedMutation = userConfirmedMutation
            )
        ) {
            ToolPolicyDecision.ALLOW -> ToolDispatchResult.Executed(action())
            ToolPolicyDecision.REQUIRE_CONFIRMATION -> ToolDispatchResult.ConfirmationRequired
            ToolPolicyDecision.DENY -> ToolDispatchResult.Denied
        }
    }
}
