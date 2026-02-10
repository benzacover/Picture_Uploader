package com.pictureuploader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Insert
    suspend fun insert(photo: PhotoEntity): Long

    /**
     * アップロード対象(PENDING or FAILED)を取得
     */
    @Query("SELECT * FROM photos WHERE uploadStatus = 'PENDING' OR uploadStatus = 'FAILED' ORDER BY capturedAt ASC")
    suspend fun getPendingOrFailed(): List<PhotoEntity>

    /**
     * アップロード状態を更新
     */
    @Query("UPDATE photos SET uploadStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /**
     * アップロード成功時にステータス・日時・DriveファイルIDを更新
     */
    @Query("UPDATE photos SET uploadStatus = :status, uploadedAt = :uploadedAt, driveFileId = :driveFileId WHERE id = :id")
    suspend fun updateUploaded(id: Long, status: String, uploadedAt: Long, driveFileId: String)

    /**
     * クリーンアップ対象を取得。
     * uploadStatus == UPLOADED かつ capturedAt が指定日時以前のもの。
     */
    @Query("SELECT * FROM photos WHERE uploadStatus = 'UPLOADED' AND capturedAt <= :cutoffMillis")
    suspend fun getCleanupTargets(cutoffMillis: Long): List<PhotoEntity>

    /**
     * DBレコードを削除
     */
    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 各ステータスの件数をリアルタイムで取得 (UI用)
     */
    @Query("SELECT COUNT(*) FROM photos WHERE uploadStatus = 'PENDING' OR uploadStatus = 'UPLOADING'")
    fun countPendingFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE uploadStatus = 'UPLOADED'")
    fun countUploadedFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE uploadStatus = 'FAILED'")
    fun countFailedFlow(): Flow<Int>
}
