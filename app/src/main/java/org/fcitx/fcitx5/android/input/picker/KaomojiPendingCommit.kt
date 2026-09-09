/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * 颜文字搜索页到输入法服务的文本提交桥。
 * 搜索 Activity 设置 pending text → 输入法 onAttached 取出并 commit → 清空。
 */
object KaomojiPendingCommit {
    @Volatile
    var text: String? = null
}