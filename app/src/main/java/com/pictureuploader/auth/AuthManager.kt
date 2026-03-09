package com.pictureuploader.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.tasks.await

/**
 * Google認証を管理するクラス。
 *
 * 1. Credential Manager でGoogleアカウント認証(ID Token取得)
 * 2. AuthorizationClient でDrive APIスコープ認可(Access Token取得)
 */
class AuthManager(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"

        /**
         * Google Cloud ConsoleのOAuthクライアントID(Webクライアント)。
         * ビルド前に必ず自分のプロジェクトのものに置き換えること。
         */
        const val WEB_CLIENT_ID = "349094932075-fprka8graivl1qhv5cf25f72hpab0rgh.apps.googleusercontent.com"

        /**
         * Drive APIスコープ。共有ドライブなど指定フォルダへのアップロードには DRIVE が必要。
         */
        val DRIVE_SCOPE = DriveScopes.DRIVE

        const val PREFS_NAME = "picture_uploader_prefs"
        const val KEY_DRIVE_ACCESS_TOKEN = "drive_access_token"
        const val KEY_ACCOUNT_EMAIL = "account_email"
    }

    private val credentialManager = CredentialManager.create(context)
    private val authorizationClient: AuthorizationClient = Identity.getAuthorizationClient(context)

    /** 認証済みのGoogleアカウントメールアドレス */
    var currentAccountEmail: String? = null
        private set

    /** Drive API用アクセストークン */
    var accessToken: String? = null
        private set

    init {
        restoreFromPrefs()
    }

    /**
     * SharedPreferences からトークン・メールを復元する。
     * Credential Manager でログインした場合、端末の AccountManager にアカウントが無いため、
     * フォルダピッカー等では保存したトークンを使う必要がある。
     */
    fun restoreFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val email = prefs.getString(KEY_ACCOUNT_EMAIL, null)?.trim()?.ifBlank { null }
        val token = prefs.getString(KEY_DRIVE_ACCESS_TOKEN, null)?.trim()?.ifBlank { null }
        if (email != null) currentAccountEmail = email
        if (token != null) accessToken = token
        Log.d(TAG, "restoreFromPrefs: email=${email?.take(3)}***, hasToken=${token != null}")
    }

    /**
     * Credential Manager でGoogleアカウントにサインインする。
     * @return 認証されたメールアドレス (失敗時はnull)
     */
    suspend fun signIn(): String? {
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(true)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response: GetCredentialResponse = credentialManager.getCredential(
                request = request,
                context = context as Activity
            )

            handleSignInResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed", e)
            null
        }
    }

    /**
     * サインインレスポンスを処理してメールアドレスを返す
     */
    private fun handleSignInResponse(response: GetCredentialResponse): String? {
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            currentAccountEmail = googleIdTokenCredential.id
            persistAccountEmail(currentAccountEmail)
            Log.d(TAG, "Signed in as: $currentAccountEmail")
            return currentAccountEmail
        }
        Log.e(TAG, "Unexpected credential type: ${credential.type}")
        return null
    }

    /**
     * Drive APIスコープの認可を要求する。
     *
     * @param activity Activity (IntentSender起動用)
     * @param authLauncher 認可画面のActivityResult用ランチャー
     * @return AuthorizationResult (認可済みの場合はAccessTokenを含む), null (ユーザー操作が必要な場合)
     */
    suspend fun requestDriveAuthorization(
        activity: Activity,
        authLauncher: ActivityResultLauncher<IntentSenderRequest>
    ): AuthorizationResult? {
        return try {
            val authRequest = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
                .build()

            val result = authorizationClient.authorize(authRequest).await()

            if (result.hasResolution()) {
                // ユーザーの同意が必要 → 同意画面を起動
                val pendingIntent = result.pendingIntent
                if (pendingIntent != null) {
                    authLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.getIntentSender()).build()
                    )
                }
                null // コールバックで結果を受け取る
            } else {
                // 既に認可済み
                accessToken = result.accessToken
                persistAccessToken(result.accessToken)
                Log.d(TAG, "Drive authorization granted (cached)")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Drive authorization failed", e)
            null
        }
    }

    /**
     * 認可画面からの結果を処理する
     */
    fun handleAuthorizationResult(data: Intent?): Boolean {
        return try {
            val result = authorizationClient.getAuthorizationResultFromIntent(data)
            accessToken = result.accessToken
            persistAccessToken(result.accessToken)
            Log.d(TAG, "Drive authorization granted via consent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle authorization result", e)
            false
        }
    }

    /**
     * サインアウト
     */
    fun signOut() {
        currentAccountEmail = null
        accessToken = null
        persistAccessToken(null)
        persistAccountEmail(null)
        Log.d(TAG, "Signed out")
    }

    /**
     * ログイン済みかどうか
     */
    fun isSignedIn(): Boolean = currentAccountEmail != null

    /**
     * Drive認可済みかどうか
     */
    fun hasDriveAccess(): Boolean = accessToken != null

    private fun persistAccessToken(token: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRIVE_ACCESS_TOKEN, token)
            .apply()
    }

    private fun persistAccountEmail(email: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNT_EMAIL, email)
            .apply()
    }
}
