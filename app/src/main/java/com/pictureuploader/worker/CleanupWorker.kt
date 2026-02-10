package com.pictureuploader.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pictureuploader.data.PhotoRepository
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 自動削除Worker (PeriodicCleanupWorker)。
 *
 * 実行頻度: 1日1回 (PeriodicWorkRequest)
 *
 * 削除条件:
 * - uploadStatus == UPLOADED
 * - capturedAt <= now - 30days
 *
 * 削除フロー:
 * 1. 対象レコード取得
 * 2. localPath のファイル存在確認
 * 3. ファイル delete()
 * 4. 成功 → DBレコードも削除
 * 5. 失敗 → ログ出力のみ
 *
 * capturedAt 基準で判定 (uploadedAt は使用しない)
 */
class CleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "CleanupWorker"
        const val WORK_NAME = "cleanup_work"
        private const val RETENTION_DAYS = 30L
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "CleanupWorker started")

        val repository = PhotoRepository(applicationContext)
        val cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)

        val targets = repository.getCleanupTargets(cutoffMillis)
        Log.d(TAG, "Cleanup targets: ${targets.size} (cutoff: $cutoffMillis)")

        if (targets.isEmpty()) {
            Log.d(TAG, "No files to clean up")
            return Result.success()
        }

        var deletedCount = 0
        var failedCount = 0

        for (photo in targets) {
            try {
                val file = File(photo.localPath)
                if (file.exists()) {
                    val deleted = file.delete()
                    if (deleted) {
                        // ファイル削除成功 → DBレコードも削除
                        repository.deleteById(photo.id)
                        deletedCount++
                        Log.d(TAG, "Deleted: ${photo.localPath}")
                    } else {
                        // ファイル削除失敗 → ログのみ
                        failedCount++
                        Log.w(TAG, "Failed to delete file: ${photo.localPath}")
                    }
                } else {
                    // ファイルが既に存在しない → DBレコードも削除
                    repository.deleteById(photo.id)
                    deletedCount++
                    Log.d(TAG, "File already gone, removed DB record: ${photo.localPath}")
                }
            } catch (e: Exception) {
                failedCount++
                Log.e(TAG, "Cleanup error: ${photo.localPath}", e)
            }
        }

        Log.d(TAG, "Cleanup finished. Deleted: $deletedCount, Failed: $failedCount")
        return Result.success()
    }
}
