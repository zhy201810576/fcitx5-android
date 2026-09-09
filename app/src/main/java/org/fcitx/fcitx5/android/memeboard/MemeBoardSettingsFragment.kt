/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast

class MemeBoardSettingsFragment : PaddingPreferenceFragment() {

    private lateinit var serverPref: EditTextPreference
    private lateinit var apiKeyPref: EditTextPreference
    private lateinit var statusPref: Preference
    private lateinit var galleryPref: Preference
    private lateinit var tagPref: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()

        serverPref = EditTextPreference(ctx).apply {
            key = "memeboard_server_url"
            isPersistent = false
            setTitle(R.string.memeboard_server_url)
            dialogTitle = ctx.getString(R.string.memeboard_server_url)
            setOnBindEditTextListener { edit ->
                edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                edit.hint = "https://your.mtmt.tech"
                edit.setSelection(edit.text.length)
            }
            setOnPreferenceChangeListener { _, newValue ->
                MemeBoardPrefs.setServerUrl(ctx, newValue as? String ?: "")
                refreshSummaries()
                true
            }
        }

        apiKeyPref = EditTextPreference(ctx).apply {
            key = "memeboard_api_key"
            isPersistent = false
            setTitle(R.string.memeboard_api_key)
            dialogTitle = ctx.getString(R.string.memeboard_api_key)
            setOnBindEditTextListener { edit ->
                edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                edit.setSelection(edit.text.length)
            }
            setOnPreferenceChangeListener { _, newValue ->
                MemeBoardPrefs.setApiKey(ctx, newValue as? String ?: "")
                refreshSummaries()
                true
            }
        }

        statusPref = Preference(ctx).apply {
            isSelectable = false
        }

        galleryPref = Preference(ctx).apply {
            setTitle(R.string.memeboard_galleries)
            setOnPreferenceClickListener {
                showGalleryPicker()
                true
            }
        }

        tagPref = Preference(ctx).apply {
            setTitle(R.string.memeboard_tags)
            setOnPreferenceClickListener {
                showTagPicker()
                true
            }
        }

        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.memeboard_gallery) {
                addPreference(statusPref)
                addPreference(serverPref)
                addPreference(apiKeyPref)
                addPreference(galleryPref)
                addPreference(tagPref)
            }
            addPreference(R.string.memeboard_test_connection) {
                testConnection()
            }
        }

        refreshSummaries()
    }

    private fun refreshSummaries() {
        val ctx = requireContext()
        val server = MemeBoardPrefs.getServerUrl(ctx)
        val apiKey = MemeBoardPrefs.getApiKey(ctx)
        val configured = server.isNotBlank() && apiKey.isNotBlank()

        serverPref.text = server
        serverPref.summary = server.ifBlank { ctx.getString(R.string.memeboard_not_configured_short) }

        apiKeyPref.text = apiKey
        apiKeyPref.summary =
            if (apiKey.isNotBlank()) ctx.getString(R.string.memeboard_configured)
            else ctx.getString(R.string.memeboard_not_configured_short)

        statusPref.setTitle(R.string.memeboard_config_status)
        statusPref.summary =
            if (configured) ctx.getString(R.string.memeboard_configured)
            else ctx.getString(R.string.memeboard_not_configured_short)

        val galleryIds = MemeBoardPrefs.getGalleryIds(ctx)
        galleryPref.summary = if (galleryIds.isEmpty()) {
            ctx.getString(R.string.memeboard_all_galleries)
        } else {
            ctx.getString(R.string.memeboard_selected_count, galleryIds.size)
        }

        val tagIds = MemeBoardPrefs.getTagIds(ctx)
        tagPref.summary = if (tagIds.isEmpty()) {
            ctx.getString(R.string.memeboard_none_selected)
        } else {
            ctx.getString(R.string.memeboard_selected_count, tagIds.size)
        }
    }

    private fun showGalleryPicker() {
        val ctx = requireContext()
        if (!MemeBoardPrefs.hasConfig(ctx)) {
            ctx.toast(R.string.memeboard_not_configured_short)
            return
        }
        val client = MtPhotosClient(MemeBoardPrefs.getServerUrl(ctx), MemeBoardPrefs.getApiKey(ctx))
        lifecycleScope.launch {
            val result = runCatching { client.galleryList() }
            result.onSuccess { galleries ->
                val names = galleries.map { it.name }.toTypedArray()
                val checked = BooleanArray(galleries.size) { i ->
                    galleries[i].id in MemeBoardPrefs.getGalleryIds(ctx)
                }
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.memeboard_galleries)
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val ids = galleries.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                        MemeBoardPrefs.setGalleryIds(ctx, ids)
                        refreshSummaries()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            result.onFailure { ctx.toast(it) }
        }
    }

    private fun showTagPicker() {
        val ctx = requireContext()
        if (!MemeBoardPrefs.hasConfig(ctx)) {
            ctx.toast(R.string.memeboard_not_configured_short)
            return
        }
        val client = MtPhotosClient(MemeBoardPrefs.getServerUrl(ctx), MemeBoardPrefs.getApiKey(ctx))
        lifecycleScope.launch {
            val result = runCatching { client.tagList() }
            result.onSuccess { tags ->
                val names = tags.map { it.name }.toTypedArray()
                val checked = BooleanArray(tags.size) { i ->
                    tags[i].id in MemeBoardPrefs.getTagIds(ctx)
                }
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.memeboard_tags)
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val ids = tags.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                        MemeBoardPrefs.setTagIds(ctx, ids)
                        refreshSummaries()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            result.onFailure { ctx.toast(it) }
        }
    }

    private fun testConnection() {
        val ctx = requireContext()
        if (!MemeBoardPrefs.hasConfig(ctx)) {
            ctx.toast(R.string.memeboard_not_configured_short)
            return
        }
        val client = MtPhotosClient(MemeBoardPrefs.getServerUrl(ctx), MemeBoardPrefs.getApiKey(ctx))
        lifecycleScope.withLoadingDialog(ctx) {
            val result = runCatching { client.getAuthCode() }
            result.onSuccess {
                ctx.toast(R.string.memeboard_test_success)
            }.onFailure {
                ctx.toast(ctx.getString(R.string.memeboard_test_failed, it.message ?: ""), Toast.LENGTH_LONG)
            }
        }
    }
}
