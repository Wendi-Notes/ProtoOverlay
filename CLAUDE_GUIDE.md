# プロト常駐アプリ（ProtoOverlay）作業ガイド

AI・人が改造するときに最初に読むファイル。

## 目標
- スマホ（Android 14）の画面左下（または右下）にプロトが常駐するアプリ。最終的にはプロトと音声で話す
- 段階1：見た目だけ（済）
- 段階2（いま）：通話（LiveKit）で PCの agent.py につなぐ。`URL/token?character=proto` → PC側がプロトとして話す（済：声で会話・文字送信・口パク/？/うれしい合図）
- カメラ（外カメラ）・画面共有もつないだ。残り：ネットのまとめ係（PC側）

## 場所とビルド
- 本体: C:\ProtoOverlay（Android Studio で「Open」→ この フォルダ）
- 実機に入れる: スマホの「ワイヤレスデバッグ」でペア設定 → Android Studio の ▶（Run）
- ライブラリ: io.livekit:livekit-android 2.26.1、kotlinx-coroutines-android。AGP 9.5.1 / Gradle 9.6.0（Android Studio のエージェントが更新）/ compileSdk 36 / targetSdk 34 / minSdk 30
  - ビルド設定を触る前に、C:\ProtoOverlay の実物（build.gradle.kts・gradle.properties・gradle-wrapper.properties）を必ず読むこと
- 素材の元: C:\Users\taiyo\OneDrive\ドキュメント\protgen 素材（.pdn）→ app_parts\ に切り出したPNG

## ファイルの役割（app/src/main/java/com/wendinotes/proto/）
- MainActivity … アイコンから起動。重ねて表示の許可 → 通知の許可 → 常駐開始して閉じる。常駐中なら設定を開く（設定の入口はここだけ。通知タップも同じ）
- ProtoService … 常駐サービス（オーバーレイ）。位置と「今の顔・動き」を決める（状態係）
  - 位置: 体の切れ目 CUT_Y を「しがみつく線」に合わせる。パネルなし＝画面の下端（顔だけ）、パネルあり＝パネルの上端（前足を出す）
  - 顔の優先順: しゃべり中 > ●REC > 考え中 > 掴まれ・起きた瞬間(見開き) > 寝てる > うれしい(にっこり) > 眠そう > ふつう(まばたき)
  - しゃべり中: 跳ねずに顔を上に向けて（Motion.TALK_UP）、開いた口と波線の口を交互に
  - うれしい（beHappy）: にっこり顔でぴょんと跳ねて、しばらくぴょこぴょこ（Motion.BOB）。充電をつないだ時・PCの kira 合図
  - カメラ・画面共有で見せてもらっている間（●REC）もわくわくしてぴょこぴょこ
  - おでこの電池マーク（ProtoView.Badge）: 充電中＝緑、電池≤15%＝赤でゆっくり点滅。どの顔にも重ねる（電池少ない時はうなだれる）
  - 寝る条件: おやすみタイム（設定で時刻を決める）／触らない時間。タップで起こすと10分は起きている
  - 触らない時間で3段階：ふつう → 半分の時間で「眠そう」（半目＝eye_blink、ゆっくり揺れ、ときどきハッと目を開ける）→ 時間が来たら「居眠り」（寝目、Motion.DOZE でこっくり）
  - しゃべり出したら（ProtoBus.talking）寝ていても起きる。PCから話しかけてきた時もこれで目が覚める
  - しまっている時（バーなし）は下から覗き込む姿勢（ProtoView.lookUp：16°上向き＋沈める。体の左下の欠けを画面下に隠すため）
  - 操作: タップ＝バーを出す／長押し→左右にはじく＝左下⇔右下の定位置へぴょんと移る（jumpTo。設定の「表示する側」にも保存。振動なし）
- ProtoView … 絵を描くだけ（表示係）。パーツを重ね、揺れ・上下・寝息を Web版と同じ数値で動かす
- PanelActivity … タップで下から出る横1列のバー [カメラ][画面共有][文字…[送る]]。上端の位置を ProtoBus に知らせる。中身は準備中（押せない）
  - ホーム・履歴へスワイプしたら閉じる（onUserLeaveHint / onStop）。色は BarTheme（ProtoBus.kt、設定の「表示」→「バーの色」）
  - 「送る」は Button だと影の余白でずれるので TextView で作っている
