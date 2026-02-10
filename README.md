# Picture Uploader

写真を撮影し、Google Drive共有ドライブへ自動アップロードするAndroidアプリです。
アップロード済みかつ撮影から30日以上経過したローカル写真は自動削除され、端末ストレージを健全に保ちます。

**野良APK** として運用します（Google Play非配布）。

---

## 機能概要

| 機能 | 説明 |
|------|------|
| 撮影 | CameraXで高速JPEG撮影 |
| 自動アップロード | WorkManager + Drive API v3で共有ドライブへ自動送信 |
| 自動削除 | 撮影から30日経過+アップロード済みのローカル写真を自動削除 |
| Google認証 | Credential Manager + AuthorizationClientで認証・認可 |
| 設定 | 共有ドライブのフォルダIDを設定 |

---

## 技術スタック

- Kotlin
- CameraX 1.4.1
- Google Drive API v3
- WorkManager 2.10.0
- Room Database 2.6.1
- Material3 (XML)
- Coroutines

---

## 前提条件

- Android Studio Ladybug 以降 (AGP 8.7.3対応)
- JDK 17
- Googleアカウント
- Google Cloud Consoleへのアクセス権

---

## 次にすべきこと（本番APKで進める）

**運用は本番（リリース）APKで行います。** 以下の順で進めてください。

| 順番 | やること | 詳細 |
|------|----------|------|
| **1** | **本番APKをビルドする** | プロジェクトルートで `.\build-release.ps1` を実行（`keystore.properties` が無い場合はスクリプトが例から作成し、パスワードを入れてから再実行）。出力: `app\build\outputs\apk\release\app-release.apk` |
| **2** | **実機に本番APKを入れる** | **方法A:** USB接続後 `.\install-release.ps1`（adb はポート 5038 で他ツールと競合しない）。**方法B:** `app-release.apk` を端末の内部ストレージにコピーし、端末の「ファイル」から開いてインストール |
| **3** | **Google Cloud を確認する** | 同じプロジェクトに Android クライアント（パッケージ `com.pictureuploader`、**リリース用 SHA-1** 登録）・Web クライアント・OAuth同意画面の**テストユーザー**（ログインするメール）を用意 |
| **4** | **アプリでGoogleログインする** | 起動 → 「Googleログイン」→ アカウント選択 → Drive スコープ同意（初回のみ）→ メールが表示されればOK |
| **5** | **共有ドライブのフォルダIDを設定する** | 設定 → 共有ドライブのフォルダを開き、URL の `folders/` の後ろのIDをコピー → アプリの設定画面に貼り付け・保存 |
| **6** | **撮影→アップロードを試す** | 撮影 → Wi‑Fi接続のまま待つ → 設定したフォルダに写真が届いているかDriveで確認。「アップロード済み」件数が増えればOK |

**補足:** 開発時のみデバッグAPKを使う場合は `.\install-debug.ps1` を使い、Google Cloud の Android クライアントに**デバッグ用 SHA-1** を追加する必要があります。

**GCP（Google Cloud）はなぜ必要か／どのGoogle IDでも使えるようにするには**

- **役割の整理:** GCPプロジェクトは「このアプリは誰か」をGoogleに伝える**アプリの身分証明**のために必要です。**ユーザーがアップロードした写真は、そのユーザー自身のGoogle Drive（または指定したフォルダ）に保存されます。** 弊社のGoogle WorkspaceやGCPにデータが集約されるわけではありません。OAuthの仕様上、Google API（サインイン・Drive）を使うアプリは必ずどこか1つのGCPプロジェクトに紐づいたクライアントIDが必要で、この部分は「GCPを使わない」という選択肢はありません。
- **どのGoogleアカウントでもログインできるようにする:** 現在、OAuth同意画面が**「テスト」**のままになっていると、**登録したテストユーザー（最大100件）だけ**がログインできます。そのため「認証に失敗しました」になることがあります。**外部のどのGoogleアカウントでも使えるようにするには、同意画面の公開ステータスを「本番」にすること**が公式の方法です（下記「1-3. OAuth同意画面」内の**本番に公開する**を参照）。本番にするとテストユーザー一覧は不要で、任意のGoogleアカウントでログイン可能になります（Drive などセンシティブなスコープを使う場合、未確認アプリの警告が出ることがありますが、ユーザーが「詳細」→「〜（安全でない）に移動」で続行できます）。

