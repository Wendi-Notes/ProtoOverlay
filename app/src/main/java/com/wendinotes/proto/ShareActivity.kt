package com.wendinotes.proto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * 共有メニューの受け口（「プロト」として出る）。画面は出さない。
 *  - 画像（スクリーンショット・写真）→ PCに送って、プロトが見て話す
 *  - 文字（YouTube やブラウザのリンクなど）→ バーの「送る」と同じように送る
 * プロトが止まっていれば起動し、PCにつながっていなければ、つないでから送る。
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        if (i?.action != Intent.ACTION_SEND) { finish(); return }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先にプロトのアプリを開いて、許可を済ませてください", Toast.LENGTH_LONG).show()
            finish(); return
        }
        // プロトが止まっていれば起動してから送る
        if (ProtoBus.service == null) startForegroundService(Intent(this, ProtoService::class.java))

        val image: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()

        Toast.makeText(this, "プロトに見せています…", Toast.LENGTH_SHORT).show()
        waitService { svc ->
            val call = svc.call
            if (call == null) { done(false); return@waitService }
            when {
                image != null -> {
                    try {
                        val bytes = contentResolver.openInputStream(image)?.use { it.readBytes() }
                        val mime = contentResolver.getType(image) ?: "image/jpeg"
                        if (bytes != null) {
                            call.sendImageConnecting(bytes, mime) { ok -> done(ok) }
                        } else {
                            done(false)
                        }
                    } catch (_: Exception) {
                        done(false)
                    }
                }
                text.isNotEmpty() -> call.sendTextConnecting(text) { ok -> done(ok) }
                else -> done(false)
            }
        }
    }

    /** 起動したてのプロトを待つ（最大5秒） */
    private fun waitService(then: (ProtoService) -> Unit) {
        var tries = 0
        val h = android.os.Handler(mainLooper)
        val r = object : Runnable {
            override fun run() {
                val s = ProtoBus.service
                if (s != null) then(s)
                else if (++tries < 50) h.postDelayed(this, 100)
                else done(false)
            }
        }
        h.post(r)
    }

    private fun done(ok: Boolean) {
        val msg = if (ok) "プロトに見せました" else when (ProtoBus.conn) {
            ProtoBus.Conn.NO_URL -> "接続先URLが未設定です（プロトの設定→接続）"
            ProtoBus.Conn.NO_MIC -> "マイクの許可が必要です（プロトの設定→接続→権限）"
            else -> "プロトに送れませんでした（PCにつながっているか確認してください）"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }
}
