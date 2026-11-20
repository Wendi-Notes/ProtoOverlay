package com.wendinotes.proto

import android.os.SystemClock
import android.view.Choreographer

/**
 * 自前の時計で動くアニメ（ValueAnimator の代わり）。
 * 端末の「アニメーション再生時間」がオフ・省電力モード・機種独自の設定だと ValueAnimator は一瞬で終わる
 * （実機の Android 14 で、バーを閉じると左下へワープしていた原因）。これは画面の描き直しの合図
 * （Choreographer）と経過時間だけで動くので、その設定に左右されない。
 */
class ProtoAnim(
    private val from: Int,
    private val to: Int,
    private val durationMs: Long,
    private val ease: (Float) -> Float,
    private val onUpdate: (Int) -> Unit,
    private val onEnd: () -> Unit = {},
) {
    private var start = 0L
    var isRunning = false
        private set

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return
            val k = ((SystemClock.uptimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
            onUpdate(from + ((to - from) * ease(k)).toInt())
            if (k >= 1f) { isRunning = false; onEnd() } else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start(): ProtoAnim {
        start = SystemClock.uptimeMillis()
        isRunning = true
        Choreographer.getInstance().postFrameCallback(frame)
        return this
    }

    fun cancel() {
        if (!isRunning) return
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    companion object {
        val easeIn: (Float) -> Float = { it * it }                       // だんだん速く（落ちる）
        val easeOut: (Float) -> Float = { 1 - (1 - it) * (1 - it) }      // だんだんゆっくり
    }
}
