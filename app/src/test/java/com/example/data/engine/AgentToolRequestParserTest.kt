package com.example.data.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentToolRequestParserTest {

    @Test
    fun parsesRunCommandRequest() {
        assertEquals(
            AgentToolRequest.RunCommand("pwd"),
            AgentToolRequestParser.parse("""{"tool":"run_command","command":"pwd"}""")
        )
    }

    @Test
    fun parsesListFilesRequest() {
        assertEquals(
            AgentToolRequest.ListFiles("src"),
            AgentToolRequestParser.parse("""{"tool":"list_files","path":"src"}""")
        )
    }

    @Test
    fun defaultsListPathToWorkspaceRoot() {
        assertEquals(
            AgentToolRequest.ListFiles("."),
            AgentToolRequestParser.parse("""{"tool":"list_files"}""")
        )
    }

    @Test
    fun parsesReadFileRequest() {
        assertEquals(
            AgentToolRequest.ReadFile("src/Main.kt"),
            AgentToolRequestParser.parse("""{"tool":"read_file","path":"src/Main.kt"}""")
        )
    }

    @Test
    fun parsesSearchFilesRequest() {
        assertEquals(
            AgentToolRequest.SearchFiles("DevAgentViewModel", "app/src"),
            AgentToolRequestParser.parse("""{"tool":"search_files","query":"DevAgentViewModel","path":"app/src"}""")
        )
    }

    @Test
    fun parsesEditFileRequest() {
        assertEquals(
            AgentToolRequest.EditFile("src/Main.kt", "fun main() = Unit", "fun main() { println(1) }", false),
            AgentToolRequestParser.parse(
                """{"tool":"edit_file","path":"src/Main.kt","old_text":"fun main() = Unit","new_text":"fun main() { println(1) }"}"""
            )
        )
    }

    @Test
    fun parsesWriteFileRequestWithoutExecutingIt() {
        assertEquals(
            AgentToolRequest.WriteFile("notes.txt", "hello"),
            AgentToolRequestParser.parse(
                """{"tool":"write_file","path":"notes.txt","content":"hello"}"""
            )
        )
    }

    @Test
    fun parsesToolRequestInsideMarkdownFenceOrWithThoughtPrefix() {
        val response = """
            Vou ler o arquivo principal primeiro para entender a estrutura.
            ```json
            {"tool":"read_file","path":"src/Main.kt"}
            ```
        """.trimIndent()
        assertEquals(
            AgentToolRequest.ReadFile("src/Main.kt"),
            AgentToolRequestParser.parse(response)
        )
        assertEquals(
            "Vou ler o arquivo principal primeiro para entender a estrutura.",
            AgentToolRequestParser.extractThoughtStep(response)
        )
    }

    @Test
    fun rejectsAbsoluteAndEmptyPaths() {
        assertNull(AgentToolRequestParser.parse("""{"tool":"read_file","path":"/etc/passwd"}"""))
        assertNull(AgentToolRequestParser.parse("""{"tool":"read_file","path":""}"""))
        assertNull(AgentToolRequestParser.parse("""{"tool":"read_file","path":"C:\\secret.txt"}"""))
    }

    @Test
    fun rejectsUnknownToolsMalformedJsonAndOrdinaryText() {
        assertNull(AgentToolRequestParser.parse("""{"tool":"run_shell","command":"rm -rf /"}"""))
        assertNull(AgentToolRequestParser.parse("""{"tool":"read_file","path":"""))
        assertNull(AgentToolRequestParser.parse("Here is the answer."))
    }

    @Test
    fun rejectsOversizedWriteContent() {
        val tooLarge = "x".repeat(500_001)
        val request = """{"tool":"write_file","path":"notes.txt","content":"$tooLarge"}"""
        assertNull(AgentToolRequestParser.parse(request))
    }
}
