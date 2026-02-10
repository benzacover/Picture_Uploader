package com.pictureuploader.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * 写真データのRepository。
 * DAOへのアクセスを集約し、UIやWorkerから利用する。
 */
class PhotoRepository(context: Context) {

    private val dao: PhotoDao = AppDatabase.getInstance(context).photoDao()

    /**
     * 新規撮影写真をPENDING状態で登録
     */
    suspend fun insertPending(localPath: String, capturedAt: Long): Long {
        val entity = PhotoEntity(
            localPath = localPath,
            capturedAt = capturedAt,
            uploadStatus = UploadStatus.PENDING.name
        )
        return dao.insert(entity)
    }

    /**
     * アップロード対象(PENDING / FAILED)を取得
     */
    suspend fun getPendingOrFailed(): List<PhotoEntity> {
        return dao.getPendingOrFailed()
    }

    /**
     * ステータスをUPLOADINGに更新
     */
    suspend fun markUploading(id: Long) {
        dao.updateStatus(id, UploadStatus.UPLOADING.name)
    }

    /**
     * アップロード成功
     */
    suspend fun markUploaded(id: Long, driveFileId: String) {
        dao.updateUploaded(
            id = id,
            status = UploadStatus.UPLOADED.name,
            uploadedAt = System.currentTimeMillis(),
            driveFileId = driveFileId
        )
    }

    /**
     * アップロード失敗
     */
    suspend fun markFailed(id: Long) {
        dao.updateStatus(id, UploadStatus.FAILED.name)
    }

    /**
     * クリーンアップ対象を取得
     */
    suspend fun getCleanupTargets(cutoffMillis: Long): List<PhotoEntity> {
        return dao.getCleanupTargets(cutoffMillis)
    }

    /**
     * DBレコード削除
     */
    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }

    // --- UI用 Flow ---

    fun countPendingFlow(): Flow<Int> = dao.countPendingFlow()

    fun countUploadedFlow(): Flow<Int> = dao.countUploadedFlow()

    fun countFailedFlow(): Flow<Int> = dao.countFailedFlow()
}
