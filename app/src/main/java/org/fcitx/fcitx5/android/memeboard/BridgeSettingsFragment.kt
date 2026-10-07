/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.link.AsrEngineController
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

/**
 * 语音模型设置页。
 *
 * Paraformer 语音引擎已内嵌输入法进程，模型固定从 asr 插件 APK 的 assets 加载，
 * 不再有运行时在线下载，因此无需跨进程绑定，也无需 HyperOS
 * 自启动 / 省电策略 / 链式启动配置。本页展示：
 *  - 语音模型插件安装状态与版本，及一键跳转插件详情；
 *  - 引擎（模型）加载状态，及手动加载。
 */
class BridgeSettingsFragment : PaddingPreferenceFragment() {

    private val pluginPackage = AsrEngineController.PLUGIN_PACKAGE

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen()
    }

    private fun buildScreen() {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory(R.string.bridge_services_category) {
                isIconSpaceReserved = false
                addPluginCard(this)
            }
            addCategory(R.string.bridge_control_category) {
                isIconSpaceReserved = false
                addEngineCard(this)
            }
        }
    }

    private fun addPluginCard(category: PreferenceCategory) {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pluginPackage, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pluginPackage, 0)
            }
        } catch (_: Throwable) {
            null
        }

        val status = if (info != null) {
            getString(R.string.bridge_installed, info.versionName ?: "")
        } else {
            getString(R.string.bridge_not_installed)
        }

        category.addPreference(R.string.bridge_asr, status)

        if (info != null) {
            category.addPreference(
                R.string.bridge_open_plugin,
                R.string.bridge_plugin_hint
            ) {
                openAppDetails(pluginPackage)
            }
        }
    }

    private fun addEngineCard(category: PreferenceCategory) {
        val ctx = requireContext()

        // 引擎加载状态
        val status = if (AsrEngineController.isReady()) {
            getString(R.string.bridge_model_ready)
        } else {
            getString(R.string.bridge_model_not_ready)
        }
        category.addPreference(R.string.bridge_model_status, status)

        category.addPreference(
            R.string.bridge_load_model,
            R.string.bridge_load_model_hint
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                AsrEngineController.prewarm(ctx)
                lifecycleScope.launch(Dispatchers.Main) { buildScreen() }
            }
        }

        category.addPreference(
            SwitchPreferenceCompat(ctx).apply {
                setTitle(R.string.bridge_asr_rescore)
                setSummary(R.string.bridge_asr_rescore_hint)
                isChecked = MemeBoardPrefs.getAsrRescoreEnabled(ctx)
                setOnPreferenceChangeListener { _, newValue ->
                    MemeBoardPrefs.setAsrRescoreEnabled(ctx, newValue as Boolean)
                    true
                }
            }
        )
    }

    /** 跳转插件应用详情页（用于查看/卸载模型插件） */
    private fun openAppDetails(packageName: String) {
        val ctx = requireContext()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}
