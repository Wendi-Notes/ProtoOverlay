package com.wendinotes.proto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * プロトの絵（表示係）。顔と動きを受け取って描くだけ。どの顔にするかは ProtoService が決める。
 * パーツは全部 480×309 の透過PNGで、同じ位置に重ねる（Web版と同じ素材）。
 */
class ProtoView(context: Context) : View(context) {

    companion object {
        const val IMG_W = 480f
        const val IMG_H = 309f
        /** 体の切れ目（Web版でバーの上端だった線）。これより下の体は隠す */
        const val CUT_Y = 221f
        /** 揺れの中心（Web版の transform-origin: 55% 72%） */
        const val PIVOT_X = 264f
        const val PIVOT_Y = 222f
        /** 絵の上に足す余白（「？」を頭より上まで出すため）。窓の高さ = (IMG_H + TOP_PAD) */
        const val TOP_PAD = 70f
        /** 動く「？」の中心と大きさ・傾き（絵の座標。上の余白にはみ出してよい） */
        const val HAT_CX = 418f
        const val HAT_CY = 22f
        const val HAT_SIZE = 150f
        const val HAT_TILT = -18f   // 左に傾ける
    }

    enum class Face { IDLE, BLINK, WIDE, SLEEP, HAPPY, CHARGE, LOW, TALK, REC, CAST }
    enum class Motion { SWAY, SWAY_SLOW, SWAY_FAST, BOB, TALK_UP, BREATH, DROOP, DOZE }
    /** おでこのマーク（どの顔・動きにも重ねる） */
    enum class Badge { NONE, CHARGE, LOW }

    var face = Face.SLEEP
    var motion = Motion.BREATH
        set(v) { if (v != field && v == Motion.DOZE) dozeStart = SystemClock.uptimeMillis(); field = v }
    private var dozeStart = 0L
    var showPaws = false
    var mirror = false
        set(v) { field = v; invalidate() }
    var mouthOpen = false
    /** しまっている時（バーなし）：少し上を向いて背伸びした姿勢。切り替えはなめらかに */
    var lookUp = true
    private var lookK = 1f

