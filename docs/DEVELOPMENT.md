# 開発者ガイド

## 開発環境

- Android Studio
- JDK 17
- Android SDK Platform 36（Gradle が求める追加コンポーネントもインストール）
- Android 11 以降の端末またはエミュレーター

```powershell
git clone https://github.com/Wendi-Notes/ProtoOverlay.git
cd ProtoOverlay
.\gradlew.bat assembleDebug
```

Android Studio では clone したフォルダーを開き、Gradle 同期が完了してから実行してください。Android Studio が生成する `local.properties` に SDK のローカルパスが記録されるため、このファイルはコミットしません。

## アプリの構成

- `app/src/main/java/com/wendinotes/proto/ProtoService.kt`: 常駐サービス、オーバーレイ、状態管理
- `app/src/main/java/com/wendinotes/proto/ProtoView.kt`: マスコット表示とアニメーション
- `app/src/main/java/com/wendinotes/proto/ProtoCall.kt`: LiveKit 接続、音声・映像・データ送信
- `app/src/main/java/com/wendinotes/proto/ProtoBus.kt`: アプリ内状態、設定項目
- `app/src/main/java/com/wendinotes/proto/SettingsActivity.kt`: ダッシュボード
- `app/src/main/java/com/wendinotes/proto/DetailSettingsActivity.kt`: 詳細設定、接続 URL、権限
- `app/src/main/AndroidManifest.xml`: 権限、Activity、Service の宣言
- `app/src/main/res/`: Android リソース、アイコン、キャラクター画像

このリポジトリはアプリのソースプロジェクトであり、他のアプリから依存関係として取り込む SDK ではありません。

## 接続プロトコル

Android アプリは設定 URL に `token?character=proto` を追加して HTTP GET を行います。PC 側エンドポイントは次の JSON を返す必要があります。

```json
{
  "url": "wss://livekit.example.invalid",
  "token": "<short-lived LiveKit access token>"
}
```

実際の LiveKit URL と短期トークンを例やドキュメントに書いたり、ソース管理へ登録したりしないでください。`lk.transcription` の文字ストリームや `lk.chat` / `proto.image` のデータストリーム、参加者属性による状態通知など、PC エージェントとの互換性を保ってください。

## APK ビルドと署名

開発・端末テスト用 APK:

```powershell
.\gradlew.bat assembleDebug
```

デバッグ APK は Android のデバッグ鍵で署名されます。Google Play や継続的な一般配布向けの正式な署名鍵ではありません。release ビルドには専用のリリース鍵が必要です。GitHub Actions で配布する場合は、以下の手順で Secrets を登録してください。ローカルで `assembleRelease` を実行する場合も同じ環境変数を設定します。Android Studio を使う場合は **Build > Generate Signed Bundle / APK** でリリース鍵を選べます。

### GitHub Releases への配布

`v*` 形式のタグを push すると GitHub Actions が `release` バリアントをビルドし、署名済み APK と SHA-256 ファイルを GitHub Release に添付します。未設定または不正な鍵情報ではワークフローと Gradle ビルドが失敗し、debug 署名へフォールバックしません。

1. 専用の Android リリース鍵（`.jks`）を用意します。既存の配布を更新する場合は、以前のリリースと同じ鍵を使ってください。
2. GitHub リポジトリの **Settings > Secrets and variables > Actions** に次の Repository secrets を登録します。

   | Secret | 値 |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | `.jks` ファイル全体の Base64 |
   | `ANDROID_KEYSTORE_PASSWORD` | キーストアのパスワード |
   | `ANDROID_KEY_ALIAS` | 鍵エイリアス |
   | `ANDROID_KEY_PASSWORD` | 鍵のパスワード |

   Base64 は秘密情報そのものです。端末上で生成して GitHub に直接登録し、チャット、リポジトリ、ログへ貼り付けないでください。例: `base64 -w 0 release-key.jks`（Linux）、`base64 release-key.jks`（macOS）、または PowerShell の `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-key.jks"))`。
3. Actions の実行が許可されていることを確認し、バージョンタグを push します。

   ```powershell
   git tag v0.2.0
   git push origin v0.2.0
   ```

4. GitHub Actions の完了後、GitHub Releases で APK と `app-release.apk.sha256` を確認します。ダウンロード後、SHA-256 を照合し、必要に応じて実機インストールと動作を確認してください。Windows では `Get-FileHash .\app-release.apk -Algorithm SHA256` で計算できます。

リリース鍵は暗号化した安全な場所に複数バックアップし、アクセスを限定してください。鍵を紛失すると、同じアプリ ID で既存ユーザーに更新を配布できなくなる場合があります。秘密鍵やパスワードをリポジトリへ登録しないでください。

## 依存関係とライセンス

アプリの依存関係・バージョンは `app/build.gradle.kts` に記載されています。依存ライブラリのライセンスは、それぞれの配布条件に従います。Apache License 2.0 は、第三者ライブラリやキャラクター素材に対する権利を付与するものではありません。再配布前に各素材と依存関係の権利・表記を確認してください。
