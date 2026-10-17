package com.example.data.api

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Read-only GitHub REST client.
 *
 * Tokens are supplied by the caller and are never persisted by this class.
 * This client deliberately does not implement writes or command execution.
 */
class GitHubRepositoryClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    data class RepositorySnapshot(
        val fullName: String,
        val defaultBranch: String,
        val files: List<RemoteFile>
    )

    data class RemoteFile(
        val path: String,
        val language: String,
        val content: String
    )

    suspend fun readRepository(
        repositoryInput: String,
        token: String = "",
        maxFiles: Int = DEFAULT_MAX_FILES
    ): Result<RepositorySnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val fullName = normalizeRepositoryName(repositoryInput)
            val authToken = token.trim()

            val repository = getJson(
                "https://api.github.com/repos/$fullName",
                authToken
            )
            val canonicalName = repository.getString("full_name")
            val branch = repository.getString("default_branch")
            val tree = getJson(
                "https://api.github.com/repos/$canonicalName/git/trees/${Uri.encode(branch)}?recursive=1",
                authToken
            )

            if (tree.optBoolean("truncated", false)) {
                throw IOException("O repositório é grande demais para esta leitura inicial. A árvore retornada pelo GitHub foi truncada.")
            }

            val entries = tree.optJSONArray("tree")
                ?: throw IOException("O GitHub não retornou a árvore de arquivos.")
            val candidates = mutableListOf<String>()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                if (entry.optString("type") != "blob") continue
                val path = entry.optString("path")
                if (path.isBlank() || isIgnoredPath(path) || !isTextCandidate(path)) continue
                candidates += path
            }

            val selectedPaths = candidates.take(maxFiles.coerceIn(1, DEFAULT_MAX_FILES))
            val files = mutableListOf<RemoteFile>()
            var totalCharacters = 0

            for (path in selectedPaths) {
                if (totalCharacters >= MAX_TOTAL_CHARACTERS) break
                val encodedPath = path.split("/").joinToString("/") { Uri.encode(it) }
                val response = getJson(
                    "https://api.github.com/repos/$canonicalName/contents/$encodedPath?ref=${Uri.encode(branch)}",
                    authToken
                )
                if (response.optString("type") != "file") continue
                if (response.optLong("size", Long.MAX_VALUE) > MAX_FILE_BYTES) continue
                val encoded = response.optString("content").replace("\n", "")
                if (encoded.isBlank()) continue

                val decoded = try {
                    String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (decoded.indexOf('\u0000') >= 0) continue

                val remaining = MAX_TOTAL_CHARACTERS - totalCharacters
                val safeContent = decoded.take(remaining)
                files += RemoteFile(
                    path = path,
                    language = languageFor(path),
                    content = safeContent
                )
                totalCharacters += safeContent.length
            }

            RepositorySnapshot(
                fullName = canonicalName,
                defaultBranch = branch,
                files = files
            )
        }
    }

    private fun getJson(url: String, token: String): JSONObject {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
        if (token.isNotBlank()) requestBuilder.header("Authorization", "Bearer $token")

        http.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    JSONObject(body).optString("message").take(240)
                } catch (_: Exception) {
                    response.message
                }
                val hint = when (response.code) {
                    401 -> " Token inválido ou expirado."
                    403 -> " Acesso negado ou limite de requisições atingido. Repositórios privados exigem um token com acesso de leitura."
                    404 -> " Repositório ou arquivo não encontrado; confirme o nome e as permissões."
                    else -> ""
                }
                throw IOException("GitHub HTTP ${response.code}: $detail.$hint")
            }
            return JSONObject(body)
        }
    }

    private fun normalizeRepositoryName(input: String): String {
        val value = input.trim().removeSuffix("/")
        val normalized = when {
            value.startsWith("https://github.com/") -> value.removePrefix("https://github.com/")
            value.startsWith("http://github.com/") -> value.removePrefix("http://github.com/")
            else -> value
        }.substringBefore(".git").trim('/')

        if (!normalized.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            throw IllegalArgumentException("Informe um repositório no formato proprietário/repositório ou uma URL do GitHub.")
        }
        return normalized
    }

    private fun isIgnoredPath(path: String): Boolean {
        val parts = path.lowercase().split("/")
        val name = parts.lastOrNull().orEmpty()
        val sensitiveName = name == ".env" ||
            name.startsWith(".env.") ||
            name.contains("secret") ||
            name.contains("credential") ||
            name.contains("private_key") ||
            name.endsWith(".jks") ||
            name.endsWith(".keystore") ||
            name.endsWith(".pem") ||
            name.endsWith(".p12") ||
            name.endsWith(".pfx") ||
            name.endsWith(".key") ||
            name == "google-services.json"
        return sensitiveName ||
            parts.any { it in setOf(".git", ".gradle", "node_modules", "build", "dist", "vendor") } ||
            name in setOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "gradle.lockfile") ||
            name.endsWith(".min.js") || name.endsWith(".map")
    }

    private fun isTextCandidate(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        val extensions = setOf(
            "kt", "kts", "java", "xml", "json", "md", "txt", "yml", "yaml",
            "toml", "properties", "gradle", "py", "js", "jsx", "ts", "tsx",
            "html", "css", "scss", "sql", "sh", "bash", "c", "h", "cpp", "rs",
            "go", "swift", "dart", "env.example", "gitignore"
        )
        return name.contains(".") && extensions.any { name.endsWith(".$it") } ||
            name in setOf("dockerfile", "makefile", ".gitignore", ".dockerignore")
    }

    private fun languageFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "json" -> "json"
        "md" -> "markdown"
        "yml", "yaml" -> "yaml"
        "toml" -> "toml"
        "properties" -> "properties"
        "py" -> "python"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "html" -> "html"
        "css", "scss" -> "css"
        "sql" -> "sql"
        "sh", "bash" -> "shell"
        "rs" -> "rust"
        "go" -> "go"
        else -> "text"
    }

    private companion object {
        const val DEFAULT_MAX_FILES = 30
        const val MAX_FILE_BYTES = 80_000L
        const val MAX_TOTAL_CHARACTERS = 400_000
    }
}
