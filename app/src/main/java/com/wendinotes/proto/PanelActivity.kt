package com.wendinotes.proto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * プロトをタップすると下から出てくるパネル。
 *   [カメラ] [⚙(設定)] [📋 ペーストして送信]
 * チャット履歴を左右の吹き出し（自分＝右、プロト＝左）で表示する。
 */
class PanelActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var connLabel: TextView
    private val historyHook: () -> Unit = { runOnUiThread { refreshHistory() } }
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyScroll: ScrollView
    private lateinit var pasteSendBtn: TextView
    private lateinit var camBtn: ImageButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var th: BarTheme
    private var resumed = false

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { report() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toggleCamera() {
        val call = ProtoBus.service?.call ?: return
        if (!ProtoBus.camOn && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAM)
            return
        }
        call.setCamera(!ProtoBus.camOn)
    }

    private fun pasteAndSend() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        }?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "クリップボードが空です", Toast.LENGTH_SHORT).show()
            return
        }
        ProtoBus.addHistory("自分", text)
        ProtoBus.service?.call?.sendText(text)
        refreshHistory()
        Toast.makeText(this, "URLを送信しました", Toast.LENGTH_SHORT).show()
    }

    private fun refreshHistory() {
        if (!::historyContainer.isInitialized) return
        historyContainer.removeAllViews()
        ChatBubbles.render(this, historyContainer, ProtoBus.history, ChatBubbles.forBar(th), 13f)
        historyScroll.post { historyScroll.scrollTo(0, 0) }  // 新しい発言は一番上
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ProtoBus.service?.call?.setCamera(true)
        }
    }

    companion object {
        private const val REQ_CAM = 21
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProtoBus.panel = this
        setFinishOnTouchOutside(true)
        th = BarTheme.current(this)
        ProtoBus.addHistoryListener(historyHook)

        val r = dp(18).toFloat()
        val base = GradientDrawable().apply {
            setColor(th.bg)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(dp(1), th.stroke)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(12))
            background = base
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: android.view.View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height + r.toInt(), r)
                }
            }
            clipToOutline = true
        }

        val patternName = ProtoArt.current(this)
        root.addOnLayoutChangeListener { v, l, t, rr, b, ol, ot, orr, ob ->
            if (rr - l == orr - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            val bmp = ProtoArt.pattern(patternName, th, rr - l, b - t, resources.displayMetrics.density)
            v.background = if (bmp == null) base
            else android.graphics.drawable.LayerDrawable(arrayOf(base, android.graphics.drawable.BitmapDrawable(resources, bmp)))
        }

        // 接続状態（右上）
        connLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(th.ink)
            alpha = 0.85f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        root.addView(connLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))

        // チャット履歴ビュー（スクロール表示・左右吹き出し）
        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        historyScroll = ScrollView(this).apply {
            background = rounded(th.field, 12, th.stroke)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(36))
            addView(historyContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(historyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // アイコンボタン生成ヘルパー
        fun iconButton(icon: Int, desc: String) = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = desc
            background = rounded(th.field, 22, th.stroke)
            setColorFilter(th.ink)
            scaleType = ImageView.ScaleType.CENTER
        }

        camBtn = iconButton(R.drawable.ic_camera, "カメラ").apply {
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { toggleCamera() }
        }

        settingsBtn = iconButton(android.R.drawable.ic_menu_preferences, "設定").apply {
            setOnClickListener {
                startActivity(Intent(this@PanelActivity, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }

        // ペーストして送信ボタン
        pasteSendBtn = TextView(this).apply {
            text = "📋 ペースト送信"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF14200F.toInt())
            background = rounded(th.accent, 18)
            setPadding(dp(12), 0, dp(12), 0)
            includeFontPadding = false
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { pasteAndSend() }
        }

        row.addView(camBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })
        row.addView(settingsBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })
        row.addView(pasteSendBtn, LinearLayout.LayoutParams(0, dp(44), 1f))

        setContentView(root)
        showConn()
        refreshHistory()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.setWindowAnimations(android.R.style.Animation_InputMethod)
        window.decorView.setPadding(0, 0, 0, 0)
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    fun showConn() {
        if (!::connLabel.isInitialized || !::camBtn.isInitialized) return
        val on = ProtoBus.conn == ProtoBus.Conn.ON
        camBtn.isEnabled = on
        camBtn.alpha = if (on) 1f else 0.4f
        pasteSendBtn.isEnabled = on
        pasteSendBtn.alpha = if (on) 1f else 0.4f

        val active = ProtoBus.camOn
        camBtn.background = rounded(if (active) th.accent else th.field, 22, th.stroke)
        camBtn.setColorFilter(if (active) 0xFF14200F.toInt() else th.ink)

        val c = ProtoBus.conn
        val sp = android.text.SpannableString("● " + c.label + if (ProtoBus.speakerMuted) "（スピーカーOFF）" else "")
        sp.setSpan(android.text.style.ForegroundColorSpan(c.color), 0, 1, 0)
        connLabel.text = sp
    }

    private fun report() {
        if (!resumed) return
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        if (root.height > 0) ProtoBus.setPanelTop(loc[1])
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ProtoBus.service?.call?.connect()
        root.post { report() }
    }

    override fun onPause() {
        resumed = false
        if (!ProtoBus.dashboardVisible) ProtoBus.setPanelTop(null)   // ⚙でダッシュボードへ移った時は、ダッシュボード側の位置を消さない
        super.onPause()
    }

    override fun onDestroy() {
        if (ProtoBus.panel === this) ProtoBus.panel = null
        ProtoBus.removeHistoryListener(historyHook)   // ダッシュボードの履歴更新はそのまま
        root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        if (!ProtoBus.dashboardVisible) ProtoBus.setPanelTop(null)
        super.onDestroy()
    }
}
