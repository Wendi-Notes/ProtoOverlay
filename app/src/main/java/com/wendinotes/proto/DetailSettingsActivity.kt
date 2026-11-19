package com.wendinotes.proto

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 各種詳細設定画面（表示、眠り、接続）。
 * ダッシュボードのメニュー（☰）から開く。
 */
class DetailSettingsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val prefs by lazy { Prefs.get(this) }

    private lateinit var body: LinearLayout
    private val tabButtons = mutableListOf<TextView>()
    private val tabs = listOf("表示", "眠り", "接続")
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // ---- ヘルパーバー（戻るボタン ＋ タイトル） ----
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Ui.SURFACE) }
        }
        val btnBack = TextView(this).apply {
            text = "‹ 戻る"
            textSize = 16f
            setTextColor(Ui.ACCENT)
            setPadding(dp(8), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        }
        val titleTv = TextView(this).apply {
            text = "各種設定"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        topBar.addView(btnBack)
        topBar.addView(titleTv)
        root.addView(topBar)

        // ---- タブ ----
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(Ui.SURFACE) }
        }
        tabs.forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showTab(i) }
            }
            tabButtons += tv
            tabRow.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i < tabs.lastIndex) dp(4) else 0
            })
        }
        root.addView(tabRow)

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        root.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        Ui.paintWindow(this)
        showTab(savedInstanceState?.getInt("tab") ?: 0)
    }

    private fun showTab(i: Int) {
        current = i
        tabButtons.forEachIndexed { j, tv ->
            val on = j == i
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (on) Ui.ACCENT else Ui.SURFACE2)
            }
            tv.setTextColor(if (on) Ui.BG else Ui.SUB)
        }
        body.removeAllViews()
        when (i) {
            0 -> buildLook()
            1 -> buildSleep()
            else -> buildConnect()
        }
    }

    private fun head(t: String) = body.addView(TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(Ui.ACCENT)
        setPadding(0, dp(18), 0, dp(6))
    })

    private fun note(t: String) = body.addView(TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Ui.SUB)
        setPadding(0, dp(2), 0, dp(4))
    })

    private fun button(label: String, onClick: () -> Unit) = body.addView(Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply {
            setColor(Ui.SURFACE)
            cornerRadius = dp(8).toFloat()
        }
        setOnClickListener { onClick() }
    })

    // ------------------------------------------------------------------ 表示・一般
    private fun buildLook() {
        head("表示する側")
        val idLeft = View.generateViewId()
        val idRight = View.generateViewId()
        val side = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        side.addView(RadioButton(this).apply { text = "左下"; id = idLeft; setTextColor(Ui.TEXT) })
        side.addView(RadioButton(this).apply { text = "右下（左右反転）"; id = idRight; setTextColor(Ui.TEXT) })
        side.check(if (prefs.getString(Prefs.SIDE, "left") == "right") idRight else idLeft)
        side.setOnCheckedChangeListener { _, id -> prefs.edit().putString(Prefs.SIDE, if (id == idRight) "right" else "left").apply() }
        body.addView(side)
        note("プロトを長押しして左右にはじいても移動できます")

        head("大きさ")
        val sizeLabel = TextView(this).apply { textSize = 12f; setTextColor(Ui.SUB) }
        val size = SeekBar(this).apply {
            max = 100
            progress = ((prefs.getFloat(Prefs.SCALE, 1f) - 0.6f) * 100).toInt()
        }
        sizeLabel.text = "${size.progress + 60}%"
        size.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                sizeLabel.text = "${p + 60}%"
                if (fromUser) prefs.edit().putFloat(Prefs.SCALE, (p + 60) / 100f).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        body.addView(sizeLabel)
        body.addView(size)

        head("顔の光り方")
        val idGlowOff = View.generateViewId()
        val idGlowOn = View.generateViewId()
        val glowGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        glowGroup.addView(RadioButton(this).apply { text = "元の色"; id = idGlowOff; setTextColor(Ui.TEXT) })
        glowGroup.addView(RadioButton(this).apply { text = "アイコン風（白く光る）"; id = idGlowOn; setTextColor(Ui.TEXT) })
        glowGroup.check(if (prefs.getBoolean(Prefs.GLOW, false)) idGlowOn else idGlowOff)
        glowGroup.setOnCheckedChangeListener { _, id -> prefs.edit().putBoolean(Prefs.GLOW, id == idGlowOn).apply() }
        body.addView(glowGroup)

        head("バーの色")
        themeChips(Prefs.BAR_THEME, BarTheme.current(this).name) { }
        head("アプリの背景の色")
        themeChips(Prefs.APP_THEME, BarTheme.app(this).name) { recreate() }
        head("アクセントの色")
        themeChips(Prefs.ACCENT, Accents.name(this)) { recreate() }
        note("起動スイッチ・自分の吹き出し・送るボタンなどの色")
    }

    /** 色の組み合わせを3つずつ並べて選ぶ（バーの色・アプリの背景の色・アクセントの色で共通） */
    private fun themeChips(key: String, currentName: String, onPicked: () -> Unit) {
        var cur = currentName
        val chips = mutableListOf<Pair<BarTheme, TextView>>()
        fun paint() = chips.forEach { (th, tv) ->
            val on = th.name == cur
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(th.field)
                setStroke(dp(if (on) 3 else 1), if (on) th.accent else th.stroke)
            }
            tv.setTextColor(th.ink)
        }
        BarTheme.ALL.chunked(3).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { th ->
                val tv = TextView(this).apply {
                    text = th.name
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener {
                        cur = th.name
                        prefs.edit().putString(key, th.name).apply()
                        paint()
                        onPicked()
                    }
                }
                chips += th to tv
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4); bottomMargin = dp(4) })
            }
            repeat(3 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            body.addView(row)
        }
        paint()
    }

    // ------------------------------------------------------------------ 眠り
    private fun buildSleep() {
        head("おやすみタイム")
        body.addView(Switch(this).apply {
            text = "この時間は寝る"
            setTextColor(Ui.TEXT)
            isChecked = prefs.getBoolean(Prefs.NIGHT, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.NIGHT, on).apply() }
        })
        fun hhmm(m: Int) = "%d:%02d".format(m / 60, m % 60)
        fun timeButton(key: String, label: String, def: Int) = Button(this).apply {
            fun show() { text = "$label ${hhmm(prefs.getInt(key, def))}" }
            show()
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { setColor(Ui.SURFACE); cornerRadius = dp(8).toFloat() }
            setOnClickListener {
                val cur = prefs.getInt(key, def)
                TimePickerDialog(this@DetailSettingsActivity, { _, h, m ->
                    prefs.edit().putInt(key, h * 60 + m).apply(); show()
                }, cur / 60, cur % 60, true).show()
            }
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(timeButton(Prefs.SLEEP_START, "寝る", 23 * 60), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            addView(timeButton(Prefs.SLEEP_END, "起きる", 6 * 60), LinearLayout.LayoutParams(0, -2, 1f))
        })

        head("居眠り")
        val idle = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val choices = listOf(0 to "しない", 5 to "5分", 10 to "10分", 30 to "30分")
        choices.forEachIndexed { i, (_, label) -> idle.addView(RadioButton(this).apply { text = label; id = 100 + i; setTextColor(Ui.TEXT) }) }
        val cur = prefs.getInt(Prefs.IDLE_MIN, 10)
        idle.check(100 + choices.indexOfFirst { it.first == cur }.coerceAtLeast(0))
        idle.setOnCheckedChangeListener { _, id -> prefs.edit().putInt(Prefs.IDLE_MIN, choices[id - 100].first).apply() }
        body.addView(idle)
    }

    // ------------------------------------------------------------------ 接続
    private fun buildConnect() {
        head("接続先 URL")

        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }

        val urlInput = EditText(this).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(Ui.FAINT)
            hint = "https://..."
            setText(prefs.getString(Prefs.SERVER_URL, ""))
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Ui.LINE)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        val btnPaste = Button(this).apply {
            text = "ペースト"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                setColor(Ui.LINE)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                if (clipText.isNotEmpty()) {
                    urlInput.setText(clipText)
                }
            }
        }

        val btnOk = Button(this).apply {
            text = "OK"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                setColor(Ui.ACCENT_DEEP)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                prefs.edit().putString(Prefs.SERVER_URL, url).apply()
                Toast.makeText(this@DetailSettingsActivity, "接続先URLを保存しました", Toast.LENGTH_SHORT).show()
                ProtoBus.service?.call?.disconnect()
                ProtoBus.service?.call?.connect()
            }
        }

        urlRow.addView(urlInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        urlRow.addView(btnPaste, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(6) })
        urlRow.addView(btnOk, LinearLayout.LayoutParams(-2, dp(48)))

        body.addView(urlRow)

        head("Tailscale")
        button("Tailscaleを開く") {
            val ts = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
            if (ts != null) startActivity(ts)
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tailscale.ipn")))
        }

        head("権限")
        fun granted(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        fun permRow(label: String, ok: Boolean, onFix: () -> Unit) = body.addView(Button(this).apply {
            text = (if (ok) "✔ " else "✖ ") + label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(0xFFFFFFFF.toInt())
            alpha = if (ok) 0.6f else 1f
            background = GradientDrawable().apply { setColor(Ui.SURFACE); cornerRadius = dp(8).toFloat() }
            setOnClickListener { if (!ok) onFix() }
        })
        permRow("マイク（プロトと話す）", granted(android.Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 31)
        }
        permRow("カメラ（外カメラで見せる）", granted(android.Manifest.permission.CAMERA)) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 32)
        }
        permRow("通知（常駐・接続状態の表示）",
            android.os.Build.VERSION.SDK_INT < 33 || granted(android.Manifest.permission.POST_NOTIFICATIONS)) {
            if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 33)
        }
        permRow("他のアプリの上に重ねて表示（プロトの常駐）", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