    var badge = Badge.NONE
    /** 寝ている間の Zzz（頭の右上でゆっくり浮かんで消える） */
    var showZzz = false
    // 動く Zzz（小さい z から順にぽこぽこ出て、浮かびながら消える）。読めなければ止まった絵
    private val zzzAnim: Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, R.drawable.proto_zzz_anim)).also {
            if (it is AnimatedImageDrawable) { it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; it.start() }
        }
    } catch (e: Exception) { null }
    private val zzz = bmp(R.drawable.proto_zzz)
    private val zzzPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val zzzRect = android.graphics.RectF()

    /** 考え中の「？」を出すか */
    var showHatena = false
    /** 聞き取り中の「ハッ」の線を出すか */
    var showListen = false
    private val listenAnim: Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, R.drawable.proto_listen_anim)).also {
            if (it is AnimatedImageDrawable) { it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; it.start() }
        }
    } catch (e: Exception) { null }

    // 動く「？」（Web版の hatena_think.webp と同じもの）
    private val hatena: Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, R.drawable.hatena_think)).also {
            if (it is AnimatedImageDrawable) { it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; it.start() }
        }
    } catch (e: Exception) { null }

    private fun bmp(id: Int): Bitmap =
        BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })

    // 顔の光り方：false＝元の色（水色）、true＝アイコン風（白く光ってふちが紫、耳のライトはピンク）。*_glow.png
    private class FaceSet(val body: Bitmap, val eye: Bitmap, val eyeBlink: Bitmap, val eyeWide: Bitmap, val eyeSleep: Bitmap,
                          val eyeHappy: Bitmap, val mouth: Bitmap, val mouthOpen: Bitmap)
    private val plainSet by lazy {
        FaceSet(bmp(R.drawable.proto_body), bmp(R.drawable.proto_eye), bmp(R.drawable.proto_eye_blink), bmp(R.drawable.proto_eye_wide),
            bmp(R.drawable.proto_eye_sleep), bmp(R.drawable.proto_eye_happy), bmp(R.drawable.proto_mouth), bmp(R.drawable.proto_mouth_open))
    }
    private val glowSet by lazy {
        FaceSet(bmp(R.drawable.proto_body_glow), bmp(R.drawable.proto_eye_glow), bmp(R.drawable.proto_eye_blink_glow), bmp(R.drawable.proto_eye_wide_glow),
            bmp(R.drawable.proto_eye_sleep_glow), bmp(R.drawable.proto_eye_happy_glow), bmp(R.drawable.proto_mouth_glow), bmp(R.drawable.proto_mouth_open_glow))
    }
    var glow = false
        set(v) { field = v; invalidate() }
    private val faceSet get() = if (glow) glowSet else plainSet
    private val body get() = faceSet.body
    private val eye get() = faceSet.eye
    private val eyeBlink get() = faceSet.eyeBlink
    private val eyeWide get() = faceSet.eyeWide
    private val eyeSleep get() = faceSet.eyeSleep
    private val eyeHappy get() = faceSet.eyeHappy
    private val mouth get() = faceSet.mouth
    private val mouthOpenBmp get() = faceSet.mouthOpen
    private val paws = bmp(R.drawable.proto_paws)
    private val charge = bmp(R.drawable.proto_charge)
    private val batteryLow = bmp(R.drawable.proto_battery_low)
    private val recBmp = bmp(R.drawable.proto_rec)
    private val castBmp = bmp(R.drawable.proto_cast)   // 画面共有中：画面＋上向き矢印（●RECと同じ赤）

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    // 体と前足用（毛の色を変えられる）。目・口・マークは色を変えない
    private val bodyPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** 毛の色：色相を何度回すか（-180〜180、0＝元の水色） */
    var hue = 0
        set(v) {
            field = v
            bodyPaint.colorFilter = if (v == 0) null else android.graphics.ColorMatrixColorFilter(hueMatrix(v.toFloat()))
            invalidate()
        }

    /** 色相回転の行列（明るさはそのまま、色味だけ回す） */
    private fun hueMatrix(deg: Float): android.graphics.ColorMatrix {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return android.graphics.ColorMatrix(floatArrayOf(
            lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0f, 0f,
            lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0f, 0f,
            lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }
    private val pulsePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** くるっと一回転（アプリを閉じて落ちる時）。度 */
    private var spin = 0f
    private var spinAnim: android.animation.ValueAnimator? = null
    fun spinOnce(ms: Long) {
        spinAnim?.cancel()
        spinAnim = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
            duration = ms
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { spin = (it.animatedValue as Float) % 360f; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { spin = 0f; invalidate() }
            })
            start()
        }
    }
    /** 落ちている間（バーを閉じた時も、アプリを閉じた時も）前足をわちゃわちゃ振る */
    private var flailUntil = 0L
    fun flail(ms: Long) { flailUntil = SystemClock.uptimeMillis() + ms; invalidate() }
    // 前足の絵（proto_paws.png）の左右それぞれの範囲。付け根を軸に振る
    private val PAW_L = android.graphics.Rect(164, 181, 300, 308)
    private val PAW_R = android.graphics.Rect(373, 188, 479, 290)
    private val pawDst = android.graphics.RectF()
    private fun drawPaw(c: Canvas, src: android.graphics.Rect, px: Float, py: Float, deg: Float, dy: Float) {
        c.save()
        c.translate(0f, dy)
        c.rotate(deg, px, py)
        pawDst.set(src)
        c.drawBitmap(paws, src, pawDst, bodyPaint)
        c.restore()
    }

    /** 姿勢（上を向く／しがみつく）をなめらかに変えず、今すぐ合わせる */
    fun snapLook() { lookK = if (lookUp) 1f else 0f; invalidate() }

    private var hopStart = -10_000L
    /** 起きた瞬間などに、ぴょこっと上がる */
    fun hop() { hopStart = SystemClock.uptimeMillis() }

    // 描き直しの間隔：寝ているときはゆっくり（電池のため）
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, if (motion == Motion.BREATH || motion == Motion.DOZE) 80L else 40L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    /** 0 → 1 → 0 をなめらかに往復する（CSS の ease-in-out の往復に近い） */
    private fun wave(t: Long, periodMs: Long): Float =
        ((1 - cos(2 * PI * (t % periodMs) / periodMs)) / 2).toFloat()

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val t = SystemClock.uptimeMillis()
        val s = width / IMG_W

        c.save()
        if (spin != 0f) c.rotate(if (mirror) -spin else spin, width / 2f, height * 0.55f)   // くるっと回りながら落ちる
        if (mirror) c.scale(-1f, 1f, width / 2f, 0f)
        c.scale(s, s)
        c.translate(0f, TOP_PAD)

        // ---- 体と顔（切れ目より下は描かない） ----
        c.save()
        c.clipRect(0f, 0f, IMG_W, CUT_Y)
        var dy = 0f
        var rot = 0f
        when (motion) {   // 数値は Web版の動き（--pw 210px 基準）を 480px に換算したもの
            Motion.SWAY -> { dy = 9f; rot = -1.6f + 3.2f * wave(t, 3400) }
            Motion.SWAY_SLOW -> { dy = 12f; rot = -1f + 2f * wave(t, 5200) }   // 眠くなってきた
            Motion.SWAY_FAST -> { dy = 9f; rot = -2f + 4f * wave(t, 2000) }
            Motion.BOB -> { dy = 7f + 18f * wave(t, 450) }   // わくわく：ぴょこぴょこ（見せてもらっている時・うれしい時）
            // しゃべる：跳ねずに、顔を上に向けて口をぱくぱく（口は ProtoService が切り替える）
            Motion.TALK_UP -> { dy = 0f; rot = -10f + 0.8f * wave(t, 900) }   // 口がバーや前足に隠れないよう、少し高く上げる
            Motion.BREATH -> { dy = 9f + 7f * wave(t, 4500) }
            Motion.DROOP -> { dy = 22f + 4f * wave(t, 5000); rot = -0.6f + 1.2f * wave(t, 5000) }
            Motion.DOZE -> {
                // 居眠り：8秒かけて前（顔の向き）へこっくり傾き、ずり落ちそうになる。寝息で少し上下
                val k = ((t - dozeStart) / 8000f).coerceIn(0f, 1f)
                val ease = k * k * (3 - 2 * k)
                rot = 7f * ease + 0.8f * wave(t, 4500)
                dy = 9f + 20f * ease + 6f * wave(t, 4500)
            }
        }
        // しまっている時の姿勢：下から覗き込むように上を向く（顔＝右側が上がり、左側が沈む）。
        // 体の左下には前足用の欠け（透明）があるので、それが画面の下に隠れる角度・深さにしている
        lookK += ((if (lookUp) 1f else 0f) - lookK) * 0.2f
        rot -= 16f * lookK
        dy += 26f * lookK

        val since = t - hopStart
        if (since in 0L..400L) dy -= (sin(PI * since / 400.0) * (30 - 18 * lookK)).toFloat()  // しまっている時は控えめに跳ねる（欠けが見えないように）
        c.translate(0f, dy)
        c.rotate(rot, PIVOT_X, PIVOT_Y)

        c.drawBitmap(body, 0f, 0f, bodyPaint)
        when (face) {
            Face.IDLE -> { c.drawBitmap(eye, 0f, 0f, paint); c.drawBitmap(mouth, 0f, 0f, paint) }
            Face.BLINK -> { c.drawBitmap(eyeBlink, 0f, 0f, paint); c.drawBitmap(mouth, 0f, 0f, paint) }
            Face.WIDE -> { c.drawBitmap(eyeWide, 0f, 0f, paint); c.drawBitmap(mouth, 0f, 0f, paint) }
            Face.SLEEP -> c.drawBitmap(eyeSleep, 0f, 0f, paint)
            Face.HAPPY -> { c.drawBitmap(eyeHappy, 0f, 0f, paint); c.drawBitmap(mouth, 0f, 0f, paint) }
            Face.CHARGE -> c.drawBitmap(charge, 0f, 0f, paint)
            Face.LOW -> {
                pulsePaint.alpha = (90 + 165 * wave(t, 1600)).toInt()
                c.drawBitmap(batteryLow, 0f, 0f, pulsePaint)
            }
            Face.TALK -> {
                c.drawBitmap(eyeWide, 0f, 0f, paint)
                c.drawBitmap(if (mouthOpen) mouthOpenBmp else mouth, 0f, 0f, paint)
            }
            Face.REC -> c.drawBitmap(recBmp, 0f, 0f, paint)
            Face.CAST -> c.drawBitmap(castBmp, 0f, 0f, paint)
        }
        // おでこの電池マーク：充電中は緑、電池が少ない時は赤でゆっくり点滅
        when (badge) {
            Badge.CHARGE -> if (face != Face.CHARGE && face != Face.REC && face != Face.CAST) c.drawBitmap(charge, 0f, 0f, paint)
            Badge.LOW -> if (face != Face.REC && face != Face.CAST) {
                pulsePaint.alpha = (90 + 165 * wave(t, 1600)).toInt()
                c.drawBitmap(batteryLow, 0f, 0f, pulsePaint)
            }
            Badge.NONE -> {}
        }
        c.restore()

        // ---- 前足（パネルのふちにかけている時だけ。体と違って揺れない） ----
        if (spinAnim?.isRunning == true || t < flailUntil) {
            // 落ちる時（「わっ！」）：前足を出して、左右ばらばらにわちゃわちゃ振る
            val ph = 2 * PI * (t % 200L) / 200.0
            drawPaw(c, PAW_L, 232f, 190f, (30 * sin(ph)).toFloat(), (-8 * abs(sin(ph))).toFloat())
            drawPaw(c, PAW_R, 425f, 196f, (30 * sin(ph + PI * 0.8)).toFloat(), (-8 * abs(sin(ph + PI * 0.8))).toFloat())
        } else if (showPaws) c.drawBitmap(paws, 0f, 0f, bodyPaint)

        c.restore()

        // ---- 聞き取り中の「ハッ」の線（頭の右上。右下にいる時は反転） ----
        val ln = listenAnim
        if (showListen && !showHatena && ln != null) {
            val size = 120f * s
            val cx = (if (mirror) IMG_W - HAT_CX else HAT_CX) * s
            val cy = (HAT_CY + TOP_PAD + 10f) * s
            c.save()
            if (mirror) c.scale(-1f, 1f, cx, cy)
            ln.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            ln.draw(c)
            c.restore()
        }

        // ---- 寝ている間の Zzz（頭の右上。右下にいる時は左向きに反転：字ではなく記号なので反転してよい） ----
        if (showZzz && !showHatena && !showListen) {
            val size = 140f * s
            val cx = (if (mirror) IMG_W - HAT_CX else HAT_CX) * s
            val cy = (HAT_CY + TOP_PAD + 8f) * s
            c.save()
            if (mirror) c.scale(-1f, 1f, cx, cy)
            val z = zzzAnim
            if (z != null) {
                z.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
                z.draw(c)
            } else {
                val k = (t % 3000) / 3000f
                zzzPaint.alpha = (255 * sin(PI * k)).toInt().coerceIn(0, 255)
                zzzRect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                c.drawBitmap(zzz, null, zzzRect, zzzPaint)
            }
            c.restore()
        }

        // ---- 考え中の「？」（字は反転しない。右下にいる時は頭の左上に出し、傾きも逆にする） ----
        val h = hatena
        if (showHatena && h != null) {
            val bob = 5f * wave(t, 1200)
            val cx = (if (mirror) IMG_W - HAT_CX else HAT_CX) * s
            val cy = (HAT_CY + TOP_PAD - bob) * s
            val half = HAT_SIZE * s / 2
            h.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            c.save()
            c.rotate(if (mirror) -HAT_TILT else HAT_TILT, cx, cy)
            h.draw(c)
            c.restore()
        }
    }
}
