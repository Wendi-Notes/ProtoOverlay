package com.wendinotes.proto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalScreencastVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通話係：PCのプロト（agent.py）と LiveKit でつなぐ。
 *  - つなぐ：バーを開いた時（PanelActivity.onResume）。接続先は設定の「接続」→ URL
 *  - token は「URL/token?character=proto」でもらう → PC側がプロトとして話す
 *  - 5分だれも話さなければ切って眠る。一時停止でも切る。ミュートはマイクだけ切る
 *  - PCからの合図：lk.agent.state=speaking → 口パク／zunda.icon=hatenahold・hatenaoff → 「？」／kira → うれしい
 */
class ProtoCall(private val ctx: Context, private val svc: ProtoService) {

    companion object {
        private const val TAG = "ProtoCall"
        private const val IDLE_LIMIT_MS = 5 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var room: Room? = null
    private var screenTrack: LocalScreencastVideoTrack? = null
    private var job: Job? = null
    private var lastIcon = ""
    private var lastActivity = SystemClock.elapsedRealtime()

    val isOn get() = room != null && ProtoBus.conn == ProtoBus.Conn.ON

    init {
        // 5分だれも話さなければ切る
        scope.launch {
            while (true) {
                delay(10_000)
                if (room != null && SystemClock.elapsedRealtime() - lastActivity > IDLE_LIMIT_MS) {
                    Log.i(TAG, "5分話さなかったので切る")
                    disconnect()
                    svc.sleepNow()
                }
            }
        }
        // 自分（スマホ側）が話している間は「聞き取り中」：5分の時計を戻し、頭の右上に「ハッ」の線
        scope.launch {
            var n = 0
            while (true) {
                delay(250)
                val speaking = room?.localParticipant?.isSpeaking == true
                ProtoBus.listening = speaking
                if (speaking && ++n % 4 == 0) touch()
            }
        }
    }

    /** だれかが話した・文字を送った・バーを開いた → 5分の時計を戻す */
    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
        svc.noteActivity()
    }

