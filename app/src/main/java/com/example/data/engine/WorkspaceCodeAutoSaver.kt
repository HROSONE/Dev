package com.example.data.engine

import java.io.File

data class ExtractedCodeBlock(
    val language: String,
    val suggestedPath: String,
    val code: String
)

sealed interface RichMessageSegment {
    data class Prose(val text: String) : RichMessageSegment
    data class CodeCard(
        val language: String,
        val filePath: String,
        val code: String
    ) : RichMessageSegment
}

/**
 * 1) Separa rigorosamente texto explicativo (Prose) e código (CodeCard) mesmo quando o LLM
 *    mistura HTML/código solto ou JSON de ferramenta na resposta.
 * 2) Consolida `index.html` para que seja sempre um arquivo inteiro, único e autocontido.
 * 3) Materializa automaticamente no workspace todos os arquivos gerados na resposta do modelo.
 */
object WorkspaceCodeAutoSaver {

    private val closedFenceRegex = Regex(
        """```([a-zA-Z0-9_+.\-/:\s]*)\n([\s\S]*?)```"""
    )

    private val rawHtmlDocRegex = Regex(
        """(<!DOCTYPE\s+html[\s\S]*?</html>|<html[\s\S]*?</html>)""",
        RegexOption.IGNORE_CASE
    )

