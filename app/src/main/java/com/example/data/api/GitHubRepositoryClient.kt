package com.example.data.api

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub REST client.
 *
 * Tokens are supplied by the caller and are never persisted by this class.
 * Writes require an explicit token with contents:write scope.
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

    data class CommitResult(
        val path: String,
        val commitSha: String,
        val htmlUrl: String,
        val message: String
    )

    suspend fun readRepository(
        repositoryInput: String,
        token: String = "",
        maxFiles: Int = DEFAULT_MAX_FILES
    ): Result<RepositorySnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(REPOSITORY_READ_TIMEOUT_MS) {
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

                candidates.sortWith(
                    compareBy<String> { filePriority(it) }
                        .thenBy { it.count { character -> character == '/' } }
                        .thenBy { it }
                )
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
    }

    /**
     * Creates or updates a single file on the default branch.
     * Requires a token with contents:write. Never persists the token.
     */
    suspend fun commitFile(
        repositoryFullName: String,
        path: String,
        content: String,
        commitMessage: String,
        token: String,
        branch: String? = null
    ): Result<CommitResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (token.isBlank()) {
                throw IOException("É necessário um token GitHub com permissão de escrita (contents:write) para fazer commit.")
            }
            val fullName = normalizeRepositoryName(repositoryFullName)
            val authToken = token.trim()

            val repository = getJson("https://api.github.com/repos/$fullName", authToken)
            val targetBranch = branch ?: repository.getString("default_branch")
            val encodedPath = path.split("/").joinToString("/") { Uri.encode(it) }

            var existingSha: String? = null
            try {
                val existing = getJson(
                    "https://api.github.com/repos/$fullName/contents/$encodedPath?ref=${Uri.encode(targetBranch)}",
                    authToken
                )
                existingSha = existing.optString("sha").takeIf { it.isNotBlank() }
            } catch (_: IOException) {
                // Arquivo novo
            }

            val payload = JSONObject().apply {
                put("message", commitMessage.take(200).ifBlank { "DevAgent: atualiza $path" })
                put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                put("branch", targetBranch)
                if (existingSha != null) put("sha", existingSha)
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.github.com/repos/$fullName/contents/$encodedPath")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer $authToken")
                .put(body)
                .build()

            http.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = try {
                        JSONObject(responseBody).optString("message").take(240)
                    } catch (_: Exception) {
                        response.message
                    }
                    val hint = when (response.code) {
                        401 -> " Token inválido."
                        403 -> " Token sem permissão de escrita ou branch protegida."
                        409 -> " Conflito de SHA; o arquivo mudou no remoto."
                        422 -> " Payload inválido ou arquivo muito grande."
                        else -> ""
                    }
                    throw IOException("GitHub commit HTTP ${response.code}: $detail.$hint")
                }
                val json = JSONObject(responseBody)
                val commit = json.optJSONObject("commit")
                CommitResult(
                    path = path,
                    commitSha = commit?.optString("sha").orEmpty(),
                    htmlUrl = commit?.optString("html_url").orEmpty().ifBlank {
                        json.optJSONObject("content")?.optString("html_url").orEmpty()
                    },
                    message = commitMessage
                )
            }
        }
    }

    /**
     * Publishes multiple workspace files as one atomic Git commit.
     * The branch ref is updated only after every blob, tree and commit was created.
     */
    suspend fun commitFiles(
        repositoryFullName: String,
        files: Map<String, String>,
        commitMessage: String,
        token: String,
        branch: String? = null
    ): Result<CommitResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (token.isBlank()) throw IOException("É necessário um token GitHub com permissão contents:write.")
            if (files.isEmpty()) throw IllegalArgumentException("Nenhum arquivo para enviar.")
            if (files.size > MAX_BATCH_FILES) throw IllegalArgumentException("Limite de $MAX_BATCH_FILES arquivos por commit.")
            files.keys.forEach { path ->
                if (path.isBlank() || path.startsWith("/") || path.split("/").any { it == ".." || it.isBlank() }) {
                    throw IllegalArgumentException("Caminho de arquivo inválido: $path")
                }
            }

            val fullName = normalizeRepositoryName(repositoryFullName)
            val authToken = token.trim()
            val repository = getJson("https://api.github.com/repos/$fullName", authToken)
            val targetBranch = branch ?: repository.getString("default_branch")
            val ref = getJson("https://api.github.com/repos/$fullName/git/ref/heads/${Uri.encode(targetBranch)}", authToken)
            val parentSha = ref.getJSONObject("object").getString("sha")
            val parentCommit = getJson("https://api.github.com/repos/$fullName/git/commits/$parentSha", authToken)
            val baseTreeSha = parentCommit.getJSONObject("tree").getString("sha")

            val treeEntries = org.json.JSONArray()
            files.toSortedMap().forEach { (path, content) ->
                val blobPayload = JSONObject().apply {
                    put("content", content)
                    put("encoding", "utf-8")
                }
                val blob = requestJson(
                    "POST",
                    "https://api.github.com/repos/$fullName/git/blobs",
                    authToken,
                    blobPayload
                )
                treeEntries.put(JSONObject().apply {
                    put("path", path)
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", blob.getString("sha"))
                })
            }

            val tree = requestJson(
                "POST",
                "https://api.github.com/repos/$fullName/git/trees",
                authToken,
                JSONObject().put("base_tree", baseTreeSha).put("tree", treeEntries)
            )
            val message = commitMessage.take(200).ifBlank { "DevAgent: atualiza ${files.size} arquivos" }
            val commit = requestJson(
                "POST",
                "https://api.github.com/repos/$fullName/git/commits",
                authToken,
                JSONObject().put("message", message)
                    .put("tree", tree.getString("sha"))
                    .put("parents", org.json.JSONArray().put(parentSha))
            )
            val newSha = commit.getString("sha")
            requestJson(
                "PATCH",
                "https://api.github.com/repos/$fullName/git/refs/heads/${Uri.encode(targetBranch)}",
                authToken,
                JSONObject().put("sha", newSha).put("force", false)
            )
            CommitResult(
                path = files.keys.sorted().joinToString(", "),
                commitSha = newSha,
                htmlUrl = commit.optString("html_url"),
                message = message
            )
        }
    }

    private fun requestJson(method: String, url: String, token: String, payload: JSONObject): JSONObject {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post(body)
            "PATCH" -> builder.patch(body)
            else -> throw IllegalArgumentException("Método HTTP não permitido: $method")
        }
        http.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try { JSONObject(responseBody).optString("message").take(240) }
                catch (_: Exception) { response.message }
                throw IOException("GitHub $method HTTP ${response.code}: $detail")
            }
            return JSONObject(responseBody)
        }
    }

    private fun filePriority(path: String): Int {
        val normalized = path.lowercase()
        val name = normalized.substringAfterLast('/')
        return when {
            name in setOf("readme.md", "agents.md", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties", "package.json", "pyproject.toml", "cargo.toml", "go.mod", "pom.xml") -> 0
            name == "androidmanifest.xml" || normalized.endsWith("/androidmanifest.xml") -> 0
            normalized.contains("/src/main/") || normalized.startsWith("src/main/") || normalized.contains("/app/src/main/") -> 1
            normalized.contains("/src/test/") || normalized.startsWith("src/test/") || normalized.contains("/tests/") -> 2
            normalized.startsWith("docs/") || normalized.contains("/docs/") -> 3
            else -> 4
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
        val safeEnvTemplate = name in setOf(".env.example", ".env.sample", ".env.template")
        val secretDataFile = name in setOf(
            "secrets.json", "credentials.json", "token.json", "api_key.json", "apikey.json",
            "secrets.properties", "credentials.properties", "token.txt", "id_rsa", "id_ed25519",
            ".npmrc", ".pypirc"
        )
        val sensitiveName = name == ".env" ||
            (name.startsWith(".env.") && !safeEnvTemplate) ||
            name == "local.properties" ||
            secretDataFile ||
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
        const val MAX_BATCH_FILES = 50
        const val MAX_TOTAL_CHARACTERS = 400_000
        const val REPOSITORY_READ_TIMEOUT_MS = 90_000L
    }
}