**トラブル時**

- ログインできない → **本番APK**では「リリース用 SHA-1」が Google Cloud に登録されているか確認（下記）。**外部ユーザー向けならOAuth同意画面を「本番」に公開**すると解消することが多いです。それでも失敗する場合はテストユーザー未追加（テスト中のとき）・WebクライアントIDの不一致も確認
- アップロードできない → フォルダIDが共有ドライブ内か、そのアカウントが編集者以上か確認。設定画面ではフォルダURLをそのまま貼ってもIDだけ保存されます。アプリ側で次を自動処理しています: フォルダIDの正規化（URLからID抽出・前後空白除去）、ファイル存在・読み取りチェック、401/403/404/429/5xx/ネットワークエラー時の分岐、リトライ（最大3回・指数バックオフ）、複数Driveスコープの利用。
- 詳細は **「8. 動作確認チェックリスト」** で項目ごとに確認

**「Googleアカウントの認証に失敗しました」と出る場合の確認（リリースAPK）**

- **どのGoogleアカウントでも使いたい場合:** まず OAuth同意画面を**本番に公開**してください（上記「1-3. OAuth同意画面」の「本番に公開する」）。テスト中のままだと、テストユーザーに追加していないアカウントはログインできません。
- それでも失敗する場合は、下の **「GCPでやること（具体手順）」** を上から順にやってください。

---

### GCPでやること（具体手順）— ログインできるようにする

**意味:** Google に「この Android アプリはパッケージ名 `com.pictureuploader` で、この SHA-1 の鍵で署名されている」と登録しておく必要があります。登録が違うと「認証に失敗しました」になります。**fukayaman** プロジェクトで次を実行してください。

#### 手順A: Android 用の登録を正しく入れる

