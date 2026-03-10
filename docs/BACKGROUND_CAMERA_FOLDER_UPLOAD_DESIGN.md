# バックグラウンド「標準カメラフォルダ」アップロード機能 — 設計書

## 1. 概要

### 1.1 目的

- **メイン機能（現状）**: ユーザーが PICTURE_UPLOADER アプリ内で撮影 → その場で Google Drive へアップロード。
- **追加機能**: ユーザーが**標準のカメラアプリ**で撮影した写真が、Android のローカルフォルダ（例: DCIM/Camera）に保存されたタイミングを検知し、**同じ Google Drive フォルダ**へバックグラウンドでアップロードする。

**背景**: うっかり標準カメラで撮ってしまった写真も、設定で指定した「監視フォルダ」に保存されていれば、自動で PICTURE_UPLOADER の Drive フォルダに送る。

### 1.2 スコープ

| 項目 | 内容 |
|------|------|
| 監視対象 | 標準カメラアプリが保存する「フォルダ」を**設定で選択可能**にする |
| アップロード先 | PICTURE_UPLOADER 本体で設定済みの **Google Drive フォルダ**（`shared_drive_folder_id`）をそのまま利用 |
| 動作 | バックグラウンド（アプリ未起動でも動くようにする） |

---

## 2. プログラミングに必要なスキル・知識の洗い出し

実装前に押さえるべき技術要素を整理する。

### 2.1 Android プラットフォーム

| スキル・知識 | 用途 | 重要度 |
|-------------|------|--------|
| **Scoped Storage / ストレージモデル** | Android 10+ では直接パスで DCIM を触れない。MediaStore API 経由で画像を扱う。 | 必須 |
| **MediaStore API** | 画像の検索・取得・「どのフォルダ（バケット）に属するか」の判定。`MediaStore.Images.Media`、`contentUri`、`BUCKET_ID` / `BUCKET_DISPLAY_NAME`。 | 必須 |
| **ContentResolver / ContentObserver** | MediaStore の変更検知（新規画像追加）。登録・解除のライフサイクル。 | 必須（検知方式による） |
| **WorkManager** | バックグラウンドでの定期スキャン・アップロード。既存の UploadWorker / CleanupWorker と並列で新 Worker を定義。 | 必須 |
| **Foreground Service** | （オプション）即時検知したい場合は常駐サービスで ContentObserver を張る。通知必須・バッテリー影響。 | 任意 |
| **権限** | `READ_MEDIA_IMAGES`（API 33+）、`READ_EXTERNAL_STORAGE`（それ未満）、必要に応じて `POST_NOTIFICATIONS`（Foreground Service 時）。 | 必須 |
| **Doze / バッテリー最適化** | WorkManager は Doze に対応済み。Foreground Service はユーザーに「常時動作」を明示する必要あり。 | 必須（設計判断） |

### 2.2 既存コードベースとの接続

| スキル・知識 | 用途 | 重要度 |
|-------------|------|--------|
| **DriveUploader** | 現状は `uploadFile(localFilePath, accountEmail, folderId)` のみ。MediaStore は **content URI** のため、**ファイルパス版**と**URI/InputStream 版**のどちらかで拡張する必要あり。 | 必須 |
| **SharedPreferences** | 既存の `picture_uploader_prefs` に「監視するフォルダ」の設定（バケット ID または表示名）を追加。 | 必須 |
| **Room / PhotoRepository** | アプリ内撮影分は既存 `photos` テーブルのみ。**外部フォルダ由来**のアップロードは「二重アップロード防止」のため、**別テーブル**または「アップロード済み content _id 一覧」の永続化が必要。 | 必須 |
| **UploadWorker の流れ** | フォルダ ID・accountEmail の取得方法、失敗時の `Result.retry()` / `Result.failure()` の使い分けを、新 Worker でも一貫させる。 | 必須 |

### 2.3 設計判断で必要な知識

| テーマ | 選択肢 | 本設計の方針（案） |
|--------|--------|---------------------|
| **検知方式** | (A) ContentObserver で即時検知 → Foreground Service で常駐 (B) WorkManager の PeriodicWork で一定間隔ポーリング | **(B) ポーリング**。実装が単純で、通知不要・バッテリー影響が小さい。遅延は許容（例: 15〜30 分間隔）。 |
| **「フォルダ」の表現** | MediaStore の **BUCKET_ID**（安定） vs **BUCKET_DISPLAY_NAME**（表示用） | **BUCKET_ID** を保存。表示は BUCKET_DISPLAY_NAME で行う。 |
| **重複防止** | アップロード済みをどう記録するか | **Room に「外部アップロード済み」テーブル**を用意し、`content_id`（MediaStore の `_id`）または content URI を保存。スキャン時に「未登録のみ」をアップロード。 |
| **Drive アップロード入力** | ファイルパス only の現状 | **Content URI から一時ファイルにコピー**して既存 `uploadFile(path)` を流用する、もしくは **DriveUploader に `uploadFromContentUri()` を追加**（InputStream で Drive API に渡す）。 |

