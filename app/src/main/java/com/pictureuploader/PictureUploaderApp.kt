package com.pictureuploader

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pictureuploader.worker.CleanupWorker
import com.pictureuploader.worker.ExternalPhotoScanWorker
import java.util.concurrent.TimeUnit

/**
 * Applicationクラス。
 * アプリ起動時にCleanupWorkerの定期実行を登録する。
 */
class PictureUploaderApp : Application() {

    companion object {
        private const val TAG = "PictureUploaderApp"
    }

    override fun onCreate() {
        super.onCreate()
        scheduleCleanupWorker()
        scheduleExternalPhotoScanWorker()
    }

    /**
     * クリーンアップWorkerを1日1回の定期実行で登録する。
     * ExistingPeriodicWorkPolicy.KEEP により多重登録を防止。
     */
    private fun scheduleCleanupWorker() {
        val constraints = Constraints.Builder()
            .build()

        val cleanupRequest = PeriodicWorkRequestBuilder<CleanupWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag(CleanupWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest
        )

        Log.d(TAG, "CleanupWorker scheduled (every 24h)")
    }

    /**
     * 監視フォルダ内の写真をスキャンして Drive にアップロードする Worker を
     * 5分間隔で登録する。ExistingPeriodicWorkPolicy.KEEP で多重登録を防止。
     * 注: WorkManager の制約で実際の最小間隔は 15 分になる場合があります。
     */
    private fun scheduleExternalPhotoScanWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val scanRequest = PeriodicWorkRequestBuilder<ExternalPhotoScanWorker>(
            repeatInterval = 5,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(ExternalPhotoScanWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ExternalPhotoScanWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            scanRequest
        )

        Log.d(TAG, "ExternalPhotoScanWorker scheduled (every 5 min)")
    }
}
