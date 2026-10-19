package com.example.data.engine

import org.json.JSONArray
import org.json.JSONObject

data class SyntaxCheckResult(
    val isValid: Boolean,
    val summary: String,
    val issues: List<String> = emptyList()
)

/**
 * Verificador estático ultrarrápido integrado ao Harness do DevAgent.
 * Inspeciona arquivos logo após edit_file / patch_lines / write_file para detectar:
 * - Chaves {}, parênteses () ou colchetes [] desbalanceados (ignorando strings e comentários)
 * - Placeholders de truncamento proibidos (ex.: "// ... existing code", "<<<<<<<")
 * - JSON malformado em arquivos .json
 * Fornece feedback imediato ao modelo de linguagem para autocorreção no mesmo loop.
 */
object SyntaxVerifier {

    private val truncationPatterns = listOf(
        Regex("""//\s*\.\.\.\s*(?:existing|rest\s+of|remaining|código\s+existente|resto\s+do)""", RegexOption.IGNORE_CASE),
        Regex("""/\*\s*\.\.\.\s*(?:existing|rest\s+of)""", RegexOption.IGNORE_CASE),
        Regex("""#\s*\.\.\.\s*(?:existing|rest\s+of)""", RegexOption.IGNORE_CASE),
        Regex("""^(?:<{7}|={7}|>{7})""", RegexOption.MULTILINE)
    )

    fun verify(path: String, content: String): SyntaxCheckResult {
        val ext = path.substringAfterLast('.', "").lowercase()
        val issues = mutableListOf<String>()

        if (content.isBlank()) {
            return SyntaxCheckResult(true, "Arquivo vazio (0 linhas)")
        }

        // 1. Detecta placeholders de código truncado pelo LLM
        for (pattern in truncationPatterns) {
            if (pattern.containsMatchIn(content)) {
                issues += "Detectado marcador de truncamento ou conflito ('${pattern.find(content)?.value?.trim()}'). Nunca abrevie código com '// ... existing code'; mantenha o código completo."
                break
            }
        }

        // 2. Validação específica de JSON
        if (ext == "json") {
            val trimmed = content.trim()
            try {
                if (trimmed.startsWith("[")) JSONArray(trimmed)
                else JSONObject(trimmed)
            } catch (e: Exception) {
                issues += "JSON inválido: ${e.message?.take(120)}"
            }
        }

        // 3. Verificação de balanço de delimitadores para linguagens baseadas em chaves/parênteses
        if (ext in setOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "css", "scss", "c", "cpp", "h", "rs", "go", "swift", "dart")) {
            checkBalancedDelimiters(content, issues)
        }

        // 4. Verificação básica de Python (parênteses/colchetes/chaves)
        if (ext == "py") {
            checkBalancedDelimiters(content, issues)
        }

        val lineCount = content.lines().size
        return if (issues.isEmpty()) {
            SyntaxCheckResult(
                isValid = true,
                summary = "Sintaxe OK ($lineCount linhas, delimitadores balanceados)"
            )
        } else {
            SyntaxCheckResult(
                isValid = false,
                summary = "ALERTA DE SINTAXE (${issues.size} problema(s) em $path): ${issues.joinToString(" | ")}",
                issues = issues
            )
        }
    }

    private fun checkBalancedDelimiters(content: String, issues: MutableList<String>) {
        data class OpenToken(val ch: Char, val line: Int)
        val stack = ArrayDeque<OpenToken>()
        var i = 0
        var line = 1
        val n = content.length

        while (i < n) {
            val c = content[i]
            if (c == '\n') {
                line++
                i++
                continue
            }
            // Comentário de linha //
            if (c == '/' && i + 1 < n && content[i + 1] == '/') {
                i += 2
                while (i < n && content[i] != '\n') i++
                continue
            }
            // Comentário de bloco /* ... */
            if (c == '/' && i + 1 < n && content[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(content[i] == '*' && content[i + 1] == '/')) {
                    if (content[i] == '\n') line++
                    i++
                }
                i += 2
                continue
            }
            // Raw string """ ... """
            if (c == '"' && i + 2 < n && content[i + 1] == '"' && content[i + 2] == '"') {
                i += 3
                while (i + 2 < n && !(content[i] == '"' && content[i + 1] == '"' && content[i + 2] == '"')) {
                    if (content[i] == '\n') line++
                    i++
                }
                i += 3
                continue
            }
            // String normal "..."
            if (c == '"') {
                i++
                while (i < n && content[i] != '"') {
                    if (content[i] == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (content[i] == '\n') line++
                    i++
                }
                i++
                continue
            }
            // Char literal '...'
            if (c == '\'') {
                i++
                while (i < n && content[i] != '\'' && content[i] != '\n') {
                    if (content[i] == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    i++
                }
                i++
                continue
            }

            when (c) {
                '{', '(', '[' -> stack.addLast(OpenToken(c, line))
                '}', ')', ']' -> {
                    val expected = when (c) {
                        '}' -> '{'
                        ')' -> '('
                        else -> '['
                    }
                    if (stack.isEmpty()) {
                        issues += "Delimitador '$c' extra fechado na linha $line sem abertura correspondente."
                        return
                    }
                    val last = stack.removeLast()
                    if (last.ch != expected) {
                        issues += "Delimitador '$c' na linha $line não corresponde a '${last.ch}' aberto na linha ${last.line}."
                        return
                    }
                }
            }
            i++
        }

        if (stack.isNotEmpty()) {
            val unclosed = stack.last()
            issues += "Falta fechar '${unclosed.ch}' (aberto na linha ${unclosed.line}; restam ${stack.size} delimitador(es) aberto(s) no fim do arquivo)."
        }
    }
}
