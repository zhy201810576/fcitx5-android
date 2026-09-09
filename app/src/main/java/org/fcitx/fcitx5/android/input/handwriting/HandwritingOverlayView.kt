package org.fcitx.fcitx5.android.input.handwriting

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.link.MlKitHandwritingClient
import org.fcitx.fcitx5.android.memeboard.MemeBoardPrefs

/**
 * 手写输入覆盖层：手写板 + 功能键。
 * 识别走 ML Kit 数字墨水识别（中文 / 日语，由手写设置中的语言切换），
 * 结果通过 [KawaiiBarComponent.showExternalCandidates] 注入 fcitx5 原生候选栏，
 * 与拼音/中州韵候选词在键盘顶部同一位置展示。
 */
class HandwritingOverlayView(
    context: Context,
    private val service: FcitxInputMethodService,
    theme: Theme,
    private val bar: KawaiiBarComponent,
    private val onExit: () -> Unit
) : LinearLayout(context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 手写识别语言：中文或日文，统一走 ML Kit 数字墨水识别。 */
    private val language = when (MemeBoardPrefs.getHandwritingLanguage(context)) {
        "ja" -> MlKitHandwritingClient.Language.JAPANESE
        else -> MlKitHandwritingClient.Language.CHINESE
    }

    private val bgColor = when (theme) {
        is Theme.Builtin -> theme.keyboardColor
        else -> theme.backgroundColor
    }
    private val keyTextColor = theme.keyTextColor

    private val padView: HandwritingPadView

    init {
        orientation = VERTICAL
        setBackgroundColor(bgColor)

        padView = HandwritingPadView(context).apply {
            strokeColor = keyTextColor
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            ).apply {
                setMargins(dp(10f), dp(8f), dp(8f), dp(8f))
            }
            onStrokeFinished = { points -> recognize(points) }
        }

        // 右侧按钮列：返回、清空、退格（竖排，自上而下）
        val rightColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dp(52f),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rightColumn.addView(
            makeButton("返回") { exit() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        rightColumn.addView(
            makeButton("清空") { padView.clear(); bar.clearExternalCandidates() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        rightColumn.addView(
            makeButton("⌫") { deleteBackward() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // 主体：手写板（占满剩余空间） + 右侧按钮列
        val body = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        body.addView(padView)
        body.addView(rightColumn)

        addView(body)

        prepareRecognizer()
    }

    private fun prepareRecognizer() {
        MlKitHandwritingClient.prepare(language) { status ->
            when {
                status == "invalid_lang_tag" ->
                    Toast.makeText(context, "ML Kit 语言码无效: $status", Toast.LENGTH_SHORT).show()
                status.startsWith("no_model_for_lang_tag") ->
                    Toast.makeText(context, "ML Kit 不支持该语言: $status", Toast.LENGTH_SHORT).show()
                status.startsWith("download_failed") || status.startsWith("check_failed") ->
                    Toast.makeText(context, "ML Kit 手写模型不可用: $status", Toast.LENGTH_LONG).show()
                // "ready" / "downloaded" 无需提示
            }
        }
    }

    private fun recognize(points: IntArray) {
        scope.launch {
            val candidates = if (MlKitHandwritingClient.isReady(language)) {
                MlKitHandwritingClient.recognize(language, points)
            } else {
                Toast.makeText(context, "手写模型下载中，请稍候…", Toast.LENGTH_SHORT).show()
                emptyList()
            }
            if (candidates.isNotEmpty()) {
                bar.showExternalCandidates(candidates) { text -> commit(text) }
            }
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(keyTextColor)
            textSize = 15f
            gravity = Gravity.CENTER
            val p = dp(8f)
            setPadding(p, 0, p, 0)
            setOnClickListener { onClick() }
        }
    }

    private fun commit(text: String) {
        service.finishComposing()
        service.commitText(text)
        padView.clear()
        bar.clearExternalCandidates()
    }

    private fun deleteBackward() {
        try {
            service.currentInputConnection?.deleteSurroundingText(1, 0)
        } catch (_: Throwable) {}
    }

    private fun exit() {
        bar.clearExternalCandidates()
        onExit()
    }

    fun onDetach() {
        scope.cancel()
        bar.clearExternalCandidates()
    }

    private fun dp(value: Float): Int {
        return (context.resources.displayMetrics.density * value).toInt()
    }
}
