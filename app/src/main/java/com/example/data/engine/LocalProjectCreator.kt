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
 * 1) Criar projetos/repositórios inteiros do zero diretamente dentro do DevAgent com arquitetura limpa
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
                "README.md" to "# $cleanName\n\nProjeto criado localmente no **DevAgent Studio**.\n\nPeça ao agente no modo **Construir (⚡ Auto)** para arquitetar e gerar todos os módulos deste projeto do zero.\n",
                ".gitignore" to ".DS_Store\n*.log\n.env\nbuild/\ndist/\nnode_modules/\n__pycache__/\n"
            )
            ProjectTemplate.ANDROID_COMPOSE -> mapOf(
                "README.md" to "# $cleanName\n\nApp Android moderno (Kotlin + Jetpack Compose + Material 3) criado no **DevAgent Studio**.\n",
                ".gitignore" to ".gradle/\nbuild/\nlocal.properties\n*.iml\n.env\n",
                "settings.gradle.kts" to "rootProject.name = \"$cleanName\"\ninclude(\":app\")\n",
                "build.gradle.kts" to "plugins {\n    id(\"com.android.application\") version \"8.7.0\" apply false\n    id(\"org.jetbrains.kotlin.android\") version \"2.0.20\" apply false\n}\n",
                "app/build.gradle.kts" to "plugins {\n    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")\n}\n\nandroid {\n    namespace = \"com.example.$pkgSlug\"\n    compileSdk = 35\n\n    defaultConfig {\n        applicationId = \"com.example.$pkgSlug\"\n        minSdk = 24\n        targetSdk = 35\n        versionCode = 1\n        versionName = \"1.0.0\"\n    }\n    buildFeatures {\n        compose = true\n    }\n}\n",
                "app/src/main/AndroidManifest.xml" to "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application android:label=\"$cleanName\" android:supportsRtl=\"true\">\n        <activity android:name=\".MainActivity\" android:exported=\"true\">\n            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>\n        </activity>\n    </application>\n</manifest>\n",
                "app/src/main/java/com/example/$pkgSlug/model/AppItem.kt" to "package com.example.$pkgSlug.model\n\ndata class AppItem(\n    val id: String,\n    val title: String,\n    val done: Boolean = false\n)\n",
                "app/src/main/java/com/example/$pkgSlug/MainActivity.kt" to "package com.example.$pkgSlug\n\nimport android.os.Bundle\nimport androidx.activity.ComponentActivity\nimport androidx.activity.compose.setContent\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.material3.*\nimport androidx.compose.runtime.*\nimport androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.unit.dp\nimport com.example.$pkgSlug.model.AppItem\n\nclass MainActivity : ComponentActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        setContent {\n            MaterialTheme {\n                Surface(modifier = Modifier.fillMaxSize()) {\n                    MainScreen(appTitle = \"$cleanName\")\n                }\n            }\n        }\n    }\n}\n\n@Composable\nfun MainScreen(appTitle: String) {\n    var items by remember {\n        mutableStateOf(listOf(AppItem(\"1\", \"Primeiro módulo pronto\", true)))\n    }\n    Column(\n        modifier = Modifier.fillMaxSize().padding(24.dp),\n        verticalArrangement = Arrangement.Center,\n        horizontalAlignment = Alignment.CenterHorizontally\n    ) {\n        Text(text = appTitle, style = MaterialTheme.typography.headlineMedium)\n        Spacer(modifier = Modifier.height(8.dp))\n        Text(text = \"Itens ativos: \${items.size}\", style = MaterialTheme.typography.bodyMedium)\n    }\n}\n"
            )
            ProjectTemplate.NODE_TS_API -> mapOf(
                "README.md" to "# $cleanName\n\nServiço Node.js + TypeScript modular criado no **DevAgent Studio**.\n\n```bash\nnpm install\nnpm run dev\n```\n",
                ".gitignore" to "node_modules/\ndist/\n.env\n*.log\n",
                "package.json" to "{\n  \"name\": \"$pkgSlug\",\n  \"version\": \"1.0.0\",\n  \"private\": true,\n  \"scripts\": {\n    \"dev\": \"tsx watch src/index.ts\",\n    \"build\": \"tsc\",\n    \"start\": \"node dist/index.js\"\n  },\n  \"dependencies\": {},\n  \"devDependencies\": {\n    \"typescript\": \"^5.6.0\",\n    \"tsx\": \"^4.19.0\"\n  }\n}\n",
                "tsconfig.json" to "{\n  \"compilerOptions\": {\n    \"target\": \"ES2022\",\n    \"module\": \"NodeNext\",\n    \"moduleResolution\": \"NodeNext\",\n    \"outDir\": \"dist\",\n    \"rootDir\": \"src\",\n    \"strict\": true,\n    \"esModuleInterop\": true\n  },\n  \"include\": [\"src/**/*\"]\n}\n",
                "src/types.ts" to "export interface HealthStatus {\n  service: string;\n  status: 'ok' | 'degraded';\n  timestamp: string;\n}\n",
                "src/index.ts" to "import { HealthStatus } from './types.js';\n\nexport function getHealth(): HealthStatus {\n  return {\n    service: '$cleanName',\n    status: 'ok',\n    timestamp: new Date().toISOString(),\n  };\n}\n\nconsole.log('Serviço iniciado:', getHealth());\n"
            )
            ProjectTemplate.PYTHON_APP -> mapOf(
                "README.md" to "# $cleanName\n\nProjeto Python modular criado no **DevAgent Studio**.\n\n```bash\npython main.py\n```\n",
                ".gitignore" to "__pycache__/\n.venv/\n*.pyc\n.env\n",
                "requirements.txt" to "requests>=2.31.0\n",
                "src/__init__.py" to "",
                "src/core.py" to "from dataclasses import dataclass\n\n\n@dataclass\nclass AppConfig:\n    name: str = \"$cleanName\"\n    version: str = \"1.0.0\"\n\n\ndef run_pipeline(config: AppConfig) -> str:\n    return f\"{config.name} v{config.version} pronto para execução!\"\n",
                "main.py" to "from src.core import AppConfig, run_pipeline\n\n\ndef main() -> None:\n    config = AppConfig()\n    print(run_pipeline(config))\n\n\nif __name__ == \"__main__\":\n    main()\n"
            )
            ProjectTemplate.WEB_APP -> mapOf(
                "README.md" to "# $cleanName\n\nWeb App autocontido (`index.html` único e completo com CSS e JS integrados) criado no **DevAgent Studio**.\n",
                ".gitignore" to "node_modules/\ndist/\n.DS_Store\n",
                "index.html" to "<!DOCTYPE html>\n<html lang=\"pt-BR\">\n<head>\n  <meta charset=\"UTF-8\" />\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n  <title>$cleanName</title>\n  <style>\n    :root { --bg: #0d1117; --card: #161b22; --border: #30363d; --accent: #38bdf8; --text: #f0f6fc; --muted: #8b949e; }\n    * { box-sizing: border-box; }\n    body { margin: 0; font-family: system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); display: flex; min-height: 100vh; align-items: center; justify-content: center; padding: 1.5rem; }\n    .container { background: var(--card); padding: 2.25rem; border-radius: 16px; border: 1px solid var(--border); max-width: 480px; width: 100%; text-align: center; box-shadow: 0 16px 40px rgba(0,0,0,0.35); }\n    .badge { display: inline-block; padding: 0.25rem 0.7rem; border-radius: 999px; background: rgba(56,189,248,0.15); color: var(--accent); font-size: 0.75rem; font-weight: 600; margin-bottom: 0.75rem; }\n    p { color: var(--muted); line-height: 1.5; }\n    button { background: var(--accent); color: #090d12; border: none; padding: 0.7rem 1.4rem; border-radius: 10px; font-weight: 700; cursor: pointer; transition: transform 0.15s ease; }\n    button:active { transform: scale(0.97); }\n  </style>\n</head>\n<body>\n  <main class=\"container\">\n    <span class=\"badge\">DevAgent Studio</span>\n    <h1>$cleanName</h1>\n    <p id=\"status\">Arquivo index.html completo pronto para o botão ▶ Play (Preview).</p>\n    <div class=\"actions\">\n      <button id=\"actionBtn\">Testar Interação</button>\n    </div>\n  </main>\n  <script>\n    const state = { clicks: 0 };\n    document.getElementById('actionBtn')?.addEventListener('click', () => {\n      state.clicks += 1;\n      const status = document.getElementById('status');\n      if (status) {\n        status.textContent = 'Interação executada (' + state.clicks + 'x) com sucesso!';\n      }\n    });\n  </script>\n</body>\n</html>\n"
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
