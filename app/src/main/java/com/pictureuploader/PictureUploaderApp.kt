package com.pictureuploader

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pictureuploader.worker.CleanupWorker
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
}
