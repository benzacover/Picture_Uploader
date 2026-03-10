package com.pictureuploader.worker

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.pictureuploader.data.AppDatabase
import com.pictureuploader.data.ExternalUploadRecord
import com.pictureuploader.drive.DriveUploader
import com.pictureuploader.drive.UploadFailureReason
import com.pictureuploader.drive.UploadResult
import com.pictureuploader.util.UploadFailureLogger
import com.pictureuploader.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 設定で指定した「監視フォルダ」（MediaStore バケット）内の画像をスキャンし、
 * 未アップロード分を Google Drive にアップロードする。
 * 制約: NetworkType.CONNECTED。
 */
class ExternalPhotoScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ExternalPhotoScanWorker"
        const val WORK_NAME = "external_photo_scan_work"

        private const val PREFS_NAME = "picture_uploader_prefs"
        private const val KEY_FOLDER_ID = "shared_drive_folder_id"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_WATCH_BUCKET_ID = "watch_bucket_id"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "ExternalPhotoScanWorker started")

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val watchBucketId = prefs.getString(KEY_WATCH_BUCKET_ID, null)?.trim()?.ifBlank { null }

        if (watchBucketId.isNullOrBlank()) {
            Log.d(TAG, "Watch bucket not configured; skipping")
            return@withContext Result.success()
        }

        val folderId = DriveUploader.normalizeFolderId(prefs.getString(KEY_FOLDER_ID, null))
        val accountEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null)?.trim()?.ifBlank { null }

        if (folderId.isNullOrBlank()) {
            Log.e(TAG, "Folder ID not configured")
            return@withContext Result.failure()
        }

        if (accountEmail.isNullOrBlank()) {
            Log.w(TAG, "Account email not set; will retry later")
            return@withContext Result.retry()
        }

        // 15分ごとの実行時に必ずトークン更新を試行（ユーザーがアプリを開かなくても期限切れを防ぐ）
        val authManager = AuthManager(applicationContext)
        if (authManager.isSignedIn()) {
            withContext(Dispatchers.Main.immediate) {
                authManager.tryRefreshDriveToken()
            }
        }

        val db = AppDatabase.getInstance(applicationContext)
        val externalDao = db.externalUploadRecordDao()
        val driveUploader = DriveUploader(applicationContext)

        val uploadedIds = externalDao.getAllContentIds().toSet()
        val cursor = applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.BUCKET_ID} = ?",
            arrayOf(watchBucketId),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        ) ?: run {
            Log.e(TAG, "ContentResolver.query returned null")
            return@withContext Result.retry()
        }

        var uploadedCount = 0
        var failureCount = 0

        cursor.use {
            val idColumnIndex = it.getColumnIndex(MediaStore.Images.Media._ID)
            if (idColumnIndex < 0) {
                Log.e(TAG, "Column _ID not found")
                return@withContext Result.success()
            }
            while (it.moveToNext()) {
                val contentId = it.getLong(idColumnIndex)
                if (contentId in uploadedIds) continue

                val contentUri: Uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentId
                )
                val uriString = contentUri.toString()

                when (val result = driveUploader.uploadFromContentUri(
                    contentUri = uriString,
                    accountEmail = accountEmail,
                    folderId = folderId
                )) {
                    is UploadResult.Success -> {
                        externalDao.insert(
                            ExternalUploadRecord(
                                contentId = contentId,
                                contentUri = uriString,
                                driveFileId = result.driveFileId,
                                uploadedAt = System.currentTimeMillis()
                            )
                        )
                        uploadedCount++
                        Log.d(TAG, "Uploaded external photo: contentId=$contentId -> ${result.driveFileId}")
                    }
                    is UploadResult.Failure -> {
                        failureCount++
                        Log.e(TAG, "Upload failed [${result.reason}]: $uriString - ${result.message}")
                        UploadFailureLogger.log(
                            applicationContext,
                            result.reason.name,
                            "contentUri=$uriString bucketId=$watchBucketId msg=${result.message}",
                            null
                        )
                        when (result.reason) {
                            UploadFailureReason.FOLDER_ID_INVALID,
                            UploadFailureReason.FOLDER_NOT_FOUND -> {
                                Log.e(TAG, "Folder problem; stopping work")
                                return@withContext Result.failure()
                            }
                            else -> { /* continue to next */ }
                        }
                    }
                }
            }
        }

        Log.d(TAG, "ExternalPhotoScanWorker finished: uploaded=$uploadedCount failures=$failureCount")
        if (failureCount > 0) Result.retry() else Result.success()
    }
}
