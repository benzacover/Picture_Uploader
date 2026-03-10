package com.pictureuploader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExternalUploadRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: ExternalUploadRecord): Long

    /** 指定 content_id が既に登録されているか */
    @Query("SELECT COUNT(*) FROM external_upload_records WHERE contentId = :contentId")
    suspend fun existsByContentId(contentId: Long): Int

    /** 複数の content_id のうち、未登録のものを判定するために全登録済み ID を取得 */
    @Query("SELECT contentId FROM external_upload_records")
    suspend fun getAllContentIds(): List<Long>
}