- SettingsActivity … タブで種類分け：表示（左右・大きさ）／眠り（おやすみタイム・居眠り）／接続（接続先URL・Tailscaleを開く）／その他（テスト・出す/止める）
- 寝ている間は頭の右上に動く Zzz（proto_zzz_anim.webp：Fluent UI Emoji の Zzz を水色＋紺ふちに加工し、小さい z から順に出て浮かんで消える動きにした。止まった絵 proto_zzz.png は予備）
- 窓は絵の上に余白 TOP_PAD を足してある（考え中の「？」を頭より上に出すため。大きさ150・左に18°傾け）
- ProtoBus.kt … アプリ内の連絡板（panelTop, talking, rec, thinking, asleep）と設定キー（Prefs）
  - thinking=true の間、頭の右上に動く「？」(res/drawable-nodpi/hatena_think.webp、Web版と同じ) が出る。設定画面にテストボタンあり
  - 段階2の通話係は ProtoBus.talking / rec を書き換えるだけで顔が変わる

## 画像（app/src/main/res/drawable-nodpi/、全部 480×309 透過PNG・同じ位置に重ねる）
- proto_body / proto_paws（前足）
- 目: proto_eye（ふつう）/ proto_eye_blink（まばたき）/ proto_eye_wide（見開き）/ proto_eye_sleep（寝目）/ proto_eye_happy（にっこり）
- 口: proto_mouth / proto_mouth_open
- バイザー表示: proto_charge（緑の電池）/ proto_battery_low（赤い電池）/ proto_rec（●REC）
- 注意: Web版（C:\livekit-agent）の proto_eye_sleep.png は中身が「まばたき」。こちらの blink と同じ絵

## 好み（作業方針）
- 正確さより速さ優先。動きは軽く（寝ている間は描き直しをゆっくりにして電池を節約）

## 通知・触る範囲（2026-11-18 追加）
- 通知はふつうの通知（メディア欄には出さない：音楽アプリと取り合わないため）。本文＝接続状態（ProtoBus.conn）・一時停止中・マイクOFF、タップで設定
  - ボタン3つ：一時停止／再開（おやすみ＋マイクOFF）・ミュート／解除（起きたままマイクだけOFF、ProtoBus.muted）・停止
- 接続状態 ProtoBus.conn（未接続／接続中…／つながっています／つながりません）は通知とバー右上の●に出る。通話係が ProtoBus.setConn() で書き換える
- バーの背景の模様は設定の「表示」→「バーの模様」（ProtoArt.kt でその場で描く：無地／ストライプ／ドット／グリッド／グラデ。色はバーの色に合わせる）
- 触る窓は絵の窓と別（ProtoService.touchView）。絵の窓は FLAG_NOT_TOUCHABLE で素通り。触る窓は顔のまわりだけ（syncTouch で絵の座標から計算）
- バーの上で上にスワイプ → 設定（PanelActivity.dispatchTouchEvent）
- 毛の色を変える仕組み（ProtoView.hue、Prefs.HUE）は残してあるが、設定には出していない（水色固定にした）
- アプリのアイコン：mipmap-anydpi-v26/ic_launcher.xml（アダプティブ）。前景 drawable-nodpi/ic_launcher_fg.png、背景色 #A8EAF9（values/colors.xml）。丸い元絵は drawable-nodpi/ic_launcher.png
- 顔の光り方（設定の「表示」→「顔の光り方」、Prefs.GLOW）：元の色／アイコン風。アイコン風は *_glow.png（目・口を白く光らせて紫のにじみ、体の耳ライトをピンク）を使う

