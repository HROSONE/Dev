package com.example.data.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatIntentClassifierTest {
    @Test
    fun questionsAboutCodeStayDiscussionEvenInBuildMode() {
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Você acha que seria melhor mudar essa arquitetura?"))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Como poderíamos melhorar esse código?"))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Por que o projeto usa essa abordagem?"))
    }

    @Test
    fun explicitNoChangeInstructionWinsOverActionWords() {
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Não altere nada ainda; quero discutir a solução."))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Não implemente isso por enquanto."))
    }

    @Test
    fun directImplementationCommandsAreActions() {
        assertEquals(ChatIntent.IMPLEMENTATION_REQUEST, ChatIntentClassifier.classify("Implemente essa solução nos arquivos."))
        assertEquals(ChatIntent.IMPLEMENTATION_REQUEST, ChatIntentClassifier.classify("Corrija esse erro no projeto."))
    }

    @Test
    fun planningIsNotImplementationPermission() {
        assertEquals(ChatIntent.PLANNING, ChatIntentClassifier.classify("Planeje uma arquitetura melhor para o projeto."))
        assertEquals(ChatIntent.AMBIGUOUS, ChatIntentClassifier.classify("Essa ideia seria interessante para o app."))
    }
}
