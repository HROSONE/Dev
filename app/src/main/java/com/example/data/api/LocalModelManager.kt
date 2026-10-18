package com.example.data.api

import android.content.Context
import com.example.data.model.QwenModelVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gerenciador de download e armazenamento dos modelos GGUF do Qwen 2.5 Coder no celular.
 * Suporta retomada de download (HTTP Range) para arquivos grandes.
 */
class LocalModelManager(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val modelsDir = File(context.applicationContext.filesDir, "models").apply { mkdirs() }

    fun getModelFile(variant: QwenModelVariant): File =
        File(modelsDir, variant.fileName)

    fun isModelDownloaded(variant: QwenModelVariant): Boolean {
        val file = getModelFile(variant)
        return file.isFile && file.length() > 10_000_000L
    }

    fun getDownloadedSizeBytes(variant: QwenModelVariant): Long {
        val file = getModelFile(variant)
        val part = File(modelsDir, "${variant.fileName}.part")
        return when {
            file.isFile -> file.length()
            part.isFile -> part.length()
            else -> 0L
        }
    }

    fun deleteModel(variant: QwenModelVariant): Boolean {
        val file = getModelFile(variant)
        val part = File(modelsDir, "${variant.fileName}.part")
        val a = if (file.exists()) file.delete() else true
        val b = if (part.exists()) part.delete() else true
        return a && b
    }

    suspend fun downloadModel(
        variant: QwenModelVariant,
        hfToken: String = "",
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!modelsDir.exists()) modelsDir.mkdirs()
            val finalFile = getModelFile(variant)
            val partFile = File(modelsDir, "${variant.fileName}.part")

            if (finalFile.isFile && finalFile.length() > 10_000_000L) {
                onProgress(finalFile.length(), finalFile.length())
                return@runCatching finalFile
            }

            val existingBytes = if (partFile.isFile) partFile.length() else 0L
            val requestBuilder = Request.Builder()
                .url(variant.downloadUrl)
                .header("User-Agent", "DevAgent-Android/1.2")
                .get()

            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }
            if (hfToken.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
            }

            http.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IllegalStateException("Falha no download HTTP ${response.code}: ${response.message}")
                }
                val isResume = response.code == 206 && existingBytes > 0L
                val body = response.body ?: throw IllegalStateException("Resposta vazia do servidor.")
                val contentLength = body.contentLength()
                val totalBytes = when {
                    isResume && contentLength > 0 -> existingBytes + contentLength
                    contentLength > 0 -> contentLength
                    else -> variant.approxBytes
                }

                var downloaded = if (isResume) existingBytes else 0L
                body.byteStream().use { input ->
                    FileOutputStream(partFile, isResume).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReported = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReported >= 256 * 1024 || downloaded >= totalBytes) {
                                lastReported = downloaded
                                onProgress(downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(finalFile.length(), finalFile.length())
                finalFile
            }
        }
    }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format("%.2f GB", mb / 1024.0)
            } else {
                String.format("%.0f MB", mb)
            }
        }
    }
}