## 通話（ProtoCall.kt）
- つなぐ：バーを開いた時（PanelActivity.onResume → call.connect）。URLは設定の「接続」。token は URL/token?character=proto
- つないだら常駐の種類にマイクを足す（ProtoService.setMicFgs。Android 14 の決まり。アプリが前にいる時に切り替える必要がある）
- 切る：5分だれも話さない（→眠る）／一時停止／停止。ミュートはマイクだけ切る（ProtoBus.muted）
- PCからの合図：参加者の属性 lk.agent.state=speaking → 口パク、zunda.icon=hatenahold/hatenaoff → 「？」、kira → うれしい
- バーの文字は lk.chat で送る（PC側はURLなら中身を見に行く）。つながっている時だけ入力できる
- 接続状態 ProtoBus.Conn：未接続／接続中…／つながっています／つながりません／接続先URLが未設定／マイクの許可が必要
- 聞き取り中（スマホ側が話している＝localParticipant.isSpeaking）：頭の右上に「ハッ」の線（proto_listen_anim.webp、ProtoBus.listening）
- カメラ：バーのカメラボタン。外カメラ（Room.videoTrackCaptureDefaults）。使う間は常駐にカメラの種類を足す（setCamFgs）。顔は●REC
- 画面共有：バーの画面共有ボタン → 許可 → 共有開始してホーム画面へ。共有の常駐は LiveKit の部品が自分で立てる
- 上スワイプで設定は、文字のバーを触って始めた時だけ（PanelActivity.dispatchTouchEvent）
- 設定の「接続」タブ先頭に「権限」：マイク・カメラ・通知・重ねて表示の ✔／✖。✖ を押すと許可を聞く（断られ済みならアプリの権限画面へ）。画面共有は毎回 Android が聞く
- Android Studio のエージェントの修正（取り込み済み）：setCameraEnabled／setScreenShareEnabled の結果を確認してログ（Logcat の ProtoCall）、画面共有は ScreenCaptureParams で渡す、Manifest に LiveKit の ScreenCaptureService（mediaProjection）を宣言
- PC側は「これ見て」「何が映ってる？」などと話しかけた時に、送られている映像の最新の1枚を見て答える
- 音は「メディアの音量」で出す（LiveKit.create の overrides：AudioType.MediaAudioType）。通話の音だと音量が通話側・音質も低いため
  - 代わりにエコー除去が弱くなるので、プロトが話している間はマイクを止める（applyMic：!muted && !talking）。プロトの話に割り込めない

## 同時に編集するときの注意
- Android Studio のエージェントもこのフォルダを編集する。Claude が直す前に、必ず C:\ProtoOverlay の実物を読み直してから変更を重ねる（古い控えで上書きしない）
- Studio のエージェントが足したもの：カメラ映像のプレビュー（TextureViewRenderer、ProtoService.syncCameraPreview）、画面共有用の常駐の切り替え（setShareFgs）、共有開始に成功した時だけホームへ移る

## 2026-11-18 夕方の方針変更
- 画面共有はやめた（バーのボタンは Android Studio のエージェントが外した。ProtoCall.setScreenShare などの中身は残っている）
- 見せる手段は「カメラのライブ映像」と「URLの貼り付け」の2本。今後：スクリーンショットを共有メニューからプロトに渡して読ませたい（未着手）
- カメラは 640×480・毎秒10コマで送る（ProtoCall の videoTrackCaptureDefaults）。高画質だとPC側が1コマずつほどく処理で重くなり、会話まで遅くなるため
- 設定画面は Android Studio のエージェントが SettingsActivity と DetailSettingsActivity に分けた（中身は各ファイルを読むこと）
- 共有メニューの受け口（ShareActivity、「プロトに見せる」）：画像（スクショ・写真）は長辺1024pxのJPEG→base64でトピック「proto.image」の文字ストリームで送る。PC側（agent.py の _on_shared_image）が目の担当で見て、感想を話す。文字（リンク）は lk.chat で送る。止まっていれば起動、未接続ならつないでから送る（ProtoCall.waitConnected）
- 注意：Android Studio のエディタで開いたままのタブが、古い中身を自動保存で書き戻すことがある。Claude が直したファイルはタブを閉じるか「File → Reload All from Disk」してから触ること

## 見た目・チャット履歴（2026-11-18 追加）
- ChatBubbles.kt … 色の定数 Ui（あたたかいグラファイト＋プロトの水色 #7FD8EC。Tailwindのslate系の青黒はやめた）と、チャット履歴の吹き出し描画（バー PanelActivity・設定 SettingsActivity で共通）
  - 自分＝右・差し色塗り・濃い文字（「文字で送信」「画像を見せた」の札つき）／プロト＝左・半透明カード＋細いふち（名前は出さない）
  - 左右寄せは各吹き出しの LayoutParams.gravity で指定（外側の gravity だけだと寄らない端末があった）
  - 自分判定は ChatBubbles.isMe（"自分" または "あなた…" で始まる）。履歴の話し手名は ProtoCall が "あなた"/"あなた（文字）"/"あなた（画像）"/"プロト"、PanelActivity のURL送信が "自分"
