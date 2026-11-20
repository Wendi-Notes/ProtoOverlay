package com.wendinotes.proto

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import io.livekit.android.renderer.TextureViewRenderer
import java.util.Calendar
import kotlin.random.Random

/**
 * 常駐サービス。オーバーレイでプロトを出し、「今どの顔・どの動きにするか」を決める（状態係）。
 *
 * 位置の決め方：体の切れ目（ProtoView.CUT_Y）を「しがみつく線」に合わせる。
 *  - パネルが閉じている → 線＝画面の下端（顔だけ見える。前足は隠す）
 *  - パネルが開いている → 線＝パネルの上端（前足をパネルのふちにかける）
 */
class ProtoService : Service() {

    companion object {
        const val ACTION_STOP = "com.wendinotes.proto.STOP"
        const val ACTION_PAUSE = "com.wendinotes.proto.PAUSE"
        const val ACTION_RESUME = "com.wendinotes.proto.RESUME"
        const val ACTION_SPEAKER_MUTE = "com.wendinotes.proto.SPEAKER_MUTE"
        const val ACTION_SPEAKER_UNMUTE = "com.wendinotes.proto.SPEAKER_UNMUTE"
        private const val CHANNEL = "proto"
        private const val NOTIF_ID = 1
        /** 倍率 1.0 のときの横幅 */
        private const val BASE_WIDTH_DP = 180f
        private const val TAG = "Proto"
    }

    private lateinit var winCtx: Context
    private lateinit var wm: WindowManager
    private lateinit var view: ProtoView
    private lateinit var lp: WindowManager.LayoutParams
    // 触る用の小さな透明の窓（顔のまわりだけ）。絵の窓は触っても素通りにして、ほかのアプリの邪魔をしない
    private lateinit var touchView: android.view.View
    private lateinit var touchLp: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var added = false
    private var yAnim: ValueAnimator? = null
    private var xAnim: ValueAnimator? = null
    private var minX = 0
    private var maxX = 0

    // 長押し→はじく で左右の定位置へ飛び移る
    /** 通話係（PCのプロトとつなぐ） */
    var call: ProtoCall? = null
        private set

    private var grabbed = false
    private var downRawX = 0f

