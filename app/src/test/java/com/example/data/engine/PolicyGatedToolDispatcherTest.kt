package com.example.data.engine

import com.example.data.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyGatedToolDispatcherTest {
    private val dispatcher = PolicyGatedToolDispatcher()

    @Test
    fun allowedReadOnlyActionExecutesAndReturnsValue() {
        var executed = false
        val result = dispatcher.dispatch(
            mode = AgentMode.ASK,
            intent = ChatIntent.EXPLANATION,
            risk = ToolRisk.READ_ONLY
        ) {
            executed = true
            "repository summary"
        }

        assertTrue(executed)
        assertEquals(ToolDispatchResult.Executed("repository summary"), result)
    }

    @Test
    fun mutatingActionIsNotExecutedBeforeConfirmation() {
        var executed = false
        val result = dispatcher.dispatch(
            mode = AgentMode.BUILD,
            intent = ChatIntent.IMPLEMENTATION_REQUEST,
            risk = ToolRisk.MUTATING
        ) {
            executed = true
            "changed"
        }

        assertFalse(executed)
        assertEquals(ToolDispatchResult.ConfirmationRequired, result)
    }

    @Test
    fun deniedActionNeverRuns() {
        var executed = false
        val result = dispatcher.dispatch(
            mode = AgentMode.BUILD,
            intent = ChatIntent.AMBIGUOUS,
            risk = ToolRisk.MUTATING,
            userConfirmedMutation = true
        ) {
            executed = true
            "should not happen"
        }

        assertFalse(executed)
        assertEquals(ToolDispatchResult.Denied, result)
    }

    @Test
    fun confirmedMutationExecutesOnlyWhenPolicyAllowsIt() {
        var executed = false
        val result = dispatcher.dispatch(
            mode = AgentMode.BUILD,
            intent = ChatIntent.IMPLEMENTATION_REQUEST,
            risk = ToolRisk.MUTATING,
            userConfirmedMutation = true
        ) {
            executed = true
            42
        }

        assertTrue(executed)
        assertEquals(ToolDispatchResult.Executed(42), result)
    }

    @Test
    fun unknownRiskNeverRunsEvenWhenConfirmed() {
        var executed = false
        val result = dispatcher.dispatch(
            mode = AgentMode.BUILD,
            intent = ChatIntent.IMPLEMENTATION_REQUEST,
            risk = ToolRisk.UNKNOWN,
            userConfirmedMutation = true
        ) {
            executed = true
            "unsafe"
        }

        assertFalse(executed)
        assertEquals(ToolDispatchResult.Denied, result)
    }
}