- 色の組み合わせ（BarTheme）は「デフォルト（あたたかいグラファイト＋水色）・夜・えだまめ・さくら・こはく・すみ」の6つ。デフォルトが初期値
- 変更前のファイルは _backup_20261118\
- アプリの背景の色：設定「表示」→「アプリの背景の色」（Prefs.APP_THEME、バーの色と同じ組み合わせから別々に選ぶ）。Ui.load(ctx) を各画面の onCreate で呼ぶ。変えたら recreate
- ダッシュボード（設定画面上部）をタップ → 起動／停止。下の「起動」ボタンは INVISIBLE（プロトの絵と重なって押せないため。場所は詰めない）
- ダッシュボード右の大きい起動スイッチ＝ProtoSwitch.kt（つまみがプロトのバイザー。ON＝水色・目「^ ^」／OFF＝暗い・目「— —」、OvershootInterpolatorで滑る）。カードのどこを押しても同じ。☰は廃止
- 詳細設定は画面を左→右へスワイプで開く（SettingsActivity の onFling）
- チャット履歴はWeb版と同じく新しい発言が上。古いほど下へ流れて薄く（ChatBubbles で5件目以降 alpha を下げる＋ScrollView の下端フェード）
- 変更前のファイル（この回）は _backup_20261118b\
- アクセントの色：設定「表示」→「アクセントの色」（Prefs.ACCENT、ProtoBus.kt の Accents）。バーの色・背景の色と同じ6つの名前・同じ札で選び、その組み合わせの accent で BarTheme.current()/app() の accent を上書き（起動スイッチ・自分の吹き出し・送るボタン・バーのカメラ等）。選ぶと recreate
- 詳細設定のタブは「表示」「眠り」「接続」。テストボタンは削除（ProtoService.testTalking/testThinking は残してある）
- ダッシュボード画面の並び（SettingsActivity）：上＝ダッシュボード（起動スイッチ）／真ん中＝左にプロト・反対側に「一時停止」「ミュート」／下＝チャット履歴（画面の下まで）
  - プロトはチャット履歴の箱の上端にしがみつく。常駐中は本物のプロトが ProtoBus.setPanelTop(箱の上端) でここへ来る（バーを開いた時と同じ仕組み、onPause で null に戻す）。停止中は寝ている絵（proto_body＋proto_eye_sleep＋proto_paws）を同じ位置に置く
  - 表示する側が「右下」なら、プロトは右・ボタンは左
- 3つの色選び（バー・背景・アクセント）は同じ themeChips で、名前・色・並びも同じ
- 停止中のダッシュボードの絵は「箱の中へ少し沈み込んだ寝姿（前足なし）＋頭の右上に動くZzz」（SettingsActivity.showSleeper）。起動スイッチを押した瞬間に箱の中へ沈んで消え、その後に本物のプロトが出る（starting フラグで重なり防止）。停止すると下から浮かんで寝姿に戻る
- ダッシュボードは開いた時・起動時に接続も始める（ProtoCall.connect）。接続状態の変化は ProtoBus.onConn で即反映。履歴の更新登録（onHistory）は空の時も先に行う。バー（⚙）から来た時に後片付けで消されないよう ProtoBus.dashboardVisible を見る
- 停止の片付け（ProtoService.onDestroy）：最初に dying=true・ProtoBus.service=null。dying の間は refreshNotification / applyFgs(startForeground) をしない（接続切れの setConn(OFF) や setCamFgs(false) が通知を出し直し、停止後も「未接続」の通知が残る不具合の対策）。最後に通知をもう一度 cancel
- 状態の同期：常駐の起動完了・停止・一時停止・ミュートの時も ProtoBus.onConn を呼ぶ（通知欄を下ろしただけではダッシュボードの onResume が来ないため、スイッチが ON のまま残る不具合の対策）
- マイクのON/OFF（ProtoCall.syncMic）は Mutex で1つずつ、その時点のあるべき状態（ミュートでなく、プロトが話していない）に合わせる。接続直後の第一声とぶつかってマイクが切れたままになる不具合の対策（接続2秒後にも念押し）。ダッシュボードは開いた時・つながった時に refreshMic でマイクの常駐とON/OFFを合わせ直す

