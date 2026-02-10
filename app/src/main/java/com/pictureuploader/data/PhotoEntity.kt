package com.pictureuploader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * アップロード状態を表すEnum。
 * Roomには文字列(name)で保存する。
 */
enum class UploadStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED
}

/**
 * 撮影した写真のメタデータを管理するEntity。
 * DBを唯一の真実(Single Source of Truth)として運用する。
 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** ローカルファイルパス */
    val localPath: String,

    /** 撮影日時 (epoch millis) */
    val capturedAt: Long,

    /** アップロード状態 (文字列で保存) */
    val uploadStatus: String = UploadStatus.PENDING.name,

    /** アップロード完了日時 (epoch millis, nullable) */
    val uploadedAt: Long? = null,

    /** Google DriveのファイルID (nullable) */
    val driveFileId: String? = null
)
