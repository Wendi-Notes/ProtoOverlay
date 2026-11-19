package com.wendinotes.proto

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * プロト メインダッシュボード画面（Tailscale風）。
 * 上部：ステータスカード ＋ 右端に大きい起動スイッチ（ProtoSwitch）
 * 中部：左（または右）にプロトの顔（チャット履歴の箱の上端に完璧にしがみつく、停止時は呼吸・眠り＋ZZZモーション）、反対側に「一時停止」「ミュート」
 * 下部：チャット履歴（左右の吹き出しスタイル）
 * ジェスチャー：画面を左から右へスワイプで詳細設定を開く。
 */
class SettingsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var statusTv: TextView
    private lateinit var protoSwitch: ProtoSwitch
    private var protoViewDashboard: ProtoView? = null
    private var onScreen = false
    private val connHook: () -> Unit = {
        runOnUiThread {
            updateStatus()
            if (ProtoBus.conn == ProtoBus.Conn.ON) ProtoBus.service?.call?.refreshMic()
        }
    }
    private var builtTheme = ""
    private lateinit var historyContainer: LinearLayout
    private val historyHook: () -> Unit = { runOnUiThread { if (onScreen) renderHistory() } }
    private lateinit var historySv: ScrollView
    private lateinit var btnStartStop: Button
    private lateinit var btnPause: Button
    private lateinit var btnMute: Button

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && e2.rawX - e1.rawX > 120 && velocityX > 150 && Math.abs(e2.rawY - e1.rawY) < Math.abs(e2.rawX - e1.rawX)) {
                    openDetailSettings()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(this)
        builtTheme = Ui.themeName   // この画面を作った時の色（各種設定で Ui が書き換わっても比べられるように自分で持つ）

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // ---- 1. 上部ステータスヘッダー（右端に大きい起動スイッチ） ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Ui.LINE)
            }
        }
        val headerTextLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "プロトAI"
            textSize = 18f
            setTextColor(Ui.TEXT)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        statusTv = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(0, dp(4), 0, 0)
        }
        headerTextLayout.addView(titleTv)
        headerTextLayout.addView(statusTv)

        protoSwitch = ProtoSwitch(this).apply {
            onToggle = { toggleService() }
        }

        header.addView(headerTextLayout, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(protoSwitch, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        header.isClickable = true
        header.setOnClickListener { protoSwitch.performClick() }

        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(16), dp(16), dp(16), dp(8))
        })

        // ---- 2. 真ん中：左にプロトの顔（正確に箱のふちにしがみつく眠りモーション）、反対側に「一時停止」「ミュート」 ----
        val protoLeft = Prefs.get(this).getString(Prefs.SIDE, "left") != "right"
        val pw = (180 * resources.displayMetrics.density * Prefs.get(this).getFloat(Prefs.SCALE, 1f).coerceIn(0.6f, 1.6f)).toInt()
        val ph = (pw * ProtoView.IMG_H / ProtoView.IMG_W).toInt()
        val cling = (pw * ProtoView.CUT_Y / ProtoView.IMG_W).toInt()   // 絵の上端から「しがみつく線」までの高さ
        val mid = android.widget.FrameLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(12)) }

        fun protoLp() = android.widget.FrameLayout.LayoutParams(pw, ph).apply {
            gravity = Gravity.TOP or (if (protoLeft) Gravity.START else Gravity.END)
        }

        btnStartStop = actionButton("起動") { toggleService() }
        btnPause = actionButton("一時停止") { togglePause() }
        btnMute = actionButton("ミュート") { toggleMute() }
        val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctrl.addView(btnPause, LinearLayout.LayoutParams(dp(112), dp(44)).apply { marginEnd = dp(8) })
        ctrl.addView(btnMute, LinearLayout.LayoutParams(dp(112), dp(44)))
        mid.addView(ctrl, android.widget.FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or (if (protoLeft) Gravity.END else Gravity.START)
            topMargin = (cling - dp(44) - dp(8)).coerceAtLeast(0)
        })

        // アバター：topMargin = 0 にすることで、画像の CUT_Y（しがみつく線）が exatamente 履歴ボックスのトップ（cling）に一致する
        protoViewDashboard = ProtoView(this).apply {
            face = ProtoView.Face.SLEEP
            motion = ProtoView.Motion.BREATH
            showZzz = true
            if (!protoLeft) mirror = true
        }
        mid.addView(protoViewDashboard, protoLp().apply { topMargin = 0 })

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        historySv = ScrollView(this).apply {
            addView(historyContainer, LinearLayout.LayoutParams(-1, -2))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Ui.SURFACE)
                setStroke(dp(1), Ui.LINE)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(56))
            setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncProtoAnchor() }
        }
        mid.addView(historySv, android.widget.FrameLayout.LayoutParams(-1, -1).apply { topMargin = cling })

        root.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        Ui.paintWindow(this)
        updateStatus()
        renderHistory()
    }

    private fun openDetailSettings() {
        startActivity(Intent(this, DetailSettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (BarTheme.app(this).name + "/" + BarTheme.accent(this).name != builtTheme) { recreate(); return }
        onScreen = true
        ProtoBus.dashboardVisible = true
        ProtoBus.onConn = connHook
        ProtoBus.addHistoryListener(historyHook)   // 履歴が空のまま開いても、新しい発言ですぐ書き換わるように
        if (ProtoBus.service != null && !ProtoBus.paused) ProtoBus.service?.call?.connect()
        updateStatus()
        renderHistory()
        syncProtoAnchor()
        window.decorView.postDelayed({ syncProtoAnchor(); if (onScreen) renderHistory() }, 400)
    }

    /**
     * 画面の入力を失った瞬間（ホームへ戻るジェスチャーの始まり・通知を引き下ろした時など）。
     * onPause はジェスチャーの動きが終わってから（約1秒後）なので、ここで先に落とし始める。
     * 戻ってきて入力が戻ったら、またチャット履歴の箱のふちへ戻す。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (onScreen) { ProtoBus.dashboardVisible = true; syncProtoAnchor() }
        } else if (onScreen && !isChangingConfigurations) {
            ProtoBus.setPanelTop(null, spin = true)
        }
    }

    /** ホームボタン等でアプリを離れる瞬間（onPause より先に来る）。取り残されないよう、すぐ落ち始める */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        onScreen = false
        ProtoBus.dashboardVisible = false
        ProtoBus.setPanelTop(null, spin = true)
    }

    override fun onPause() {
        super.onPause()
        onScreen = false
        ProtoBus.dashboardVisible = false
        if (ProtoBus.onConn === connHook) ProtoBus.onConn = null
        ProtoBus.removeHistoryListener(historyHook)
        ProtoBus.setPanelTop(null, spin = true)   // アプリを閉じた：くるっと回りながら画面の下へ落ちる
    }

    private fun syncProtoAnchor() {
        if (!::historySv.isInitialized || !onScreen) return
        historySv.post {
            if (historySv.height == 0) return@post
            val loc = IntArray(2)
            historySv.getLocationOnScreen(loc)
            ProtoBus.setPanelTop(loc[1])
        }
    }

    private fun updateStatus() {
        if (!::statusTv.isInitialized) return
        val running = ProtoBus.service != null
        val conn = ProtoBus.conn.label
        val paused = ProtoBus.paused
        val muted = ProtoBus.muted

        statusTv.text = if (running) {
            "● 起動中 | 接続: $conn" + (if (paused) " (一時停止)" else "") + (if (muted) " (マイクOFF)" else "")
        } else {
            "○ 停止中"
        }
        statusTv.setTextColor(if (running) Ui.OK else Ui.SUB)
        if (::protoSwitch.isInitialized) protoSwitch.setChecked(running, false)

        btnPause.text = if (paused) "再開" else "一時停止"
        btnPause.background = GradientDrawable().apply {
            setColor(if (paused) Ui.ACCENT else Ui.SURFACE2)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Ui.LINE)
        }
        btnPause.setTextColor(if (paused) Ui.ON_ACCENT else Ui.TEXT)

        btnMute.text = if (muted) "ミュート解除" else "ミュート"
        btnMute.background = GradientDrawable().apply {
            setColor(if (muted) Ui.ACCENT else Ui.SURFACE2)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Ui.LINE)
        }
        btnMute.setTextColor(if (muted) Ui.ON_ACCENT else Ui.TEXT)

        protoViewDashboard?.visibility = if (!running) View.VISIBLE else View.INVISIBLE
    }

    private fun toggleService() {
        val running = ProtoBus.service != null
        if (!running) {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, ProtoService::class.java))
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        } else {
            stopService(Intent(this, ProtoService::class.java))
        }
        window.decorView.postDelayed({ updateStatus(); syncProtoAnchor() }, 500)
    }

    private fun togglePause() {
        val running = ProtoBus.service != null
        if (!running) return
        ProtoBus.service?.setPaused(!ProtoBus.paused)
        updateStatus()
    }

    private fun toggleMute() {
        val running = ProtoBus.service != null
        if (!running) return
        ProtoBus.service?.setMuted(!ProtoBus.muted)
        updateStatus()
    }

    private fun renderHistory() {
        if (!::historyContainer.isInitialized) return
        historyContainer.removeAllViews()

        if (ProtoBus.history.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "（会話履歴はありません。プロトをタップして話しかけるか、URLを送信してください）"
                textSize = 13f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(32))
            }
            historyContainer.addView(emptyTv)
            return
        }

        ChatBubbles.render(this, historyContainer, ProtoBus.history, ChatBubbles.app(), 14f)
        historySv.post { historySv.scrollTo(0, 0) }
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Ui.TEXT)
        background = GradientDrawable().apply {
            setColor(Ui.SURFACE2)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Ui.LINE)
        }
        setOnClickListener { onClick() }
    }
}
