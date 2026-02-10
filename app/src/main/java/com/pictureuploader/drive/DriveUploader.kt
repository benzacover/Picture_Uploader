package com.pictureuploader.drive

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.min

/**
 * Google Drive API v3 を使ったファイルアップロード処理。
 * 共有ドライブ(Shared Drive)対応。複数失敗要因に対応し、リトライ・条件分岐を行う。
 */
class DriveUploader(private val context: Context) {

    companion object {
        private const val TAG = "DriveUploader"
        private const val APP_NAME = "PictureUploader"
        private const val MIME_TYPE_JPEG = "image/jpeg"
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 2_000L

        /** フォルダIDを正規化（前後空白除去、URLからIDのみ抽出） */
        fun normalizeFolderId(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            var s = raw.trim()
            if (s.isEmpty()) return null
            // https://drive.google.com/drive/folders/XXXX 形式から ID のみ抽出
            val prefix = "drive.google.com/drive/folders/"
            val i = s.indexOf(prefix)
            if (i >= 0) {
                s = s.substring(i + prefix.length).trim()
                val end = s.indexOfAny(charArrayOf('/', '?', '&', ' '))
                if (end > 0) s = s.substring(0, end)
            }
            return s.ifBlank { null }
        }
    }

    /**
     * 指定されたローカルファイルをGoogle Driveにアップロードする。
     * フォルダIDは正規化され、ファイル存在・読み取り可能を確認してから実行。
     * サーバーエラー・ネットワークエラー・429はリトライする。
     *
     * @return Success(driveFileId) または Failure(reason, message, retriable)
     */
    suspend fun uploadFile(
        localFilePath: String,
        accountEmail: String,
        folderId: String
    ): UploadResult = withContext(Dispatchers.IO) {
        val normalizedFolderId = normalizeFolderId(folderId)
        if (normalizedFolderId.isNullOrBlank()) {
            Log.e(TAG, "Folder ID invalid or empty: [$folderId]")
            return@withContext UploadResult.Failure(
                UploadFailureReason.FOLDER_ID_INVALID,
                "Folder ID is empty or invalid",
                retriable = false
            )
        }

        val localFile = File(localFilePath)
        if (!localFile.exists()) {
            Log.e(TAG, "Local file not found: $localFilePath")
            return@withContext UploadResult.Failure(
                UploadFailureReason.FILE_NOT_FOUND,
                "Local file not found: $localFilePath",
                retriable = false
            )
        }
        if (!localFile.canRead()) {
            Log.e(TAG, "Local file not readable: $localFilePath")
            return@withContext UploadResult.Failure(
                UploadFailureReason.FILE_UNREADABLE,
                "Cannot read file: $localFilePath",
                retriable = false
            )
        }
        if (localFile.length() == 0L) {
            Log.e(TAG, "Local file is empty: $localFilePath")
            return@withContext UploadResult.Failure(
                UploadFailureReason.FILE_UNREADABLE,
                "File is empty: $localFilePath",
                retriable = false
            )
        }

        Log.d(TAG, "Uploading: $localFilePath to folder: $normalizedFolderId (size=${localFile.length()})")

        // 複数スコープを要求（DRIVE を優先しつつ drive.file も利用可能に）
        val scopes = listOf(DriveScopes.DRIVE, DriveScopes.DRIVE_FILE).distinct()
        var lastResult: UploadResult? = null
        var attempt = 0

        while (attempt < MAX_RETRIES) {
            attempt++
            lastResult = executeUpload(
                localFile = localFile,
                accountEmail = accountEmail,
                folderId = normalizedFolderId,
                scopes = scopes
            )
            when (lastResult) {
                is UploadResult.Success -> return@withContext lastResult
                is UploadResult.Failure -> {
                    if (!lastResult.retriable) return@withContext lastResult
                    val delayMs = INITIAL_BACKOFF_MS * (1 shl (attempt - 1))
                    Log.w(TAG, "Retriable failure (attempt $attempt/$MAX_RETRIES), retrying in ${delayMs}ms: ${lastResult.message}")
                    kotlinx.coroutines.delay(min(delayMs, 30_000L))
                }
            }
        }
        lastResult ?: UploadResult.Failure(
            UploadFailureReason.UNKNOWN,
            "No result after $MAX_RETRIES attempts",
            retriable = true
        )
    }

    private fun executeUpload(
        localFile: File,
        accountEmail: String,
        folderId: String,
        scopes: List<String>
    ): UploadResult {
        return try {
            val driveService = buildDriveService(accountEmail, scopes)
            val driveFileMetadata = DriveFile().apply {
                name = localFile.name
                parents = listOf(folderId)
            }
            val mediaContent = FileContent(MIME_TYPE_JPEG, localFile)
            val uploadedFile = driveService.files().create(driveFileMetadata, mediaContent)
                .setSupportsAllDrives(true)
                .setFields("id, name")
                .execute()
            Log.d(TAG, "Upload success: ${uploadedFile.id} (${uploadedFile.name})")
            UploadResult.Success(uploadedFile.id)
        } catch (e: HttpResponseException) {
            val code = e.statusCode
            val reason = when (code) {
                401 -> UploadFailureReason.UNAUTHORIZED
                403 -> {
                    val body = e.content?.toString() ?: ""
                    when {
                        body.contains("rateLimitExceeded", ignoreCase = true) ||
                        body.contains("userRateLimitExceeded", ignoreCase = true) ->
                            UploadFailureReason.RATE_LIMIT
                        else -> UploadFailureReason.FORBIDDEN
                    }
                }
                404 -> UploadFailureReason.FOLDER_NOT_FOUND
                429 -> UploadFailureReason.RATE_LIMIT
                500, 502, 503 -> UploadFailureReason.SERVER_ERROR
                else -> UploadFailureReason.UNKNOWN
            }
            val retriable = reason in listOf(
                UploadFailureReason.RATE_LIMIT,
                UploadFailureReason.SERVER_ERROR
            )
            Log.e(TAG, "Drive API error: $code ${e.statusMessage} -> $reason", e)
            UploadResult.Failure(reason, "HTTP $code: ${e.statusMessage}", retriable)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Network timeout", e)
            UploadResult.Failure(UploadFailureReason.NETWORK_ERROR, "Timeout: ${e.message}", retriable = true)
        } catch (e: IOException) {
            Log.e(TAG, "Network/IO error", e)
            val msg = e.message ?: e.javaClass.simpleName
            UploadResult.Failure(UploadFailureReason.NETWORK_ERROR, msg, retriable = true)
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            UploadResult.Failure(
                UploadFailureReason.UNKNOWN,
                "${e.javaClass.simpleName}: ${e.message}",
                retriable = false
            )
        }
    }

    private fun buildDriveService(accountEmail: String, scopes: List<String>): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(context, scopes)
        credential.selectedAccountName = accountEmail
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()
    }
}