    private val fileNameHintRegex = Regex(
        """(?:^|[\s`"'(*:/\\])([a-zA-Z0-9_\-./]+\.(?:html?|css|js|jsx|ts|tsx|kt|kts|py|java|json|xml|sql|sh|md|yaml|yml))(?:$|[\s`"')*:])""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Divide qualquer texto do modelo em segmentos visuais estritamente separados:
     * - Prose (somente texto limpo, sem blocos de código misturados)
     * - CodeCard (bloco de código isolado com linguagem, nome do arquivo, botão Copiar e botão Play)
     */
    fun parseRichSegments(rawText: String): List<RichMessageSegment> {
        if (rawText.isBlank()) return emptyList()

        // Se o modelo vazou um JSON {"tool":"write_file",...} na resposta final, converte em CodeCard
        val writeToolRequests = AgentToolRequestParser.parseAll(rawText)
            .filterIsInstance<AgentToolRequest.WriteFile>()
        val cleanedFromToolJson = if (writeToolRequests.isNotEmpty()) {
            removeRawToolJsonObjects(rawText)
        } else {
            rawText
        }

        val normalizedText = closeUnclosedCodeFenceIfNeeded(cleanedFromToolJson)
        val segments = mutableListOf<RichMessageSegment>()
        val usedNames = mutableSetOf<String>()
        var lastIndex = 0

        for (match in closedFenceRegex.findAll(normalizedText)) {
            val preceding = normalizedText.substring(lastIndex, match.range.first)
            appendProseOrRawHtml(preceding, segments, usedNames)

            val headerInfo = match.groupValues[1].trim()
            val body = match.groupValues[2].trimEnd()
            if (body.isNotBlank()) {
                val lang = extractLanguage(headerInfo, body)
                val path = inferFilePath(
                    headerInfo = headerInfo,
                    code = body,
                    language = lang,
                    precedingText = preceding.takeLast(260),
                    usedPaths = usedNames
                )
                usedNames += path
                segments += RichMessageSegment.CodeCard(
                    language = lang,
                    filePath = path,
                    code = body
                )
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < normalizedText.length) {
            val tail = normalizedText.substring(lastIndex)
            appendProseOrRawHtml(tail, segments, usedNames)
        }

        // Adiciona quaisquer arquivos vindos de write_file JSON que não estavam em cercas ```
        for (req in writeToolRequests) {
            val normPath = normalizeHtmlTypo(req.path)
            if (segments.none { it is RichMessageSegment.CodeCard && it.filePath.equals(normPath, ignoreCase = true) }) {
                val lang = languageFromPath(normPath, req.content)
                segments += RichMessageSegment.CodeCard(
                    language = lang,
                    filePath = normPath,
                    code = req.content.trimEnd()
                )
            }
        }

        return consolidateHtmlSegmentsIfNeeded(segments)
    }

    /**
     * Se o modelo gerou `index.html` e separou `<style>` (CSS) ou `<script>` (JS) em blocos diferentes,
     * garante que o código de `index.html` também tenha o CSS e JS integrados para que o arquivo
     * seja 100% inteiro, completo e funcione imediatamente no Preview (botão Play).
     */
    private fun consolidateHtmlSegmentsIfNeeded(
        segments: List<RichMessageSegment>
    ): List<RichMessageSegment> {
        val codeCards = segments.filterIsInstance<RichMessageSegment.CodeCard>()
        val htmlCard = codeCards.firstOrNull {
            it.language == "html" || it.filePath.endsWith(".html", ignoreCase = true)
        } ?: return segments

        val cssBlocks = codeCards.filter { it.language == "css" }.map { it.code.trim() }.filter { it.isNotEmpty() }
        val jsBlocks = codeCards.filter { it.language == "javascript" || it.language == "js" }
            .map { it.code.trim() }
            .filter { it.isNotEmpty() }

        val completeHtml = ensureCompleteHtmlDocument(
            rawHtml = htmlCard.code,
            extraCss = cssBlocks,
            extraJs = jsBlocks
        )

        return segments.map { seg ->
            if (seg === htmlCard) {
                htmlCard.copy(
                    filePath = if (htmlCard.filePath.isBlank()) "index.html" else normalizeHtmlTypo(htmlCard.filePath),
                    code = completeHtml
                )
            } else {
                seg
            }
        }
    }

    /**
     * Garante que um código HTML seja um documento completo (`<!DOCTYPE html>...</html>`)
     * e injeta CSS/JS auxiliares caso o modelo tenha quebrado em blocos separados.
     */
    fun ensureCompleteHtmlDocument(
        rawHtml: String,
        extraCss: List<String> = emptyList(),
        extraJs: List<String> = emptyList()
    ): String {
        var html = rawHtml.trim()
        val lower = html.lowercase()

        // Se o modelo mandou apenas um fragmento HTML sem <html>, envelopa num documento HTML5 completo
        if (!lower.contains("<html")) {
            val styleTag = if (extraCss.isNotEmpty()) {
                "\n  <style>\n${extraCss.joinToString("\n\n")}\n  </style>"
            } else ""
            val scriptTag = if (extraJs.isNotEmpty()) {
                "\n  <script>\n${extraJs.joinToString("\n\n")}\n  </script>"
            } else ""
            return """
                <!DOCTYPE html>
                <html lang="pt-BR">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>Preview DevAgent</title>$styleTag
                </head>
                <body>
                $html$scriptTag
                </body>
                </html>
            """.trimIndent()
        }

        // Se já tem <html>, injeta extraCss antes de </head> (se ainda não estiver inline)
        if (extraCss.isNotEmpty()) {
            val combinedCss = extraCss.filter { snippet ->
                val sample = snippet.lines().firstOrNull { it.trim().length > 5 }?.trim() ?: snippet.take(30)
                sample.isNotEmpty() && !html.contains(sample)
            }.joinToString("\n\n")

            if (combinedCss.isNotBlank()) {
                val styleBlock = "<style>\n$combinedCss\n</style>"
                html = if (html.contains("</head>", ignoreCase = true)) {
                    html.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$styleBlock\n</head>")
                } else {
                    "$styleBlock\n$html"
                }
            }
        }

        // Injeta extraJs antes de </body> (se ainda não estiver inline)
        if (extraJs.isNotEmpty()) {
            val combinedJs = extraJs.filter { snippet ->
                val sample = snippet.lines().firstOrNull { it.trim().length > 5 }?.trim() ?: snippet.take(30)
                sample.isNotEmpty() && !html.contains(sample)
            }.joinToString("\n\n")

            if (combinedJs.isNotBlank()) {
                val scriptBlock = "<script>\n$combinedJs\n</script>"
                html = if (html.contains("</body>", ignoreCase = true)) {
                    html.replaceFirst(Regex("</body>", RegexOption.IGNORE_CASE), "$scriptBlock\n</body>")
                } else {
                    "$html\n$scriptBlock"
                }
            }
        }

        if (!html.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)) {
            html = "<!DOCTYPE html>\n$html"
        }
        if (!html.contains("</html>", ignoreCase = true)) {
            if (!html.contains("</body>", ignoreCase = true)) {
                html += "\n</body>"
            }
            html += "\n</html>"
        }
        return html
    }

    /**
     * Extrai todos os blocos de código da resposta final do modelo e grava como arquivos reais
     * dentro do `workspaceRoot`, retornando a lista de caminhos relativos salvos.
     */
    fun autoSaveGeneratedCodeToWorkspace(
        workspaceRoot: File,
        userPrompt: String,
        modelResponse: String,
        alreadyWrittenPaths: Set<String> = emptySet()
    ): List<ExtractedCodeBlock> {
        val segments = parseRichSegments(modelResponse)
        val codeCards = segments.filterIsInstance<RichMessageSegment.CodeCard>()
        if (codeCards.isEmpty()) return emptyList()

        val root = workspaceRoot.canonicalFile
        if (!root.exists()) root.mkdirs()

        // Verifica se o usuário pediu um nome específico de arquivo no prompt (ex.: "crie calculadora.html" ou "index.hml")
        val promptFileHint = extractExplicitFileFromText(userPrompt)
        val savedBlocks = mutableListOf<ExtractedCodeBlock>()
        val writtenLower = alreadyWrittenPaths.map { it.lowercase() }.toMutableSet()

        for ((index, card) in codeCards.withIndex()) {
            val rawTarget = if (index == 0 && promptFileHint != null &&
                sameFamily(promptFileHint, card.filePath, card.language)
            ) {
                promptFileHint
            } else {
                card.filePath
            }
            val cleanRelPath = normalizeHtmlTypo(rawTarget)
                .trim()
                .removePrefix("/")
                .replace('\\', '/')

            if (cleanRelPath.isBlank() || cleanRelPath.contains("../")) continue
            if (cleanRelPath.lowercase() in writtenLower && codeCards.size > 1) continue

            // Não sobrescreve arquivos existentes grandes do projeto com snippets minúsculos de 1-2 linhas em modo explicação
            val targetFile = File(root, cleanRelPath).canonicalFile
            if (!targetFile.toPath().startsWith(root.toPath()) || targetFile == root) continue

            val isSubstantialCode = card.code.lines().size >= 3 ||
                card.language == "html" ||
                cleanRelPath.endsWith(".html", ignoreCase = true) ||
                !targetFile.exists()

            if (!isSubstantialCode) continue

            targetFile.parentFile?.mkdirs()
            targetFile.writeText(card.code, Charsets.UTF_8)
            writtenLower += cleanRelPath.lowercase()
            savedBlocks += ExtractedCodeBlock(
                language = card.language,
                suggestedPath = cleanRelPath,
                code = card.code
            )
        }

        return savedBlocks
    }

    fun isPreviewableWebCode(language: String, filePath: String, code: String): Boolean {
        val lang = language.lowercase()
        val path = filePath.lowercase()
        val trimmed = code.trimStart().lowercase()
        return lang in setOf("html", "htm", "svg", "xml") ||
            path.endsWith(".html") ||
            path.endsWith(".htm") ||
            path.endsWith(".svg") ||
            trimmed.startsWith("<!doctype html") ||
            trimmed.startsWith("<html") ||
            (trimmed.startsWith("<") && (trimmed.contains("<div") || trimmed.contains("<body") || trimmed.contains("<style") || trimmed.contains("<script") || trimmed.contains("<svg")))
    }

    private fun appendProseOrRawHtml(
        chunk: String,
        segments: MutableList<RichMessageSegment>,
        usedNames: MutableSet<String>
    ) {
        if (chunk.isBlank()) return
        var cursor = 0
        for (htmlMatch in rawHtmlDocRegex.findAll(chunk)) {
            val before = chunk.substring(cursor, htmlMatch.range.first).trim()
            if (before.isNotEmpty()) {
                segments += RichMessageSegment.Prose(cleanProseText(before))
            }
            val htmlCode = htmlMatch.value.trim()
            val path = if ("index.html" !in usedNames) "index.html" else "page_${usedNames.size + 1}.html"
            usedNames += path
            segments += RichMessageSegment.CodeCard(
                language = "html",
                filePath = path,
                code = ensureCompleteHtmlDocument(htmlCode)
            )
            cursor = htmlMatch.range.last + 1
        }
        if (cursor < chunk.length) {
            val rem = chunk.substring(cursor).trim()
            if (rem.isNotEmpty()) {
                segments += RichMessageSegment.Prose(cleanProseText(rem))
            }
        }
    }

    private fun cleanProseText(text: String): String {
        return text
            .replace(Regex("""</?(?:thought|plan)>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
            .replace("**", "")
            .trim()
    }

