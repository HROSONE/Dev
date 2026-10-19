package com.example.data.engine

import com.example.data.api.LlmStreamListener
import com.example.data.api.ThinkTagSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkTagSplitterTest {
    @Test fun splitsThinkBlocksAcrossChunkBoundaries() {
        val reasoning = StringBuilder()
        val text = StringBuilder()
        val splitter = ThinkTagSplitter(object : LlmStreamListener {
            override fun onReasoning(delta: String) { reasoning.append(delta) }
            override fun onText(delta: String) { text.append(delta) }
        })
        listOf("<thi", "nk>plano A", " e B</th", "ink>Resposta", " final <b>").forEach(splitter::feed)
        splitter.flush()
        assertEquals("plano A e B", reasoning.toString())
        assertEquals("Resposta final <b>", text.toString())
    }
}
