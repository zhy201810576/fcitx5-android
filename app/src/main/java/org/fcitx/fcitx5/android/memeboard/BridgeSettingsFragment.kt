/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.link.AsrEngineController
import org.fcitx.fcitx5.android.link.AsrModelManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

/**
 * 语音模型设置页。
 *
 * SenseVoice 语音引擎已内嵌输入法进程，模型优先从在线下载目录读取、回退
 * asr 插件 APK 的 assets（出厂模型），因此不再有跨进程绑定，也无需 HyperOS
 * 自启动 / 省电策略 / 链式启动配置。本页展示：
 *  - 语音模型插件安装状态与版本，及一键跳转插件详情；
 *  - 引擎（模型）加载状态、模型版本，及手动加载 / 在线检查更新。
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

        // 模型版本与来源
        val localSha = AsrModelManager.localModelSha256(ctx)
        val versionSummary = if (localSha != null) {
            getString(R.string.bridge_model_version_downloaded)
        } else {
            getString(R.string.bridge_model_version_builtin)
        }
        category.addPreference(R.string.bridge_model_version, versionSummary)

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
            R.string.bridge_check_update,
            R.string.bridge_check_update_hint
        ) {
            checkAndUpdate()
        }
    }

    private fun checkAndUpdate() {
        val ctx = requireContext()
        val progress = ProgressDialog(ctx).apply {
            setMessage(ctx.getString(R.string.bridge_update_checking))
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            val remote = withContext(Dispatchers.IO) { AsrModelManager.checkUpdate() }
            if (remote == null) {
                progress.dismiss()
                toast(ctx, ctx.getString(R.string.bridge_update_failed))
                return@launch
            }
            val localSha = AsrModelManager.localModelSha256(ctx)
            val isLatest = localSha == remote.modelSha256 ||
                (localSha == null && remote.modelSha256 == AsrModelManager.BUILTIN_MODEL_SHA256)
            if (isLatest) {
                progress.dismiss()
                toast(ctx, ctx.getString(R.string.bridge_update_uptodate))
                return@launch
            }
            progress.setMessage(ctx.getString(R.string.bridge_update_downloading, 0))
            val ok = withContext(Dispatchers.IO) {
                AsrModelManager.download(ctx, remote) { p ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        progress.setMessage(ctx.getString(R.string.bridge_update_downloading, (p * 100).toInt()))
                    }
                }
            }
            progress.dismiss()
            if (ok) {
                AsrEngineController.reload(ctx)
                toast(ctx, ctx.getString(R.string.bridge_update_success))
                buildScreen()
            } else {
                toast(ctx, ctx.getString(R.string.bridge_update_failed))
            }
        }
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

    private fun toast(ctx: Context, msg: String) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
