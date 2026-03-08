package com.pictureuploader.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pictureuploader.data.PhotoRepository
import com.pictureuploader.data.UploadStatus
import com.pictureuploader.drive.DriveUploader
import com.pictureuploader.drive.UploadFailureReason
import com.pictureuploader.drive.UploadResult
import com.pictureuploader.util.UploadFailureLogger

/**
 * アップロードWorker。
 *
 * PENDING / FAILED 状態の写真を取得し、Google Driveへアップロードする。
 * フォルダIDは正規化（URLからID抽出・前後空白除去）し、
 * 失敗理由に応じて「再試行」「失敗で止める」「次のファイルへ」を分岐する。
 *
 * 条件: NetworkType.CONNECTED
 * 多重起動防止: ExistingWorkPolicy.KEEP
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "UploadWorker"
        const val WORK_NAME = "upload_work"

        private const val PREFS_NAME = "picture_uploader_prefs"
        private const val KEY_FOLDER_ID = "shared_drive_folder_id"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "UploadWorker started")

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawFolderId = prefs.getString(KEY_FOLDER_ID, null)
        val folderId = DriveUploader.normalizeFolderId(rawFolderId)
        val accountEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null)?.trim()?.ifBlank { null }

        if (folderId.isNullOrBlank()) {
            Log.e(TAG, "Folder ID not configured or invalid: [$rawFolderId]")
            return Result.failure()
        }

        if (accountEmail.isNullOrBlank()) {
            Log.e(TAG, "Account email not set (not signed in)")
            return Result.retry()
        }

        val repository = PhotoRepository(applicationContext)
        val driveUploader = DriveUploader(applicationContext)

        val targets = repository.getPendingOrFailed()
        Log.d(TAG, "Upload targets: ${targets.size} (folderId=$folderId)")

        if (targets.isEmpty()) {
            Log.d(TAG, "No files to upload")
            return Result.success()
        }

        var hasFailure = false
        var shouldFailWork = false  // フォルダ不正等で以降の再試行も無意味な場合

        for (photo in targets) {
            if (shouldFailWork) break

            try {
                repository.markUploading(photo.id)

                when (val result = driveUploader.uploadFile(
                    localFilePath = photo.localPath,
                    accountEmail = accountEmail,
                    folderId = folderId
                )) {
                    is UploadResult.Success -> {
                        repository.markUploaded(photo.id, result.driveFileId)
                        Log.d(TAG, "Uploaded: ${photo.localPath} -> ${result.driveFileId}")
                    }
                    is UploadResult.Failure -> {
                        Log.e(TAG, "Upload failed [${result.reason}]: ${photo.localPath} - ${result.message}")
                        UploadFailureLogger.log(
                            applicationContext,
                            result.reason.name,
                            "path=${photo.localPath} folderId=$folderId msg=${result.message}"
                        )
                        repository.markFailed(photo.id)
                        hasFailure = true

                        when (result.reason) {
                            UploadFailureReason.FOLDER_ID_INVALID,
                            UploadFailureReason.FOLDER_NOT_FOUND -> {
                                Log.e(TAG, "Folder problem (${result.reason}), stopping work to avoid pointless retries")
                                shouldFailWork = true
                            }
                            UploadFailureReason.UNAUTHORIZED -> {
                                Log.w(TAG, "Token invalid; user may need to re-sign-in. Will retry later.")
                            }
                            UploadFailureReason.FILE_NOT_FOUND,
                            UploadFailureReason.FILE_UNREADABLE -> {
                                Log.w(TAG, "File problem (${result.reason}), skipping; will not retry this file.")
                            }
                            else -> {
                                if (result.retriable) Log.d(TAG, "Retriable; WorkManager will retry.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error uploading: ${photo.localPath}", e)
                UploadFailureLogger.log(
                    applicationContext,
                    "UNKNOWN",
                    "path=${photo.localPath} unexpected=${e.javaClass.simpleName}: ${e.message}",
                    e
                )
                repository.markFailed(photo.id)
                hasFailure = true
            }
        }

        return when {
            shouldFailWork -> {
                Log.e(TAG, "Work failed due to folder/config issue")
                Result.failure()
            }
            hasFailure -> {
                Log.w(TAG, "Some uploads failed; WorkManager will retry")
                Result.retry()
            }
            else -> {
                Log.d(TAG, "All uploads completed")
                Result.success()
            }
        }
    }
}
