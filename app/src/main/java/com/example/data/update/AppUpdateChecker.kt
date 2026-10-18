package com.example.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val assetApiUrl: String = "",
    val releaseNotes: String,
    val htmlUrl: String
)

/**
 * Auto-update via GitHub Releases (HROSONE/Dev).
 * Funciona tanto com repositório público quanto privado, valida a integridade do APK (cabeçalho ZIP/PK)
 * e separa o cache por versionCode para nunca reutilizar um APK antigo.
 */
class AppUpdateChecker(
    private val owner: String = "HROSONE",
    private val repo: String = "Dev",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    suspend fun checkForUpdate(githubToken: String = ""): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = githubToken.trim()
            val url = "https://api.github.com/repos/$owner/$repo/releases/latest"

            // Tenta com token (se houver); se o token estiver inválido num repo público (401), tenta sem token
            var response = executeReleaseRequest(url, cleanToken)
            if ((response.code == 401 || response.code == 403) && cleanToken.isNotBlank()) {
                response.close()
                response = executeReleaseRequest(url, "")
            }

            response.use { resp ->
                if (resp.code == 404) {
                    if (cleanToken.isBlank()) {
                        throw Exception("Release não encontrada ou repositório privado (HTTP 404).")
                    }
                    return@runCatching null
                }
                if (!resp.isSuccessful) {
                    throw Exception("GitHub releases HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v")
                val notes = json.optString("body").orEmpty()
                val htmlUrl = json.optString("html_url")
                val assets = json.optJSONArray("assets") ?: return@runCatching null

                var browserDownloadUrl = ""
                var assetApiUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name").lowercase()
                    if (name.endsWith(".apk")) {
                        browserDownloadUrl = asset.optString("browser_download_url")
                        assetApiUrl = asset.optString("url")
                        if (name.contains("devagent") || name.contains("debug")) break
                    }
                }
                if (browserDownloadUrl.isBlank() && assetApiUrl.isBlank()) return@runCatching null

                val remoteCode = parseVersionCode(tag, notes)
                if (remoteCode <= BuildConfig.VERSION_CODE) return@runCatching null

                AppUpdateInfo(
                    tagName = tag,
                    versionName = tag.ifBlank { "$remoteCode" },
                    versionCode = remoteCode,
                    apkUrl = browserDownloadUrl.ifBlank { assetApiUrl },
                    assetApiUrl = assetApiUrl,
                    releaseNotes = notes.take(2_000),
                    htmlUrl = htmlUrl
                )
            }
        }
    }

    private fun executeReleaseRequest(url: String, token: String): okhttp3.Response {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "DevAgent/${BuildConfig.VERSION_NAME}")
            .get()
        if (token.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }
        return http.newCall(reqBuilder.build()).execute()
    }

    fun getCachedApkFile(context: Context, versionCode: Int): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "devagent-update-$versionCode.apk")
    }

    fun isValidApkFile(file: File): Boolean {
        if (!file.isFile || file.length() < 100_000L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        assetApiUrl: String = "",
        githubToken: String = "",
        versionCode: Int = 0,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = githubToken.trim()
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Remove APKs antigos de outras versões para evitar conflito
            dir.listFiles()?.forEach { f ->
                if (f.name != "devagent-update-$versionCode.apk") {
                    runCatching { f.delete() }
                }
            }
            val out = File(dir, if (versionCode > 0) "devagent-update-$versionCode.apk" else "devagent-update.apk")

            // 1. Tenta baixar direto da URL pública (browser_download_url) se o repo for público
            if (url.isNotBlank() && !url.contains("api.github.com")) {
                val pubReq = Request.Builder()
                    .url(url)
                    .header("User-Agent", "DevAgent/${BuildConfig.VERSION_NAME}")
                    .get()
                    .build()
                http.newCall(pubReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        writeBodyToFile(resp, out, onProgress)
                        if (isValidApkFile(out)) return@runCatching out
                    }
                }
            }

            // 2. Fallback para a API de Asset do GitHub (funciona em repositórios privados e públicos)
            val apiTarget = assetApiUrl.ifBlank { url }
            val reqBuilder = Request.Builder()
                .url(apiTarget)
                .header("User-Agent", "DevAgent/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/octet-stream")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get()

            if (cleanToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $cleanToken")
            }

            http.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Download HTTP ${response.code}")
                writeBodyToFile(response, out, onProgress)
                if (!isValidApkFile(out)) {
                    out.delete()
                    throw Exception("O arquivo baixado não é um APK válido.")
                }
                out
            }
        }
    }

    private fun writeBodyToFile(
        response: okhttp3.Response,
        out: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)?
    ) {
        val body = response.body ?: throw Exception("Corpo vazio")
        val total = body.contentLength()
        body.byteStream().use { input ->
            out.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var downloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress?.invoke(downloaded, total)
                }
                output.flush()
            }
        }
    }

    fun installApk(context: Context, apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun installPermissionSettingsIntent(context: Context): Intent {
        return Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun parseVersionCode(tag: String, releaseBody: String): Int {
        Regex("versionCode\s*[=:]\s*(\d+)", RegexOption.IGNORE_CASE)
            .find(releaseBody)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it }

        val parts = tag.filter { it.isDigit() || it == '.' }
            .split('.')
            .mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return 0
        if (parts.size >= 3) return parts.last()
        if (parts.size == 1) return parts[0]
        return parts[0] * 100 + parts[1]
    }
}
