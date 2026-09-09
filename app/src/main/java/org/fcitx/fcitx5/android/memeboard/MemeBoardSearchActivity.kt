/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.clipboard.SpacesItemDecoration
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.inputMethodManager
import splitties.dimensions.dp
import java.io.File

/**
 * 表情包搜索页：顶部锚定的紧凑对话框（普通 Activity，能正常唤起键盘）。
 * 实时（防抖）搜索 + 结果网格；点击结果复制到剪贴板后返回，长按结果走系统分享。
 */
class MemeBoardSearchActivity : Activity() {

    private val scope = MainScope()
    private val repository by lazy { MemeBoardRepository(this) }
    private val theme get() = ThemeManager.activeTheme

    private val imageLoader by lazy {
        ImageLoader.Builder(this)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = MtPhotosClient.httpClient))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("memeboard_images").toOkioPath())
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    private lateinit var editText: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var tipListView: ListView
    private lateinit var adapter: MemeBoardAdapter

    private var searchJob: Job? = null
    private var tipJob: Job? = null
    private var lastAutoRetryAt = 0L

    /** 打开搜索页时传入的目标 App 包名（用于识别 QQ）。 */
    private var targetPackage: String? = null

    /** 点击联想项后回填文本会再次触发 TextWatcher，用此标志抑制一次自动搜索。 */
    private var suppressAutoSearch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)

        // 顶部锚定的紧凑对话框，不占满全屏，也不会顶到状态栏
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        }
        // 系统 Dialog 主题默认圆角偏大，这里用小圆角背景替代
        window.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(theme.keyBackgroundColor)
                cornerRadius = dp(8).toFloat()
            }
        )

        editText = EditText(this).apply {
            hint = getString(R.string.memeboard_search_llm_hint)
            setText(MemeBoardPrefs.getLastQuery(this@MemeBoardSearchActivity))
            isSingleLine = true
            textSize = 15f
            setTextColor(theme.keyTextColor)
            setHintTextColor(theme.altKeyTextColor)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSelection(text.length)
        }
        val cancelButton = TextView(this).apply {
            text = getString(R.string.memeboard_search_cancel)
            textSize = 14f
            setTextColor(theme.accentKeyBackgroundColor)
            setPadding(dp(8), 0, dp(8), 0)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(theme.barColor)
            addView(cancelButton, LinearLayout.LayoutParams(dp(56), dp(40)))
            addView(editText, LinearLayout.LayoutParams(0, dp(44), 1f))
        }

        emptyText = TextView(this).apply {
            setText(R.string.memeboard_no_results)
            setTextColor(theme.altKeyTextColor)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MemeBoardSearchActivity, 4)
            addItemDecoration(SpacesItemDecoration(dp(2)))
        }
        adapter = MemeBoardAdapter(
            repository.client(),
            theme,
            imageLoader,
            ::onResultClick,
            ::onResultLongClick,
            ::onImageError,
        )
        recyclerView.adapter = adapter

        // 结果区用 FrameLayout 承载网格 + 居中空态提示（居中，键盘遮不住）
        val contentArea = FrameLayout(this).apply {
            addView(recyclerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(emptyText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        // 联想下拉（人物 · 标签 · 智能），叠加在结果区上方，默认隐藏
        tipListView = ListView(this).apply {
            visibility = View.GONE
            setBackgroundColor(theme.popupBackgroundColor)
            divider = null
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // 背景由 window 的小圆角 GradientDrawable 提供，根布局保持透明以免盖住圆角
                addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(tipListView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(contentArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        )

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                if (suppressAutoSearch) {
                    suppressAutoSearch = false
                    return
                }
                searchJob?.cancel()
                tipJob?.cancel()
                if (q.trim().isEmpty()) {
                    hideTips()
                    showEmpty(null)
                    return
                }
                searchJob = scope.launch {
                    delay(300)
                    performSearch(q)
                }
                tipJob = scope.launch {
                    delay(300)
                    loadTips(q)
                }
            }
        })
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                performSearch(editText.text.toString())
                hideKeyboard()
                true
            } else false
        }

        editText.requestFocus()
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        // 预填了上次搜索词，直接出结果
        if (editText.text.toString().trim().isNotEmpty()) performSearch(editText.text.toString())
    }

    private fun performSearch(query: String) {
        val q = query.trim()
        MemeBoardPrefs.setLastQuery(this, q)
        hideTips()
        if (q.isEmpty()) {
            showEmpty(null)
            return
        }
        scope.launch {
            val files = runCatching {
                if (!repository.hasConfig()) emptyList()
                else {
                    adapter.authCode = repository.getAuthCode()
                    repository.search(q)
                }
            }.getOrDefault(emptyList())
            adapter.submit(files)
            showEmpty(if (files.isEmpty()) R.string.memeboard_no_results else null)
        }
    }

    /** 拉取搜索联想（人物/标签/智能），展示在下拉列表。 */
    private suspend fun loadTips(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            hideTips()
            return
        }
        val tips = runCatching {
            if (!repository.hasConfig()) emptyList()
            else repository.searchTips(q)
        }.getOrDefault(emptyList())
        if (tips.isEmpty()) {
            hideTips()
            return
        }
        val labels = tips.map { tip ->
            when (tip.type) {
                "people" -> getString(R.string.memeboard_tip_people, tip.name)
                "tag" -> getString(R.string.memeboard_tip_tag, tip.name)
                "llm_tag" -> getString(R.string.memeboard_tip_llm_tag, tip.name)
                else -> tip.name
            }
        }
        tipListView.adapter = object : ArrayAdapter<String>(
            this@MemeBoardSearchActivity,
            android.R.layout.simple_list_item_1,
            labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(theme.keyTextColor)
                v.setPadding(this@MemeBoardSearchActivity.dp(16), this@MemeBoardSearchActivity.dp(10), this@MemeBoardSearchActivity.dp(16), this@MemeBoardSearchActivity.dp(10))
                return v
            }
        }
        tipListView.setOnItemClickListener { _, _, position, _ ->
            val tip = tips.getOrNull(position) ?: return@setOnItemClickListener
            suppressAutoSearch = true
            editText.setText(tip.name)
            editText.setSelection(tip.name.length)
            tipJob?.cancel()
            performSearch(tip.name)
        }
        tipListView.visibility = View.VISIBLE
    }

    private fun hideTips() {
        tipListView.visibility = View.GONE
    }

    private fun showEmpty(messageRes: Int?) {
        emptyText.visibility = if (messageRes == null) View.GONE else View.VISIBLE
        messageRes?.let { emptyText.setText(it) }
    }

    private fun onResultClick(file: MtFile) {
        repository.touchRecent(file)
        scope.launch {
            runCatching { download(file) }
                .onSuccess { (f, mime) ->
                    if (isQQTarget()) {
                        // QQ 环境：点结果也强制走分享（复制剪贴板在 QQ 里贴不上）
                        Toast.makeText(applicationContext, R.string.memeboard_qq_share_hint, Toast.LENGTH_SHORT).show()
                        share(f, mime)
                    } else {
                        copyImageToClipboard(f, mime)
                        Toast.makeText(applicationContext, R.string.memeboard_copied, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                .onFailure { toastSendFailed() }
        }
    }

    /** 搜索页当前目标是否为 QQ（含 TIM）。 */
    private fun isQQTarget(): Boolean {
        val pkg = targetPackage ?: return false
        return pkg == "com.tencent.mobileqq" || pkg == "com.tencent.tim"
    }

    private fun onResultLongClick(file: MtFile) {
        repository.touchRecent(file)
        scope.launch {
            runCatching { download(file) }
                .onSuccess { (f, mime) -> share(f, mime) }
                .onFailure { toastSendFailed() }
        }
    }

    private suspend fun download(file: MtFile): Pair<File, String> {
        val local = repository.download(file)
        return local to guessMime(local.extension)
    }

    private fun copyImageToClipboard(file: File, mimeType: String) {
        val uri = MemeBoardMediaStore.publish(this, file, mimeType)
            ?: FileProvider.getUriForFile(this, "${packageName}.memeboard.fileprovider", file)
        val clip = ClipData(ClipDescription("MemeBoard", arrayOf(mimeType)), ClipData.Item(uri))
        clipboardManager.setPrimaryClip(clip)
    }

    private fun share(file: File, mimeType: String) {
        val uri = MemeBoardMediaStore.publish(this, file, mimeType)
            ?: FileProvider.getUriForFile(this, "${packageName}.memeboard.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, mimeType, uri)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun onImageError() {
        val now = System.currentTimeMillis()
        if (now - lastAutoRetryAt < 30_000L) return
        lastAutoRetryAt = now
        scope.launch {
            repository.invalidateAuthCode()
            adapter.authCode = repository.getAuthCode()
            val q = editText.text.toString().trim()
            if (q.isNotEmpty()) performSearch(q)
        }
    }

    private fun toastSendFailed() {
        Toast.makeText(applicationContext, R.string.memeboard_send_failed, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        inputMethodManager.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "memeboard.target_package"
    }
}
