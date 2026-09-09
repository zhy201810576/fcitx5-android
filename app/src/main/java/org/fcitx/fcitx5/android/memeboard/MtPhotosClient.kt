/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/* ================= 数据模型（统一用 kotlinx.serialization 反序列化） ================= */

@Serializable
data class MtFile(
    @SerialName("id") val id: Long = 0,
    // 实际响应 md5 字段名可能为 "MD5" 或 "md5"，两者兼容
    @SerialName("MD5") @JsonNames("md5") val md5: String = "",
    @SerialName("fileName") val fileName: String = "",
)

@Serializable
data class MtGallery(
    val id: Long = 0,
    val name: String = "",
    val forUpload: Boolean = false,
)

@Serializable
data class MtTag(
    val id: Long = 0,
    val name: String = "",
    @SerialName("is_hide") val isHide: Boolean = false,
)

/** 文件列表响应：{list:[...]} 或 {result:[...]}。
 *  result 有两种形态：searchCLIPV2 返回扁平文件数组 [{id,fileName,MD5}]；
 *  searchV2/tagFiles 返回分组数组 [{day,list|files}]。故 MtResultGroup 同时兼容两者。 */
@Serializable
data class MtFileListResponse(
    val list: List<MtFile>? = null,
    val result: List<MtResultGroup>? = null,
)

@Serializable
data class MtResultGroup(
    @SerialName("id") val id: Long = 0,
    @SerialName("MD5") @JsonNames("md5") val md5: String = "",
    @SerialName("fileName") val fileName: String = "",
    val list: List<MtFile>? = null,
    val files: List<MtFile>? = null,
) {
    /** 该元素本身是否为一个文件（扁平结构），而非分组容器。 */
    val isFile: Boolean get() = md5.isNotEmpty() || fileName.isNotEmpty() || id != 0L
}

@Serializable
data class MtClipStatus(val active: Boolean = false)

@Serializable
data class MtAuthCodeResponse(@SerialName("auth_code") val authCode: String = "")

@Serializable
data class MtAuthCodeRequest(@SerialName("api_key") val apiKey: String)

@Serializable
data class MtSearchRequest(
    val searchType: String? = null,
    @SerialName("searchKey") val searchKey: String,
    val count: Int? = null,
    val galleryIds: List<Long>? = null,
    val tags: List<Long>? = null,
    val tagUseOr: Boolean? = null,
)

@Serializable
data class MtSearchTipsRequest(
    val key: String,
)

/** 搜索提示项：name 为人物名/标签名，type 为 people / tag / llm_tag。id 可能是数字或字符串，故忽略。 */
@Serializable
data class MtSearchTip(
    val name: String = "",
    val type: String = "",
)

/**
 * MT Photos 开放 API 客户端。
 * JSON API 使用 x-api-key header 鉴权；图片 URL 使用 auth_code query 鉴权。
 *
 * 所有实例共享同一个 [httpClient]（复用连接池/线程池）与同一份 auth_code 缓存，
 * 因此反复 `new MtPhotosClient(...)` 也不会重复换 code 或重建连接。
 */
class MtPhotosClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    // explicitNulls=false：序列化请求体时跳过 null 字段（可选参数不输出）
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun isConfigured() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    /** 获取 auth_code（缓存 20 小时，24 小时有效）。缓存按 server+apiKey 共享，跨实例命中。 */
    suspend fun getAuthCode(): String = withContext(Dispatchers.IO) {
        val key = cacheKey()
        readCachedAuth(key)?.let { return@withContext it }
        synchronized(authLock) {
            readCachedAuth(key)?.let { return@withContext it }
            val body = json.encodeToString(MtAuthCodeRequest(apiKey))
            val req = Request.Builder()
                .url(root() + "/auth/auth_code")
                .post(body.toRequestBody(jsonMedia))
                .build()
            val code = json.decodeFromString<MtAuthCodeResponse>(executeBody(req)).authCode
            if (code.isEmpty()) throw IllegalStateException("auth_code is empty, check API key")
            cachedAuthCode = code
            cachedAuthAt = System.currentTimeMillis()
            cachedAuthKey = key
            code
        }
    }

    /** 主动失效当前 auth_code 缓存（下次获取时重新换取）。 */
    fun invalidateAuthCode() {
        synchronized(authLock) {
            cachedAuthCode = null
            cachedAuthAt = 0L
            cachedAuthKey = ""
        }
    }

    private fun cacheKey() = baseUrl + "\u0000" + apiKey

    /** 用户可访问的图库列表 */
    suspend fun galleryList(): List<MtGallery> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(root() + "/gateway/myGalleryList")
            .header("x-api-key", apiKey)
            .get().build()
        val result = json.decodeFromString<List<MtGallery>>(executeBody(req))
        Timber.d("MemeBoard galleryList -> %d", result.size)
        result
    }

    /** 图库内文件（时间线）。galleryIds 为空表示全部图库。 */
    suspend fun galleryFiles(galleryIds: List<Long>): List<MtFile> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(root()).append("/gateway/filesInTimelineV2")
            if (galleryIds.isNotEmpty()) {
                append("?galleryIds=").append(galleryIds.joinToString("_"))
            }
        }
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .get().build()
        val files = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("request failed: HTTP " + resp.code)
            val body = resp.body?.string() ?: return@withContext emptyList()
            // 响应为 {result:[{day,addr,list:[...]}]} 分组结构，复用统一解析
            parseFilesResponse(json.decodeFromString<MtFileListResponse>(body))
        }
        Timber.d("MemeBoard galleryFiles %s -> %d", url.removePrefix(root()), files.size)
        files
    }

    /** 标签列表（按 id 降序，排除隐藏标签） */
    suspend fun tagList(): List<MtTag> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(root() + "/api-tag?type=all")
            .header("x-api-key", apiKey)
            .get().build()
        val result = json.decodeFromString<List<MtTag>>(executeBody(req))
            .filter { !it.isHide }
            .sortedByDescending { it.id }
        Timber.d("MemeBoard tagList -> %d", result.size)
        result
    }

    /** 标签关联的文件列表；galleryIds 传 "all" 表示全部图库，或逗号分隔的图库 id */
    suspend fun tagFiles(tagId: Long, galleryIds: String): List<MtFile> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(root()).append("/api-tag/files/").append(tagId)
                if (galleryIds.isNotBlank()) {
                    append("?galleryIds=").append(galleryIds)
                }
            }
            val req = Request.Builder()
                .url(url)
                .header("x-api-key", apiKey)
                .get().build()
            val files = parseFilesResponse(json.decodeFromString(executeBody(req)))
            Timber.d("MemeBoard tagFiles tag=%s -> %d", tagId, files.size)
            files
        }

    /** CLIP 搜索是否可用 */
    suspend fun clipStatus(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(root() + "/gateway/CLIP_status")
            .header("x-api-key", apiKey)
            .post("".toRequestBody(null))
            .build()
        val active = json.decodeFromString<MtClipStatus>(executeBody(req)).active
        Timber.d("MemeBoard clipStatus -> %s", active)
        active
    }

    /**
     * 搜索：直接走 searchCLIPV2 以文搜图。
     *
     * 官方前端并不调用 CLIP_status，而是统一调 searchCLIPV2——服务端会按配置自动选择
     * 向量来源：配了「大模型智能识别(LLM)」就用 LLM 描述向量，否则用 CLIP 向量。
     * 若先查 CLIP_status，在「配了 LLM 但没配独立 CLIP 服务」时会误判不可用、降级成
     * 普通综合搜索（searchV2），用户会感觉「没走 LLM 搜索」。故这里去掉前置门控，
     * 只在 searchCLIPV2 真正失败/空结果时回退 searchV2。
     */
    suspend fun search(query: String, galleryIds: List<Long>, tags: List<Long>): List<MtFile> =
        withContext(Dispatchers.IO) {
            try {
                val files = clipSearch(query, galleryIds, tags)
                if (files.isNotEmpty()) return@withContext files
            } catch (e: Exception) {
                Timber.w(e, "MemeBoard clipSearch failed, fallback to searchV2")
            }
            searchV2(query, galleryIds, tags)
        }

    private suspend fun clipSearch(query: String, galleryIds: List<Long>, tags: List<Long>): List<MtFile> =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                MtSearchRequest(
                    // 关键：前端调 searchCLIPV2 时带 searchType="CLIP"，服务端据此走
                    // CLIP/LLM 语义向量搜索；缺这个字段会退回普通匹配，导致「哈哈大笑」等
                    // 语义词搜不到（只能命中文件名/OCR 等固定词汇）。
                    searchType = "CLIP",
                    searchKey = query,
                    count = 200,
                    galleryIds = galleryIds.ifEmpty { null },
                    tags = tags.ifEmpty { null },
                    tagUseOr = if (tags.isNotEmpty()) true else null,
                )
            )
            val req = Request.Builder()
                .url(root() + "/gateway/searchCLIPV2")
                .header("x-api-key", apiKey)
                .post(body.toRequestBody(jsonMedia))
                .build()
            val files = parseFilesResponse(json.decodeFromString(executeBody(req)))
            Timber.d("MemeBoard clipSearch '%s' -> %d", query, files.size)
            files
        }

    private suspend fun searchV2(query: String, galleryIds: List<Long>, tags: List<Long>): List<MtFile> =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                MtSearchRequest(
                    searchType = "v1", // 综合搜索
                    searchKey = query,
                    galleryIds = galleryIds.ifEmpty { null },
                    tags = tags.ifEmpty { null },
                    tagUseOr = if (tags.isNotEmpty()) true else null,
                )
            )
            val req = Request.Builder()
                .url(root() + "/gateway/searchV2")
                .header("x-api-key", apiKey)
                .post(body.toRequestBody(jsonMedia))
                .build()
            val files = parseFilesResponse(json.decodeFromString(executeBody(req)))
            Timber.d("MemeBoard searchV2 '%s' -> %d", query, files.size)
            files
        }

    /** 搜索提示（人物/标签建议）。 */
    suspend fun searchTips(key: String): List<MtSearchTip> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(MtSearchTipsRequest(key))
        val req = Request.Builder()
            .url(root() + "/gateway/searchTips")
            .header("x-api-key", apiKey)
            .post(body.toRequestBody(jsonMedia))
            .build()
        val result = json.decodeFromString<List<MtSearchTip>>(executeBody(req))
        Timber.d("MemeBoard searchTips '%s' -> %d", key, result.size)
        result
    }

    /** 缩略图 URL（展示用） */
    fun thumbUrl(md5: String, authCode: String): String =
        root() + "/gateway/h220/" + md5 + "?auth_code=" + enc(authCode)

    /** 预览图 URL（发送用） */
    fun previewUrl(id: Long, md5: String, authCode: String): String =
        root() + "/gateway/file/" + id + "/" + md5 + "?type=proxy&auth_code=" + enc(authCode)

    /**
     * 下载预览图到 destDir，返回本地文件。
     * 以 md5 为内容指纹命名并命中缓存：同一张图已下载过则直接复用，不再重复下载。
     */
    suspend fun downloadPreview(id: Long, md5: String, destDir: File, fileName: String): File =
        withContext(Dispatchers.IO) {
            destDir.mkdirs()
            val hit = destDir.listFiles { f ->
                f.isFile && f.length() > 0 && f.name.startsWith(md5 + ".")
            }?.firstOrNull()
            if (hit != null) return@withContext hit

            val code = getAuthCode()
            val req = Request.Builder().url(previewUrl(id, md5, code)).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("download failed: HTTP " + resp.code)
                val bytes = resp.body?.bytes() ?: throw IllegalStateException("empty download response")
                val ext = guessExt(resp.header("Content-Type"), fileName)
                val f = File(destDir, md5 + "." + ext)
                f.writeBytes(bytes)
                f
            }
        }

    /** 执行请求并返回响应体字符串；非 2xx 抛异常。 */
    private fun executeBody(req: Request): String {
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Timber.w("MemeBoard request failed: %s -> HTTP %d", req.url, resp.code)
                throw IllegalStateException("request failed: HTTP " + resp.code)
            }
            val s = resp.body?.string() ?: ""
            Timber.d("MemeBoard resp %s => %s", req.url.encodedPath, s.take(300))
            return s
        }
    }

    /** 解析文件列表响应：{list:[...]} 优先；否则 result 既可能是扁平文件数组
     *  （searchCLIPV2），也可能是分组数组（searchV2/tagFiles）；过滤 md5 为空的项。 */
    private fun parseFilesResponse(resp: MtFileListResponse): List<MtFile> {
        val files = resp.list
            ?: resp.result?.flatMap { group ->
                if (group.isFile) listOf(MtFile(group.id, group.md5, group.fileName))
                else group.list ?: group.files ?: emptyList()
            }
            ?: emptyList()
        return files.filter { it.md5.isNotEmpty() }
    }

    private fun root() = baseUrl.trimEnd('/')

    companion object {
        private const val AUTH_TTL_MS = 20 * 3600_000L

        /** 全局共享的 OkHttpClient：复用连接池与线程池，避免频繁重建。 */
        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val resp = chain.proceed(chain.request())
                    // 图片 URL 鉴权码过期（401/403）时失效缓存，下次请求自动换新 code 自愈
                    if ((resp.code == 401 || resp.code == 403) &&
                        chain.request().url.queryParameter("auth_code") != null
                    ) {
                        synchronized(authLock) {
                            cachedAuthCode = null
                            cachedAuthAt = 0L
                            cachedAuthKey = ""
                        }
                    }
                    resp
                }
                .build()
        }

        private val authLock = Any()

        @Volatile private var cachedAuthCode: String? = null
        @Volatile private var cachedAuthAt: Long = 0L
        @Volatile private var cachedAuthKey: String = ""

        private fun readCachedAuth(key: String): String? {
            val code = cachedAuthCode
            if (code != null && cachedAuthKey == key &&
                System.currentTimeMillis() - cachedAuthAt < AUTH_TTL_MS
            ) return code
            return null
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private val KNOWN_EXTS = setOf("png", "webp", "gif", "heic", "heif", "jpg", "jpeg", "bmp")

        private fun guessExt(contentType: String?, fileName: String): String {
            // 文件名扩展名最可靠：proxy 接口的 Content-Type 可能不准，
            // 若猜错扩展名会导致后续 MIME 类型错误，QQ 等 App 会拒收
            val fromName = fileName.substringAfterLast('.', "").lowercase()
            if (fromName in KNOWN_EXTS) return fromName
            return when {
                contentType == null -> "jpg"
                contentType.contains("png") -> "png"
                contentType.contains("webp") -> "webp"
                contentType.contains("gif") -> "gif"
                contentType.contains("heic") || contentType.contains("heif") -> "heic"
                else -> "jpg"
            }
        }
    }
}

fun guessMime(ext: String): String = when (ext.lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    else -> "image/jpeg"
}
