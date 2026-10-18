package com.example.data.engine

/**
 * Deterministic first-pass intent classifier.
 *
 * This is a routing hint, not a security boundary: any future mutating tool must
 * independently check the action policy before executing.
 */
enum class ChatIntent {
    DISCUSSION,
    EXPLANATION,
    PLANNING,
    IMPLEMENTATION_REQUEST,
    AMBIGUOUS
}

object ChatIntentClassifier {
    private val normalizeWhitespace = Regex("\\s+")

    // Explicit preservation language wins over action words elsewhere in the message.
    private val preservePatterns = listOf(
        Regex("\\b(n[aã]o\\s+(?:precisa|precise|quero|queria|deve|deveria|vai|v[aá])\\b[^.!?;]{0,100}\\b(?:mudar|mexer|alterar|editar|modificar|implementar|corrigir|aplicar|executar|rodar|instalar|publicar|arquivos?|c[oó]digo|projeto)\\b)"),
        Regex("\\b(n[aã]o\\s+(?:mude|altere|edite|modifique|implemente|corrija|aplique|fa[cç]a|execute|rode|instale|publique)\\b)"),
        Regex("\\b(?:ainda\\s+n[aã]o|por\\s+enquanto\\s+n[aã]o|sem\\s+(?:alterar|editar|modificar|implementar|corrigir|executar))\\b"),
        Regex("\\b(?:s[oó]\\s+(?:quero|queria)\\s+(?:conversar|discutir|entender|saber|pensar|analisar))\\b"),
        Regex("\\b(?:n[aã]o\\s+[eé]\\s+para\\s+(?:alterar|editar|implementar|corrigir|executar))\\b"),
        Regex("\\b(?:apenas|somente)\\s+(?:analise|analisa|explique|explique-me|me\\s+explique|revise|avalie)\\b[^.!?;]{0,80}\\bsem\\s+(?:alterar|editar|modificar|implementar)\\b"),
        Regex("\\b(?:analise|analisa|revise|avalie|explique)\\b[^.!?;]{0,80}\\bsem\\s+(?:alterar|editar|modificar|implementar|corrigir)\\b")
    )

    private val planningPatterns = listOf(
        Regex("\\b(planeje|planejar|planejamento|proponha|sugira|desenhe|arquitet(e|ar)|monte\\s+(?:um\\s+)?plano|quais\\s+(?:seriam|s[aã]o)\\s+os\\s+passos|liste\\s+as\\s+etapas|defina\\s+uma\\s+estrat[eé]gia)\\b"),
        Regex("\\b(plan|propose|suggest|design|outline|planning|roadmap)\\b")
    )

    private val directActionPatterns = listOf(
        Regex("\\b(implemente|implementar|construa|crie|adicione|remova|exclua|apague|corrija|conserte|refatore|altere|edite|modifique|aplique|execute|rode|instale|publique|envie|fa[cç]a\\s+merge|fa[cç]a\\s+(?:a\\s+)?altera[cç][aã]o|atualize|integre|configure|migre|substitua|repare|melhore|otimize|ajuste|reescreva)\\b"),
        Regex("\\b(implement|create|add|remove|delete|fix|refactor|change|edit|modify|apply|run|install|publish|merge|update|integrate|configure|migrate|replace|repair|improve|optimize)\\b")
    )

    // Polite, explicit requests are actions even when phrased as questions.
    private val explicitActionRequestPatterns = listOf(
        Regex("\\b(?:pode|poderia|consegue|conseguiria)\\s+(?:por\\s+favor\\s+)?(?:me\\s+)?(?:implementar|criar|adicionar|remover|excluir|apagar|corrigir|consertar|refatorar|alterar|editar|modificar|aplicar|executar|rodar|instalar|publicar|integrar|configurar|atualizar|substituir|melhorar|ajustar)\\b"),
        Regex("\\b(?:can|could|would\\s+you)\\s+(?:please\\s+)?(?:implement|create|add|remove|delete|fix|refactor|change|edit|modify|apply|run|install|publish|integrate|configure|update|replace|improve)\\b"),
        Regex("\\b(?:quero|preciso|solicito|gostaria)\\s+que\\s+(?:voc[eê]\\s+)?(?:implemente|crie|adicione|remova|corrija|refatore|altere|edite|modifique|execute|publique|integre|configure|atualize|melhore|ajuste|fa[cç]a)\\b")
    )