---

## 3. アーキテクチャ設計

### 3.1 全体フロー

```
[標準カメラで撮影] → [DCIM/Camera 等に保存]
        ↓
[MediaStore に新規行が追加]
        ↓
[WorkManager: ExternalPhotoScanWorker が定期実行]
        ↓
  設定で指定した「監視バケット」に含まれる画像を MediaStore で検索
        ↓
  既に「アップロード済み」テーブルに無いものだけを対象に
        ↓
  各画像を Content URI → (一時ファイル or uploadFromContentUri) → DriveUploader
        ↓
  成功したら「アップロード済み」テーブルに content_id を登録
```

- **トリガー**: WorkManager の `PeriodicWorkRequest`（例: 15 分〜1 時間間隔、制約は `NetworkType.CONNECTED`）。
- **アップロード先**: 既存の `shared_drive_folder_id` と `account_email`（`picture_uploader_prefs`）。

### 3.2 コンポーネント構成

| コンポーネント | 役割 |
|----------------|------|
| **Settings** | 新設定項目「監視するフォルダ」を追加。MediaStore で取得したバケット一覧からユーザーが 1 つ選択。`watch_bucket_id` を SharedPreferences に保存。 |
| **ExternalPhotoScanWorker** | 定期実行。監視バケット内の「未アップロード」画像を MediaStore で取得 → Drive へアップロード → アップロード済みを DB に記録。 |
| **DriveUploader 拡張** | Content URI 対応。`uploadFromContentUri(uri, accountEmail, folderId)` を追加するか、呼び出し側で一時ファイルにコピーして既存 `uploadFile` を使用。 |
| **ExternalUploadRecord（Room Entity）** | 外部フォルダ由来でアップロード済みのメディアを記録。`content_id`（MediaStore._id）と `drive_file_id`、`uploaded_at` など。 |
| **PictureUploaderApp** | 起動時に `ExternalPhotoScanWorker` の PeriodicWork を `enqueueUniquePeriodicWork(KEEP)` で登録。 |

### 3.3 データ設計

#### 3.3.1 設定（SharedPreferences）

- 既存: `shared_drive_folder_id`, `account_email`, `drive_access_token`, `tap_to_capture`
- **追加**:
  - `watch_bucket_id`: String? … 監視する MediaStore の BUCKET_ID。null なら「監視しない」。
  - （任意）`watch_bucket_display_name`: String? … UI 表示用のフォルダ名。

#### 3.3.2 Room：外部アップロード済みテーブル

- **テーブル名**: `external_upload_records`
- **カラム案**:
  - `id`: Long, PK, autoGenerate
  - `content_id`: Long … MediaStore.Images.Media._id（一意なので重複防止に使う）
  - `content_uri`: String? … 参考用（ID があれば必須ではない）
  - `drive_file_id`: String?
  - `uploaded_at`: Long … epoch millis
- **インデックス**: `content_id` に UNIQUE を付与し、同一メディアの二重アップロードを防止。

---

## 4. 権限・マニフェスト

- **READ_MEDIA_IMAGES**（API 33+）: 画像へのアクセスに必要。
- **READ_EXTERNAL_STORAGE**（API 32 以下）: 従来のストレージ読み取り。
- **INTERNET / ACCESS_NETWORK_STATE**: 既存のまま（アップロードに使用）。
- Foreground Service を採用する場合は **FOREGROUND_SERVICE** と **FOREGROUND_SERVICE_DATA_SYNC**（または適切な type）、**POST_NOTIFICATIONS** を追加。

本設計では **WorkManager のみ** を想定するため、上記のストレージ＋ネットワーク権限で足りる。

---

## 5. 設定 UI

### 5.1 設定画面の変更（SettingsActivity）

- **「監視するフォルダ」セクションを追加**
  - 説明文: 「標準カメラアプリで撮影した写真を保存するフォルダを選ぶと、そのフォルダに保存された写真を自動で Google Drive にアップロードします。」
  - 現在選択中のフォルダ名を表示（`watch_bucket_display_name` またはバケット ID から取得）。
  - 「フォルダを選択」ボタン → **監視フォルダ選択用の Activity またはダイアログ** を起動。

### 5.2 監視フォルダの選択方法

- **MediaStore で「画像が含まれるバケット」一覧を取得**
  - `ContentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ...)` で `BUCKET_ID`, `BUCKET_DISPLAY_NAME` を取得し、`BUCKET_ID` で GROUP BY するか、重複を除いたリストを表示。
  - よくある例: "Camera", "DCIM", "Screenshots", "Download" など。
