package com.pictureuploader.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * アップロード失敗時に端末内のファイルにログを追記する。
 * adb pull で取得可能: adb pull /sdcard/Android/data/com.pictureuploader/files/upload_failures.log
 */
object UploadFailureLogger {
    private const val FILENAME = "upload_failures.log"
    private const val MAX_LINES = 500
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun log(context: Context, reason: String, detail: String, throwable: Throwable? = null) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val file = File(dir, FILENAME)
            val line = buildString {
                append(dateFormat.format(Date()))
                append(" | ")
                append(reason)
                append(" | ")
                append(detail)
                if (throwable != null) {
                    append("\n")
                    append(StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString())
                }
                append("\n")
            }
            if (!file.exists()) file.createNewFile()
            var current = file.readText()
            current = (line + current).lineSequence().take(MAX_LINES).joinToString("\n")
            if (!current.endsWith("\n")) current += "\n"
            file.writeText(current)
        } catch (e: Exception) {
            Log.e("UploadFailureLogger", "Failed to write log file", e)
        }
    }

    /** ログファイルのパス（adb pull 用） */
    fun getLogPath(context: Context): String? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(dir, FILENAME).takeIf { it.exists() }?.absolutePath
    }
}