    private val explanationPatterns = listOf(
        Regex("\\b(me\\s+)?explique\\b|\\bexplique-me\\b|\\bexplica(?:r)?\\b|\\bcomo\\s+(?:funciona|funcionaria|é\\s+que\\s+funciona|(?:eu\\s+)?(?:implemento|faço|corrijo|configuro|crio|adiciono))\\b|\\bpor\\s+que\\b|\\bo\\s+que\\s+(?:significa|é|s[aã]o)\\b|\\bqual\\s+(?:a\\s+)?diferen[cç]a\\b|\\bhow\\s+does\\b|\\bexplain\\b|\\bwhat\\s+does\\b")
    )

    private val discussionPatterns = listOf(
        Regex("\\b(acha|acham|seria\\s+melhor|o\\s+que\\s+voc[eê]\\s+pensa|vale\\s+a\\s+pena|vamos\\s+discutir|vamos\\s+pensar|e\\s+se|opini[aã]o|concorda|faz\\s+sentido|seria\\s+poss[ií]vel)\\b"),
        Regex("\\b(do\\s+you\\s+think|should\\s+we|is\\s+it\\s+better|is\\s+it\\s+worth|what\\s+if|discuss|your\\s+opinion)\\b")
    )

    fun hasExplicitNoChange(message: String): Boolean {
        val text = message.lowercase().replace(normalizeWhitespace, " ").trim()
        return preservePatterns.any { it.containsMatchIn(text) }
    }

    fun classify(message: String): ChatIntent {
        val text = message.lowercase().replace(normalizeWhitespace, " ").trim()
        if (text.isBlank()) return ChatIntent.AMBIGUOUS

        if (preservePatterns.any { it.containsMatchIn(text) }) return ChatIntent.DISCUSSION

        // Questions about whether an action is advisable are not authorization to perform it.
        val advisoryQuestionPatterns = listOf(
            Regex("\\b(?:voc[eê]\\s+)?acha(?:m)?\\b"),
            Regex("\\bcomo\\s+pod[eé]r[ií]amos\\b"),
            Regex("\\bshould\\s+we\\b"),
            Regex("\\bdo\\s+you\\s+think\\b"),
            Regex("\\bis\\s+it\\s+(?:better|worth)\\b")
        )
        if (text.endsWith("?") && advisoryQuestionPatterns.any { it.containsMatchIn(text) }) {
            return ChatIntent.DISCUSSION
        }
        if (planningPatterns.any { it.containsMatchIn(text) }) return ChatIntent.PLANNING
        if (explicitActionRequestPatterns.any { it.containsMatchIn(text) }) {
            return ChatIntent.IMPLEMENTATION_REQUEST
        }
        if (explanationPatterns.any { it.containsMatchIn(text) }) return ChatIntent.EXPLANATION
        if (discussionPatterns.any { it.containsMatchIn(text) }) return ChatIntent.DISCUSSION
        if (directActionPatterns.any { it.containsMatchIn(text) }) {
            return ChatIntent.IMPLEMENTATION_REQUEST
        }
        if (text.contains("?")) return ChatIntent.DISCUSSION

        if (Regex("^(oi|ol[aá]|bom dia|boa tarde|boa noite|obrigad[oa]|valeu|hello|hi)\\b").containsMatchIn(text)) {
            return ChatIntent.DISCUSSION
        }
        return ChatIntent.AMBIGUOUS
    }

    /**
     * A conservative policy for future tools. Prompt instructions alone are not
     * sufficient authorization to run a mutating tool.
     */
    fun allowsMutation(intent: ChatIntent): Boolean =
        intent == ChatIntent.IMPLEMENTATION_REQUEST

    fun instructionFor(intent: ChatIntent): String = when (intent) {
        ChatIntent.DISCUSSION ->
            "INTENÇÃO: CONVERSA/DISCUSSÃO. Você pode inspecionar e ler arquivos se necessário para responder com precisão, mas não altere arquivos."
        ChatIntent.EXPLANATION ->
            "INTENÇÃO: EXPLICAÇÃO. Inspecione os arquivos necessários e explique com clareza; não altere arquivos."
        ChatIntent.PLANNING ->
            "INTENÇÃO: PLANEJAMENTO. Inspecione o projeto e entregue etapas claras, arquivos afetados e critérios de validação."
        ChatIntent.IMPLEMENTATION_REQUEST ->
            "INTENÇÃO: IMPLEMENTAÇÃO AUTÔNOMA (ESTILO CLAUDE CODE). Pesquise, leia os arquivos relevantes, aplique as edições cirúrgicas necessárias (edit_file ou write_file) e verifique o resultado antes de dar a resposta final."
        ChatIntent.AMBIGUOUS ->
            "INTENÇÃO: EXPLORAÇÃO. Use as ferramentas de leitura se precisar de contexto ou responda de forma direta."
    }
}