## README の動く絵（2026-11-19）
- docs/images/demo/*.gif（待機・おしゃべり・考え中・聞き取り中・うれしい・居眠り・REC・充電）。README.md / README.en.md の冒頭と「プロトの表情と動き」の表で使う
- 作り直し：`python docs/images/demo/render_demo.py`（Pillow）。app/src/main/res/drawable-nodpi の部品を ProtoView.kt と同じ数値で重ねて描く。ProtoView の動きの数値を変えたら、render_demo.py の frame() も合わせる
- おしゃべりの姿勢を dy 0・上向き 10°（-10°）に変更（以前の dy 9・-8° だと開いた口がバーの線と右前足に隠れていた）

## 落ちる動き・起動時の接続（2026-11-19）
- 画面の下へ戻る時（panelTop が null になり、下へ h/6 以上動く時）は ProtoService.applyLayout が「落下」：AccelerateInterpolator(1.6)、バーを閉じた時 440ms・顔は WIDE（「わっ」、fallUntil）。アプリ（ダッシュボード）を閉じた時は ProtoBus.setPanelTop(null, spin = true) で 560ms＋ProtoView.spinOnce で一回転（体の欠けが目立たない）。着地で view.snapLook()（姿勢を即そろえる＝着地後に沈んで浮いて見えるのを防ぐ）＋ view.hop()
- SettingsActivity.onUserLeaveHint（ホームボタン等、onPause より先）でも落とし始める（取り残され対策）
- 起動時の接続：ProtoService.onCreate の最後に、ダッシュボードかバーが前にいれば 300ms 後に call.connect()（スイッチを入れても「未接続」のままだった件）
- 追記：ジェスチャーでホームへ戻ると onUserLeaveHint/onPause はアニメ終了後（約1.1秒後）にしか来ず上に取り残されていた → SettingsActivity.onWindowFocusChanged(false) で即 setPanelTop(null, spin=true)。フォーカスが戻ったら syncProtoAnchor で箱のふちへ戻す（通知シェードを下ろした時も一度落ちて戻る）

## 回りながら落ちる時の前足（2026-11-19）
- ProtoView：spinOnce の回転中（spinAnim が動いている間）は、前足（proto_paws.png）を左右別々に切り出して付け根を軸に ±30°・200ms周期で、左右ずらして振る（drawPaw、PAW_L / PAW_R）。回転していない時は今まで通り showPaws のときだけ止まった前足。
- 確認用の絵：backup\flail_preview.gif（PIL での再現。実機とは少し違う）
- 2026-11-20 再修正：バーを閉じて落ちる時は前足がしがみつきポーズのまま落ちていた（わちゃわちゃが spinAnim の間だけだったため）。ProtoView.flail(ms)（flailUntil）を足し、ProtoService の落下では回転の有無に関係なく毎回呼ぶ。
  - あわせて PanelActivity.onWindowFocusChanged(false)／onUserLeaveHint で即 setPanelTop(null)（onPause は閉じるアニメの後なので、前足をかけたまま一瞬止まって見えていた）。フォーカスが戻ったら report() でふちへ戻る
  - 前の版：backup\*.kt.20261120_1915_fallpaws
- 2026-11-20 追加：実機の Android 14 でバーを閉じると左下へ一瞬でワープしていた（エミュレーターでは正常）。ValueAnimator は端末の「アニメーション再生時間」オフ・省電力・機種独自設定で一瞬になるのが原因と推定。
  - ProtoAnim.kt（Choreographer＋経過時間で動く自前アニメ）を作り、ProtoService の yAnim（落下・上がる）と xAnim（左右へ飛び移る）を置き換えた。ValueAnimator は使わない
  - 動きをシンプルに：回転（spinOnce）はやめ、落下は 380ms（だんだん速く）。前足は左右交互に ±12°・320ms周期でぱたぱた（上下のがたつき無し）。前足を出していれば体の左下の欠けは隠れる
  - 前の版：backup\*.kt.20261120_1925_clock
- 2026-11-20 回転を復活：アプリ（ダッシュボード）を閉じた時だけ、くるっと一回転しながら 560ms で落ちる（バーを閉じた時はまっすぐ 380ms）。回転も ProtoView.spinDeg（経過時間で角度を計算）にして ValueAnimator をやめた。前足のぱたぱたはどちらも同じ
  - 前の版：backup\*.kt.20261120_2015_spinback
- 2026-11-20 アプリ（ダッシュボード）を開いた時の「下からせり上がる」動きをなくした：SettingsActivity.syncProtoAnchor → ProtoBus.setPanelTop(top, instant = true) → ProtoService.onPanelMoved でアニメなし＋snapLook。バーを開いた時の上がり方はそのまま
  - 前の版：backup\*.kt.20261120_2020_noriseanim
- 2026-11-20 追記：最新版のエミュレーターではまだ下から上がっていた → Android 本体の「窓が動いた時の自動アニメ」と推定。ProtoService.noMoveAnimation で重ねて表示の窓に PRIVATE_FLAG_NO_MOVE_ANIMATION(0x40) を付けた（リフレクション、失敗したら何もしない）
  - 前の版：backup\ProtoService.kt.20261120_2025_nomoveanim
- 2026-11-20 アプリを開く時：新しい Android では開くアニメより先にプロトがふちへ移って浮いて見えた → SettingsActivity.onResume でプロトを隠し（ProtoService.hideForOpen）、onEnterAnimationComplete（来なければ 800ms）でふちへ置いてから見せる。onPause・onUserLeaveHint では必ず見せる（回りながら落ちるのを見せるため）
  - 前の版：backup\*.kt.20261120_2035_hideopen

## チャット履歴がその場で更新されない問題（2026-11-19）
- 原因：SettingsActivity.renderHistory() が、履歴が空だと早めに return していて、その後ろの「更新の知らせを受け取る登録」（ProtoBus.onHistory）まで届かなかった。空のまま開くと、新しい発言が来ても描き直されなかった。さらに onHistory は1つしか入れられず、バー（PanelActivity）が閉じる時に消すこともあった。
- 直し方：ProtoBus に addHistoryListener / removeHistoryListener（何人でも入れる）。ダッシュボードは onResume で登録・onPause で外す。バーは onCreate で登録・onDestroy で外す。
- PCの音声認識が「話している途中の文字」も送るようになった（同じ lk.segment_id で何度も届く）ので、addHistory(who, text, key) で同じ key の行は書き換える。プロトの返事も reader.flow で届いた分から少しずつ出す。
- 前の版：backup\*.kt.*_hist

## 音声をメディア経路へ（2026-11-21）
- 通話の音声（MODE_IN_COMMUNICATION / STREAM_VOICE_CALL）だと音量キーでメディア音量として下げられないため、既定をメディア経路に戻した（ProtoCall.audioOverrides）
  - AudioType.MediaAudioType（USAGE_MEDIA・STREAM_MUSIC・MODE_NORMAL）＋ AudioSwitchHandler を自前で渡して manageAudioFocus=false（つないだだけで動画・音楽が止まらないように）。disableCommunicationModeWorkaround=true
  - エコー対策：JavaAudioDeviceModule の setUseHardwareAcousticEchoCanceler(false)。端末のAECは通話モード前提でメディア再生の音を参照できないことが多いので、WebRTC内蔵のソフトAEC（自分が再生した音を参照して消す）に任せる。HWのノイズ抑制はそのまま
  - バー・ダッシュボードを開いている間は volumeControlStream=STREAM_MUSIC（何も鳴っていなくても音量キー＝メディア音量）
- 自分の声よけ（ハウリング・ループ対策）：ProtoCall.guardMic()。設定「接続」→「音声」→「プロトが話している間のマイク」（Prefs.TALK_MIC）
  - 自動（既定）：メディア経路でスピーカーから出している時だけ、lk.agent.state=speaking の間＋話し終わり MIC_TAIL_MS(450ms) はマイク送信を止める。イヤホン・Bluetooth・USBヘッドセットなら止めない（割り込みOK）。AudioDeviceCallback で抜き差しに追従
  - 割り込みOK：止めない（PC側 agent.py の resume_false_interruption と自己エコー照合 _is_recent_echo に任せる）／止める：常に止める
- 設定「プロトの声をメディア音量で出す」（Prefs.AUDIO_MEDIA、既定ON）をOFFにすると前の通話経路（端末のAEC・割り込み優先）に戻る。経路はつなぐ時に決まるので、切り替えると自動でつなぎ直す
- logcat：ProtoCall の「音声の経路=…」「マイク送信=ON/OFF / 自分の声よけ=…」
- PC側は変更なし。コンパイル・実機確認は未実施（Android Studio でビルドしてから実機で：音量キー・スピーカーでのループ有無・イヤホンでの割り込み）
- 前の版：_backup_20261121_media_audio\

## バーを開く時のワープ・ダッシュボードの入力欄（2026-11-21）
- バーを開いた時、プロトが下からスライドして上がっていた → PanelActivity.report() を setPanelTop(top, instant = true) にして、アニメなしでふちへ（ワープ）
- ダッシュボード（SettingsActivity）の一番下に、Web版と同じ文字入力欄＋「送る」を追加（アプリ版のみ）。sendInput()：履歴に「あなた（文字）」で足して ProtoCall.sendTextConnecting（つながっていなければ、つないでから lk.chat で送る）。キーボードの「送信」でも送れる。プロト未起動なら案内のトーストだけ
  - キーボードが出たら SOFT_INPUT_ADJUST_RESIZE で画面を縮める（プロトは履歴の箱のふちについていく）
  - 前の版：backup\*.kt.20261121_2100_barwarp_input