    fun connect() {
        touch()
        if (room != null || ProtoBus.conn == ProtoBus.Conn.CONNECTING) return
        if (ProtoBus.paused) return
        val base = Prefs.get(ctx).getString(Prefs.SERVER_URL, "")?.trim().orEmpty()
        if (base.isEmpty()) { ProtoBus.setConn(ProtoBus.Conn.NO_URL); return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ProtoBus.setConn(ProtoBus.Conn.NO_MIC); return
        }
        ProtoBus.setConn(ProtoBus.Conn.CONNECTING)
        svc.setMicFgs(true)   // マイクを使う常駐に切り替える（バーが開いている＝アプリが前にいる今のうちに）
        job = scope.launch {
            try {
                val (url, token) = withContext(Dispatchers.IO) { fetchToken(base) }
                // 音は「メディアの音量」で出す（通話の音にすると音量が通話側になり、音質も下がるため）
                val r = LiveKit.create(
                    appContext = ctx.applicationContext,
                    overrides = LiveKitOverrides(audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType())),
                )
                // カメラは外カメラ（見せたいものを映す）
                // PCで見るのは「これ見て」の時の1枚だけなので、640×480・毎秒10コマで十分。
                // 高画質のまま送ると、PC側が1コマずつほどく処理で重くなり、会話まで遅くなる
                r.videoTrackCaptureDefaults = LocalVideoTrackOptions(
                    position = CameraPosition.BACK,
                    captureParams = VideoCaptureParameter(640, 480, 10),
                )
                room = r
                // 会話の文字起こし（PC側が送ってくる）をチャット履歴へ
                r.registerTextStreamHandler("lk.transcription") { reader, from ->
                    scope.launch {
                        try {
                            val text = reader.readAll().joinToString("")
                            val me = from == r.localParticipant.identity
                            ProtoBus.addHistory(if (me) "あなた" else "プロト", text)
                        } catch (e: Exception) {
                            Log.w(TAG, "文字起こしを読めなかった", e)
                        }
                    }
                }
                launch { r.events.collect { onEvent(it) } }
                r.connect(url, token)
                syncMic()
                launch { delay(2000); syncMic() }   // 第一声とぶつかった時の念押し
                ProtoBus.setConn(ProtoBus.Conn.ON)
                touch()
            } catch (e: Exception) {
                Log.e(TAG, "つながらなかった", e)
                cleanup()
                ProtoBus.setConn(ProtoBus.Conn.ERROR)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        room?.disconnect()
        cleanup()
        ProtoBus.setConn(ProtoBus.Conn.OFF)
    }

    private fun cleanup() {
        room?.release()
        room = null
        lastIcon = ""
        ProtoBus.talking = false
        ProtoBus.thinking = false
        ProtoBus.listening = false
        ProtoBus.camOn = false
        ProtoBus.shareOn = false
        svc.setCamFgs(false)
        svc.setMicFgs(false)
    }

    /**
     * マイクのON/OFFは必ず1つずつ順番に、その時点の「あるべき状態」（ミュートでなく、プロトが話していない）に合わせる。
     * 接続直後にプロトが第一声を話すと、接続時のON・話し始めのOFF・話し終わりのONが同時に走り、
     * 最後にOFFが勝ってマイクが切れたままになることがあったため。
     */
    private val micLock = kotlinx.coroutines.sync.Mutex()
    private suspend fun syncMic() {
        micLock.lock()
        try {
            val r = room ?: return
            val want = !ProtoBus.muted && !ProtoBus.talking
            try { r.localParticipant.setMicrophoneEnabled(want) } catch (e: Exception) { Log.w(TAG, "マイク切替失敗", e) }
        } finally { micLock.unlock() }
    }

    /** ミュート／解除・プロトの話し始め／終わりをマイクに反映 */
    fun applyMic() {
        if (room == null) return
        scope.launch { syncMic() }
    }

    /** ダッシュボードから：つながっていれば、マイクの常駐とON/OFFを合わせ直す */
    fun refreshMic() {
        if (room == null) return
        svc.setMicFgs(true)
        applyMic()
    }

    /** カメラ（外カメラ）を送る／やめる。PC側は「これ見て」で映像を見る。顔は●REC */
    fun setCamera(on: Boolean) {
        val r = room ?: return
        touch()
        if (on) svc.setCamFgs(true)   // バーが開いている（アプリが前にいる）今のうちにカメラ用の常駐へ
        scope.launch {
            try {
                val ok = r.localParticipant.setCameraEnabled(on)
                Log.i(TAG, "setCameraEnabled($on) result = $ok")
                ProtoBus.camOn = on && ok
            } catch (e: Exception) {
                Log.w(TAG, "カメラを切り替えられなかった", e)
                ProtoBus.camOn = false
            }
            if (!ProtoBus.camOn) svc.setCamFgs(false)
            svc.syncCameraPreview()
            ProtoBus.panel?.showConn()
        }
    }

    /** 画面共有を始める（許可の結果 data を渡す）／やめる（null） */
    fun setScreenShare(data: android.content.Intent?, onComplete: ((Boolean) -> Unit)? = null) {
        val r = room
        if (r == null || !isOn) {
            Log.w(TAG, "画面共有失敗: ルーム未接続 (room=$r, isOn=$isOn)")
            android.widget.Toast.makeText(ctx, "通話に接続されていません", android.widget.Toast.LENGTH_SHORT).show()
            onComplete?.invoke(false)
            return
        }
        touch()
        scope.launch {
            try {
                if (data != null) {
                    svc.setShareFgs(true)
                    val track = r.localParticipant.createScreencastTrack(
                        mediaProjectionPermissionResultData = data,
                        onStop = {
                            scope.launch { setScreenShare(null) }
                        }
                    )
                    screenTrack = track
                    track.startCapture()
                    val published = r.localParticipant.publishVideoTrack(track)
                    Log.i(TAG, "手動画面共有 publish 結果: $published")
                    ProtoBus.shareOn = published
                    if (!published) {
                        track.stopCapture()
                        track.stop()
                        track.dispose()
                        screenTrack = null
                        svc.setShareFgs(false)
                    }
                    ProtoBus.panel?.showConn()
                    onComplete?.invoke(published)
                } else {
                    screenTrack?.let { track ->
                        r.localParticipant.unpublishTrack(track)
                        track.stopCapture()
                        track.stop()
                        track.dispose()
                    }
                    screenTrack = null
                    ProtoBus.shareOn = false
                    svc.setShareFgs(false)
                    ProtoBus.panel?.showConn()
                    onComplete?.invoke(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "手動画面共有エラー例外発生", e)
                screenTrack?.dispose()
                screenTrack = null
                ProtoBus.shareOn = false
                svc.setShareFgs(false)
                ProtoBus.panel?.showConn()
                onComplete?.invoke(false)
            }
        }
    }

    /** カメラ映像のローカルプレビューを取り付ける */
    fun attachCameraPreview(renderer: TextureViewRenderer) {
        val r = room ?: return
        try {
            r.initVideoRenderer(renderer)
            val track = r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            track?.addRenderer(renderer)
        } catch (e: Exception) {
            Log.w(TAG, "カメラプレビューの取り付け失敗", e)
        }
    }

    /** カメラ映像のローカルプレビューを取り外す */
    fun detachCameraPreview(renderer: TextureViewRenderer) {
        val r = room ?: return
        try {
            val track = r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            track?.removeRenderer(renderer)
        } catch (e: Exception) {
            Log.w(TAG, "カメラプレビューの取り外し失敗", e)
        }
    }

    /** バーの文字（見せたい動画や画像のURLなど）を送る。PC側は lk.chat で受け取る（URLなら中身を見に行く） */
    fun sendText(text: String) {
        val r = room ?: return
        touch()
        ProtoBus.addHistory("あなた（文字）", text)
        scope.launch {
            try {
                r.localParticipant.sendText(text, StreamTextOptions(topic = "lk.chat"))
            } catch (e: Exception) {
                Log.w(TAG, "送れなかった", e)
            }
        }
    }

    /**
     * 共有メニューから受け取った画像（スクリーンショットなど）をPCに送る。
     * 長辺1024pxのJPEGにして base64 にし、トピック「proto.image」の文字ストリームで送る（PC側が見て話す）。
     * つながっていなければ、つないでから送る。
     */
    fun sendImage(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        scope.launch {
            val b64 = withContext(Dispatchers.IO) {
                try {
                    val src = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?: return@withContext null
                    val k = 1024f / maxOf(src.width, src.height)
                    val bmp = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                    android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.w(TAG, "画像を読めなかった", e); null
                }
            }
            if (b64 == null) { onDone(false); return@launch }
            val r = waitConnected() ?: run { onDone(false); return@launch }
            touch()
            ProtoBus.addHistory("あなた（画像）", "画像を見せた")
            try {
                r.localParticipant.sendText(b64, StreamTextOptions(topic = "proto.image"))
                onDone(true)
            } catch (e: Exception) {
                Log.w(TAG, "画像を送れなかった", e); onDone(false)
            }
        }
    }

    /** 共有メニューから受け取った文字（リンクなど）を送る。つながっていなければ、つないでから */
    fun sendTextConnecting(text: String, onDone: (Boolean) -> Unit) {
        scope.launch {
            if (waitConnected() == null) { onDone(false); return@launch }
            sendText(text)
            onDone(true)
        }
    }

    /** つながるまで最大15秒待つ（つながっていなければ、つなぎ始める） */
    private suspend fun waitConnected(): Room? {
        if (!isOn) connect()
        repeat(60) {
            if (isOn) return room
            if (ProtoBus.conn == ProtoBus.Conn.ERROR || ProtoBus.conn == ProtoBus.Conn.NO_URL || ProtoBus.conn == ProtoBus.Conn.NO_MIC) return null
            delay(250)
        }
        return if (isOn) room else null
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    // ------------------------------------------------------------------

    private fun fetchToken(base: String): Pair<String, String> {
        val b = if (base.endsWith("/")) base else "$base/"
        val conn = URL(b + "token?character=proto").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            return j.getString("url") to j.getString("token")
        } finally {
            conn.disconnect()
        }
    }

    private fun onEvent(ev: RoomEvent) {
        when (ev) {
            is RoomEvent.ParticipantAttributesChanged ->
                room?.remoteParticipants?.values?.forEach { onAttrs(it) }
            is RoomEvent.Disconnected -> {
                cleanup()
                ProtoBus.setConn(ProtoBus.Conn.OFF)
            }
            else -> {}
        }
    }

    private fun onAttrs(p: Participant) {
        if (p.identity == room?.localParticipant?.identity) return
        val a = p.attributes
        a["lk.agent.state"]?.let { st ->
            val wasTalking = ProtoBus.talking
            ProtoBus.talking = st == "speaking"
            if (st == "speaking" || st == "thinking") touch()
            // メディアの音で出しているとエコー除去が弱いので、プロトが話している間はマイクを止める（自分の声を拾わないように）
            if (ProtoBus.talking != wasTalking) applyMic()
        }
        val raw = a["proto.icon"] ?: return
        if (raw == lastIcon) return
        lastIcon = raw
        when (raw.substringBefore(":")) {
            "hatenahold" -> ProtoBus.thinking = true      // 大きいモデルで調べ中 → 「？」
            "hatenaoff" -> ProtoBus.thinking = false
            "kira" -> svc.beHappy()                       // うれしい → にっこりでぴょん
        }
    }
}