    // 状態
    private var lastTouch = SystemClock.elapsedRealtime()
    private var manualSleep = false
    private var awakeUntil = 0L   // タップで起こした後は、夜でもしばらく起きている
    private var wideUntil = 0L    // 起きた瞬間の見開き目
    private var testThinkUntil = 0L  // 設定のテストボタン用
    private var testTalkUntil = 0L
    private var happyUntil = 0L      // にっこり（うれしい時。ぴょんと跳ねる）
    private var fallUntil = 0L       // 落ちている間（「わっ」の見開き目）
    private var battReady = false
    private var nextBlink = 0L
    private var blinkUntil = 0L
    private var battLevel = 100
    private var charging = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.HUE -> view.hue = prefs.getInt(Prefs.HUE, 0)
            Prefs.GLOW -> view.glow = prefs.getBoolean(Prefs.GLOW, false)
            else -> applyLayout(false)
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0 && scale > 0) battLevel = lvl * 100 / scale
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val now = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            if (now && !charging && battReady) beHappy()   // 充電をつないだ → うれしい
            charging = now
            battReady = true
        }
    }

    // 画面の回転など
    private val winCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { handler.post { applyLayout(false) } }
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {}
    }

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 140)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 止まりかけ（onDestroy の最中）。この間は通知を出し直さない（停止したのに「未接続」の通知がよみがえるのを防ぐ） */
    private var dying = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        ProtoBus.service = this
        ProtoBus.clearHistory()   // チャット履歴は常駐の間だけ
        prefs = Prefs.get(this)

        // オーバーレイ用の「窓の文脈」を作る（回転・画面サイズを正しく受け取るため）
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        winCtx = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        winCtx.registerComponentCallbacks(winCallbacks)
        wm = winCtx.getSystemService(WindowManager::class.java)

        view = ProtoView(winCtx)
        fun overlayLp(touchable: Boolean, name: String) = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = name
        }
        lp = overlayLp(false, "Proto")
        touchView = android.view.View(winCtx)
        touchLp = overlayLp(true, "ProtoTouch")
        setupTouch()
        applyLayout(false)
        try {
            wm.addView(view, lp)
            wm.addView(touchView, touchLp)
            added = true
            syncTouch()
        } catch (e: Exception) {
            Log.e(TAG, "オーバーレイを出せなかった", e)
            stopSelf()
            return
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= 33) registerReceiver(battery, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(battery, filter)
        sticky?.let { battery.onReceive(this, it) }

        view.hue = prefs.getInt(Prefs.HUE, 0)
        view.glow = prefs.getBoolean(Prefs.GLOW, false)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        call = ProtoCall(this, this)
        update()
        handler.post(tick)
        ProtoBus.onConn?.invoke()   // ダッシュボードのスイッチ・表示を「起動中」に合わせる
        // ダッシュボードでスイッチを入れた時は、その場でPCにつなぐ（アプリが前にいる今ならマイクも使える）
        handler.postDelayed({
            if (ProtoBus.service === this && (ProtoBus.dashboardVisible || ProtoBus.panel != null) && !ProtoBus.paused) call?.connect()
        }, 300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                try {
                    getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
                } catch (_: Exception) {}
                stopSelf()
            }
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_SPEAKER_MUTE -> setSpeakerMuted(true)
            ACTION_SPEAKER_UNMUTE -> setSpeakerMuted(false)
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (added) handler.post { applyLayout(false) }
    }

    override fun onDestroy() {
        dying = true
        if (ProtoBus.service === this) ProtoBus.service = null   // 先に外す（片付け中の接続切れで通知を出し直さないように）
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        try {
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (_: Exception) {}
        if (camPreviewRenderer != null) {
            val renderer = camPreviewRenderer
            camPreviewRenderer = null
            if (renderer != null) {
                call?.detachCameraPreview(renderer)
                try { wm.removeView(renderer) } catch (_: Exception) {}
                renderer.release()
            }
        }
        call?.release()
        call = null
        handler.removeCallbacksAndMessages(null)
        yAnim?.cancel()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (added) {
            try { unregisterReceiver(battery) } catch (_: Exception) {}
            try { wm.removeView(view) } catch (_: Exception) {}
            try { wm.removeView(touchView) } catch (_: Exception) {}
            added = false
        }
        if (::winCtx.isInitialized) winCtx.unregisterComponentCallbacks(winCallbacks)
        ProtoBus.paused = false
        ProtoBus.speakerMuted = false
        ProtoBus.panel?.finish()
        if (ProtoBus.service === this) ProtoBus.service = null
        ProtoBus.clearHistory()
        // 片付けの途中で何か出し直していても、最後にもう一度消す
        try { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (_: Exception) {}
        ProtoBus.onConn?.invoke()   // ダッシュボードのスイッチ・表示を「停止中」に合わせる
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 位置

    /** パネルが開いた・閉じた・動いた（キーボードが出た等）とき */
    fun onPanelMoved(spin: Boolean = false) = applyLayout(true, spin)

    private fun applyLayout(animate: Boolean, spin: Boolean = false) {
        if (!::view.isInitialized) return
        val b = wm.currentWindowMetrics.bounds
        val density = resources.displayMetrics.density
        val scale = prefs.getFloat(Prefs.SCALE, 1f).coerceIn(0.6f, 1.6f)
        val w = (BASE_WIDTH_DP * density * scale).toInt()
        val h = (w * (ProtoView.IMG_H + ProtoView.TOP_PAD) / ProtoView.IMG_W).toInt()
        val left = prefs.getString(Prefs.SIDE, "left") != "right"
        val margin = (4 * density).toInt()

        minX = b.left + margin
        maxX = b.right - w - margin
        lp.width = w
        lp.height = h
        if (xAnim?.isRunning != true) {
            view.mirror = !left
            lp.x = if (left) minX else maxX
        }

        val panelTop = ProtoBus.panelTop
        view.showPaws = panelTop != null
        view.lookUp = panelTop == null
        val anchor = panelTop ?: b.bottom
        val cut = ((ProtoView.CUT_Y + ProtoView.TOP_PAD) * w / ProtoView.IMG_W).toInt()
        val targetY = anchor - cut

        yAnim?.cancel()
        val falling = animate && added && panelTop == null && targetY - lp.y > h / 6
        if (falling) {
            // 画面の下へ戻る時：重力っぽく落ちる（落ちている間は「わっ」の顔）。アプリを閉じた時はくるっと回りながら
            val ms = if (spin) 560L else 440L
            fallUntil = SystemClock.elapsedRealtime() + ms
            if (spin) view.spinOnce(ms)
            view.flail(ms)   // バーを閉じて落ちる時も前足を振る（しがみつきポーズのまま落ちないように）
            yAnim = ValueAnimator.ofInt(lp.y, targetY).apply {
                duration = ms
                interpolator = android.view.animation.AccelerateInterpolator(1.6f)
                addUpdateListener {
                    lp.y = it.animatedValue as Int
                    if (added) { wm.updateViewLayout(view, lp); syncTouch() }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        view.snapLook()   // 着地した時に姿勢もそろえる（着いてから沈んで「浮いて」見えないように）
                        view.hop()        // 着地でぴょこっ
                    }
                })
                start()
            }
        } else if (animate && added && lp.y != targetY) {
            yAnim = ValueAnimator.ofInt(lp.y, targetY).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    lp.y = it.animatedValue as Int
                    if (added) { wm.updateViewLayout(view, lp); syncTouch() }
                }
                start()
            }
        } else {
            lp.y = targetY
        }
        if (added) { wm.updateViewLayout(view, lp); syncTouch() }
    }

    /**
     * 触る窓を、顔のまわりだけに合わせる（絵の座標で囲む）。
     * しまっている時は画面の下から見えている顔の部分だけ、バーが開いている時は頭から前足まで。
     */
    private fun syncTouch() {
        if (!added) return
        val s = lp.width / ProtoView.IMG_W
        val open = ProtoBus.panelTop != null
        var x0 = 130f; val x1 = 420f
        val y0 = if (open) 40f else 120f
        val y1 = if (open) 300f else ProtoView.CUT_Y
        var left = x0; var right = x1
        if (view.mirror) { left = ProtoView.IMG_W - x1; right = ProtoView.IMG_W - x0 }
        touchLp.x = lp.x + (left * s).toInt()
        touchLp.y = lp.y + ((ProtoView.TOP_PAD + y0) * s).toInt()
        touchLp.width = ((right - left) * s).toInt()
        touchLp.height = ((y1 - y0) * s).toInt()
        wm.updateViewLayout(touchView, touchLp)
    }

    // ------------------------------------------------------------------ 操作

    private fun setupTouch() {
        val gd = GestureDetector(winCtx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { downRawX = e.rawX; return true }
            override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(); return true }
            override fun onLongPress(e: MotionEvent) {
                grabbed = true      // 掴まれた：見開き目になる（振動はしない）
                wakeUp()
            }
        })
        touchView.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if (grabbed && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) {
                grabbed = false
                val dx = e.rawX - downRawX
                val flick = 40 * resources.displayMetrics.density
                if (dx > flick) jumpTo(right = true) else if (dx < -flick) jumpTo(right = false)
            }
            true
        }
    }

    /** 左下⇔右下の定位置へぴょんと飛び移る。着いたら設定にも保存する */
    private fun jumpTo(right: Boolean) {
        val nowRight = prefs.getString(Prefs.SIDE, "left") == "right"
        if (right == nowRight) return
        val target = if (right) maxX else minX
        val mid = (minX + maxX) / 2
        xAnim?.cancel()
        view.hop()
        xAnim = ValueAnimator.ofInt(lp.x, target).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.x = it.animatedValue as Int
                view.mirror = lp.x > mid   // 真ん中を越えたら向きを変える
                if (added) { wm.updateViewLayout(view, lp); syncTouch() }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    prefs.edit().putString(Prefs.SIDE, if (right) "right" else "left").apply()
                }
            })
            start()
        }
    }

    /** タップ：パネルを開く（寝ていたら起きる）。開いていたら閉じる */
    private fun onTap() {
        val panel = ProtoBus.panel
        if (panel != null) { panel.finish(); return }
        if (ProtoBus.paused) setPaused(false)   // 一時停止中にタップ → 再開
        wakeUp()
        startActivity(Intent(this, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun wakeUp() {
        val now = SystemClock.elapsedRealtime()
        if (ProtoBus.asleep) {
            wideUntil = now + 700
            view.hop()
        }
        manualSleep = false
        awakeUntil = now + 10 * 60_000L
        lastTouch = now
    }

    /** 設定画面のテスト：考え中の「？」を5秒出す */
    fun testThinking() {
        wakeUp()
        testThinkUntil = SystemClock.elapsedRealtime() + 5000
    }

    /** 設定画面のテスト：口パク（見開き目＋開いた口と波線の口を交互）を4秒 */
    fun testTalking() {
        wakeUp()
        testTalkUntil = SystemClock.elapsedRealtime() + 4000
    }

    /** うれしい：にっこり顔でぴょんと跳ねる（充電をつないだ時。あとでPCからの「うれしい」合図にも使う） */
    fun beHappy(ms: Long = 2500) {
        if (!::view.isInitialized) return
        wakeUp()
        happyUntil = SystemClock.elapsedRealtime() + ms
        view.hop()
    }

    fun sleepNow() {
        manualSleep = true
        awakeUntil = 0L
    }

    // ------------------------------------------------------------------ 状態（顔と動き）

    /** おやすみタイム中か（日をまたぐ設定にも対応） */
    private fun isNight(): Boolean {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val start = prefs.getInt(Prefs.SLEEP_START, 23 * 60)
        val end = prefs.getInt(Prefs.SLEEP_END, 6 * 60)
        return if (start <= end) m in start until end else m >= start || m < end
    }

    private fun update() {
        val now = SystemClock.elapsedRealtime()
        val panelOpen = ProtoBus.panelTop != null
        if (panelOpen) lastTouch = now
        // しゃべっている間は起きている（PCが「話したいことがある」と話しかけてきた時も、ここで目が覚める）
        if (ProtoBus.talking) { if (ProtoBus.asleep) wakeUp(); lastTouch = now }

        val idleMin = prefs.getInt(Prefs.IDLE_MIN, 10)
        val night = prefs.getBoolean(Prefs.NIGHT, true) && isNight()
        val idleMs = now - lastTouch
        val idle = idleMin > 0 && idleMs > idleMin * 60_000L
        // 居眠りの手前：半分の時間がたったら眠そう（半目、ときどきハッと目を開ける）
        val drowsy = idleMin > 0 && idleMs > idleMin * 30_000L
        // 夜はタップで起こすと10分だけ起きている（awakeUntil）。居眠りは「触っていない時間」だけで決まる
        val asleep = !panelOpen && (manualSleep || (night && now > awakeUntil) || idle)
        // しばらく相手にされていない（おやすみタイム以外）→ 居眠り：寝目でこっくり、ずり落ちそう
        val dozing = asleep && !night && !manualSleep
        ProtoBus.asleep = asleep
        val low = battLevel <= 15 && !charging
        view.badge = when {
            charging -> ProtoView.Badge.CHARGE
            low -> ProtoView.Badge.LOW
            else -> ProtoView.Badge.NONE
        }
        val thinking = ProtoBus.thinking || now < testThinkUntil
        view.showHatena = thinking
        view.showListen = ProtoBus.listening && !ProtoBus.talking
        ProtoBus.rec = ProtoBus.camOn || ProtoBus.shareOn

        val (face, motion) = when {
            ProtoBus.talking || now < testTalkUntil -> {
                if (Random.nextFloat() < 0.65f) view.mouthOpen = !view.mouthOpen
                ProtoView.Face.TALK to ProtoView.Motion.TALK_UP
            }
            ProtoBus.rec -> (if (ProtoBus.shareOn) ProtoView.Face.CAST else ProtoView.Face.REC) to ProtoView.Motion.BOB  // 見せてもらっている：わくわく
            thinking -> ProtoView.Face.BLINK to ProtoView.Motion.SWAY_SLOW   // 考え中：細目で「？」
            SystemClock.elapsedRealtime() < fallUntil -> ProtoView.Face.WIDE to ProtoView.Motion.SWAY   // 落ちている：わっ
            grabbed || now < wideUntil -> ProtoView.Face.WIDE to ProtoView.Motion.SWAY
            asleep -> ProtoView.Face.SLEEP to (if (dozing) ProtoView.Motion.DOZE else ProtoView.Motion.BREATH)
            now < happyUntil -> ProtoView.Face.HAPPY to ProtoView.Motion.BOB  // うれしい：ぴょこぴょこ
            drowsy -> {
                if (now > nextBlink) {          // 半目のまま、たまにハッと目を開ける
                    blinkUntil = now + 500
                    nextBlink = now + 6000 + Random.nextLong(6000)
                }
                (if (now < blinkUntil) ProtoView.Face.IDLE else ProtoView.Face.BLINK) to ProtoView.Motion.SWAY_SLOW
            }
            // 電池マーク（充電中＝緑／少ない＝赤）はおでこに重ねるだけ。顔と動きはふだん通り（少ない時はうなだれる）
            else -> {
                if (now > nextBlink) {
                    blinkUntil = now + 150
                    nextBlink = now + 2500 + Random.nextLong(4000)
                }
                (if (now < blinkUntil) ProtoView.Face.BLINK else ProtoView.Face.IDLE) to
                    (if (low) ProtoView.Motion.DROOP else ProtoView.Motion.SWAY)
            }
        }
        if (face != ProtoView.Face.TALK) view.mouthOpen = false
        view.showZzz = face == ProtoView.Face.SLEEP
        view.face = face
        view.motion = motion
    }

    // ------------------------------------------------------------------ 通知（常駐に必要）
    // ふつうの通知（メディア欄には出さない：音楽アプリの操作と取り合わないように）。
    // 本文に接続状態、ボタンは「一時停止／再開」「音声ミュート／解除」「停止」。
    // 一時停止は接続を切って眠り、音声ミュートは LiveKit の受信音声だけを切る。

    fun setPaused(p: Boolean) {
        ProtoBus.paused = p
        if (p) { call?.disconnect(); sleepNow() } else wakeUp()   // 一時停止＝PCとの接続も切る
        refreshNotification()
        ProtoBus.onConn?.invoke()   // ダッシュボードのボタン表示も合わせる
    }

    /** 話した・送った等：触ったのと同じ扱い（居眠りしない） */
    fun noteActivity() {
        lastTouch = SystemClock.elapsedRealtime()
    }

    private var fgsMic = false
    private var fgsCam = false
    private var fgsShare = false

    private var camPreviewRenderer: TextureViewRenderer? = null

    /** マイク・カメラ・画面共有を使う常駐に切り替える／戻す（Android 14 は使う種類を宣言する必要がある） */
    fun setMicFgs(on: Boolean) { fgsMic = on; applyFgs() }
    fun setCamFgs(on: Boolean) { fgsCam = on; applyFgs() }
    fun setShareFgs(on: Boolean) { fgsShare = on; applyFgs() }

    private fun applyFgs() {
        if (dying || Build.VERSION.SDK_INT < 34) return
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (fgsMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
            (if (fgsCam) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
            (if (fgsShare) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "常駐の切替失敗", e)
        }
    }

    /** カメラONの時、画面右上に120x160dpのリアルタイムプレビュー窓を出す */
    fun syncCameraPreview() {
        handler.post {
            val show = ProtoBus.camOn
            if (show && camPreviewRenderer == null && ::winCtx.isInitialized) {
                val density = resources.displayMetrics.density
                val w = (120 * density).toInt()
                val h = (160 * density).toInt()
                val r = 12 * density
                val renderer = TextureViewRenderer(winCtx).apply {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: android.view.View, o: Outline) {
                            o.setRoundRect(0, 0, v.width, v.height, r)
                        }
                    }
                    clipToOutline = true
                }
                val lpCam = WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = (16 * density).toInt()
                    y = (48 * density).toInt()
                    title = "ProtoCamPreview"
                }
                try {
                    wm.addView(renderer, lpCam)
                    camPreviewRenderer = renderer
                    call?.attachCameraPreview(renderer)
                } catch (e: Exception) {
                    Log.w(TAG, "カメラプレビュー画面の追加に失敗", e)
                }
            } else if (!show && camPreviewRenderer != null) {
                val renderer = camPreviewRenderer
                camPreviewRenderer = null
                if (renderer != null) {
                    call?.detachCameraPreview(renderer)
                    try { wm.removeView(renderer) } catch (_: Exception) {}
                    renderer.release()
                }
            }
        }
    }

    fun setSpeakerMuted(muted: Boolean) {
        ProtoBus.speakerMuted = muted
        call?.setSpeakerMuted(muted)
        ProtoBus.panel?.showConn()
        refreshNotification()
        ProtoBus.onConn?.invoke()
    }

    private fun buildNotification(): Notification {
        val paused = ProtoBus.paused
        val speakerMuted = ProtoBus.speakerMuted
        fun svc(code: Int, action: String) = PendingIntent.getService(
            this, code, Intent(this, ProtoService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE  // アプリを開く＝設定
        )
        val toggle = Notification.Action.Builder(
            Icon.createWithResource(this, if (paused) R.drawable.ic_play else R.drawable.ic_pause),
            if (paused) "再開" else "一時停止", svc(3, if (paused) ACTION_RESUME else ACTION_PAUSE)
        ).build()
        val mute = Notification.Action.Builder(
            Icon.createWithResource(this, if (speakerMuted) R.drawable.ic_volume_up else R.drawable.ic_volume_off),
            if (speakerMuted) "音声ON" else "ミュート",
            svc(4, if (speakerMuted) ACTION_SPEAKER_UNMUTE else ACTION_SPEAKER_MUTE)
        ).build()
        val stop = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stop), "停止", svc(1, ACTION_STOP)
        ).build()
        // Tailscale 風：見出しはアプリ名（自動）、大きい字は状態だけ（短く1行に収める）
        val state = listOfNotNull(
            ProtoBus.conn.label,
            if (paused) "一時停止中" else null,
            if (speakerMuted) "ミュート中" else null,
        ).joinToString("・")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_proto)
            .setContentTitle(state)
            .setColor(0xFF6FC8E0.toInt())   // 通知の左の丸を水色に（中のシルエットは白く抜ける）
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(toggle)
            .addAction(mute)
            .addAction(stop)
            .build()
    }

    fun refreshNotification() {
        if (dying) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "プロト常駐", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
