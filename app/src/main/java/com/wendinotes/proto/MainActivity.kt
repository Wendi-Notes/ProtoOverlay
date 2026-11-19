package com.wendinotes.proto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * アイコンから起動したときの入口。
 * 「他のアプリの上に重ねて表示」の許可を確認し、常にダッシュボード（SettingsActivity）を開く。
 * ※勝手にバックグラウンドでサービスを起動せず、必ずダッシュボードを経由する。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            val d = resources.displayMetrics.density
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
            }
            col.addView(TextView(this).apply {
                text = "プロトを画面の端に出すには、\n「他のアプリの上に重ねて表示」の許可が必要です。\n次の画面で「プロト」をオンにして、戻ってきてください。"
                textSize = 16f
                gravity = Gravity.CENTER
            })
            col.addView(Button(this).apply {
                text = "許可の画面を開く"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            })
            setContentView(col)
            return
        }

        // 許可済みなら、常にダッシュボード（SettingsActivity）を開く
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
    }
}
