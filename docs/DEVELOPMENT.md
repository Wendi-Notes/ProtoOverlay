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

デバッグ APK は Android のデバッグ鍵で署名されます。Google Play や継続的な一般配布向けの正式な署名鍵ではありません。release ビルドには署名設定を含めていないため、`assembleRelease` だけでは未署名 APK が生成されます。配布用 APK は Android Studio の **Build > Generate Signed Bundle / APK** で、自分で管理するリリース鍵を選んで作成してください。

1. Android Studio の **Build > Generate Signed Bundle / APK** から APK を選び、リリース鍵を用意します。または、Gradle の release signing config をローカル専用の秘密情報から設定します。
2. 秘密鍵（`.jks` / `.keystore`）、パスワード、ローカル署名設定を Git に追加しません。`.gitignore` を確認し、GitHub Actions で署名するなら GitHub Secrets を利用します。
3. リリース鍵は安全な場所にバックアップします。鍵を失うと、同じアプリ ID で既存ユーザーに更新を配布できなくなる場合があります。
4. APK を公開する前に、端末へのインストール、主要機能、署名、SHA-256 を確認し、リリースノートに記載します。

`app/build.gradle.kts` は release バリアントを Android の debug signing config で署名しない設定です。Gradle の release signing config を追加する場合は、鍵情報をビルドファイルへ直書きせず、ローカル専用のプロパティまたは GitHub Secrets から読み込んでください。

## 依存関係とライセンス

アプリの依存関係・バージョンは `app/build.gradle.kts` に記載されています。依存ライブラリのライセンスは、それぞれの配布条件に従います。Apache License 2.0 は、第三者ライブラリやキャラクター素材に対する権利を付与するものではありません。再配布前に各素材と依存関係の権利・表記を確認してください。
