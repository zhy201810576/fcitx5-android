/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File

/**
 * 透明的分享桥 Activity。
 *
 * 背景：输入法（后台服务）直接 startActivity 拉起系统分享面板会被 HyperOS 的
 * 「后台启动限制 / Device Guard」拦截（日志：Abort background activity starts），
 * 导致 QQ 等收不到分享。这里先拉起一个前台透明 Activity，由它发起分享，
 * 前台 Activity 拉起分享面板 / 目标 App 不受该限制。
 */
class MemeBoardShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            fireShare()
        }
        finish()
    }

    private fun fireShare() {
        val path = intent.getStringExtra(EXTRA_FILE) ?: return
        val mime = intent.getStringExtra(EXTRA_MIME) ?: return
        val file = File(path)
        if (!file.exists()) return
        // 直接用 FileProvider 授权给目标 App（前台 Activity 发起的分享授权是可靠的），
        // 不发布到相册，避免污染 Pictures/MemeBoard。
        val uri = FileProvider.getUriForFile(this, "${packageName}.memeboard.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, mime, uri)
        }
        startActivity(Intent.createChooser(send, null))
    }

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_MIME = "mime"
    }
}
