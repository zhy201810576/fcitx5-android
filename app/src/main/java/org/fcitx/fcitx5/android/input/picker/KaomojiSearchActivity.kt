/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import splitties.dimensions.dp

/**
 * 颜文字搜索弹窗：模仿表情包搜索页的独立 Activity 设计。
 * 顶部锚定的紧凑对话框，带 EditText + 防抖搜索 + 结果列表。
 * 点击结果 → 存入 KaomojiPendingCommit → finish() → 输入法在下次 onAttached 时上屏。
 */
class KaomojiSearchActivity : Activity() {

    private val theme get() = ThemeManager.activeTheme

    private lateinit var editText: EditText
    private lateinit var resultList: ListView
    private lateinit var resultAdapter: ArrayAdapter<String>
    private var results = emptyList<String>()

    private var lastQueryTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 顶部锚定，55% 屏幕高度的紧凑对话框
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        }
        // 小圆角背景（与表情包搜索页一致）
        window.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(theme.keyBackgroundColor)
                cornerRadius = dp(8).toFloat()
            }
        )

        val ctx = this

        editText = EditText(ctx).apply {
            hint = "搜索颜文字..."
            isSingleLine = true
            textSize = 15f
            setTextColor(theme.keyTextColor)
            setHintTextColor(theme.altKeyTextColor)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString().orEmpty()
                    if (q.trim().isEmpty()) {
                        showResults(emptyList())
                    } else {
                        // 防抖 200ms (本地搜索，比网络搜索响应更快)
                        val now = System.currentTimeMillis()
                        lastQueryTime = now
                        editText.postDelayed({
                            if (now == lastQueryTime) doSearch(q)
                        }, 200)
                    }
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch(text.toString())
                    true
                } else false
            }
        }

        val cancelBtn = TextView(ctx).apply {
            text = "取消"
            textSize = 14f
            setTextColor(theme.accentKeyBackgroundColor)
            setPadding(dp(8), 0, dp(8), 0)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(theme.barColor)
            addView(cancelBtn, LinearLayout.LayoutParams(dp(56), dp(40)))
            addView(editText, LinearLayout.LayoutParams(0, dp(44), 1f))
        }

        resultAdapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.textSize = 18f // 颜文字需要大一点的字号才能看清
                v.setTextColor(theme.keyTextColor)
                v.gravity = Gravity.CENTER
                v.setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                return v
            }
        }

        resultList = ListView(ctx).apply {
            adapter = resultAdapter
            divider = null
            setBackgroundColor(theme.keyBackgroundColor)
            setOnItemClickListener { _, _, position, _ ->
                val kaomoji = results.getOrNull(position) ?: return@setOnItemClickListener
                KaomojiPendingCommit.text = kaomoji
                finish()
            }
        }

        setContentView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(topBar, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(resultList, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f
                ))
            }
        )

        editText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun doSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            showResults(emptyList())
            return
        }
        results = KaomojiDataLoader.search(q)
        showResults(results)
    }

    private fun showResults(items: List<String>) {
        results = items
        resultAdapter.clear()
        if (items.isEmpty()) {
            // 无结果提示
            resultAdapter.add("无匹配结果")
        } else {
            resultAdapter.addAll(items)
        }
        resultAdapter.notifyDataSetChanged()
    }
}