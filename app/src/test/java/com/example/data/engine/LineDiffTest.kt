package com.example.data.engine

import com.example.data.model.DiffLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {
    @Test fun identicalTextHasNoDiff() {
        val result = LineDiff.compute("a\nb", "a\nb")
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertTrue(result.lines.isEmpty())
    }

    @Test fun newFileCountsAllLinesAsAdded() {
        val result = LineDiff.compute(null, "a\nb\nc\n")
        assertEquals(3, result.added)
        assertEquals(0, result.removed)
        assertEquals(DiffLineType.HEADER, result.lines.first().type)
    }

    @Test fun singleLineChangeInMiddleProducesOneHunk() {
        val old = (1..20).joinToString("\n") { "line $it" }
        val new = old.replace("line 10", "line DEZ")
        val result = LineDiff.compute(old, new)
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals(1, result.lines.count { it.type == DiffLineType.HEADER })
        assertTrue(result.lines.any { it.type == DiffLineType.ADDED && it.text == "+line DEZ" && it.newLineNumber == 10 })
        // 3 linhas de contexto de cada lado
        assertEquals(6, result.lines.count { it.type == DiffLineType.CONTEXT })
    }

    @Test fun insertionKeepsSurroundingLinesAsContext() {
        val result = LineDiff.compute("a\nb\nc", "a\nb\nx\nc")
        assertEquals(1, result.added)
        assertEquals(0, result.removed)
    }
}
