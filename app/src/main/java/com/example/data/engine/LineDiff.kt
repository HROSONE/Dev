package com.example.data.engine

import com.example.data.model.DiffLine
import com.example.data.model.DiffLineType

data class LineDiffResult(
    val lines: List<DiffLine>,
    val added: Int,
    val removed: Int
)

/**
 * Diff de linhas estilo `git diff` (hunks com 3 linhas de contexto), usado nos cards de
 * ferramenta e no diálogo de aprovação. Remove prefixo/sufixo comuns e roda LCS apenas no
 * trecho do meio, então edições pequenas em arquivos grandes continuam baratas.
 */
object LineDiff {
    private const val CONTEXT = 3
    private const val MAX_LCS_CELLS = 1_500_000L

    fun compute(oldText: String?, newText: String?, maxOutputLines: Int = 400): LineDiffResult {
        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)

        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) suffix++

        val oldMid = oldLines.subList(prefix, oldLines.size - suffix)
        val newMid = newLines.subList(prefix, newLines.size - suffix)

        // Sequência completa de operações: ' ' contexto, '-' removida, '+' adicionada.
        data class Op(val type: Char, val text: String, val oldNo: Int?, val newNo: Int?)
        val ops = ArrayList<Op>(oldLines.size + newMid.size)
        for (i in 0 until prefix) ops += Op(' ', oldLines[i], i + 1, i + 1)

        val midOps = diffMiddle(oldMid, newMid)
        var o = prefix
        var n = prefix
        for (type in midOps) {
            when (type) {
                ' ' -> { ops += Op(' ', oldLines[o], o + 1, n + 1); o++; n++ }
                '-' -> { ops += Op('-', oldLines[o], o + 1, null); o++ }
                '+' -> { ops += Op('+', newLines[n], null, n + 1); n++ }
            }
        }
        for (i in 0 until suffix) {
            ops += Op(' ', oldLines[o], o + 1, n + 1)
            o++; n++
        }

        val added = ops.count { it.type == '+' }
        val removed = ops.count { it.type == '-' }
        if (added == 0 && removed == 0) return LineDiffResult(emptyList(), 0, 0)

        // Agrupa em hunks com contexto.
        val changedIdx = ops.indices.filter { ops[it].type != ' ' }
        val output = mutableListOf<DiffLine>()
        var hunkStart = -1
        var hunkEnd = -1
        fun flushHunk() {
            if (hunkStart < 0) return
            val slice = ops.subList(hunkStart, hunkEnd + 1)
            val oldStart = slice.firstNotNullOfOrNull { it.oldNo } ?: 0
            val newStart = slice.firstNotNullOfOrNull { it.newNo } ?: 0
            val oldCount = slice.count { it.type != '+' }
            val newCount = slice.count { it.type != '-' }
            output += DiffLine(DiffLineType.HEADER, "@@ -$oldStart,$oldCount +$newStart,$newCount @@")
            for (op in slice) {
                output += when (op.type) {
                    '+' -> DiffLine(DiffLineType.ADDED, "+${op.text}", newLineNumber = op.newNo)
                    '-' -> DiffLine(DiffLineType.REMOVED, "-${op.text}", oldLineNumber = op.oldNo)
                    else -> DiffLine(DiffLineType.CONTEXT, " ${op.text}", op.oldNo, op.newNo)
                }
            }
        }
        for (idx in changedIdx) {
            val start = (idx - CONTEXT).coerceAtLeast(0)
            val end = (idx + CONTEXT).coerceAtMost(ops.lastIndex)
            if (hunkStart >= 0 && start <= hunkEnd + 1) {
                hunkEnd = maxOf(hunkEnd, end)
            } else {
                flushHunk()
                hunkStart = start
                hunkEnd = end
            }
        }
        flushHunk()

        val limited = if (output.size > maxOutputLines) {
            output.take(maxOutputLines) +
                DiffLine(DiffLineType.HEADER, "… diff truncado (${output.size - maxOutputLines} linhas omitidas)")
        } else output
        return LineDiffResult(limited, added, removed)
    }

    private fun splitLines(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        return if (normalized.endsWith("\n")) lines.dropLast(1) else lines
    }

    private fun diffMiddle(a: List<String>, b: List<String>): List<Char> {
        if (a.isEmpty()) return List(b.size) { '+' }
        if (b.isEmpty()) return List(a.size) { '-' }
        if (a.size.toLong() * b.size.toLong() > MAX_LCS_CELLS) {
            return List(a.size) { '-' } + List(b.size) { '+' }
        }
        val rows = a.size + 1
        val cols = b.size + 1
        val table = IntArray(rows * cols)
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                table[i * cols + j] = if (a[i] == b[j]) {
                    table[(i + 1) * cols + j + 1] + 1
                } else {
                    maxOf(table[(i + 1) * cols + j], table[i * cols + j + 1])
                }
            }
        }
        val result = ArrayList<Char>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { result += ' '; i++; j++ }
                table[(i + 1) * cols + j] >= table[i * cols + j + 1] -> { result += '-'; i++ }
                else -> { result += '+'; j++ }
            }
        }
        while (i < a.size) { result += '-'; i++ }
        while (j < b.size) { result += '+'; j++ }
        return result
    }
}