    private fun closeUnclosedCodeFenceIfNeeded(text: String): String {
        val fenceCount = Regex("```").findAll(text).count()
        return if (fenceCount % 2 == 1) "$text\n```" else text
    }

    private fun removeRawToolJsonObjects(text: String): String {
        var result = text
        val starts = Regex("""\{\s*"tool"\s*:""").findAll(text).toList().asReversed()
        for (match in starts) {
            val startIdx = match.range.first
            var depth = 0
            var inString = false
            var escaped = false
            var endIdx = -1
            for (i in startIdx until text.length) {
                val ch = text[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (ch == '{') depth++
                    else if (ch == '}') {
                        depth--
                        if (depth == 0) {
                            endIdx = i
                            break
                        }
                    }
                }
            }
            if (endIdx >= startIdx) {
                result = result.removeRange(startIdx, endIdx + 1)
            }
        }
        return result.replace(Regex("""```json\s*```"""), "").trim()
    }

    private fun extractLanguage(headerInfo: String, code: String): String {
        val firstToken = headerInfo.split(Regex("""[\s:]+""")).firstOrNull()?.trim()?.lowercase().orEmpty()
        if (firstToken.isNotBlank() && !firstToken.contains('.')) {
            return when (firstToken) {
                "htm" -> "html"
                "js" -> "javascript"
                "ts" -> "typescript"
                "py" -> "python"
                "kt", "kts" -> "kotlin"
                else -> firstToken
            }
        }
        if (firstToken.contains('.')) {
            return languageFromPath(firstToken, code)
        }
        val trimmed = code.trimStart()
        return when {
            trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true) ||
                (trimmed.startsWith("<") && trimmed.contains("</")) -> "html"
            trimmed.contains("fun ") && (trimmed.contains("val ") || trimmed.contains("package ")) -> "kotlin"
            trimmed.startsWith("def ") || trimmed.startsWith("import ") && trimmed.contains("print(") -> "python"
            trimmed.contains("function ") || trimmed.contains("document.getElementById") || trimmed.contains("const ") -> "javascript"
            trimmed.contains("{") && (trimmed.contains("background:") || trimmed.contains("display:") || trimmed.contains("color:")) -> "css"
            else -> "code"
        }
    }

    private fun inferFilePath(
        headerInfo: String,
        code: String,
        language: String,
        precedingText: String,
        usedPaths: Set<String>
    ): String {
        // 1. No próprio cabeçalho da cerca: ```html index.html
        extractExplicitFileFromText(headerInfo)?.let { return normalizeHtmlTypo(it) }

        // 2. Comentário nas primeiras 3 linhas do código: <!-- index.html --> ou // File: src/App.kt
        val firstLines = code.lines().take(3).joinToString("\n")
        extractExplicitFileFromText(firstLines)?.let { return normalizeHtmlTypo(it) }

        // 3. No texto imediatamente anterior ao bloco de código
        extractExplicitFileFromText(precedingText)?.let { candidate ->
            val norm = normalizeHtmlTypo(candidate)
            if (sameFamily(norm, "", language)) return norm
        }

        // 4. Inferência inteligente pelo conteúdo/linguagem
        val defaultPath = when (language.lowercase()) {
            "html", "htm" -> "index.html"
            "css" -> "styles.css"
            "javascript", "js" -> "app.js"
            "typescript", "ts" -> "index.ts"
            "python", "py" -> "main.py"
            "kotlin", "kt" -> {
                val classMatch = Regex("""(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""").find(code)
                val name = classMatch?.groupValues?.getOrNull(1) ?: "Main"
                "$name.kt"
            }
            "java" -> {
                val classMatch = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)""").find(code)
                val name = classMatch?.groupValues?.getOrNull(1) ?: "Main"
                "$name.java"
            }
            "json" -> "data.json"
            "sql" -> "schema.sql"
            "sh", "bash" -> "script.sh"
            "xml" -> "layout.xml"
            "svg" -> "graphic.svg"
            else -> "snippet.txt"
        }

        if (defaultPath !in usedPaths || defaultPath == "index.html") {
            return defaultPath
        }
        val base = defaultPath.substringBeforeLast('.')
        val ext = defaultPath.substringAfterLast('.')
        return "${base}_${usedPaths.size + 1}.$ext"
    }

    private fun extractExplicitFileFromText(text: String): String? {
        val normalized = normalizeHtmlTypo(text)
        val match = fileNameHintRegex.find(normalized) ?: return null
        val path = match.groupValues[1].trim().trim('.', '/', '\\')
        if (path.isBlank() || path.startsWith("http", ignoreCase = true)) return null
        return path
    }

    fun normalizeHtmlTypo(input: String): String {
        return input
            .replace(Regex("""\bindex\.hml\b""", RegexOption.IGNORE_CASE), "index.html")
            .replace(Regex("""\.hml\b""", RegexOption.IGNORE_CASE), ".html")
    }

    private fun sameFamily(pathA: String, pathB: String, language: String): Boolean {
        val ext = pathA.substringAfterLast('.', "").lowercase()
        val lang = language.lowercase()
        return when (lang) {
            "html", "htm" -> ext in setOf("html", "htm")
            "css" -> ext == "css"
            "javascript", "js" -> ext in setOf("js", "jsx", "mjs")
            "typescript", "ts" -> ext in setOf("ts", "tsx")
            "python", "py" -> ext == "py"
            "kotlin", "kt" -> ext in setOf("kt", "kts")
            else -> pathB.isEmpty() || ext == pathB.substringAfterLast('.', "").lowercase()
        }
    }

    private fun languageFromPath(path: String, code: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "html"
            "css" -> "css"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "json" -> "json"
            "xml", "svg" -> "xml"
            "sh" -> "bash"
            else -> extractLanguage("", code)
        }
    }
}
