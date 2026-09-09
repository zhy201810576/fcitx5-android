/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配置存储：服务端地址（明文）+ API Key（Android Keystore AES/GCM 加密），
 * 以及图库/标签多选、上次搜索词、收藏与最近使用。
 */
object MemeBoardPrefs {
    private const val NAME = "memeboard_prefs"
    private const val KEY_SERVER = "server_url"
    private const val KEY_APIKEY_ENC = "api_key_enc"
    private const val KEY_GALLERY_IDS = "gallery_ids"
    private const val KEY_TAG_IDS = "tag_ids"
    private const val KEY_LAST_QUERY = "last_query"
    private const val KEY_STROKE_WIDTH = "handwriting_stroke_width"
    private const val KEY_SENSITIVITY = "handwriting_sensitivity"
    private const val KEY_HANDWRITING_LANGUAGE = "handwriting_language"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_RECENT = "recent"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "memeboard_api_key"

    private const val MAX_RECENT = 50

    private val fileJson = Json { ignoreUnknownKeys = true }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getServerUrl(ctx: Context): String = sp(ctx).getString(KEY_SERVER, "") ?: ""
    fun setServerUrl(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_SERVER, v.trim()).apply()

    fun getApiKey(ctx: Context): String {
        val enc = sp(ctx).getString(KEY_APIKEY_ENC, "") ?: return ""
        if (enc.isEmpty()) return ""
        return try { decrypt(enc) } catch (e: Exception) { "" }
    }

    fun setApiKey(ctx: Context, v: String) {
        val value = v.trim()
        if (value.isEmpty()) {
            sp(ctx).edit().remove(KEY_APIKEY_ENC).apply()
        } else {
            sp(ctx).edit().putString(KEY_APIKEY_ENC, encrypt(value)).apply()
        }
    }

    fun hasConfig(ctx: Context) = getServerUrl(ctx).isNotBlank() && getApiKey(ctx).isNotBlank()

    /* ========== 图库 / 标签多选 ========== */

    fun getGalleryIds(ctx: Context): Set<Long> = getIds(ctx, KEY_GALLERY_IDS)
    fun setGalleryIds(ctx: Context, ids: Set<Long>) = setIds(ctx, KEY_GALLERY_IDS, ids)

    fun getTagIds(ctx: Context): Set<Long> = getIds(ctx, KEY_TAG_IDS)
    fun setTagIds(ctx: Context, ids: Set<Long>) = setIds(ctx, KEY_TAG_IDS, ids)

    private fun getIds(ctx: Context, key: String): Set<Long> =
        sp(ctx).getStringSet(key, emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

    private fun setIds(ctx: Context, key: String, ids: Set<Long>) {
        sp(ctx).edit().putStringSet(key, ids.map { it.toString() }.toSet()).apply()
    }

    /* ========== 上次搜索词 ========== */

    fun getLastQuery(ctx: Context): String = sp(ctx).getString(KEY_LAST_QUERY, "") ?: ""
    fun setLastQuery(ctx: Context, q: String) = sp(ctx).edit().putString(KEY_LAST_QUERY, q.trim()).apply()

    /* ========== 手写体验 ========== */

    /** 笔迹最大宽度（dp），范围 6..24，默认 14 */
    fun getStrokeWidth(ctx: Context): Int = sp(ctx).getInt(KEY_STROKE_WIDTH, 14)
    fun setStrokeWidth(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_STROKE_WIDTH, v).apply()

    /** 笔迹移动灵敏度档位（1..5），数值越大越灵敏，默认 3 */
    fun getSensitivity(ctx: Context): Int = sp(ctx).getInt(KEY_SENSITIVITY, 3)
    fun setSensitivity(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_SENSITIVITY, v).apply()

    /** 手写识别语言："zh"（中文，默认）或 "ja"（日语），走 ML Kit 数字墨水识别。 */
    fun getHandwritingLanguage(ctx: Context): String =
        sp(ctx).getString(KEY_HANDWRITING_LANGUAGE, "zh") ?: "zh"

    fun setHandwritingLanguage(ctx: Context, v: String) =
        sp(ctx).edit().putString(KEY_HANDWRITING_LANGUAGE, v).apply()

    /* ========== 收藏（md5 为内容指纹） ========== */

    fun getFavorites(ctx: Context): List<MtFile> = decodeList(ctx, KEY_FAVORITES)

    fun isFavorite(ctx: Context, md5: String): Boolean =
        getFavorites(ctx).any { it.md5 == md5 }

    /** 切换收藏状态，返回切换后「是否已收藏」。 */
    fun toggleFavorite(ctx: Context, file: MtFile): Boolean {
        val list = getFavorites(ctx).toMutableList()
        val idx = list.indexOfFirst { it.md5 == file.md5 }
        return if (idx >= 0) {
            list.removeAt(idx)
            encodeList(ctx, KEY_FAVORITES, list)
            false
        } else {
            list.add(0, file)
            encodeList(ctx, KEY_FAVORITES, list)
            true
        }
    }

    /* ========== 最近使用（最新在前，去重 + 限量） ========== */

    fun getRecent(ctx: Context): List<MtFile> = decodeList(ctx, KEY_RECENT)

    fun touchRecent(ctx: Context, file: MtFile) {
        val list = getRecent(ctx).toMutableList()
        list.removeAll { it.md5 == file.md5 }
        list.add(0, file)
        encodeList(ctx, KEY_RECENT, list.take(MAX_RECENT))
    }

    /* ========== 列表 JSON 存取 ========== */

    private fun decodeList(ctx: Context, key: String): List<MtFile> = runCatching {
        val s = sp(ctx).getString(key, null) ?: return emptyList()
        fileJson.decodeFromString<List<MtFile>>(s)
    }.getOrDefault(emptyList())

    private fun encodeList(ctx: Context, key: String, list: List<MtFile>) {
        sp(ctx).edit().putString(key, fileJson.encodeToString(list)).apply()
    }

    /* ========== Keystore AES/GCM ========== */

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
    }

    private fun decrypt(data: String): String {
        val parts = data.split(":")
        if (parts.size != 2) return ""
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val enc = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(enc), Charsets.UTF_8)
    }
}
