package com.pictureuploader.drive

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.pictureuploader.R
import com.pictureuploader.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Google Drive のドライブ一覧・フォルダ一覧を取得する。
 * 設定画面のフォルダピッカーで利用する。
 */
class DriveFolderBrowser(private val context: Context) {

    companion object {
        private const val TAG = "DriveFolderBrowser"
        private const val APP_NAME = "PictureUploader"
        /** マイドライブのルートを示すID。Drive API で標準的に使う。 */
        const val MY_DRIVE_ROOT_ID = "root"
        private const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
        private const val PAGE_SIZE = 100
    }

    /**
     * ルート一覧を返す。「マイドライブ」のみ返す（drives().list() は AccountManager 依存のため呼ばない）。
     * 共有ドライブは listFolders で「マイドライブ」を開いたあと必要なら別途対応可能。
     */
    suspend fun listDriveRoots(accountEmail: String): Result<List<DriveFolderItem>> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "listDriveRoots start accountEmail=${accountEmail.take(3)}***")
            val list = mutableListOf<DriveFolderItem>()
            list.add(DriveFolderItem(MY_DRIVE_ROOT_ID, context.getString(R.string.folder_picker_my_drive)))
            // 共有ドライブ一覧は GoogleAccountCredential が端末の AccountManager を必要とし、
            // Credential Manager のみでログインした場合は失敗するため、まずはマイドライブのみ表示する。
            val drive = buildDriveService(accountEmail)
            var pageToken: String? = null
            try {
                do {
                    val result = drive.drives().list()
                        .setPageSize(PAGE_SIZE)
                        .setPageToken(pageToken)
                        .execute()
                    result.drives?.forEach { driveItem ->
                        val id = driveItem.id ?: return@runCatching throw IOException("Drive has no id")
                        val name = driveItem.name?.trim()?.ifBlank { null } ?: id
                        list.add(DriveFolderItem(id, name))
                    }
                    pageToken = result.nextPageToken
                } while (pageToken != null)
            } catch (e: Exception) {
                Log.w(TAG, "drives().list() failed (shared drives skipped): ${e.message}")
                // マイドライブのみで続行
            }
            Log.d(TAG, "listDriveRoots: ${list.size} items")
            list
        }.onFailure { e ->
            Log.e(TAG, "listDriveRoots failed: ${e.javaClass.simpleName} ${e.message}", e)
            e.cause?.let { Log.e(TAG, "listDriveRoots cause: ${it.javaClass.simpleName} ${it.message}", it) }
            if (e is HttpResponseException && e.statusCode == 401) clearStoredToken()
        }
    }

    /**
     * 指定した親フォルダ直下のフォルダ一覧を返す。
     * parentId に "root" または 共有ドライブID または フォルダID を指定可能。
     */
    suspend fun listFolders(accountEmail: String, parentId: String): Result<List<DriveFolderItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = buildDriveService(accountEmail)
            val list = mutableListOf<DriveFolderItem>()
            val q = "'${parentId.replace("'", "\\'")}' in parents and mimeType='$MIME_TYPE_FOLDER'"
            var pageToken: String? = null
            do {
                val result = drive.files().list()
                    .setQ(q)
                    .setPageSize(PAGE_SIZE)
                    .setPageToken(pageToken)
                    .setOrderBy("name")
                    .setFields("nextPageToken, files(id, name)")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .execute()
                result.files?.forEach { file ->
                    val id = file.id ?: return@runCatching throw IOException("File has no id")
                    val name = file.name?.trim()?.ifBlank { null } ?: id
                    list.add(DriveFolderItem(id, name))
                }
                pageToken = result.nextPageToken
            } while (pageToken != null)
            Log.d(TAG, "listFolders($parentId): ${list.size} items")
            list
        }.onFailure { e ->
            Log.e(TAG, "listFolders failed parentId=$parentId: ${e.javaClass.simpleName} ${e.message}", e)
            e.cause?.let { Log.e(TAG, "listFolders cause: ${it.javaClass.simpleName} ${it.message}", it) }
            if (e is HttpResponseException && e.statusCode == 401) clearStoredToken()
        }
    }

    private fun buildDriveService(accountEmail: String): Drive {
        val prefs = context.getSharedPreferences(AuthManager.PREFS_NAME, Context.MODE_PRIVATE)
        val accessToken = prefs.getString(AuthManager.KEY_DRIVE_ACCESS_TOKEN, null)?.trim()?.ifBlank { null }

        return if (!accessToken.isNullOrEmpty()) {
            Log.d(TAG, "Using stored access token for Drive API")
            val initializer = HttpRequestInitializer { request: HttpRequest ->
                request.headers.authorization = "Bearer $accessToken"
            }
            Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                initializer
            )
                .setApplicationName(APP_NAME)
                .build()
        } else {
            Log.d(TAG, "Using GoogleAccountCredential for Drive API")
            val name = accountEmail.trim().ifBlank { null }
            if (name.isNullOrEmpty() || !name.contains("@")) {
                throw IllegalArgumentException("Valid account email required")
            }
            val scopes = listOf(DriveScopes.DRIVE, DriveScopes.DRIVE_FILE).distinct()
            val credential = GoogleAccountCredential.usingOAuth2(context, scopes)
            credential.selectedAccountName = name
            Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
            .setApplicationName(APP_NAME)
            .build()
        }
    }

    private fun clearStoredToken() {
        try {
            context.getSharedPreferences(AuthManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(AuthManager.KEY_DRIVE_ACCESS_TOKEN)
                .apply()
            Log.w(TAG, "Cleared stored token (401)")
        } catch (e: Exception) { Log.e(TAG, "Failed to clear token", e) }
    }
}
