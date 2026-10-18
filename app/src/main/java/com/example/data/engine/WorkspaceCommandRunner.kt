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
 * Minimal local command runner. It deliberately is NOT an arbitrary shell:
 * commands are tokenized without a shell and only pwd, ls and cat are accepted.
 * File arguments are constrained to the app-owned workspace.
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
        if (executable !in setOf("pwd", "ls", "cat")) {
            throw IOException("Comando não permitido: $executable. Permitidos nesta versão: pwd, ls e cat.")
        }
        if (args.any { token -> token.any { it in SHELL_META } }) {
            throw IOException("Caracteres de shell não são permitidos. Envie um comando simples, sem pipes ou encadeamento.")
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
                        // Stop a command that produces more output than we can safely retain.
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
            // If waitFor itself is interrupted, do not leave the child process running.
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
        // Intentionally simple grammar: no quoting, expansion, substitutions or shell operators.
        return commandLine.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private companion object {
        val SHELL_META = setOf(';', '|', '&', '>', '<', '`', '$', '(', ')')
    }
}
