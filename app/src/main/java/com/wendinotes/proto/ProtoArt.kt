package com.wendinotes.proto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * タップで出るバーの背景の模様。設定の「表示」→「バーの模様」で選ぶ。
 * 画像ファイルは持たず、その場で描く。色はバーの色（BarTheme）に合わせる。背景は透明（バーの色の上に重ねる）。
 */
object ProtoArt {
    val NAMES = listOf("無地", "ストライプ", "ドット", "グリッド", "グラデ")

    fun current(c: Context): String = Prefs.get(c).getString(Prefs.BAR_PATTERN, "無地") ?: "無地"

    /** 模様だけの絵（透明の上に線や点）。「無地」は null */
    fun pattern(name: String, th: BarTheme, w: Int, h: Int, density: Float): Bitmap? {
        if (name == "無地" || w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val d = density
        val line = (th.ink and 0x00FFFFFF) or 0x1C000000   // 文字色をごく薄く
        when (name) {
            "ストライプ" -> {
                p.color = line
                p.strokeWidth = 7f * d
                var x = -h.toFloat()
                while (x < w + h) { cv.drawLine(x, h.toFloat(), x + h, 0f, p); x += 18f * d }
            }
            "ドット" -> {
                p.color = line
                val step = 12f * d
                var row = 0
                var y = step / 2
                while (y < h) {
                    var x = if (row % 2 == 0) step / 2 else step
                    while (x < w) { cv.drawCircle(x, y, 1.8f * d, p); x += step }
                    y += step * 0.87f; row++
                }
            }
            "グリッド" -> {
                p.color = line
                p.strokeWidth = 1f * d
                val step = 14f * d
                var v = 0f
                while (v <= w) { cv.drawLine(v, 0f, v, h.toFloat(), p); v += step }
                v = 0f
                while (v <= h) { cv.drawLine(0f, v, w.toFloat(), v, p); v += step }
            }
            "グラデ" -> {
                val top = (th.accent and 0x00FFFFFF) or 0x33000000
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, 0x00000000, Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
        }
        return bmp
    }
}
