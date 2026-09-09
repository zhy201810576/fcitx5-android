/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 语音模型在线更新管理器（ModelScope 源）。
 *
 * 模型使用 sherpa-onnx 导出的 SenseVoice int8 ONNX，托管在 ModelScope（魔搭）：
 *  - 仓库：`poloniumrock/SenseVoiceSmallOnnx`（sherpa-onnx 格式的 model.int8.onnx + tokens.txt）；
 *  - 下载走 ModelScope resolve 直链（国内直连，无需代理）；
 *  - 下载后用 ModelScope 返回的 SHA-256 做完整性校验（动态值，不会因模型更新而过期）；
 *  - 「检测更新」用 ModelScope API 查仓库文件列表，比对 model.int8.onnx 的 SHA-256。
 *
 * 出厂模型内置在 asr 插件 APK（同一份 sherpa-onnx 模型），下载的新模型存到
 * `externalFilesDir/asr-models/`，加载时优先用已下载模型、回退出厂模型。
 */
object AsrModelManager {

    private const val TAG = "AsrModelMgr"

    // ModelScope 源（sherpa-onnx 格式的 SenseVoiceSmall int8 onnx）
    private const val MS_REPO = "poloniumrock/SenseVoiceSmallOnnx"
    private const val MS_BASE = "https://modelscope.cn/models/$MS_REPO"
    private const val MS_FILES_API =
        "https://modelscope.cn/api/v1/models/$MS_REPO/repo/files?Revision=master&Root="

    private const val MODEL_DIR_NAME = "asr-models"
    private const val MODEL_FILE = "model.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"
    private const val VERSION_FILE = "model.sha256"

    /** 出厂模型（插件 assets 内置）的 SHA-256，与 ModelScope 上当前版本一致 */
    const val BUILTIN_MODEL_SHA256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"

    /** 远程模型文件信息（sha256 用于下载后校验） */
    data class RemoteModel(val modelSha256: String, val tokensSha256: String)

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class MsRepoFiles(val Data: MsRepoData = MsRepoData())

    @Serializable
    private data class MsRepoData(val Files: List<MsRepoFile> = emptyList())

    @Serializable
    private data class MsRepoFile(
        @SerialName("Name") val name: String = "",
        @SerialName("Sha256") val sha256: String = "",
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    /** 已下载模型所在目录（应用专属外部目录，无需存储权限） */
    fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, MODEL_DIR_NAME)

    /** 本地已下载模型的 SHA-256；null 表示未下载（用出厂模型） */
    fun localModelSha256(context: Context): String? {
        val f = File(modelDir(context), VERSION_FILE)
        return runCatching { f.readText().trim().takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** 是否存在完整可用的已下载模型 */
    fun hasDownloadedModel(context: Context): Boolean {
        val dir = modelDir(context)
        return localModelSha256(context) != null &&
            File(dir, MODEL_FILE).exists() &&
            File(dir, TOKENS_FILE).exists()
    }

    /**
     * 从 ModelScope 查询远程模型文件信息（model/tokens 的 sha256）。
     * 失败（无网络等）返回 null。
     */
    suspend fun checkUpdate(): RemoteModel? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(MS_FILES_API).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val files = json.decodeFromString<MsRepoFiles>(body).Data.Files
                val modelSha = files.firstOrNull { it.name == MODEL_FILE }?.sha256 ?: return@withContext null
                val tokensSha = files.firstOrNull { it.name == TOKENS_FILE }?.sha256 ?: return@withContext null
                RemoteModel(modelSha, tokensSha)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkUpdate failed", t)
            null
        }
    }

    /**
     * 下载并安装模型（用 [remote] 提供的 sha256 校验）。成功后返回 true。
     * [onProgress] 回报 0f~1f（仅模型下载阶段，tokens 很小忽略）。
     */
    suspend fun download(
        context: Context,
        remote: RemoteModel,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = modelDir(context)
            dir.mkdirs()
            val tmpDir = File(dir, ".tmp").apply { mkdirs() }

            val modelTmp = File(tmpDir, MODEL_FILE)
            if (!downloadFile("$MS_BASE/resolve/master/$MODEL_FILE", modelTmp, onProgress)) {
                return@withContext false
            }
            if (!verifySha256(modelTmp, remote.modelSha256)) {
                Log.w(TAG, "model sha256 mismatch")
                modelTmp.delete()
                return@withContext false
            }

            val tokensTmp = File(tmpDir, TOKENS_FILE)
            if (!downloadFile("$MS_BASE/resolve/master/$TOKENS_FILE", tokensTmp) { }) {
                return@withContext false
            }
            if (!verifySha256(tokensTmp, remote.tokensSha256)) {
                Log.w(TAG, "tokens sha256 mismatch")
                tokensTmp.delete()
                return@withContext false
            }

            // 原子替换
            val modelFinal = File(dir, MODEL_FILE)
            val tokensFinal = File(dir, TOKENS_FILE)
            if (modelFinal.exists()) modelFinal.delete()
            if (tokensFinal.exists()) tokensFinal.delete()
            if (!modelTmp.renameTo(modelFinal) || !tokensTmp.renameTo(tokensFinal)) {
                Log.w(TAG, "rename failed")
                return@withContext false
            }
            File(dir, VERSION_FILE).writeText(remote.modelSha256)
            Log.i(TAG, "model installed")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "download failed", t)
            false
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit): Boolean {
        return try {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                val total = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = input.read(buf)
                        var totalRead = 0L
                        while (read != -1) {
                            out.write(buf, 0, read)
                            totalRead += read
                            if (total > 0) onProgress((totalRead.toFloat() / total).coerceIn(0f, 1f))
                            read = input.read(buf)
                        }
                    }
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "downloadFile failed", t)
            false
        }
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return runCatching {
            sha256(file).equals(expected.trim(), ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read = input.read(buf)
            while (read != -1) {
                md.update(buf, 0, read)
                read = input.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
