package com.wendinotes.proto

import android.content.Context
import android.content.SharedPreferences

/**
 * アプリ内の連絡板。パネル・設定・（あとで足す）通話係と、常駐サービスをつなぐ。
 * 全部メインスレッドから使う。
 */
object ProtoBus {
    /** パネルの上端（画面の上からのpx）。null = パネルは閉じている */
    var panelTop: Int? = null
        private set

    /** 通話係（あとで追加）が書き込む。true の間、口パク／●REC になる */
    var talking = false
    var rec = false
    /** 考え中・調べ中（大きいモデルが動いている間など）。頭の右上に動く「？」が出る */
    var thinking = false
    /** 相手（スマホ側）が話している＝聞き取り中。頭の右上に「ハッ」の線が出る */
    var listening = false
    /** カメラ・画面共有を送っている（顔は●REC） */
    var camOn = false
    var shareOn = false

    /** PCとの接続状態（通知とバーに表示する）。通話係（あとで追加）が書き換える */
    enum class Conn(val label: String, val color: Int) {
        OFF("未接続", 0xFF8A9499.toInt()),
        CONNECTING("接続中…", 0xFFE8C860.toInt()),
        ON("つながっています", 0xFF7BD88F.toInt()),
        ERROR("つながりません", 0xFFE87A7A.toInt()),
        NO_URL("接続先URLが未設定", 0xFFE87A7A.toInt()),
        NO_MIC("マイクの許可が必要", 0xFFE87A7A.toInt()),
    }
    var conn = Conn.OFF
        private set
    fun setConn(c: Conn) {
        if (conn == c) return
        conn = c
        service?.refreshNotification()
        panel?.showConn()
        onConn?.invoke()   // ダッシュボードの「接続:」もすぐ書き換える
    }
    /** 接続状態が変わった時に呼ぶ（ダッシュボードが前にいる間だけ入る） */
    var onConn: (() -> Unit)? = null

    /** チャット履歴（この常駐の間だけ。停止・再起動で消える）。設定の「一般」に表示 */
    val history = mutableListOf<Pair<String, String>>()   // (話した人, 内容)
    private val historyKeys = mutableListOf<String?>()     // 発言ごとの印（同じ印＝話している途中の書き換え）
    /** 履歴が変わった時に呼ぶ相手（ダッシュボードとバーの両方が同時に入れる） */
    private val historyListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun addHistoryListener(l: () -> Unit) { historyListeners += l }
    fun removeHistoryListener(l: () -> Unit) { historyListeners -= l }
    private fun notifyHistory() { historyListeners.forEach { it() } }

    /** key が同じ発言はその行を書き換える（話している途中の文字が、確定の文に置き換わる） */
    fun addHistory(who: String, text: String, key: String? = null) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        if (key != null) {
            val i = historyKeys.lastIndexOf(key)
            if (i >= 0) {
                if (history[i].second != trimmed) { history[i] = who to trimmed; notifyHistory() }
                return
            }
        }
        // 直近の履歴と同じ内容が連続して追加されるのを防ぐ（重複防止）
        if (history.isNotEmpty() && history.last().second == trimmed) {
            return
        }
        history += who to trimmed
        historyKeys += key
        while (history.size > 200) { history.removeAt(0); historyKeys.removeAt(0) }
        notifyHistory()
    }
    fun clearHistory() { history.clear(); historyKeys.clear(); notifyHistory() }

    /** 一時停止中（通知のボタン）。おやすみ＋マイクも切る */
    var paused = false
    /** LiveKit の受信音声をミュート中。スマホのマイクには影響しない */
    var speakerMuted = false

    /** いま寝ているか（サービスが毎回更新する） */
    var asleep = false

    var service: ProtoService? = null
    var panel: PanelActivity? = null
    /** ダッシュボード（SettingsActivity）が前にいる間 true。バーが閉じてもプロトを画面下へ戻さないため */
    var dashboardVisible = false

    /** spin=true：アプリ（ダッシュボード）を閉じた時。くるっと回りながら落ちる */
    fun setPanelTop(top: Int?, spin: Boolean = false) {
        if (panelTop == top) return
        panelTop = top
        service?.onPanelMoved(spin)
    }
}

