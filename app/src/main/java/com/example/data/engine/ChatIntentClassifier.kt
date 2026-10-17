package com.example.data.engine

/**
 * Conservative intent gate for the chat. It is deliberately deterministic and
 * side-effect free: only clear implementation verbs count as an action request.
 * Questions, brainstorming, and explicit "don't change" language take precedence.
 */
enum class ChatIntent {
    DISCUSSION,
    EXPLANATION,
    PLANNING,
    IMPLEMENTATION_REQUEST,
    AMBIGUOUS
}

object ChatIntentClassifier {
    private val preservePatterns = listOf(
        Regex("\\b(n[aã]o\\s+(mude|altere|edite|modifique|implemente|corrija|aplique|fa[cç]a))\\b"),
        Regex("\\b(ainda\\s+n[aã]o|por\\s+enquanto\\s+n[aã]o|sem\\s+(alterar|editar|modificar|implementar))\\b"),
        Regex("\\b(s[oó]\\s+(quero|queria)\\s+(conversar|discutir|entender|saber|pensar))\\b"),
        Regex("\\b(n[aã]o\\s+[eé]\\s+para\\s+(alterar|editar|implementar|corrigir))\\b")
    )

    private val actionPatterns = listOf(
        Regex("\\b(implemente|implementar|implemente-o|implemente isso|implemente essa|construa|crie|adicione|remova|exclua|apague|corrija|conserte|refatore|altere|edite|modifique|aplique|fa[cç]a|execute|rode|instale|publique|envie|fa[cç]a merge)\\b"),
        Regex("\\b(implement|create|add|remove|delete|fix|refactor|change|edit|modify|apply|run|install|publish|merge)\\b")
    )

    private val planningPatterns = listOf(
        Regex("\\b(planeje|planejar|proponha|sugira|desenhe|arquitet(e|ar)|monte um plano|quais seriam os passos)\\b"),
        Regex("\\b(plan|propose|suggest|design|outline)\\b")
    )

    fun classify(message: String): ChatIntent {
        val text = message.lowercase().replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return ChatIntent.AMBIGUOUS
        if (preservePatterns.any { it.containsMatchIn(text) }) return ChatIntent.DISCUSSION

        val asksOrDiscusses = text.contains("?") ||
            Regex("\\b(acha|ach(a|as)|seria melhor|o que você pensa|o que voce pensa|por que|porque|como funciona|vale a pena|o que significa|me explica|explique|qual a diferença|vamos discutir|vamos pensar|e se)\\b").containsMatchIn(text)

        // A question about a possible change is still a discussion, not permission to edit.
        if (asksOrDiscusses) return ChatIntent.DISCUSSION
        if (actionPatterns.any { it.containsMatchIn(text) }) return ChatIntent.IMPLEMENTATION_REQUEST
        if (planningPatterns.any { it.containsMatchIn(text) }) return ChatIntent.PLANNING
        if (Regex("^(oi|ol[aá]|bom dia|boa tarde|boa noite|obrigad[oa]|valeu)\\b").containsMatchIn(text)) {
            return ChatIntent.DISCUSSION
        }
        return ChatIntent.AMBIGUOUS
    }

    fun instructionFor(intent: ChatIntent): String = when (intent) {
        ChatIntent.DISCUSSION ->
            "INTENÇÃO: CONVERSA/DISCUSSÃO. Responda à pergunta, debata ideias e explique alternativas. " +
                "Não proponha que uma edição já foi autorizada; não gere uma alteração como se tivesse sido pedida. Preserve arquivos e estado do projeto."
        ChatIntent.EXPLANATION ->
            "INTENÇÃO: EXPLICAÇÃO. Explique sem alterar arquivos."
        ChatIntent.PLANNING ->
            "INTENÇÃO: PLANEJAMENTO. Apresente plano e trade-offs; não implemente sem pedido explícito."
        ChatIntent.IMPLEMENTATION_REQUEST ->
            "INTENÇÃO: PEDIDO DE IMPLEMENTAÇÃO. Responda ao pedido de ação, mas respeite capacidades reais e confirmações necessárias."
        ChatIntent.AMBIGUOUS ->
            "INTENÇÃO: AMBÍGUA. Responda de forma informativa ou faça uma pergunta curta para esclarecer. Não assuma autorização para editar."
    }
}
