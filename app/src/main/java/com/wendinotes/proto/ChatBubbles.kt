package com.wendinotes.proto

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** アプリ全体の色。設定「表示」→「アプリの背景の色」（バーと同じ色の組み合わせから選ぶ）で変わる */
object Ui {
    var BG = 0xFF1A1917.toInt()        // 画面の地
    var SURFACE = 0xFF24221F.toInt()   // カード・ヘッダー
    var SURFACE2 = 0xFF2E2B27.toInt()  // 入力欄など一段明るい面
    var LINE = 0xFF3A3631.toInt()      // ふち・区切り
    var TEXT = 0xFFF2EFEA.toInt()      // 本文
    var SUB = 0xFFA39E95.toInt()       // 補足の文字
    var FAINT = 0xFF7D776E.toInt()     // さらに薄い文字
    var ACCENT = 0xFF7FD8EC.toInt()    // 差し色（初期値はプロトの毛の水色）
    var ACCENT_DEEP = 0xFF2F7F92.toInt() // 白文字を乗せるボタン用
    var ON_ACCENT = 0xFF10201F.toInt() // 差し色の上に乗せる文字
    val OK = 0xFF9BD47A.toInt()        // つながっている等
    var themeName = ""

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** 窓そのもの（ステータスバー・ナビバーの裏も含む）を背景の色で塗る。setContentView の後に呼ぶ */
    fun paintWindow(a: android.app.Activity) {
        a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
        @Suppress("DEPRECATION")
        run { a.window.statusBarColor = BG; a.window.navigationBarColor = BG }
    }

    /** 画面を作る前に呼ぶ */
    fun load(c: Context) {
        val appTh = BarTheme.app(c)
        val accentTh = BarTheme.accent(c)
        themeName = appTh.name + "/" + accentTh.name

        // 1. アプリの背景の色 -> ダッシュボードの背景(BG)とチャットボックス等の表面(SURFACE)
        //    元の組み合わせの bg/field はどれもほぼ黒で見分けがつかないので、
        //    その組み合わせの差し色を混ぜて「選んだ色」がはっきり出るようにする（デフォルトだけは元のまま）
        val tint = if (appTh.name == "デフォルト") 0f else 1f
        val appBg = (appTh.bg and 0x00FFFFFF) or (0xFF shl 24)
        val appField = (appTh.field and 0x00FFFFFF) or (0xFF shl 24)
        BG = mix(appBg, appTh.accent, 0.16f * tint)
        SURFACE = mix(appField, appTh.accent, 0.22f * tint)
        LINE = mix((appTh.stroke and 0x00FFFFFF) or (0xFF shl 24), appTh.accent, 0.30f * tint)

        // 2. アクセントの色 -> 一時停止・ミュート・スイッチ等のインタラクトボタン(SURFACE2, ACCENT)
        SURFACE2 = (accentTh.field and 0x00FFFFFF) or (0xFF shl 24)
        ACCENT = (accentTh.accent and 0x00FFFFFF) or (0xFF shl 24)
        ACCENT_DEEP = mix(accentTh.accent, BG, 0.5f)
        ON_ACCENT = 0xFF14200F.toInt()

        TEXT = 0xFFFFFFFF.toInt()
        SUB = 0xFF94A3B8.toInt()
        FAINT = 0xFF64748B.toInt()
    }
}

/**
 * チャット履歴の吹き出し（バーの中・設定画面で共通）。
 * - 自分：右・差し色で塗りつぶし・濃い文字。「文字」「画像」で送ったものは小さな札を付ける
 * - プロト：左・半透明のカード＋細いふち（名前は出さない）
 * - 同じ人が続くときは間を詰めて、話し手が変わるところで間を空ける
 */
object ChatBubbles {
    data class Colors(val mine: Int, val onMine: Int, val other: Int, val otherStroke: Int, val ink: Int, val sub: Int, val name: Int)

    fun isMe(who: String) = who.startsWith("自分") || who.startsWith("あなた")

    private fun tagOf(who: String): String = when {
        "文字" in who -> "文字で送信"
        "画像" in who -> "画像を見せた"
        who == "自分" -> "リンクを送信"
        else -> ""
    }

    fun render(ctx: Context, box: LinearLayout, history: List<Pair<String, String>>, c: Colors, textSp: Float) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.72f).toInt()
        var prevMe: Boolean? = null
        // Web版と同じ：新しい発言が上、古くなるほど下へ流れて薄くなる
        for ((k, pair) in history.asReversed().withIndex()) {
            val (who, msg) = pair
            val me = isMe(who)
            val first = prevMe != me
            prevMe = me
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (me) Gravity.END else Gravity.START
            }
            // 名札・札（話し手が変わった最初の1つだけ／自分の札は毎回）
            val label = if (me) tagOf(who) else ""
            if (label.isNotEmpty()) {
                col.addView(TextView(ctx).apply {
                    text = label
                    textSize = 11f
                    setTextColor(if (me) c.sub else c.name)
                    if (!me) setTypeface(null, Typeface.BOLD)
                    setPadding(dp(6), 0, dp(6), dp(2))
                }, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (me) Gravity.END else Gravity.START })
            }
            col.addView(TextView(ctx).apply {
                text = msg
                textSize = textSp
                setLineSpacing(0f, 1.15f)
                setTextColor(if (me) c.onMine else c.ink)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                maxWidth = maxW
                setTextIsSelectable(true)
                background = GradientDrawable().apply {
                    val r = dp(16).toFloat(); val t = dp(4).toFloat()
                    // しっぽ側の角だけ小さく（話し手が変わった最初の吹き出し）
                    cornerRadii = if (me) floatArrayOf(r, r, if (first) t else r, if (first) t else r, r, r, r, r)
                                  else floatArrayOf(if (first) t else r, if (first) t else r, r, r, r, r, r, r)
                    if (me) setColor(c.mine) else { setColor(c.other); setStroke(dp(1), c.otherStroke) }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (me) Gravity.END else Gravity.START })
            col.alpha = if (k < 4) 1f else (1f - (k - 3) * 0.13f).coerceAtLeast(0.15f)
            box.addView(col, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (first) dp(10) else dp(3) })
        }
    }

    /** アプリ画面用の色 */
    fun app() = Colors(Ui.ACCENT, Ui.ON_ACCENT, 0x14FFFFFF, 0x24FFFFFF, Ui.TEXT, Ui.SUB, Ui.ACCENT)

    /** バー用の色（設定の「バーの色」に合わせる） */
    fun forBar(th: BarTheme) = Colors(th.accent, 0xFF141414.toInt(), 0x18FFFFFF, 0x28FFFFFF, th.ink, (th.ink and 0x00FFFFFF) or (0xB0 shl 24), th.accent)
}
