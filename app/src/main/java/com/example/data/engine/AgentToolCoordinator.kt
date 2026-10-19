package com.example.data.engine

import com.example.data.model.AgentMode
import java.io.IOException

sealed class AgentToolExecution {
    data class NotToolRequest(val response: String) : AgentToolExecution()
    data class Completed(val toolName: String, val output: String) : AgentToolExecution()
    data class NeedsConfirmation(
        val toolName: String,
        val path: String,
        val contentPreview: String = ""
    ) : AgentToolExecution()
    data class Rejected(val reason: String) : AgentToolExecution()
}

class AgentToolCoordinator(
    private val workspaceTools: WorkspaceFileTools,
    private val commandRunner: WorkspaceCommandRunner? = null,
    private val maxListedFiles: Int = 200,
    private val maxOutputChars: Int = 20_000
) {
    init {
        require(maxListedFiles > 0)
        require(maxOutputChars > 0)
    }

    fun verifyFileSyntax(relativePath: String): SyntaxCheckResult? =
        workspaceTools.verifySyntaxDirect(relativePath)

    fun execute(
        modelResponse: String,
        mode: AgentMode,
        intent: ChatIntent,
        userConfirmedMutation: Boolean = false
    ): AgentToolExecution {
        val request = AgentToolRequestParser.parse(modelResponse)
            ?: return AgentToolExecution.NotToolRequest(modelResponse)
        return executeRequest(request, mode, intent, userConfirmedMutation)
    }

    fun executeRequest(
        request: AgentToolRequest,
        mode: AgentMode,
        intent: ChatIntent,
        userConfirmedMutation: Boolean = false
    ): AgentToolExecution {
        return when (request) {
            is AgentToolRequest.ListFiles -> {
                try {
                    when (val result = workspaceTools.listFiles(request.path, mode, intent)) {
                        is ToolDispatchResult.Executed -> {
                            val files = result.value
                            val shown = files.take(maxListedFiles)
                            val suffix = if (files.size > shown.size) {
                                "\n… e mais ${files.size - shown.size} arquivos."
                            } else ""
                            AgentToolExecution.Completed(
                                toolName = "list_files",
                                output = (shown.joinToString("\n") + suffix).take(maxOutputChars)
                            )
                        }
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.Rejected("A política exige confirmação para esta operação.")
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a leitura solicitada.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "list_files",
                        output = "Erro em list_files (${request.path}): ${e.message}"
                    )
                }
            }
            is AgentToolRequest.ReadFile -> {
                try {
                    when (val result = workspaceTools.readFile(request.path, mode, intent)) {
                        is ToolDispatchResult.Executed -> AgentToolExecution.Completed(
                            toolName = "read_file",
                            output = result.value.take(maxOutputChars) +
                                if (result.value.length > maxOutputChars) "\n[Conteúdo truncado pelo limite de saída.]" else ""
                        )
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.Rejected("A política exige confirmação para esta operação.")
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a leitura solicitada.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "read_file",
                        output = "Erro em read_file (${request.path}): ${e.message}"
                    )
                }
            }
            is AgentToolRequest.ReadFileLines -> {
                try {
                    when (val result = workspaceTools.readFileLines(
                        request.path,
                        request.startLine,
                        request.endLine,
                        mode,
                        intent
                    )) {
                        is ToolDispatchResult.Executed -> AgentToolExecution.Completed(
                            toolName = "read_file_lines",
                            output = result.value.take(maxOutputChars)
                        )
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.Rejected("A política exige confirmação para esta operação.")
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a leitura solicitada.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "read_file_lines",
                        output = "Erro em read_file_lines (${request.path}): ${e.message}"
                    )
                }
            }
            is AgentToolRequest.SearchFiles -> {
                try {
                    when (val result = workspaceTools.searchFiles(request.query, request.path, mode, intent)) {
                        is ToolDispatchResult.Executed -> AgentToolExecution.Completed(
                            toolName = "search_files",
                            output = result.value.take(maxOutputChars)
                        )
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.Rejected("A política exige confirmação para esta operação.")
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a busca solicitada.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "search_files",
                        output = "Erro em search_files (${request.query}): ${e.message}"
                    )
                }
            }
            is AgentToolRequest.FindSymbol -> {
                try {
                    when (val result = workspaceTools.findSymbol(request.query, mode, intent)) {
                        is ToolDispatchResult.Executed -> AgentToolExecution.Completed(
                            toolName = "find_symbol",
                            output = result.value.take(maxOutputChars)
                        )
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.Rejected("A política exige confirmação para esta operação.")
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a busca de símbolo.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "find_symbol",
                        output = "Erro em find_symbol (${request.query}): ${e.message}"
                    )
                }
            }
            is AgentToolRequest.VerifySyntax -> {
                try {
                    when (val result = workspaceTools.verifySyntax(request.path, mode, intent)) {
                        is ToolDispatchResult.Executed -> AgentToolExecution.Completed(
                            toolName = "verify_syntax",
                            output = result.value.take(maxOutputChars)
                        )
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.Rejected("A política exige confirmação para esta operação.")
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a verificação.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "verify_syntax",
                        output = "Erro em verify_syntax (${request.path}): ${e.message}"
                    )
                }
            }
            is AgentToolRequest.RunCommand -> {
                if (ToolExecutionPolicy.evaluate(mode, intent, ToolRisk.READ_ONLY) != ToolPolicyDecision.ALLOW) {
                    return AgentToolExecution.Rejected("A política de segurança negou a execução do comando.")
                }
                val runner = commandRunner
                    ?: return AgentToolExecution.Rejected("Execução de comandos não está configurada.")
                try {
                    val result = runner.run(request.command)
                    val status = when {
                        result.timedOut -> "TIMEOUT após o limite de tempo"
                        else -> "código de saída ${result.exitCode}"
                    }
                    val suffix = buildString {
                        if (result.timedOut) append("\n[Comando interrompido por tempo limite.]")
                        if (result.outputTruncated) append("\n[Saída truncada pelo limite.]")
                    }
                    AgentToolExecution.Completed(
                        toolName = "run_command",
                        output = "Comando: ${result.command}\n$status\n${result.output}$suffix".take(maxOutputChars)
                    )
                } catch (e: Exception) {
                    AgentToolExecution.Rejected(e.message ?: "Comando negado.")
                }
            }
            is AgentToolRequest.EditFile -> {
                try {
                    if (!userConfirmedMutation) {
                        val decision = ToolExecutionPolicy.evaluate(mode, intent, ToolRisk.MUTATING, false)
                        if (decision == ToolPolicyDecision.DENY) {
                            return AgentToolExecution.Rejected("A política de segurança negou a alteração solicitada.")
                        }
                        val preview = workspaceTools.previewEditFile(
                            relativePath = request.path,
                            oldText = request.oldText,
                            newText = request.newText,
                            replaceAll = request.replaceAll
                        )
                        return AgentToolExecution.NeedsConfirmation(
                            toolName = "edit_file",
                            path = request.path,
                            contentPreview = preview.take(100_000)
                        )
                    }
                    when (val result = workspaceTools.editFile(
                        relativePath = request.path,
                        oldText = request.oldText,
                        newText = request.newText,
                        replaceAll = request.replaceAll,
                        mode = mode,
                        intent = intent,
                        userConfirmedMutation = true
                    )) {
                        is ToolDispatchResult.Executed ->
                            AgentToolExecution.Completed("edit_file", result.value)
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.NeedsConfirmation(
                                toolName = "edit_file",
                                path = request.path,
                                contentPreview = request.newText.take(4_000)
                            )
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a alteração solicitada.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "edit_file",
                        output = "Erro em edit_file (${request.path}): ${e.message}"
                    )
                }
            }
            is AgentToolRequest.PatchLines -> {
                try {
                    if (!userConfirmedMutation) {
                        val decision = ToolExecutionPolicy.evaluate(mode, intent, ToolRisk.MUTATING, false)
                        if (decision == ToolPolicyDecision.DENY) {
                            return AgentToolExecution.Rejected("A política de segurança negou a alteração solicitada.")
                        }
                        val preview = workspaceTools.previewPatchLines(
                            relativePath = request.path,
                            startLine = request.startLine,
                            endLine = request.endLine,
                            newText = request.newText
                        )
                        return AgentToolExecution.NeedsConfirmation(
                            toolName = "patch_lines",
                            path = request.path,
                            contentPreview = preview.take(100_000)
                        )
                    }
                    when (val result = workspaceTools.patchLines(
                        relativePath = request.path,
                        startLine = request.startLine,
                        endLine = request.endLine,
                        newText = request.newText,
                        mode = mode,
                        intent = intent,
                        userConfirmedMutation = true
                    )) {
                        is ToolDispatchResult.Executed ->
                            AgentToolExecution.Completed("patch_lines", result.value)
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.NeedsConfirmation(
                                toolName = "patch_lines",
                                path = request.path,
                                contentPreview = request.newText.take(4_000)
                            )
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a alteração solicitada.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "patch_lines",
                        output = "Erro em patch_lines (${request.path}): ${e.message}"
                    )
                }
            }
            is AgentToolRequest.WriteFile -> {
                try {
                    when (val result = workspaceTools.writeFile(
                        relativePath = request.path,
                        content = request.content,
                        mode = mode,
                        intent = intent,
                        userConfirmedMutation = userConfirmedMutation
                    )) {
                        is ToolDispatchResult.Executed ->
                            AgentToolExecution.Completed("write_file", result.value)
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.NeedsConfirmation(
                                toolName = "write_file",
                                path = request.path,
                                contentPreview = request.content.take(4_000)
                            )
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a alteração solicitada.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "write_file",
                        output = "Erro em write_file (${request.path}): ${e.message}"
                    )
                }
            }
            is AgentToolRequest.DeleteFile -> {
                try {
                    when (val result = workspaceTools.deleteFile(
                        relativePath = request.path,
                        mode = mode,
                        intent = intent,
                        userConfirmedMutation = userConfirmedMutation
                    )) {
                        is ToolDispatchResult.Executed ->
                            AgentToolExecution.Completed("delete_file", result.value)
                        ToolDispatchResult.ConfirmationRequired ->
                            AgentToolExecution.NeedsConfirmation(
                                toolName = "delete_file",
                                path = request.path,
                                contentPreview = ""
                            )
                        ToolDispatchResult.Denied ->
                            AgentToolExecution.Rejected("A política de segurança negou a exclusão solicitada.")
                    }
                } catch (e: IOException) {
                    AgentToolExecution.Completed(
                        toolName = "delete_file",
                        output = "Erro em delete_file (${request.path}): ${e.message}"
                    )
                }
            }
        }
    }
}
