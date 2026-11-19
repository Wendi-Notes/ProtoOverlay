package com.wendinotes.proto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * ダッシュボード右の大きい起動スイッチ。つまみがプロトのバイザーになっていて、
 * ON＝水色に光って目が「^ ^」、OFF＝暗くて目が「— —」（寝目）。押すとぴょこっと滑る。
 */
class ProtoSwitch(ctx: Context) : View(ctx) {
    var onToggle: ((Boolean) -> Unit)? = null
    var checked = false
        private set
    private var pos = 0f   // 0=OFF側(左) 1=ON側(右)
    private var anim: ValueAnimator? = null
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        contentDescription = "起動／停止"
        setOnClickListener {
            setChecked(!checked, true)
            onToggle?.invoke(checked)
        }
    }

    fun setChecked(v: Boolean, animate: Boolean) {
        if (v == checked && anim == null) { pos = if (v) 1f else 0f; invalidate(); return }
        checked = v
        anim?.cancel()
        if (!animate) { pos = if (v) 1f else 0f; anim = null; invalidate(); return }
        anim = ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 320
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { anim = null }
            })
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((124 * d).toInt(), (56 * d).toInt())
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - tt) + ((b shr sh) and 0xFF) * tt).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = pos.coerceIn(0f, 1f)
        val rad = h / 2
        // 溝（ON：差し色がうっすら光る／OFF：暗い）
        r.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.color = mix(Ui.SURFACE2, mix(Ui.SURFACE2, Ui.ACCENT, 0.28f), t)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = mix(Ui.LINE, Ui.ACCENT, t)
        r.inset(d, d)
        c.drawRoundRect(r, rad, rad, p)

        // ON / OFF の文字（つまみの反対側）
        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 14 * d
        p.textAlign = Paint.Align.CENTER
        val ty = h / 2 - (p.descent() + p.ascent()) / 2
        val kwT = h * 0.98f  // つまみの幅（文字はつまみの反対側の空きの真ん中に置く）
        p.color = Ui.ACCENT; p.alpha = (255 * t).toInt()
        c.drawText("ON", (w - kwT) / 2, ty, p)
        p.color = Ui.SUB; p.alpha = (255 * (1 - t)).toInt()
        c.drawText("OFF", (w + kwT) / 2, ty, p)
        p.alpha = 255

        // つまみ＝バイザー（横長の角丸）
        val m = 5 * d
        val kw = h * 0.98f; val kh = h - 2 * m
        val kx = m + (w - 2 * m - kw) * pos
        r.set(kx, m, kx + kw, m + kh)
        p.color = mix(0xFF2A2A2A.toInt(), 0xFF141B1E.toInt(), t)
        c.drawRoundRect(r, kh / 2.2f, kh / 2.2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = mix(Ui.FAINT, Ui.ACCENT, t)
        c.drawRoundRect(r, kh / 2.2f, kh / 2.2f, p)

        // 目
        p.strokeWidth = 2.6f * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = mix(Ui.FAINT, Ui.ACCENT, t)
        val cy = r.centerY(); val ex = kw * 0.2f; val es = kw * 0.11f
        for (sx in floatArrayOf(r.centerX() - ex, r.centerX() + ex)) {
            if (t > 0.5f) {   // ^ ^
                c.drawLine(sx - es, cy + es * 0.55f, sx, cy - es * 0.55f, p)
                c.drawLine(sx, cy - es * 0.55f, sx + es, cy + es * 0.55f, p)
            } else {          // — —
                c.drawLine(sx - es, cy + es * 0.2f, sx + es, cy + es * 0.2f, p)
            }
        }
        p.strokeCap = Paint.Cap.BUTT
    }
}
