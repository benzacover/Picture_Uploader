package com.pictureuploader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 外部フォルダ（標準カメラ等）由来でアップロード済みのメディアを記録するEntity。
 * MediaStore の _id をキーに二重アップロードを防止する。
 */
@Entity(
    tableName = "external_upload_records",
    indices = [Index(value = ["contentId"], unique = true)]
)
data class ExternalUploadRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** MediaStore.Images.Media._id（一意・重複防止に使用） */
    val contentId: Long,

    /** 参考用（content URI）。必須ではない */
    val contentUri: String? = null,

    /** Google Drive のファイル ID */
    val driveFileId: String? = null,

    /** アップロード完了日時 (epoch millis) */
    val uploadedAt: Long
)