- ユーザーが 1 つ選択 → `watch_bucket_id` と `watch_bucket_display_name` を保存。
- 「監視しない」オプションを明示できるとよい（バケット未選択 = 監視しない、でも可）。

---

## 6. 実装時の注意点・リスク

1. **MediaStore の _id の永続性**  
   再起動やアプリ更新後も同じ画像は同じ _id が使われることが多いが、保証はない。可能なら **content_uri** も一緒に保存し、ID で見つからない場合のフォールバックに使う検討の余地あり。

2. **バッテリー・実行間隔**  
   PeriodicWork の最小間隔は 15 分程度。それ以上頻繁にしたくても制約がある。ユーザーに「最大 15 分程度遅れる」ことを説明するとよい。

3. **既存の UploadWorker との役割分担**  
   - **UploadWorker**: アプリ内撮影分（`photos` テーブルの PENDING/FAILED）のみ。
   - **ExternalPhotoScanWorker**: 設定で指定したバケット内の「未登録」画像のみ。
   両方とも同じ `DriveUploader` と `shared_drive_folder_id` を使う。

4. **DriveUploader の Content URI 対応**  
   `FileContent` の代わりに `InputStreamContent` を使うオーバーロードを追加する場合、ContentResolver から `openInputStream(uri)` でストリームを渡す。一時ファイルにコピーする方式なら既存コードの変更は最小で済む。

5. **クリーンアップ**  
   外部アップロード済みレコードは、ローカル側で写真を削除しても MediaStore の _id は残ることがある。必要なら「一定期間経過したレコードの削除」は CleanupWorker と別のポリシーで検討（本フェーズでは必須ではない）。

---

## 7. 実装タスク一覧（チェックリスト）

- [ ] **設計・データ**
  - [ ] Room: `ExternalUploadRecord` Entity と DAO、DB バージョンアップ・マイグレーション
  - [ ] SharedPreferences: `watch_bucket_id` / `watch_bucket_display_name` のキー定義と読み書き

- [ ] **DriveUploader**
  - [ ] Content URI 対応: `uploadFromContentUri()` の追加、または呼び出し側で一時ファイル作成 → `uploadFile()` でアップロード

- [ ] **Worker**
  - [ ] `ExternalPhotoScanWorker`: 監視バケット取得、MediaStore で未アップロード画像取得、アップロード、レコード挿入
  - [ ] `PictureUploaderApp`: ExternalPhotoScanWorker の PeriodicWork 登録（間隔・制約の決定）

- [ ] **設定 UI**
  - [ ] 設定画面: 「監視するフォルダ」セクション、現在値表示、「フォルダを選択」ボタン
  - [ ] 監視フォルダ選択: MediaStore からバケット一覧取得、リスト表示、選択結果の保存

- [ ] **権限**
  - [ ] AndroidManifest: READ_MEDIA_IMAGES（API 33+）、READ_EXTERNAL_STORAGE（それ未満）
  - [ ] ランタイム権限リクエスト（必要なら Settings または初回起動時）

- [ ] **テスト・検証**
  - [ ] 監視フォルダ未設定のときは Worker がスキップすることを確認
  - [ ] 標準カメラで撮影 → 指定フォルダに保存 → 次回 Worker 実行でアップロードされることを確認
  - [ ] 同じ写真が二重アップロードされないことを確認

---

## 8. まとめ

- **標準カメラで保存された写真**を、**ユーザーが設定で選んだ「監視フォルダ」（MediaStore のバケット）**に限定して検知し、既存の **Google Drive フォルダ**へバックグラウンドでアップロードする。
- **検知は WorkManager の定期ポーリング**で行い、**二重アップロード防止**は Room の「外部アップロード済み」テーブルで行う。
- 実装に必要なスキルは、**Scoped Storage / MediaStore / ContentResolver、WorkManager、Room、既存 DriveUploader の拡張、設定 UI** に集約される。  
  Foreground Service は「即時性」が必要になった場合の拡張オプションとして保留とする。

この設計に沿って実装を進めれば、メインの撮影〜アップロード機能と役割を分離したまま、標準カメラ誤撮影ケースをカバーできる。

---

## 9. 実装仕様確認（実機 Redmi 9T で取得）

実機に USB（adb）で接続し、API level と MediaStore のバケットを確認した結果。実装時の具体的な値として参照する。

### 9.1 端末・OS

| 項目 | 値 |
|------|-----|
| 機種 | Xiaomi Redmi 9T (M2010J19SG / lime) |
| Android バージョン | 10 |
| **API Level (SDK)** | **29** |
| セキュリティパッチ | 2021-06-01 |

- API 29 = Android 10 のため Scoped Storage が必須。ファイルパス直接アクセスは不可で、MediaStore API が必須。

### 9.2 アプリ側の前提（build.gradle.kts）