1. ブラウザで [Google Cloud Console](https://console.cloud.google.com/) を開く。
2. 左上の「プロジェクトを選択」で **fukayaman** を選ぶ。
3. 左メニュー **「APIとサービス」** → **「認証情報」** をクリック。
4. 画面中央の「認証情報」一覧で、**種類が「Android」** の行を探す。  
   - なければ **「＋ 認証情報を作成」** → **「OAuth クライアント ID」** → アプリケーションの種類で **「Android」** を選び、下の 5・6 の内容を入力して「作成」。  
   - あればその行の **右端のペン（編集）アイコン** をクリック。
5. **「パッケージ名」** の欄に、次の文字を **そのまま** 入力（コピペ可）:  
   `com.pictureuploader`
6. **「SHA-1 証明書フィンガープリント」** の欄に、次の文字を **そのまま** 入力（コピペ推奨）:  
   `AD:23:36:97:D7:AE:B2:14:01:62:79:12:58:5E:D8:FE:98:0F:8F:3B`
7. **「保存」** をクリック。

#### 手順B: Web クライアント ID をアプリと一致させる

1. 同じ「認証情報」画面で、種類が **「ウェブアプリケーション」** の行を探す。
2. その行をクリックするか編集アイコンで開き、**「クライアント ID」** をコピーする（`xxxxx.apps.googleusercontent.com` の形）。
3. このプロジェクトの `app/src/main/java/com/pictureuploader/auth/AuthManager.kt` を開く。
4. 中の `WEB_CLIENT_ID = "..."` の `"..."` 部分を、コピーした **クライアント ID に置き換えて保存**する。
5. リリースAPKを入れ直す: `.\build-release.ps1` のあと、`.\install-release.ps1` または `app-release.apk` を端末にコピーしてインストール。

#### 手順C: 変更後に試す

- GCP の保存後は **数分** かかることがあるので、少し待ってからアプリで再度「Googleログイン」を試す。
- ログイン画面で「このアプリは確認されていません」と出た場合は、**「詳細」** → **「Picture Uploader（安全でない）に移動」** をタップして進む。

**認証失敗時のログ取得（原因切り分け用）**

1. スマホを **USB で接続**し、**USB デバッグ** を ON にする  
2. `.\adb-devices.ps1` で端末が一覧に出ることを確認（このプロジェクトは adb をポート 5038 で使用するため他ツールと競合しない）  
3. `.\adb-logcat.ps1` を実行したまま、スマホのアプリで **Googleログイン** を試す  
4. 失敗した直後に PowerShell に表示された `AuthManager` や `AndroidRuntime` の行をメモする  

**アップロード失敗時のログ**

- アップロードが失敗するたびに、アプリは端末内の `Android/data/com.pictureuploader/files/upload_failures.log` に理由・パス・フォルダIDを追記します。  
- 端末を USB 接続した状態で `.\pull-upload-log.ps1` を実行すると、このログをプロジェクト直下の `upload_failures.log` に取り込み、末尾を表示します。

---

## 1. Google Cloud Console 設定手順

### 1-1. プロジェクト作成

1. [Google Cloud Console](https://console.cloud.google.com/) にアクセス
2. 「プロジェクトを選択」→「新しいプロジェクト」
3. プロジェクト名: 任意（例: `picture-uploader`）
4. 「作成」をクリック

### 1-2. Drive API 有効化

1. 左メニュー「APIとサービス」→「ライブラリ」
2. 「Google Drive API」を検索
3. 「有効にする」をクリック

### 1-3. OAuth同意画面 作成

1. 「APIとサービス」→「OAuth同意画面」（または Google Auth Platform の **対象** / **Branding**）
2. ユーザーの種類: **外部**
3. アプリ名: `Picture Uploader`
4. ユーザーサポートメール: 自分のメールアドレス
5. スコープ: **`https://www.googleapis.com/auth/drive`** を追加（アップロード用。`.file` だけだと共有ドライブで失敗することがあります）
6. 「保存して次へ」

**どのGoogleアカウントでもログインできるようにする（本番に公開する）**

- 公開ステータスが **「テスト中」** の間は、**テストユーザーとして追加したメールアドレス（最大100件）だけ**がログインできます。それ以外のアカウントでは「認証に失敗しました」になることがあります。
- **任意のGoogleアカウントで使えるようにするには:** OAuth同意画面の **「アプリを公開」**（Publish app）を実行し、公開ステータスを **「本番」**（In production）にします。本番にするとテストユーザー一覧は不要で、どのGoogleアカウントでもログイン可能です。Drive スコープを使うため「未確認アプリ」の警告がユーザーに表示される場合がありますが、ユーザーが「詳細」→「〜（安全でない）に移動」で続行すれば利用できます。
- 手順: Google Cloud Console → **Google Auth Platform**（または OAuth同意画面）→ **対象**（Audience）→ **「アプリを公開」** をクリック → 確認で **「公開」**。これで公開ステータスが「本番」になります。

**テスト中のときだけ: テストユーザーの追加方法**

- **「本番環境」のときはテストユーザー欄は出ません。** 自分用・少人数用なら **「テストに戻る」** を押して **「テスト中」** に戻すと、**「テストユーザー」** のセクションが表示され、そこからログインに使うメールアドレスを追加できます。
- **「対象」ページでテストユーザーが出ない場合:** 「ユーザーの種類」が **「内部」** だとテストユーザー欄は表示されません。**「外部に公開」** にし、公開ステータスは **「テスト中」** を選ぶと表示されます。
- **新しい画面（Google Auth Platform）の場合:** 左メニュー **「対象」** → 公開ステータスが **テスト中** であることを確認 → **「テストユーザー」** セクションの **「+ ユーザーを追加」** でメールアドレスを追加 → 保存。
- **従来の画面の場合:** 「APIとサービス」→「OAuth同意画面」を開き、ユーザーの種類を **外部**、ステータスを **テスト** にしてから、**「テストユーザー」** の **「+ ADD USERS」** でメールアドレスを追加 → 保存。

### 1-4. Android OAuthクライアント作成・確認

**既に「Android」の認証情報がある場合（fukayaman など）:** 「認証情報」一覧でその行の**編集（ペン）アイコン**をクリックし、以下が入っているか確認。違っていれば直して「保存」。

**新規に作る場合:**

1. 「APIとサービス」→「認証情報」→「＋ 認証情報を作成」→「OAuth クライアント ID」
2. アプリケーションの種類: **Android**
3. **パッケージ名** に `com.pictureuploader` を入力（コピペ可）
4. **SHA-1 証明書フィンガープリント** に以下を**そのまま**コピペ（本プロジェクトのリリース用）:  
   `AD:23:36:97:D7:AE:B2:14:01:62:79:12:58:5E:D8:FE:98:0F:8F:3B`
5. 「作成」

### 1-5. WebクライアントID作成（Credential Manager用）

1. 「認証情報を作成」→「OAuthクライアントID」
2. アプリケーションの種類: **ウェブアプリケーション**
3. 名前: 任意（例: `PictureUploader Web`）
4. 「作成」
5. **表示されるクライアントIDをコピー**

### 1-6. クライアントIDをアプリに設定

`app/src/main/java/com/pictureuploader/auth/AuthManager.kt` を開き、以下を置き換え:

```kotlin
const val WEB_CLIENT_ID = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"
```

↓ コピーしたWebクライアントIDに置き換え:

```kotlin
const val WEB_CLIENT_ID = "123456789-abcdefg.apps.googleusercontent.com"
```

---

## 2. 共有ドライブのフォルダID取得方法

1. ブラウザで [Google Drive](https://drive.google.com/) を開く
2. 左メニュー「共有ドライブ」から対象のドライブを選択
3. アップロード先のフォルダを開く（なければ作成）
4. ブラウザのURLを確認:

```
https://drive.google.com/drive/folders/XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
```

5. `folders/` の後ろの文字列が **フォルダID**
6. アプリの設定画面でこのフォルダIDを入力

### 注意事項

- 使用するGoogleアカウントが共有ドライブの **編集者** 以上の権限を持っていること
- アップロード失敗時: アプリは **`DriveScopes.DRIVE`**（`https://www.googleapis.com/auth/drive`）を使用しています。GCP の **Google Auth Platform → データアクセス（Data Access）→ スコープ** にこのスコープが追加されているか確認してください。追加後、アプリで一度ログアウトしてから再ログインすると新しいスコープが付与されます。

---

## 3. Keystore作成方法

### 3-1. 新規Keystore作成

```bash
keytool -genkey -v \
  -keystore picture-uploader.keystore \
  -alias pictureuploader \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

対話形式で以下を入力:
- キーストアのパスワード
- 名前（姓名）
- 組織名
- 国コード（JP）

### 3-2. SHA-1 / SHA-256 フィンガープリント取得

```bash
keytool -list -v \
  -keystore picture-uploader.keystore \
  -alias pictureuploader
```

出力例:
```
証明書のフィンガプリント:
   SHA1: AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD
   SHA256: AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99
```

**SHA-1** を Google Cloud Console の Android OAuth クライアントに登録してください。

**本番運用:** リリースAPK（`app-release.apk`）で運用する場合は、**リリース用 SHA-1 のみ**を登録すれば十分です（上記 keytool で取得した値）。  
**開発時:** デバッグAPK（`app-debug.apk`）でログインする場合は、同じ Android クライアントに**デバッグ用の SHA-1 を追加**する必要があります。リリース用だけだとデバッグAPKでは「Googleアカウントの認証に失敗しました」になります。

### デバッグ用SHA-1の取得

Android Studioのデバッグ署名キーのSHA-1を取得する場合:

```bash
# Windows (PowerShell または コマンドプロンプト)
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android

# macOS / Linux
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

出力の「証明書のフィンガープリント」の **SHA1:** の値（例: `7B:0E:5C:F8:02:50:7F:F3:26:32:FD:65:11:51:EA:2F:92:C2:34:81`）をコピーし、Google Cloud Console → 認証情報 → 対象の **Android** 用 OAuth 2.0 クライアント ID を編集 → 「SHA-1 証明書フィンガープリント」に**追加**（既存のリリース用 SHA-1 に加えて登録可能）→ 保存。反映に数分かかることがあります。

---

## 4. APK署名・ビルド手順

運用は **リリースAPK** を主軸にします。開発時のみデバッグAPKを使う場合は 4-2 を参照してください。

### 4-1. リリースビルド（本番用・署名付き）

#### 方法A: ワンコマンドでビルド（推奨）

プロジェクトルートで次を実行するだけです。`keystore.properties` が無ければ例から作成され、パスワードを入れて再実行するよう促されます。

```powershell
.\build-release.ps1
```

初回のみ: `keystore.properties` を用意（`keystore.properties.example` をコピーして `keystore.properties` とし、パスワードを編集）。**Git にコミットしない**（`.gitignore` に含めています）。

#### 方法B: Gradle を直接使う場合

```powershell
.\gradlew.bat assembleRelease
```

APK出力先:
```
app\build\outputs\apk\release\app-release.apk
```

本番APKは **リリース用 SHA-1 で署名** されているため、Google Cloud に登録済みのリリース用 SHA-1 だけでログインできます（デバッグ用 SHA-1 は不要）。

#### 方法C: コマンドラインで apksigner を使う場合

```bash
# 1. リリースAPKをビルド
./gradlew assembleRelease

# 2. 署名 (apksigner を使用)
# Android SDK の build-tools にパスが通っていること
apksigner sign \
  --ks picture-uploader.keystore \
  --ks-key-alias pictureuploader \
  --out app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

# 3. 署名の検証
apksigner verify --verbose app-release-signed.apk
```

### 4-2. デバッグビルド（開発時のみ）

```bash
# プロジェクトルートで実行
./gradlew assembleDebug
```

APK出力先:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. adb install 手順

### 5-1. 端末の準備

1. Androidスマートフォンの「設定」→「端末情報」→ ビルド番号を7回タップ → 開発者モード有効化
2. 「設定」→「開発者向けオプション」→「USBデバッグ」をON
3. **「USB経由でインストール」をON**（機種により名称は「Install via USB」「USB デバッグ（セキュリティ設定）」など。Xiaomi はここを有効にしないと `INSTALL_FAILED_USER_RESTRICTED` になる）
4. 「設定」→「セキュリティ」→「不明なアプリのインストール」→ 該当アプリを許可（必要に応じて）

### 5-2. インストール

**本番（リリース）APK**

- **PowerShell（推奨）:** 端末を USB 接続後、`.\install-release.ps1` を実行。このプロジェクトは adb をポート 5038 で使うため、他ツールと競合しません。
- **手動:** `app\build\outputs\apk\release\app-release.apk` を端末にコピーし、端末の「ファイル」などから開いてインストール。

```bash
# デバイスが認識されているか確認（このプロジェクトは adb をポート 5038 で使用）
.\adb-devices.ps1

# リリースAPKをインストール（PowerShell）
.\install-release.ps1

# または adb を直接使う場合（ポート 5038 を指定）
set ANDROID_ADB_SERVER_PORT=5038
adb install app\build\outputs\apk\release\app-release.apk
adb install -r app\build\outputs\apk\release\app-release.apk   # 上書き時
```

**開発時（デバッグAPK）**

- `.\install-debug.ps1` でデバッグAPKをインストール。Google Cloud の Android クライアントにデバッグ用 SHA-1 の追加が必要です。

```bash
.\install-debug.ps1
# または
adb install app/build/outputs/apk/debug/app-debug.apk
```

**adb の競合を避ける（根本対策）**

このプロジェクトのスクリプトは **adb をポート 5038** で動かします。通常の adb（Android Studio など）は 5037 を使うため、**同時に起動していても競合しません**。kill や Android Studio 終了は不要です。

- リリースAPKのインストール: `.\install-release.ps1`
- デバッグAPKのインストール: `.\install-debug.ps1`
- デバイス一覧: `.\adb-devices.ps1`
- 認証エラー時のログ: `.\adb-logcat.ps1`（実行中にスマホでログインを試す）
- アップロード失敗ログの取得: `.\pull-upload-log.ps1`（端末接続時、失敗履歴を pull）

いずれも同じポート 5038 を使うため、そのまま実行してください。

**Windows で `adb` が認識されない場合**

`adb` が PATH に含まれていないときは、`.\install-release.ps1` や `.\adb-devices.ps1` を使うと SDK の adb を自動で参照します。直接呼ぶ場合は Android SDK の `platform-tools` をフルパスで指定し、ポート 5038 を指定してください。

```powershell
$env:ANDROID_ADB_SERVER_PORT = 5038
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install app\build\outputs\apk\release\app-release.apk
```

恒常的に使う場合は、ユーザー環境変数 `Path` に次を追加すると便利です。

- `C:\Users\<ユーザー名>\AppData\Local\Android\Sdk\platform-tools`

**「List of devices attached」の下が空のとき**  
   USB 接続・USB デバッグ ON・ケーブル（データ転送対応）・端末の「この PC でデバッグを許可しますか？」で許可、を確認してください。**このプロジェクトでは `.\adb-devices.ps1` を使うとポート 5038 で adb が動くため、他ツールと競合しません。**

**「INSTALL_FAILED_USER_RESTRICTED: Install canceled by user」が出る場合**  
   開発者向けオプション内の **「USB経由でインストール」**（または「Install via USB」）をONにしてください。Xiaomi では「USB デバッグ（セキュリティ設定）」から有効にできる場合があります。再度 `adb install` を実行し、**端末画面に確認ダイアログが出たら「許可」をタップ**してください。

**「USB経由でインストール」をONにするとすぐオフに戻り「Insert your SIM card」と出る場合（Xiaomi / MIUI など）**  
   機種によっては、この項目を有効にするために **SIM の挿入** を要求します。SIM が未挿入・未検出だとトグルがすぐオフに戻ります。  
   **adb を使わずにインストールする方法:** APK を端末にコピーし、端末側でファイルを開いてインストールしてください（「USB経由でインストール」は不要です）。  
   - **本番運用:** PC で `app\build\outputs\apk\release\app-release.apk` を USB の「ファイル転送」で端末の「Download」などにコピー  
   - **開発時:** `app\build\outputs\apk\debug\app-debug.apk` を同様にコピー  
   - または Google Drive / メールなどで自分に送り、端末でダウンロード  
   - 端末で「ファイル」アプリなどから該当 APK を開く → インストール  
   - 「不明なアプリのインストール」を許可するよう求められたら、使用するアプリ（ファイル／Chrome など）を許可

---

## 6. プロジェクト構造

```
Picture_Uploader/
├── app/
│   ├── build.gradle.kts              # アプリモジュール設定・依存関係
│   ├── proguard-rules.pro            # ProGuardルール
│   └── src/main/
│       ├── AndroidManifest.xml       # マニフェスト
│       ├── java/com/pictureuploader/
│       │   ├── PictureUploaderApp.kt # Application (CleanupWorker登録)
│       │   ├── MainActivity.kt       # メイン画面 (撮影・認証・ステータス)
│       │   ├── SettingsActivity.kt   # 設定画面 (フォルダID入力)
│       │   ├── auth/
│       │   │   └── AuthManager.kt    # Google認証 (Credential Manager + AuthorizationClient)
│       │   ├── camera/
│       │   │   └── CameraHelper.kt   # CameraX撮影ロジック
│       │   ├── data/
│       │   │   ├── PhotoEntity.kt    # Room Entity
│       │   │   ├── PhotoDao.kt       # DAO
│       │   │   ├── AppDatabase.kt    # Room Database
│       │   │   └── PhotoRepository.kt # Repository
│       │   ├── drive/
│       │   │   ├── DriveUploader.kt  # Drive API v3アップロード（正規化・リトライ・複数スコープ）
│       │   │   └── UploadResult.kt   # アップロード結果・失敗理由
│       │   ├── util/
│       │   │   └── UploadFailureLogger.kt  # 失敗時に端末内ログファイルへ追記
│       │   └── worker/
│       │       ├── UploadWorker.kt   # アップロードWorker
│       │       └── CleanupWorker.kt  # 自動削除Worker
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   └── activity_settings.xml
│           └── values/
│               ├── colors.xml
│               ├── strings.xml
│               └── themes.xml
├── build.gradle.kts                  # ルートGradle設定
├── settings.gradle.kts               # プロジェクト設定
├── gradle.properties                 # Gradle設定
├── gradle/wrapper/
│   └── gradle-wrapper.properties     # Gradleラッパー設定
├── build-release.ps1                 # リリースAPK ワンコマンドビルド
├── install-release.ps1               # リリースAPK インストール（adb ポート 5038）
├── install-debug.ps1                # デバッグAPK インストール
├── adb-devices.ps1                   # デバイス一覧（ポート 5038）
├── adb-logcat.ps1                    # logcat（認証・アップロード用タグ）
├── pull-upload-log.ps1               # 端末内アップロード失敗ログを pull
├── keystore.properties.example       # 署名用プロパティの例（コピーして keystore.properties に）
└── README.md                         # このファイル
```

---

## 7. アーキテクチャ

```
UI層 (MainActivity / SettingsActivity)
  ↓
Repository (PhotoRepository)
  ↓
Room DB (AppDatabase / PhotoDao / PhotoEntity)
  ↓
Worker (UploadWorker / CleanupWorker)
  ↓
Drive API (DriveUploader)
```

設計思想: **「人間の操作を極限まで排除する」**

ユーザーは撮影するだけ。それ以外は全自動。

---

## 8. 動作確認チェックリスト

### 初期設定

- [ ] Google Cloud Consoleでプロジェクト作成済み
- [ ] Drive API有効化済み
- [ ] OAuth同意画面作成済み
- [ ] Android OAuthクライアントID作成済み（**リリース用 SHA-1** 登録済み）
- [ ] WebクライアントID作成済み
- [ ] `AuthManager.kt` の `WEB_CLIENT_ID` を置き換え済み
- [ ] リリースAPKビルド成功（`.\build-release.ps1`）
- [ ] 端末にリリースAPKをインストール成功（`.\install-release.ps1` または APK をコピーしてインストール）

### Google認証

- [ ] 「Googleログイン」ボタンでアカウント選択画面が表示される
- [ ] ログイン後、メールアドレスが画面に表示される
- [ ] Drive APIのスコープ同意画面が表示される（初回のみ）
- [ ] ログアウトボタンで認証情報がクリアされる

### 撮影

- [ ] カメラプレビューが表示される
- [ ] 「撮影」ボタンでJPEG保存される
- [ ] ファイル名が `YYYY-MM-DD_HH-mm-ss_picture.jpg` 形式
- [ ] 保存先が `getExternalFilesDir(DIRECTORY_PICTURES)`
- [ ] 撮影後にRoom DBにPENDING状態で登録される

### アップロード

- [ ] 設定画面でフォルダIDを入力・保存できる
- [ ] 撮影後にUploadWorkerが起動する
- [ ] Wi-Fi接続時にDrive共有ドライブへアップロードされる
- [ ] アップロード成功でステータスがUPLOADEDに変わる
- [ ] アップロード失敗でステータスがFAILEDに変わる
- [ ] FAILED状態のファイルが再試行される
- [ ] アップロード件数がメイン画面に表示される

### 自動削除

- [ ] CleanupWorkerが定期実行される（ログで確認）
- [ ] UPLOADED + 30日経過のファイルが削除される
- [ ] 削除成功でDBレコードも削除される
- [ ] capturedAt基準で判定されている（uploadedAtではない）

### エラー処理

- [ ] 未ログイン時に撮影すると適切なエラーメッセージが表示される
- [ ] フォルダID未設定時に撮影すると適切なエラーメッセージが表示される
- [ ] ネットワーク切断時はWorkerがリトライ待機する
- [ ] 不正なフォルダIDでアップロード失敗→FAILED状態になる

---

## ライセンス

プライベート利用。
