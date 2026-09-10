/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.clipboard.SpacesItemDecoration
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.utils.AppUtil
import splitties.dimensions.dp
import timber.log.Timber
import java.io.File
import java.io.IOException

class MemeBoardWindow : InputWindow.ExtendedInputWindow<MemeBoardWindow>() {

    private enum class ViewMode { BROWSE, FAVORITES, RECENT }

    private val service by manager.inputMethodService()
    private val theme by manager.theme()

    private val repository by lazy { MemeBoardRepository(service) }

    /** 缩略图/预览图磁盘缓存上限 100MB（Coil 默认无磁盘缓存，这里显式开启）。 */
    private val imageLoader by lazy {
        ImageLoader.Builder(service)
            .components {
                // 复用全局 OkHttpClient，让 auth_code 过期自愈拦截器对图片请求同样生效
                add(OkHttpNetworkFetcherFactory(callFactory = MtPhotosClient.httpClient))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(service.cacheDir.resolve("memeboard_images").toOkioPath())
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    /** 标签列表 + 当前选中的标签（单选浏览过滤） */
    private var tags: List<MtTag> = emptyList()
    private var selectedTagId: Long? = null
    private var viewMode = ViewMode.BROWSE

    private var allFiles: List<MtFile> = emptyList()
    private var displayCount = 0
    private var loadingMore = false

    private var showingMenu: Dialog? = null

    /** 当前加载任务：新请求会取消旧任务，避免快速切换时的竞态。 */
    private var loadJob: Job? = null

    /** auth_code 过期自愈节流：两次自动重试至少间隔 30s，防止死循环。 */
    private var lastAutoRetryAt = 0L

    private val adapter by lazy {
        MemeBoardAdapter(repository.client(), theme, imageLoader, ::send, ::showFileMenu, ::onImageError)
    }

    private val ui by lazy {
        MemeBoardUi(service, theme).apply {
            recyclerView.layoutManager = GridLayoutManager(service, 4)
            recyclerView.adapter = adapter
            recyclerView.addItemDecoration(SpacesItemDecoration(service.dp(2)))
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lm = rv.layoutManager as? GridLayoutManager ?: return
                    if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 12) {
                        loadMore()
                    }
                }
            })
            searchButton.setOnClickListener { openSearch() }
            refreshButton.setOnClickListener { reload() }
            settingsButton.setOnClickListener { openConfig() }
            openSettingsButton.setOnClickListener { openConfig() }
        }
    }

    override fun onCreateView(): View = ui.root

    override fun onAttached() {
        openDefault()
    }

    override fun onDetached() {
        showingMenu?.dismiss()
        showingMenu = null
    }

    /* ================= 视图切换（浏览 / 收藏 / 最近） ================= */

    private fun refreshChips() {
        ui.setChips(
            tags = tags,
            selectedTagId = selectedTagId,
            allActive = viewMode == ViewMode.BROWSE && selectedTagId == null,
            favoritesActive = viewMode == ViewMode.FAVORITES,
            recentActive = viewMode == ViewMode.RECENT,
            onAll = ::onAllClick,
            onFavorites = ::onFavoritesClick,
            onRecent = ::onRecentClick,
            onTagClick = ::onTagClick,
        )
    }

    private fun onAllClick() {
        viewMode = ViewMode.BROWSE
        selectedTagId = null
        refreshChips()
        reloadCurrent()
    }

    private fun onFavoritesClick() {
        viewMode = ViewMode.FAVORITES
        selectedTagId = null
        refreshChips()
        showFavorites()
    }

    private fun onRecentClick() {
        viewMode = ViewMode.RECENT
        selectedTagId = null
        refreshChips()
        showRecent()
    }

    private fun onTagClick(tag: MtTag) {
        viewMode = ViewMode.BROWSE
        selectedTagId = if (tag.id == selectedTagId) null else tag.id
        refreshChips()
        reloadCurrent()
    }

    private fun showFavorites() {
        submitFiles(repository.favorites(), R.string.memeboard_no_favorites)
    }

    private fun showRecent() {
        submitFiles(repository.recent(), R.string.memeboard_no_recent)
    }

    /* ================= 加载编排 ================= */

    /** 统一的加载入口：取消旧任务 + loading 态 + 错误分类提示。 */
    private fun launchLoad(block: suspend () -> Unit) {
        loadJob?.cancel()
        ui.showLoading()
        loadJob = service.lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoadFailed(e)
            }
        }
    }

    private fun reload() {
        viewMode = ViewMode.BROWSE
        refreshChips()
        launchLoad {
            if (!repository.hasConfig()) {
                ui.showInstruction()
                return@launchLoad
            }
            adapter.authCode = repository.getAuthCode()
            tags = repository.tagList()
            refreshChips()
            if (selectedTagId == null) loadGalleries() else loadTagFiles()
        }
    }

    /** 每次打开面板默认定位到「全部」：浏览全部图库，不筛标签。 */
    private fun openDefault() {
        viewMode = ViewMode.BROWSE
        selectedTagId = null
        refreshChips()
        launchLoad {
            if (!repository.hasConfig()) {
                ui.showInstruction()
                return@launchLoad
            }
            adapter.authCode = repository.getAuthCode()
            tags = repository.tagList()
            refreshChips()
            loadGalleries()
        }
    }

    private fun reloadCurrent() {
        launchLoad {
            if (!repository.hasConfig()) {
                ui.showInstruction()
                return@launchLoad
            }
            adapter.authCode = repository.getAuthCode()
            if (selectedTagId == null) loadGalleries() else loadTagFiles()
        }
    }

    private suspend fun loadGalleries() {
        submitFiles(repository.loadGalleries())
    }

    private suspend fun loadTagFiles() {
        val tagId = selectedTagId ?: return
        submitFiles(repository.loadTagFiles(tagId))
    }

    private fun submitFiles(files: List<MtFile>, emptyMessageRes: Int = R.string.memeboard_no_results) {
        allFiles = files
        displayCount = minOf(200, files.size)
        adapter.submit(files.take(displayCount))
        ui.showGrid()
        if (files.isEmpty()) {
            Toast.makeText(service, emptyMessageRes, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadMore() {
        if (loadingMore || displayCount >= allFiles.size) return
        loadingMore = true
        val next = allFiles.drop(displayCount).take(200)
        if (next.isNotEmpty()) {
            adapter.append(next)
            displayCount += next.size
        }
        loadingMore = false
    }

    /* ================= 搜索 ================= */

    /**
     * 打开搜索：跳转到独立的搜索页（普通 Activity）。
     * 之前用「悬浮 Dialog + FLAG_ALT_FOCUSABLE_IM」实现，会与输入法菜单栏重叠，
     * 且该 flag 会阻止键盘弹出导致无法输入；独立 Activity 可正常唤起键盘。
     */
    private fun openSearch() {
        service.startActivity(
            Intent(service, MemeBoardSearchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 搜索页是独立 Activity，启动后 IME 会重新绑定到搜索页自己的输入框，
                // 届时 currentTargetPackage() 已变成输入法自身；故在启动这一刻把目标包名传过去，
                // 供搜索页判断是否为 QQ，进而决定点结果走「分享」还是「复制剪贴板」。
                putExtra(MemeBoardSearchActivity.EXTRA_TARGET_PACKAGE, service.currentTargetPackage())
            }
        )
    }

    /* ================= 图片操作菜单（长按） ================= */

    private fun showFileMenu(file: MtFile) {
        val ctx = service
        val isFav = repository.isFavorite(file.md5)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.popupBackgroundColor)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        fun item(label: String, action: () -> Unit) {
            val b = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextColor(theme.keyTextColor)
                setOnClickListener {
                    showingMenu?.dismiss()
                    action()
                }
            }
            content.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(52)))
        }
        item(ctx.getString(R.string.memeboard_send)) { send(file) }
        item(ctx.getString(R.string.memeboard_share)) { share(file) }
        item(ctx.getString(if (isFav) R.string.memeboard_favorite_remove else R.string.memeboard_favorites)) {
            toggleFavorite(file)
        }

        val dialog = Dialog(ctx).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
        }
        dialog.window?.apply {
            val lp = attributes
            lp.token = service.window?.window?.decorView?.windowToken
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            lp.gravity = Gravity.CENTER
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            attributes = lp
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.setOnDismissListener { showingMenu = null }
        showingMenu = dialog
        dialog.show()
    }

    private fun toggleFavorite(file: MtFile) {
        val nowFav = repository.toggleFavorite(file)
        Toast.makeText(
            service,
            if (nowFav) R.string.memeboard_favorited else R.string.memeboard_unfavorited,
            Toast.LENGTH_SHORT
        ).show()
        // 在收藏视图里取消收藏后即时刷新列表
        if (viewMode == ViewMode.FAVORITES) {
            showFavorites()
        }
    }

    /* ================= 发送 / 分享 ================= */

    private fun openConfig() {
        AppUtil.launchMainToMemeBoard(service)
    }

    private fun send(file: MtFile) {
        // QQ 不支持 commitContent 直发，剪贴板粘贴也很容易失效（误操作高频），
        // 因此检测到当前目标是 QQ 时强制走「分享」路径，避免用户误点后发不出去。
        if (isQQTarget()) {
            Toast.makeText(service, R.string.memeboard_qq_share_hint, Toast.LENGTH_SHORT).show()
            share(file)
            return
        }
        repository.touchRecent(file)
        service.lifecycleScope.launch {
            runCatching { download(file) }
                .onSuccess { (f, mime) -> service.commitImage(f, mime) }
                .onFailure { toastSendFailed(it) }
        }
    }

    /** 当前输入目标是否为 QQ（含 TIM，二者均无法 commitContent 直发图片）。 */
    private fun isQQTarget(): Boolean {
        val pkg = service.currentTargetPackage() ?: return false
        return pkg == "com.tencent.mobileqq" || pkg == "com.tencent.tim"
    }

    private fun share(file: MtFile) {
        repository.touchRecent(file)
        service.lifecycleScope.launch {
            runCatching { download(file) }
                .onSuccess { (f, mime) ->
                    // 先拉起前台透明桥 Activity 再发起分享，规避 HyperOS 后台启动限制
                    service.startActivity(
                        Intent(service, MemeBoardShareActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(MemeBoardShareActivity.EXTRA_FILE, f.absolutePath)
                            putExtra(MemeBoardShareActivity.EXTRA_MIME, mime)
                        }
                    )
                }
                .onFailure { toastSendFailed(it) }
        }
    }

    private suspend fun download(file: MtFile): Pair<File, String> {
        val local = repository.download(file)
        val mime = guessMime(local.extension)
        // 发送前统一归一化尺寸/体积（静图 240×240 ≤500KB，动图 240×240 ≤1MB）
        return MemeBoardImageProcessor.prepare(local, mime)
    }

    /* ================= 错误处理与自愈 ================= */

    private fun onImageError() {
        val now = System.currentTimeMillis()
        if (now - lastAutoRetryAt < AUTO_RETRY_INTERVAL_MS) return
        lastAutoRetryAt = now
        service.lifecycleScope.launch {
            if (viewMode != ViewMode.BROWSE) return@launch
            // auth_code 过期会导致缩略图 403；失效缓存并重载，换新 code 后图片即恢复
            repository.invalidateAuthCode()
            reload()
        }
    }

    private fun describeError(e: Throwable): String = when {
        e is IOException ->
            service.getString(R.string.memeboard_err_network)
        isAuthError(e) ->
            service.getString(R.string.memeboard_err_auth)
        else ->
            service.getString(R.string.memeboard_load_failed, e.message ?: "")
    }

    private fun isAuthError(e: Throwable): Boolean {
        val m = e.message ?: return false
        return m.contains("HTTP 401") || m.contains("HTTP 403")
    }

    private fun toastSendFailed(e: Throwable) {
        Toast.makeText(
            service,
            service.getString(R.string.memeboard_send_failed, e.message ?: ""),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showLoadFailed(e: Throwable) {
        Timber.e(e, "MemeBoard load failed")
        ui.showGrid()
        Toast.makeText(service, describeError(e), Toast.LENGTH_LONG).show()
    }

    override val title: String by lazy { service.getString(R.string.memeboard_gallery) }

    override fun onCreateBarExtension(): View = ui.extension

    companion object {
        private const val AUTO_RETRY_INTERVAL_MS = 30_000L
    }
}