| 項目 | 値 |
|------|-----|
| minSdk | 26 |
| targetSdk | 35 |
| compileSdk | 35 |

- 実機は API 29 のため、API 29 向けの権限・MediaStore 実装で問題ない。
- 権限: API 33 未満では **READ_EXTERNAL_STORAGE**、API 33 以上では **READ_MEDIA_IMAGES** を付与する実装とする。

### 9.3 MediaStore：実機で取得したバケット一覧

| bucket_id | bucket_display_name | 備考 |
|-----------|---------------------|------|
| -1739773001 | **Camera** | 標準カメラの保存先（DCIM/Camera） |
| -1313584517 | Screenshots | スクリーンショット |
| -2068453783 | Chatwork | アプリ別フォルダ |
| 1389444597 | 0 | その他 |

- 監視対象の候補として **Camera**（bucket_id = -1739773001）を設定で選べるようにすればよい。

### 9.4 MediaStore：Camera バケットの詳細（実機）

- **URI**: `content://media/external/images/media`
- **投影カラム**: `_id`, `bucket_id`, `bucket_display_name`, `date_added`, `relative_path` などが利用可能。
- **Camera の例**:
  - `relative_path` = `DCIM/Camera/`
  - `bucket_display_name` = `Camera`
  - `bucket_id` = `-1739773001`（整数。保存時は String 化でよい）

実装時のポイント:

- バケット一覧は `ContentResolver.query(EXTERNAL_CONTENT_URI, projection, null, null, sort)` で取得し、`BUCKET_ID` で重複を除く。
- 監視対象の指定は **BUCKET_ID を String で保存**（例: `"-1739773001"`）。表示は `BUCKET_DISPLAY_NAME` を使う。
- 二重アップロード防止は MediaStore の **`_id`** をキーに「アップロード済み」テーブルに記録する方針でよい。

### 9.5 実装時の具体的な値（参考）

- **標準カメラの保存先**: `bucket_display_name = "Camera"`, `bucket_id = -1739773001`, `relative_path = "DCIM/Camera/"`。
- クエリ例（監視バケット指定）:  
  `ContentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, "bucket_id = ?", arrayOf(watchBucketId), "date_added DESC")`
---

## 10. 実装後の検証手順

実装完了後、以下の操作で動作を確認する。

### 10.1 ビルド・インストール

- `.\gradlew assembleDebug` でビルド成功すること。
- 実機に `app-debug.apk` をインストール（`adb install -r` または `.\install-release.ps1` の代わりに debug を指定）。

### 10.2 設定 UI の確認

1. アプリを起動し、**設定**（歯車）を開く。
2. 「**監視するフォルダ**」セクションが表示されていること。
3. 初期状態で「**監視しない**」と表示されていること。
4. **「フォルダを選択」** をタップ → ストレージ許可を求めるダイアログが出る場合、**許可**する。
5. バケット一覧（「監視しない」「Camera」「Screenshots」など）が表示されること。
6. **「Camera」** を選択 → 設定画面に戻り「**選択中: Camera**」と表示されること。
7. 再度「フォルダを選択」で **「監視しない」** を選ぶと「監視しない」に戻ること。

### 10.3 バックグラウンドアップロードの確認

1. 設定で **アップロード先フォルダ**（Drive フォルダ ID）と **Google ログイン** を済ませておく。
2. **監視するフォルダ** を **「Camera」** に設定する。
3. 端末の **標準カメラアプリ** で 1 枚写真を撮影し、DCIM/Camera に保存されることを確認する。
4. アプリをバックグラウンドにしたまま、最大 15 分程度待つ（または WorkManager のテスト用に一時的に間隔を短くして再ビルドする）。
5. Google Drive の設定したフォルダに、その写真がアップロードされていることを確認する。
6. 同じ写真が二重にアップロードされていないことを確認する。

### 10.4 コードレビュー観点（実施済み）

- **セキュリティ**: watchBucketId は SharedPreferences から取得し、MediaStore の selection にバインド引数で渡しているため SQL インジェクションの心配はない。Content URI はシステムが提供する値のみ使用。
- **パフォーマンス**: 監視バケット内の全件をメモリに載せず、cursor で 1 件ずつ処理。uploadedIds は Set で O(1) 参照。一時ファイルはアップロード後に削除。
- **可読性**: 定数・TAG でログと設定キーを整理。Worker と設定の責務が分離されている。
- **テスト**: 単体テストは未追加。手動で上記 10.2 / 10.3 を確認すること。

### 10.5 ログ確認（adb logcat）

- `adb logcat -s ExternalPhotoScanWorker:D PictureUploaderApp:D` で Worker の実行ログを確認できる。
- 「Watch bucket not configured; skipping」→ 監視フォルダ未設定でスキップ。
- 「Uploaded external photo: contentId=...」→ アップロード成功。

---