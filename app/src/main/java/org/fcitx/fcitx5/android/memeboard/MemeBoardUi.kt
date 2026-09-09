/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ViewAnimator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.styles.AndroidStyles
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.setPaddingDp

class MemeBoardUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val androidStyles = AndroidStyles(ctx)

    val searchButton = androidStyles.button.borderless {
        text = ctx.getString(R.string.memeboard_search)
        isAllCaps = false
        setTextColor(theme.accentKeyBackgroundColor)
        setPadding(dp(8), 0, dp(8), 0)
    }

    val settingsButton = ToolButton(ctx, R.drawable.ic_baseline_settings_24, theme).apply {
        contentDescription = ctx.getString(R.string.memeboard_settings)
    }

    val refreshButton = ToolButton(ctx, R.drawable.ic_baseline_sync_24, theme).apply {
        contentDescription = ctx.getString(R.string.memeboard_refresh)
    }

    val recyclerView = recyclerView {
        // spacing added by item decoration in the window
    }

    private val instructionText = textView {
        setText(R.string.memeboard_not_configured)
        setPaddingDp(24, 24, 24, 12)
        setTextColor(theme.keyTextColor)
        gravity = Gravity.CENTER
    }

    val openSettingsButton = androidStyles.button.borderless {
        setText(R.string.memeboard_open_settings)
        setTextColor(theme.accentKeyBackgroundColor)
    }

    private val instructionRoot = view(::LinearLayout) {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        add(instructionText, lParams(wrapContent, wrapContent))
        add(openSettingsButton, lParams(wrapContent, wrapContent))
    }

    private val loadingRoot = view(::LinearLayout) {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        add(ProgressBar(ctx), lParams(wrapContent, wrapContent))
        add(textView {
            setText(R.string.memeboard_loading)
            setTextColor(theme.keyTextColor)
            setPaddingDp(0, 12, 0, 0)
        }, lParams(wrapContent, wrapContent))
    }

    private val viewAnimator = view(::ViewAnimator) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(instructionRoot, lParams(matchParent, matchParent))
        add(loadingRoot, lParams(matchParent, matchParent))
    }

    private val tagContainer = view(::LinearLayout) {
        orientation = LinearLayout.HORIZONTAL
    }

    private val tagScroll = view(::HorizontalScrollView) {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(tagContainer, LinearLayout.LayoutParams(wrapContent, ctx.dp(40)))
    }

    private val topBar = horizontalLayout {
        add(searchButton, lParams(wrapContent, dp(40)))
        add(tagScroll, LinearLayout.LayoutParams(0, dp(40), 1f))
    }

    override val root = view(::LinearLayout) {
        orientation = LinearLayout.VERTICAL
        add(topBar, LinearLayout.LayoutParams(matchParent, wrapContent))
        add(viewAnimator, LinearLayout.LayoutParams(matchParent, 0, 1f))
    }

    val extension = horizontalLayout {
        add(settingsButton, lParams(dp(40), dp(40)))
        add(refreshButton, lParams(dp(40), dp(40)))
    }

    fun showGrid() {
        viewAnimator.displayedChild = 0
    }

    fun showInstruction() {
        viewAnimator.displayedChild = 1
    }

    fun showLoading() {
        viewAnimator.displayedChild = 2
    }

    /** 渲染顶部横向 chip 列表：全部 / 收藏 / 最近（固定）+ 标签（单选）。 */
    fun setChips(
        tags: List<MtTag>,
        selectedTagId: Long?,
        allActive: Boolean,
        favoritesActive: Boolean,
        recentActive: Boolean,
        onAll: () -> Unit,
        onFavorites: () -> Unit,
        onRecent: () -> Unit,
        onTagClick: (MtTag) -> Unit,
    ) {
        tagContainer.removeAllViews()
        addChip(ctx.getString(R.string.memeboard_all), allActive, onAll)
        addChip(ctx.getString(R.string.memeboard_favorites), favoritesActive, onFavorites)
        addChip(ctx.getString(R.string.memeboard_recent), recentActive, onRecent)
        tags.forEach { tag ->
            addChip(tag.name, tag.id == selectedTagId) { onTagClick(tag) }
        }
    }

    private fun addChip(label: String, active: Boolean, onClick: () -> Unit) {
        val chip = androidStyles.button.borderless {
            text = label
            isAllCaps = false
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            setTextColor(if (active) theme.accentKeyBackgroundColor else theme.keyTextColor)
            setOnClickListener { onClick() }
        }
        tagContainer.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ctx.dp(40)))
    }
}
