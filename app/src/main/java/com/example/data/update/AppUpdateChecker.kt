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
    val releaseNotes: String,
    val htmlUrl: String
)

/**
 * Consulta a última release pública de HROSONE/Dev e compara com versionCode local.
 * O APK deve ser asset da release com nome terminando em .apk (preferência: app-debug.apk / DevAgent.apk).
 */
class AppUpdateChecker(
    private val owner: String = "HROSONE",
    private val repo: String = "Dev",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    suspend fun checkForUpdate(): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                if (response.code == 404) return@runCatching null
                if (!response.isSuccessful) {
                    throw Exception("GitHub releases HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v")
                val notes = json.optString("body").take(2_000)
                val htmlUrl = json.optString("html_url")
                val assets = json.optJSONArray("assets") ?: return@runCatching null

                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name").lowercase()
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        if (name.contains("debug") || name.contains("devagent")) break
                    }
                }
                if (apkUrl.isBlank()) return@runCatching null

                val remoteCode = parseVersionCode(tag, json.optString("name"))
                if (remoteCode <= BuildConfig.VERSION_CODE) return@runCatching null

                AppUpdateInfo(
                    tagName = tag,
                    versionName = tag,
                    versionCode = remoteCode,
                    apkUrl = apkUrl,
                    releaseNotes = notes,
                    htmlUrl = htmlUrl
                )
            }
        }
    }

    suspend fun downloadApk(context: Context, url: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Download HTTP ${response.code}")
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "devagent-update.apk")
                response.body?.byteStream()?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: throw Exception("Corpo vazio no download")
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

    private fun parseVersionCode(tag: String, releaseName: String): Int {
        // Aceita tags: 1.2.3, v1.2.3, 12, build-12
        val fromName = Regex("versionCode[=:]\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(releaseName)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (fromName != null) return fromName

        val digits = tag.filter { it.isDigit() || it == '.' }
        val parts = digits.split('.').mapNotNull { it.toIntOrNull() }
        return when {
            parts.isEmpty() -> 0
            parts.size == 1 -> parts[0]
            parts.size == 2 -> parts[0] * 100 + parts[1]
            else -> parts[0] * 10_000 + parts[1] * 100 + parts[2]
        }
    }
}
