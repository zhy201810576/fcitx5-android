/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.SeekBarPreference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

/**
 * 手写输入体验设置：手写识别语言（中文 / 日文）、笔迹粗细、移动灵敏度。
 */
class HandwritingSettingsFragment : PaddingPreferenceFragment() {

    private lateinit var languagePref: ListPreference
    private lateinit var strokeWidthPref: SeekBarPreference
    private lateinit var sensitivityPref: SeekBarPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()

        languagePref = ListPreference(ctx).apply {
            key = "handwriting_language"
            isPersistent = false
            setTitle(R.string.handwriting_language)
            setSummary(R.string.handwriting_language_summary)
            entries = arrayOf(
                ctx.getString(R.string.handwriting_language_zh),
                ctx.getString(R.string.handwriting_language_ja),
            )
            entryValues = arrayOf("zh", "ja")
            setOnPreferenceChangeListener { _, newValue ->
                MemeBoardPrefs.setHandwritingLanguage(ctx, newValue as String)
                refreshSummaries()
                true
            }
        }

        strokeWidthPref = SeekBarPreference(ctx).apply {
            key = "handwriting_stroke_width"
            isPersistent = false
            setTitle(R.string.handwriting_stroke_width)
            min = 6
            max = 24
            seekBarIncrement = 1
            setOnPreferenceChangeListener { _, newValue ->
                MemeBoardPrefs.setStrokeWidth(ctx, newValue as Int)
                refreshSummaries()
                true
            }
        }

        sensitivityPref = SeekBarPreference(ctx).apply {
            key = "handwriting_sensitivity"
            isPersistent = false
            setTitle(R.string.handwriting_sensitivity)
            min = 1
            max = 5
            seekBarIncrement = 1
            setOnPreferenceChangeListener { _, newValue ->
                MemeBoardPrefs.setSensitivity(ctx, newValue as Int)
                refreshSummaries()
                true
            }
        }

        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.handwriting_category) {
                addPreference(languagePref)
                addPreference(strokeWidthPref)
                addPreference(sensitivityPref)
            }
        }

        refreshSummaries()
    }

    private fun refreshSummaries() {
        val ctx = requireContext()
        languagePref.value = MemeBoardPrefs.getHandwritingLanguage(ctx)
        languagePref.summary = languagePref.entry
        strokeWidthPref.value = MemeBoardPrefs.getStrokeWidth(ctx)
        strokeWidthPref.summary = "${MemeBoardPrefs.getStrokeWidth(ctx)} dp"
        sensitivityPref.value = MemeBoardPrefs.getSensitivity(ctx)
        sensitivityPref.summary = "${MemeBoardPrefs.getSensitivity(ctx)} / 5"
    }
}