/** 設定の保存場所 */
object Prefs {
    const val SIDE = "side"          // "left" / "right"
    const val SCALE = "scale"        // 大きさの倍率 0.6〜1.6
    const val NIGHT = "night"        // おやすみタイムを使う
    const val SLEEP_START = "sleep_start"  // おやすみタイムの始まり（0時からの分）既定 23:00
    const val SLEEP_END = "sleep_end"      // 終わり 既定 6:00
    const val IDLE_MIN = "idle_min"  // 触らないと寝るまでの分（0 = 寝ない）
    const val SERVER_URL = "server_url"  // 接続先（自宅PCの通話ページ。Tailscale の名前）
    const val BAR_THEME = "bar_theme"    // バーの色（BarTheme の名前）
    const val ACCENT = "accent_color"    // アクセントの色（BarTheme の名前。その組み合わせの差し色を使う）
    const val APP_THEME = "app_theme"    // アプリの背景の色（BarTheme の名前。バーとは別に選ぶ）
    const val GLOW = "glow"              // 顔の光り方（true＝アイコン風）
    const val HUE = "hue"                // 毛の色（色相を何度回すか -180〜180）
    const val BAR_PATTERN = "bar_pattern"  // バーの背景の模様（ProtoArt.NAMES）

    fun get(c: Context): SharedPreferences = c.getSharedPreferences("proto", Context.MODE_PRIVATE)
}

/** バーの色の組み合わせ（設定の「表示」で選ぶ） */
data class BarTheme(
    val name: String,
    val bg: Int,       // バー全体
    val stroke: Int,   // ふち
    val field: Int,    // 入力欄・丸ボタン
    val accent: Int,   // 送るボタン・選択中
    val ink: Int,      // アイコン・文字
) {
    companion object {
        val ALL = listOf(
            BarTheme("デフォルト", 0xF21A1917.toInt(), 0xFF3A3631.toInt(), 0xFF2E2B27.toInt(), 0xFF7FD8EC.toInt(), 0xFFF2EFEA.toInt()),
            BarTheme("夜", 0xF21B2530.toInt(), 0xFF3A6A80.toInt(), 0xFF26394A.toInt(), 0xFF8FCFE0.toInt(), 0xFFBFEFFF.toInt()),
            BarTheme("えだまめ", 0xF2172014.toInt(), 0xFF3D5036.toInt(), 0xFF2C3A28.toInt(), 0xFF8FCF5A.toInt(), 0xFFCFE8B6.toInt()),
            BarTheme("さくら", 0xF22A1C23.toInt(), 0xFF6A3A50.toInt(), 0xFF3A2630.toInt(), 0xFFFF9EC4.toInt(), 0xFFFFD6E8.toInt()),
            BarTheme("こはく", 0xF226201A.toInt(), 0xFF6A5236.toInt(), 0xFF3A3024.toInt(), 0xFFE0B26F.toInt(), 0xFFF5E2C2.toInt()),
            BarTheme("すみ", 0xF2181818.toInt(), 0xFF444444.toInt(), 0xFF2A2A2A.toInt(), 0xFFDDDDDD.toInt(), 0xFFEEEEEE.toInt()),
        )
        fun current(c: Context): BarTheme {
            val n = Prefs.get(c).getString(Prefs.BAR_THEME, "デフォルト")
            return Accents.apply(c, ALL.firstOrNull { it.name == n } ?: ALL[0])
        }
        fun app(c: Context): BarTheme {
            val n = Prefs.get(c).getString(Prefs.APP_THEME, "デフォルト")
            return ALL.firstOrNull { it.name == n } ?: ALL[0]
        }
        fun accent(c: Context): BarTheme {
            val n = Prefs.get(c).getString(Prefs.ACCENT, "デフォルト")
            return ALL.firstOrNull { it.name == n } ?: ALL[0]
        }
    }
}

/** アクセントの色（設定の「表示」→「アクセントの色」）。バーの色・アプリの背景の色と同じ6つの名前から選び、その組み合わせの差し色を使う */
object Accents {
    /** 保存が無い・古い名前（おまかせ等）のときは「デフォルト」が選ばれている扱い */
    fun name(c: Context): String {
        val n = Prefs.get(c).getString(Prefs.ACCENT, null)
        return if (BarTheme.ALL.any { it.name == n }) n!! else "デフォルト"
    }
    fun apply(c: Context, th: BarTheme): BarTheme {
        val pick = BarTheme.ALL.firstOrNull { it.name == name(c) } ?: BarTheme.ALL[0]
        return th.copy(accent = pick.accent)
    }
}
