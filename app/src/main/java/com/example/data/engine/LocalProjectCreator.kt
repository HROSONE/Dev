package com.example.data.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.ProjectTemplate
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Permite:
 * 1) Criar projetos/repositórios inteiros do zero diretamente dentro do DevAgent
 * 2) Importar arquivos individuais ou pacotes .zip diretamente da memória do celular
 */
object LocalProjectCreator {

    fun createProjectFromTemplate(
        workspaceRoot: File,
        projectName: String,
        template: ProjectTemplate
    ): List<String> {
        val root = workspaceRoot.canonicalFile
        if (root.exists()) {
            root.listFiles()?.forEach { it.deleteRecursively() }
        } else {
            root.mkdirs()
        }

        val cleanName = projectName.trim().ifBlank { "meu-projeto" }
        val pkgSlug = cleanName.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "meuapp" }
        val files = when (template) {
            ProjectTemplate.BLANK -> mapOf(
                "README.md" to "# $cleanName\n\nProjeto criado localmente no **DevAgent**.\n",
                ".gitignore" to ".DS_Store\n*.log\n.env\nbuild/\ndist/\n"
            )
            ProjectTemplate.ANDROID_COMPOSE -> mapOf(
                "README.md" to "# $cleanName\n\nApp Android (Kotlin + Jetpack Compose) criado no **DevAgent**.\n",
                ".gitignore" to ".gradle/\nbuild/\nlocal.properties\n*.iml\n.env\n",
                "settings.gradle.kts" to "rootProject.name = \"$cleanName\"\ninclude(\":app\")\n",
                "build.gradle.kts" to "plugins {\n    id(\"com.android.application\") version \"8.7.0\" apply false\n    id(\"org.jetbrains.kotlin.android\") version \"2.0.20\" apply false\n}\n",
                "app/build.gradle.kts" to "plugins {\n    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")\n}\n\nandroid {\n    namespace = \"com.example.$pkgSlug\"\n    compileSdk = 35\n\n    defaultConfig {\n        applicationId = \"com.example.$pkgSlug\"\n        minSdk = 24\n        targetSdk = 35\n        versionCode = 1\n        versionName = \"1.0.0\"\n    }\n    buildFeatures {\n        compose = true\n    }\n}\n",
                "app/src/main/AndroidManifest.xml" to "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application android:label=\"$cleanName\" android:supportsRtl=\"true\">\n        <activity android:name=\".MainActivity\" android:exported=\"true\">\n            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>\n        </activity>\n    </application>\n</manifest>\n",
                "app/src/main/java/com/example/$pkgSlug/MainActivity.kt" to "package com.example.$pkgSlug\n\nimport android.os.Bundle\nimport androidx.activity.ComponentActivity\nimport androidx.activity.compose.setContent\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.material3.*\nimport androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\n\nclass MainActivity : ComponentActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        setContent {\n            MaterialTheme {\n                Surface(modifier = Modifier.fillMaxSize()) {\n                    Box(contentAlignment = Alignment.Center) {\n                        Text(text = \"Olá do $cleanName!\")\n                    }\n                }\n            }\n        }\n    }\n}\n"
            )
            ProjectTemplate.PYTHON_APP -> mapOf(
                "README.md" to "# $cleanName\n\nProjeto Python criado no **DevAgent**.\n\n```bash\npython main.py\n```\n",
                ".gitignore" to "__pycache__/\n.venv/\n*.pyc\n.env\n",
                "requirements.txt" to "requests>=2.31.0\n",
                "main.py" to "def main():\n    print(\"Projeto $cleanName iniciado com sucesso!\")\n\nif __name__ == \"__main__\":\n    main()\n"
            )
            ProjectTemplate.WEB_APP -> mapOf(
                "README.md" to "# $cleanName\n\nWeb App interativo criado no **DevAgent**.\n",
                ".gitignore" to "node_modules/\ndist/\n.DS_Store\n",
                "index.html" to "<!DOCTYPE html>\n<html lang=\"pt-BR\">\n<head>\n  <meta charset=\"UTF-8\" />\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n  <title>$cleanName</title>\n  <link rel=\"stylesheet\" href=\"styles.css\" />\n</head>\n<body>\n  <main class=\"container\">\n    <h1>$cleanName</h1>\n    <p id=\"status\">Pronto para editar com o DevAgent.</p>\n    <button id=\"actionBtn\">Testar Ação</button>\n  </main>\n  <script src=\"app.js\"></script>\n</body>\n</html>\n",
                "styles.css" to "body { margin: 0; font-family: system-ui, sans-serif; background: #0d1117; color: #f0f6fc; display: flex; min-height: 100vh; align-items: center; justify-content: center; }\n.container { background: #161b22; padding: 2rem; border-radius: 12px; border: 1px solid #30363d; text-align: center; }\nbutton { background: #38bdf8; color: #090d12; border: none; padding: 0.6rem 1.2rem; border-radius: 8px; font-weight: 600; cursor: pointer; }\n",
                "app.js" to "document.getElementById(\"actionBtn\")?.addEventListener(\"click\", () => {\n  const status = document.getElementById(\"status\");\n  if (status) status.textContent = \"Botão acionado com sucesso!\";\n});\n"
            )
        }

        val createdPaths = mutableListOf<String>()
        files.forEach { (relPath, content) ->
            val target = File(root, relPath).canonicalFile
            if (target.toPath().startsWith(root.toPath())) {
                target.parentFile?.mkdirs()
                target.writeText(content, Charsets.UTF_8)
                createdPaths += relPath
            }
        }
        return createdPaths.sorted()
    }

    fun importUrisFromDevice(
        context: Context,
        workspaceRoot: File,
        uris: List<Uri>,
        targetSubfolder: String = ""
    ): List<String> {
        val root = workspaceRoot.canonicalFile
        if (!root.exists()) root.mkdirs()
        val imported = mutableListOf<String>()
        val cleanSub = targetSubfolder.trim().trim('/')

        for (uri in uris) {
            val fileName = queryDisplayName(context, uri) ?: "arquivo_${System.currentTimeMillis()}.txt"
            if (fileName.lowercase().endsWith(".zip")) {
                imported += extractZipFromUri(context, root, uri)
            } else {
                val relPath = if (cleanSub.isNotEmpty()) "$cleanSub/$fileName" else fileName
                val dest = File(root, relPath).canonicalFile
                if (dest == root || !dest.toPath().startsWith(root.toPath())) {
                    throw IOException("Caminho inválido para importação: $relPath")
                }
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    dest.writeBytes(bytes)
                    imported += dest.relativeTo(root).invariantSeparatorsPath
                }
            }
        }
        return imported
    }

    private fun extractZipFromUri(context: Context, root: File, uri: Uri): List<String> {
        val tempExtract = File(context.cacheDir, "zip_import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/').trimStart('/')
                        if (name.isNotBlank() && !name.contains("../") && !name.startsWith("__MACOSX")) {
                            val outFile = File(tempExtract, name).canonicalFile
                            if (outFile.toPath().startsWith(tempExtract.canonicalFile.toPath())) {
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    outFile.outputStream().use { out -> zis.copyTo(out) }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            val topList = tempExtract.listFiles() ?: emptyArray()
            val topChildren = topList.filter { it.name != "__MACOSX" }
            val effectiveRoot = if (topChildren.size == 1 && topChildren[0].isDirectory) {
                topChildren[0]
            } else {
                tempExtract
            }

            val copied = mutableListOf<String>()
            effectiveRoot.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(effectiveRoot).invariantSeparatorsPath
                    if (!rel.startsWith(".git/")) {
                        val dest = File(root, rel).canonicalFile
                        if (dest.toPath().startsWith(root.toPath())) {
                            dest.parentFile?.mkdirs()
                            file.copyTo(dest, overwrite = true)
                            copied += rel
                        }
                    }
                }
            return copied
        } finally {
            runCatching { tempExtract.deleteRecursively() }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
