# Picture Uploader

**バージョン:** 1.0.2

- **1.0.2:** アイコン白背景・余白調整、共有ドライブ選択、ログイン／トークン永続化、シャッター音カスタム、エラー時の再ログイン案内
- **1.0.1:** 設定画面にログイン／フォルダ選択、README 整理

写真を撮影し、Google Drive（共有ドライブ含む）へ自動アップロードする Android アプリです。  
野良APKとして運用（Google Play 非配布）を想定しています。

---

## 機能

| 機能 | 説明 |
|------|------|
| 撮影 | CameraX・3000×4000（3:4）・JPEG・シャッター音 |
| タップで撮影 | 画面タップで撮影（デフォルト）。撮影ボタンでも撮影可能 |
| 自動アップロード | WorkManager + Drive API v3 で指定フォルダへ送信 |
| 自動削除 | アップロード済みかつ撮影から30日経過のローカル写真を削除 |
| 認証 | Credential Manager + AuthorizationClient（Google ログイン・Drive スコープ） |
| 設定 | 設定画面でログイン/ログアウト・Drive 内でフォルダ選択またはフォルダID入力 |

---

## 技術スタック

- **Kotlin** / Coroutines
- **CameraX** 1.4.1
- **Google Drive API** v3
- **WorkManager** 2.10.0
- **Room** 2.6.1
- **Material 3**（XML）
- Credential Manager / Play Services Auth

---

## クイックスタート

### 1. ビルド

```powershell
.\build-release.ps1
```

初回は `keystore.properties` が必要です。`keystore.properties.example` をコピーして `keystore.properties` とし、パスワードを設定してください（Git にコミットしないこと）。

### 2. インストール

- **USB:** 端末接続後 `.\install-release.ps1`（adb はポート **5038** で動作）
- **手動:** `app\build\outputs\apk\release\app-release.apk` を端末にコピーしてインストール

### 3. Google Cloud の準備

- [Google Cloud Console](https://console.cloud.google.com/) でプロジェクトを作成
- **Drive API** を有効化
- **OAuth 同意画面** を作成（スコープ: `https://www.googleapis.com/auth/drive`）
- **Android** 用 OAuth クライアント ID を作成
  - パッケージ名: `com.pictureuploader`
  - **SHA-1 を登録**（下記のどちらか、または両方）
    - **デバッグ APK** でログインする場合: デバッグキーストアの SHA-1 を登録（例: `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android` で表示。代表値: `7B:0E:5C:F8:02:50:7F:F3:26:32:FD:65:11:51:EA:2F:92:C2:34:81` は環境により異なります）
    - **リリース APK** の場合は **リリース用 SHA-1** を登録
- **Web アプリケーション** 用 OAuth クライアント ID を作成し、表示されたクライアント ID をコピー
- `app/src/main/java/com/pictureuploader/auth/AuthManager.kt` の `WEB_CLIENT_ID` をその値に置き換え

> ⚠ **「設定に失敗する」「Developer console is not set up correctly」が出る場合**  
> ログに `package name and SHA-1 certificate fingerprint match what you registered` と出ていれば、**この Android クライアント**に、今インストールしている APK をビルドしたキーストアの **SHA-1** が登録されていません。デバッグなら上記のデバッグ SHA-1 を、リリースならリリース用 SHA-1 を GCP の「認証情報」→ 該当 Android クライアント → 「SHA-1 証明書フィンガープリント」に追加してください。

### 4. アプリでの設定

1. 起動 → 設定 → **ログイン** → Google アカウント選択 → Drive スコープ同意（初回のみ）
2. 設定 → **「Driveでフォルダを選択」** でアップロード先フォルダを選択（またはフォルダIDを手入力して保存）
3. 撮影 → ネットワーク接続中に自動でアップロード

---

## 主なスクリプト

| スクリプト | 用途 |
|------------|------|
| `.\build-release.ps1` | リリース APK ビルド |
| `.\install-release.ps1` | リリース APK を端末にインストール（adb ポート 5038） |
| `.\install-debug.ps1` | デバッグ APK をインストール |
| `.\adb-devices.ps1` | 接続端末一覧 |
| `.\adb-logcat.ps1` | 認証・アップロード関連の logcat |
| `.\pull-upload-log.ps1` | 端末内のアップロード失敗ログを取得 |

---

## トラブルシューティング

- **ログインできない / 設定に失敗する**
  - ログに **「package name and SHA-1 certificate fingerprint match what you registered」** や **「Developer console is not set up correctly」** が出ている場合:
    - **デバッグ APK** で試しているなら、GCP の Android OAuth クライアントに **デバッグキーストアの SHA-1** を追加する（`keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android` で確認）。
    - **リリース APK** の場合は **リリース用 SHA-1** が GCP の Android クライアントに登録されているか確認。
  - 任意の Google アカウントで使う場合は、OAuth 同意画面を **本番に公開** する。

- **アップロードが「the name must not be empty: null」で失敗**  
  - 一度ログアウトしてから、再度「Googleログイン」でログインし直す。

- **アップロード失敗の詳細**  
  - 端末内に `upload_failures.log` が出力されます。USB 接続で `.\pull-upload-log.ps1` を実行するとプロジェクト直下に取り込めます。

---

## プロジェクト構造

```
Picture_Uploader/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/pictureuploader/
│       │   ├── MainActivity.kt          # 撮影・プレビュー・ステータス
│       │   ├── SettingsActivity.kt     # ログイン/ログアウト・フォルダ設定
│       │   ├── FolderPickerActivity.kt
│       │   ├── auth/AuthManager.kt
│       │   ├── camera/CameraHelper.kt
│       │   ├── data/                    # Room, Repository
│       │   ├── drive/                   # DriveUploader, FolderBrowser
│       │   ├── util/UploadFailureLogger.kt
│       │   └── worker/                  # UploadWorker, CleanupWorker
│       └── res/
├── build-release.ps1
├── install-release.ps1
├── install-debug.ps1
├── adb-devices.ps1
├── adb-logcat.ps1
├── pull-upload-log.ps1
├── keystore.properties.example
└── README.md
```

---

## ライセンス

プライベート利用。
