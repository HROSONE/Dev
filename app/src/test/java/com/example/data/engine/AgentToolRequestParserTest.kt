package com.example.data.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentToolRequestParserTest {

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
    fun parsesWriteFileRequestWithoutExecutingIt() {
        assertEquals(
            AgentToolRequest.WriteFile("notes.txt", "hello"),
            AgentToolRequestParser.parse(
                """{"tool":"write_file","path":"notes.txt","content":"hello"}"""
            )
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
