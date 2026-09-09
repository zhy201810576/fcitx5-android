/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 *
 * 进程内语音输入客户端。
 *
 * 原实现通过 AIDL 绑定独立 asr-bridge 进程（BiBi「说点啥」协议），本文件改为
 * 直接调用进程内 [SpeechEngine]（SenseVoice 离线模型，模型在 asr 插件 APK 里），
 * 因此不再有跨进程 bindService，也彻底摆脱 HyperOS 链式启动管控。
 *
 * 保留：录音（AudioRecord）、麦克风权限、音频焦点、覆盖层 UI、上屏逻辑。
 * 移除：Binder/AIDL 事务、输入上下文纠错协商（SenseVoice 离线模型不支持）。
 */
package org.fcitx.fcitx5.android.link

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber
import kotlin.math.sqrt

object AsrkbSpeechClient {
    private const val TAG = "AsrkbLink"

    private const val STATE_IDLE = 0
    private const val STATE_RECORDING = 1
    private const val STATE_PROCESSING = 2

    @Volatile
    private var holding = false
    private var currentState: Int = STATE_IDLE
    private var ctxRef: FcitxInputMethodService? = null
    private var audioJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val recordingAudioFocusOwner = AsrkbRecordingAudioFocusSessionOwner()
    private var hasPcmFrame = false

    // 本次会话持有的进程内引擎与会话
    private var engine: SpeechEngine? = null
    private var session: SpeechEngine.Session? = null

    fun startHoldSession(service: FcitxInputMethodService) {
        if (holding) return
        cancelSession()
        ctxRef = service
        holding = true
        hasPcmFrame = false

        val e = AsrEngineController.ensureLoaded(service)
        if (e == null) {
            // 引擎未就绪：插件未装或模型仍在后台加载
            toast(service, service.getString(R.string.asrkb_err_model_not_ready))
            runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
            cancelSession()
            return
        }
        engine = e
        session = e.newSession()
        currentState = STATE_RECORDING
        startAudioStreaming(service)
    }

    fun stopHoldSession() {
        if (!holding) return
        holding = false
        val service = ctxRef
        stopAudioStreaming()
        val s = session
        session = null
        val e = engine
        if (service != null && s != null && e != null && hasPcmFrame) {
            currentState = STATE_PROCESSING
            service.lifecycleScope.launch(Dispatchers.IO) {
                val text = runCatching { s.finish() }.getOrNull()
                service.lifecycleScope.launch(Dispatchers.Main) {
                    currentState = STATE_IDLE
                    engine = null
                    if (!text.isNullOrEmpty()) {
                        service.finishComposing()
                        service.commitText(text)
                    } else {
                        toast(service, service.getString(R.string.asrkb_err_no_result))
                    }
                    runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
                }
            }
        } else {
            currentState = STATE_IDLE
            engine = null
            s?.release()
            runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
        }
    }

    fun isHolding(): Boolean = holding

    fun onServiceDestroyed(service: FcitxInputMethodService) {
        if (ctxRef !== service) return
        cancelSession()
    }

    // ---- 以下为保持与 FcitxInputMethodService 调用点兼容的空实现 ----
    // 输入上下文纠错协商随 AIDL 远程引擎一并移除（SenseVoice 离线模型不支持）。
    fun onStartInput(info: EditorInfo, restarting: Boolean) = Unit
    fun onFinishInput() = Unit
    fun onEditorAction() = Unit
    fun onEditorEvent(service: FcitxInputMethodService) = Unit

    private fun cancelSession() {
        stopAudioStreaming()
        session?.release()
        session = null
        engine = null
        currentState = STATE_IDLE
        holding = false
        ctxRef = null
        hasPcmFrame = false
    }

    private fun startAudioStreaming(service: FcitxInputMethodService) {
        stopAudioStreaming()

        // 检查录音权限
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(service, MicPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                service.startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start MicPermissionActivity", t)
                toast(service, service.getString(R.string.asrkb_client_need_mic_permission))
            }
            runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
            cancelSession()
            return
        }

        acquireRecordingAudioFocusIfEnabled(service)

        audioJob = service.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sr = 16000
                val ch = AudioFormat.CHANNEL_IN_MONO
                val fmt = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioRecord.getMinBufferSize(sr, ch, fmt)
                val bytesPerSample = 2
                val chunkBytes = (sr * 200 / 1000) * bytesPerSample
                val bufSize = maxOf(minBuf, chunkBytes * 2)
                var rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sr, ch, fmt, bufSize
                )
                audioRecord = rec
                try {
                    rec.startRecording()
                } catch (t: Throwable) {
                    Log.w(TAG, "AudioRecord start failed, fallback MIC", t)
                    try { rec.release() } catch (_: Throwable) {}
                    rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr, ch, fmt, bufSize)
                    audioRecord = rec
                    try {
                        rec.startRecording()
                    } catch (e: Throwable) {
                        Log.e(TAG, "AudioRecord MIC failed", e)
                        service.lifecycleScope.launch {
                            toast(service, service.getString(R.string.asrkb_err_audio_record_failed))
                            runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
                            cancelSession()
                        }
                        return@launch
                    }
                }

                val chunk = ByteArray(chunkBytes)
                var notifiedRecordingStarted = false
                while (true) {
                    val s = session
                    if (s == null || !holding) break
                    val n = try { audioRecord?.read(chunk, 0, chunk.size) ?: -1 } catch (_: Throwable) { -1 }
                    if (n < 0) break
                    if (n == 0) {
                        delay(10)
                        continue
                    }
                    if (!notifiedRecordingStarted) {
                        notifiedRecordingStarted = true
                        runCatching { VoiceOverlayUiBridge.onRecordingStarted?.invoke() }
                    }
                    acceptPcmFrame(s, chunk, n)
                }
            } finally {
                recordingAudioFocusOwner.release()
            }
        }
    }

    private fun acceptPcmFrame(s: SpeechEngine.Session, buf: ByteArray, len: Int) {
        if (len > 0) hasPcmFrame = true
        val frame = if (len == buf.size) buf else buf.copyOf(len)
        s.accept(frame)
        runCatching { VoiceOverlayUiBridge.onAmplitude?.invoke(rmsAmplitude(frame)) }
    }

    private fun stopAudioStreaming() {
        try { audioJob?.cancel() } catch (_: Throwable) {}
        audioJob = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        recordingAudioFocusOwner.release()
    }

    private fun acquireRecordingAudioFocusIfEnabled(service: FcitxInputMethodService) {
        if (!AppPrefs.getInstance().keyboard.asrkbDuckMediaOnRecord.getValue()) {
            Timber.d("ASRKB media avoidance disabled; skip audio focus request")
            return
        }

        val executor = ContextCompat.getMainExecutor(service)
        lateinit var controller: AsrkbRecordingAudioFocusController
        controller = AsrkbRecordingAudioFocusController(service) { loss ->
            Timber.w("ASRKB recording audio focus lost: $loss")
            executor.execute {
                if (recordingAudioFocusOwner.owns(controller) && holding) {
                    stopHoldSession()
                }
            }
        }
        if (!recordingAudioFocusOwner.acquire(controller)) {
            Timber.w("ASRKB recording continues without audio focus")
        }
    }

    private fun rmsAmplitude(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() and 0xFF
            val v = (hi shl 8) or lo
            val s = if (v >= 32768) (v - 65536) / 32768.0 else v / 32768.0
            sum += s * s
            i += 2
        }
        val rms = sqrt(sum / (pcm.size / 2))
        return rms.coerceIn(0.0, 1.0).toFloat()
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            ContextCompat.getMainExecutor(ctx).execute {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
        }
    }
}
