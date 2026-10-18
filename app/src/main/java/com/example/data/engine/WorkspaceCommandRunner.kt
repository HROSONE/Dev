package com.example.data.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CommandResult(
    val command: String,
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
    val outputTruncated: Boolean = false
)

/**
 * Local workspace command runner.
 *
 * Supports native OS execution for pwd, ls and cat, plus built-in safe workspace
 * commands (grep, find, head, tail, wc, tree) constrained to the canonical workspace root.
 */
class WorkspaceCommandRunner(
    workspaceRoot: File,
    private val timeoutSeconds: Long = 5,
    private val maxOutputBytes: Int = 32_768
) {
    private val root = workspaceRoot.canonicalFile

    init {
        require(timeoutSeconds > 0)
        require(maxOutputBytes > 0)
        if (!root.exists() && !root.mkdirs()) throw IOException("Não foi possível criar o workspace.")
        if (!root.isDirectory) throw IOException("A raiz do workspace não é um diretório.")
    }

    fun run(commandLine: String): CommandResult {
        val args = tokenize(commandLine)
        if (args.isEmpty()) throw IOException("Informe um comando.")
        val executable = args.first()
        if (executable !in ALLOWED_COMMANDS) {
            throw IOException(
                "Comando não permitido: $executable. Permitidos: ${ALLOWED_COMMANDS.joinToString(", ")}."
            )
        }
        if (args.any { token -> token.any { it in SHELL_META } }) {
            throw IOException("Caracteres de shell não são permitidos. Envie um comando simples, sem pipes ou encadeamento.")
        }

        // Built-in portable workspace commands (work reliably across all Android devices)
        when (executable) {
            "find" -> return runBuiltinFind(args)
            "tree" -> return runBuiltinTree(args)
            "grep" -> return runBuiltinGrep(args)
            "head" -> return runBuiltinHeadTail(args, isHead = true)
            "tail" -> return runBuiltinHeadTail(args, isHead = false)
            "wc" -> return runBuiltinWc(args)
        }

        val resolvedArgs = when (executable) {
            "pwd" -> {
                if (args.size != 1) throw IOException("Uso: pwd")
                listOf(executable)
            }
            "ls" -> {
                if (args.size > 2) throw IOException("Uso: ls [pasta-relativa]")
                if (args.size == 1) args
                else listOf(executable, resolveWorkspacePath(args[1], mustExist = true).absolutePath)
            }
            "cat" -> {
                if (args.size != 2) throw IOException("Uso: cat arquivo-relativo")
                val file = resolveWorkspacePath(args[1], mustExist = true)
                if (!file.isFile) throw IOException("O caminho não é um arquivo.")
                listOf(executable, file.absolutePath)
            }
            else -> error("Comando validado anteriormente")
        }

        val process = try {
            ProcessBuilder(resolvedArgs)
                .directory(root)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw IOException("Não foi possível iniciar '$executable' neste dispositivo: ${e.message}", e)
        }

        val output = ByteArrayOutputStream(minOf(maxOutputBytes, 8_192))
        val truncated = AtomicBoolean(false)
        val reader = Thread {
            process.inputStream.use { stream ->
                val buffer = ByteArray(1_024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    var overflow = false
                    synchronized(output) {
                        val remaining = maxOutputBytes - output.size()
                        val accepted = minOf(count, remaining.coerceAtLeast(0))
                        if (accepted > 0) output.write(buffer, 0, accepted)
                        if (accepted < count) {
                            truncated.set(true)
                            overflow = true
                        }
                    }
                    if (overflow) {
                        process.destroyForcibly()
                        break
                    }
                }
            }
        }.apply {
            name = "workspace-command-output"
            isDaemon = true
            start()
        }

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } finally {
            if (Thread.currentThread().isInterrupted) process.destroyForcibly()
        }
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
        reader.join(1_000)
        if (reader.isAlive) {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            reader.join(1_000)
        }

        val bytes = synchronized(output) { output.toByteArray() }
        return CommandResult(
            command = resolvedArgs.joinToString(" "),
            exitCode = if (finished && !truncated.get()) process.exitValue() else null,
            output = decodeCompleteUtf8(bytes),
            timedOut = !finished,
            outputTruncated = truncated.get()
        )
    }

    private fun runBuiltinFind(args: List<String>): CommandResult {
        val targetRel = args.getOrNull(1) ?: "."
        val dir = resolveWorkspacePath(targetRel, mustExist = true)
        val filter = args.getOrNull(2)?.lowercase()
        val files = dir.walkTopDown()
            .onEnter { it.canonicalFile == root || it.canonicalFile.toPath().startsWith(root.toPath()) }
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { filter == null || it.lowercase().contains(filter) }
            .sorted()
            .toList()
        return boundedResult(args.joinToString(" "), files.joinToString("\n"))
    }

    private fun runBuiltinTree(args: List<String>): CommandResult {
        val targetRel = args.getOrNull(1) ?: "."
        val dir = resolveWorkspacePath(targetRel, mustExist = true)
        val entries = dir.walkTopDown()
            .onEnter { it.canonicalFile == root || it.canonicalFile.toPath().startsWith(root.toPath()) }
            .filter { it != dir }
            .map {
                val rel = it.relativeTo(dir).invariantSeparatorsPath
                val depth = rel.count { c -> c == '/' }
                val indent = "  ".repeat(depth)
                val suffix = if (it.isDirectory) "/" else ""
                "$indent${it.name}$suffix"
            }
            .toList()
        return boundedResult(args.joinToString(" "), entries.joinToString("\n"))
    }

    private fun runBuiltinGrep(args: List<String>): CommandResult {
        if (args.size < 2) throw IOException("Uso: grep <termo> [caminho-relativo]")
        val pattern = args[1]
        val targetRel = args.getOrNull(2) ?: "."
        val target = resolveWorkspacePath(targetRel, mustExist = true)
        val matches = mutableListOf<String>()
        val files = if (target.isFile) listOf(target) else {
            target.walkTopDown()
                .onEnter { it.canonicalFile == root || it.canonicalFile.toPath().startsWith(root.toPath()) }
                .filter { it.isFile }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }
        for (file in files) {
            if (matches.size >= 150) break
            val text = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
            if (text.indexOf('\u0000') >= 0) continue
            val rel = file.relativeTo(root).invariantSeparatorsPath
            text.lines().forEachIndexed { idx, line ->
                if (matches.size < 150 && line.contains(pattern, ignoreCase = true)) {
                    matches += "$rel:${idx + 1}: ${line.trim()}"
                }
            }
        }
        return boundedResult(args.joinToString(" "), matches.joinToString("\n"))
    }

    private fun runBuiltinHeadTail(args: List<String>, isHead: Boolean): CommandResult {
        if (args.size < 2) throw IOException("Uso: ${if (isHead) "head" else "tail"} <arquivo-relativo> [linhas]")
        val file = resolveWorkspacePath(args[1], mustExist = true)
        if (!file.isFile) throw IOException("O caminho não é um arquivo.")
        val count = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 500) ?: 30
        val lines = file.readLines(Charsets.UTF_8)
        val slice = if (isHead) lines.take(count) else lines.takeLast(count)
        return boundedResult(args.joinToString(" "), slice.joinToString("\n"))
    }

    private fun runBuiltinWc(args: List<String>): CommandResult {
        if (args.size != 2) throw IOException("Uso: wc <arquivo-relativo>")
        val file = resolveWorkspacePath(args[1], mustExist = true)
        if (!file.isFile) throw IOException("O caminho não é um arquivo.")
        val text = file.readText(Charsets.UTF_8)
        val lines = text.lines().size
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val bytes = text.toByteArray(Charsets.UTF_8).size
        val rel = file.relativeTo(root).invariantSeparatorsPath
        return boundedResult(args.joinToString(" "), "$lines $words $bytes $rel")
    }

    private fun boundedResult(command: String, rawOutput: String): CommandResult {
        val rawBytes = rawOutput.toByteArray(Charsets.UTF_8)
        val truncated = rawBytes.size > maxOutputBytes
        val slice = if (truncated) rawBytes.copyOf(maxOutputBytes) else rawBytes
        return CommandResult(
            command = command,
            exitCode = 0,
            output = decodeCompleteUtf8(slice),
            timedOut = false,
            outputTruncated = truncated
        )
    }

    /** Decode only complete UTF-8 code points when the byte limit cuts through a character. */
    private fun decodeCompleteUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var length = bytes.size
        while (length >= 0) {
            try {
                return decoder.reset().decode(ByteBuffer.wrap(bytes, 0, length)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                length--
            }
        }
        return ""
    }

    private fun resolveWorkspacePath(relative: String, mustExist: Boolean): File {
        if (relative.isBlank() || File(relative).isAbsolute ||
            relative.startsWith("\\\\") || Regex("^[A-Za-z]:").containsMatchIn(relative)
        ) throw IOException("Use apenas caminhos relativos dentro do workspace.")
        val file = File(root, relative).canonicalFile
        if (file != root && !file.toPath().startsWith(root.toPath())) {
            throw IOException("O caminho solicitado sai dos limites do workspace.")
        }
        if (mustExist && !file.exists()) throw IOException("O caminho solicitado não existe no workspace.")
        return file
    }

    private fun tokenize(commandLine: String): List<String> {
        if (commandLine.length > 2_048) throw IOException("Comando excede o limite de 2.048 caracteres.")
        return commandLine.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private companion object {
        val ALLOWED_COMMANDS = setOf("pwd", "ls", "cat", "grep", "find", "head", "tail", "wc", "tree")
        val SHELL_META = setOf(';', '|', '&', '>', '<', '`', '$', '(', ')')
    }
}
