package com.example.data.engine

import com.example.data.model.ProjectTemplate
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalProjectCreatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsAndroidComposeProjectStructureLocally() {
        val root = temporaryFolder.newFolder("local-android-project")
        val files = LocalProjectCreator.createProjectFromTemplate(
            workspaceRoot = root,
            projectName = "SuperApp",
            template = ProjectTemplate.ANDROID_COMPOSE
        )

        assertTrue(files.contains("README.md"))
        assertTrue(files.contains("settings.gradle.kts"))
        assertTrue(files.contains("app/build.gradle.kts"))
        assertTrue(File(root, "README.md").readText().contains("SuperApp"))
    }

    @Test
    fun createsWebAppProjectLocally() {
        val root = temporaryFolder.newFolder("local-web-project")
        val files = LocalProjectCreator.createProjectFromTemplate(
            workspaceRoot = root,
            projectName = "MeuSite",
            template = ProjectTemplate.WEB_APP
        )

        // O template web é um index.html único e autocontido (CSS e JS embutidos).
        assertTrue(files.contains("index.html"))
        val html = File(root, "index.html").readText()
        assertTrue(html.contains("<style>"))
        assertTrue(html.contains("<script>"))
    }
}
