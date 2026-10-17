package com.example.data.engine

import com.example.data.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionPolicyTest {

    @Test
    fun readOnlyActionIsAllowedForClearNonAmbiguousIntent() {
        assertEquals(
            ToolPolicyDecision.ALLOW,
            ToolExecutionPolicy.evaluate(
                mode = AgentMode.ASK,
                intent = ChatIntent.EXPLANATION,
                risk = ToolRisk.READ_ONLY
            )
        )
    }

    @Test
    fun ambiguousIntentCannotTriggerTools() {
        assertEquals(
            ToolPolicyDecision.DENY,
            ToolExecutionPolicy.evaluate(
                mode = AgentMode.BUILD,
                intent = ChatIntent.AMBIGUOUS,
                risk = ToolRisk.READ_ONLY
            )
        )
    }

    @Test
    fun mutationRequiresSeparateConfirmation() {
        assertEquals(
            ToolPolicyDecision.REQUIRE_CONFIRMATION,
            ToolExecutionPolicy.evaluate(
                mode = AgentMode.BUILD,
                intent = ChatIntent.IMPLEMENTATION_REQUEST,
                risk = ToolRisk.MUTATING
            )
        )
    }

    @Test
    fun confirmedMutationIsAllowedOnlyInBuildMode() {
        assertEquals(
            ToolPolicyDecision.ALLOW,
            ToolExecutionPolicy.evaluate(
                mode = AgentMode.BUILD,
                intent = ChatIntent.IMPLEMENTATION_REQUEST,
                risk = ToolRisk.MUTATING,
                userConfirmedMutation = true
            )
        )
        assertEquals(
            ToolPolicyDecision.DENY,
            ToolExecutionPolicy.evaluate(
                mode = AgentMode.ASK,
                intent = ChatIntent.IMPLEMENTATION_REQUEST,
                risk = ToolRisk.MUTATING,
                userConfirmedMutation = true
            )
        )
    }

    @Test
    fun classifierIntentAloneDoesNotAuthorizeMutation() {
        assertEquals(
            ToolPolicyDecision.REQUIRE_CONFIRMATION,
            ToolExecutionPolicy.evaluate(
                mode = AgentMode.BUILD,
                intent = ChatIntent.IMPLEMENTATION_REQUEST,
                risk = ToolRisk.MUTATING,
                userConfirmedMutation = false
            )
        )
    }

    @Test
    fun unknownRiskIsAlwaysDenied() {
        assertEquals(
            ToolPolicyDecision.DENY,
            ToolExecutionPolicy.evaluate(
                mode = AgentMode.BUILD,
                intent = ChatIntent.IMPLEMENTATION_REQUEST,
                risk = ToolRisk.UNKNOWN,
                userConfirmedMutation = true
            )
        )
    }
}
