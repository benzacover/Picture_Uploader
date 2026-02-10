package com.pictureuploader.drive

/**
 * アップロード結果。
 * 失敗時は理由と「再試行で直る可能性があるか」を保持。
 */
sealed class UploadResult {
    data class Success(val driveFileId: String) : UploadResult()
    data class Failure(
        val reason: UploadFailureReason,
        val message: String,
        val retriable: Boolean
    ) : UploadResult()
}

/**
 * 想定しうる失敗理由（ログ・条件分岐用）。
 */
enum class UploadFailureReason {
    /** ローカルファイルが存在しない */
    FILE_NOT_FOUND,
    /** ファイルが読めない／0バイト */
    FILE_UNREADABLE,
    /** フォルダIDが空・不正 */
    FOLDER_ID_INVALID,
    /** 401 トークン無効・要再認証 */
    UNAUTHORIZED,
    /** 403 権限不足（フォルダ・スコープ・未確認アプリ等） */
    FORBIDDEN,
    /** 429 レート制限 → リトライ */
    RATE_LIMIT,
    /** 404 フォルダが存在しない */
    FOLDER_NOT_FOUND,
    /** 500/502/503 サーバーエラー → リトライ */
    SERVER_ERROR,
    /** ネットワーク・タイムアウト → リトライ */
    NETWORK_ERROR,
    /** その他（ログに詳細を残す） */
    UNKNOWN
}
