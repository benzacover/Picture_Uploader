package com.pictureuploader.drive

/**
 * フォルダピッカー用のドライブ／フォルダ一覧アイテム。
 * @param id フォルダまたはドライブのID（マイドライブのルートは "root"）
 * @param name 表示名
 */
data class DriveFolderItem(val id: String, val name: String)
