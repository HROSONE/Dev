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
 * Suporta tanto repositórios públicos quanto privados (autenticando via Bearer token
 * e baixando o asset com Accept: application/octet-stream pela API do GitHub).
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
            val reqBuilder = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "DevAgent/${BuildConfig.VERSION_NAME}")
                .get()

            if (cleanToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $cleanToken")
            }

            http.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 404) {
                    if (cleanToken.isBlank()) {
                        throw Exception("Repositório $owner/$repo privado (HTTP 404). Salve seu token GitHub em Configurações para buscar atualizações.")
                    }
                    return@runCatching null
                }
                if (!response.isSuccessful) {
                    throw Exception("GitHub releases HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
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

    suspend fun downloadApk(
        context: Context,
        url: String,
        assetApiUrl: String = "",
        githubToken: String = "",
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = githubToken.trim()
            // Em repositórios privados, browser_download_url falha com 404 sem sessão web;
            // usamos a URL de asset da API com Accept: application/octet-stream + Bearer token.
            val targetUrl = if (cleanToken.isNotBlank() && assetApiUrl.isNotBlank()) {
                assetApiUrl
            } else {
                url
            }
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "DevAgent/${BuildConfig.VERSION_NAME}")
                .get()

            if (cleanToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $cleanToken")
                reqBuilder.header("Accept", "application/octet-stream")
                reqBuilder.header("X-GitHub-Api-Version", "2022-11-28")
            }

            http.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Download HTTP ${response.code}")
                val body = response.body ?: throw Exception("Corpo vazio")
                val total = body.contentLength()
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "devagent-update.apk")
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress?.invoke(downloaded, total)
                        }
                    }
                }
                out
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
        Regex("versionCode\\s*[=:]\\s*(\\d+)", RegexOption.IGNORE_CASE)
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
