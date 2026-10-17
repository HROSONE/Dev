package com.example.data.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatIntentClassifierTest {
    @Test
    fun discussionQuestionsDoNotAuthorizeChanges() {
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Você acha que seria melhor mudar essa arquitetura?"))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Como poderíamos melhorar esse código?"))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Por que o projeto usa essa abordagem?"))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Você acha que devemos implementar isso?"))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Should we change this design?"))
    }

    @Test
    fun explicitNoChangeInstructionWinsOverActionWords() {
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Não altere nada ainda; quero discutir a solução."))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Não implemente isso por enquanto."))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Não precisa mexer nos arquivos, só me explique."))
        assertEquals(ChatIntent.DISCUSSION, ChatIntentClassifier.classify("Analise o código sem alterar arquivos."))
    }

    @Test
    fun directImplementationCommandsAreActions() {
        assertEquals(ChatIntent.IMPLEMENTATION_REQUEST, ChatIntentClassifier.classify("Implemente essa solução nos arquivos."))
        assertEquals(ChatIntent.IMPLEMENTATION_REQUEST, ChatIntentClassifier.classify("Corrija esse erro no projeto."))
        assertEquals(ChatIntent.IMPLEMENTATION_REQUEST, ChatIntentClassifier.classify("Pode implementar isso?"))
        assertEquals(ChatIntent.IMPLEMENTATION_REQUEST, ChatIntentClassifier.classify("Você poderia corrigir esse erro?"))
        assertEquals(ChatIntent.IMPLEMENTATION_REQUEST, ChatIntentClassifier.classify("Please fix this bug."))
    }

    @Test
    fun explanationsDoNotBecomeImplementationRequests() {
        assertEquals(ChatIntent.EXPLANATION, ChatIntentClassifier.classify("Como eu implemento uma busca nesse app?"))
        assertEquals(ChatIntent.EXPLANATION, ChatIntentClassifier.classify("Explique como corrigir esse erro."))
    }

    @Test
    fun planningIsNotImplementationPermission() {
        assertEquals(ChatIntent.PLANNING, ChatIntentClassifier.classify("Planeje uma arquitetura melhor para o projeto."))
        assertEquals(ChatIntent.PLANNING, ChatIntentClassifier.classify("Quais seriam os passos para corrigir isso?"))
        assertEquals(ChatIntent.PLANNING, ChatIntentClassifier.classify("Propose a roadmap for this feature."))
        assertEquals(ChatIntent.AMBIGUOUS, ChatIntentClassifier.classify("Essa ideia seria interessante para o app."))
        assertEquals(ChatIntent.AMBIGUOUS, ChatIntentClassifier.classify(""))
    }
}
