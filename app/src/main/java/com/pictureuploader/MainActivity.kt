package com.pictureuploader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pictureuploader.auth.AuthManager
import com.pictureuploader.camera.CameraHelper
import com.pictureuploader.data.PhotoRepository
import com.pictureuploader.databinding.ActivityMainBinding
import com.pictureuploader.worker.UploadWorker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * メイン画面。
 *
 * - カメラプレビュー
 * - 撮影ボタン
 * - Googleログイン / ログアウト
 * - アップロード状態表示
 * - 設定画面への導線
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "picture_uploader_prefs"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var authManager: AuthManager
    private lateinit var cameraHelper: CameraHelper
    private lateinit var repository: PhotoRepository

    // カメラ権限リクエスト
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraPreview()
        } else {
            Toast.makeText(this, getString(R.string.error_camera_permission), Toast.LENGTH_LONG).show()
        }
    }

    // Drive認可画面の結果
    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val success = authManager.handleAuthorizationResult(result.data)
            if (success) {
                Toast.makeText(this, getString(R.string.msg_sign_in_success), Toast.LENGTH_SHORT).show()
                updateAuthUI()
            } else {
                Toast.makeText(this, getString(R.string.error_drive_permission), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.error_drive_permission), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)
        cameraHelper = CameraHelper(this)
        repository = PhotoRepository(this)

        setupButtons()
        requestCameraPermission()
        restoreAuthState()
        observeUploadStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraHelper.shutdown()
    }

    // =============================================
    // ボタン設定
    // =============================================

    private fun setupButtons() {
        // 撮影ボタン
        binding.btnCapture.setOnClickListener {
            onCaptureClicked()
        }

        // ログインボタン
        binding.btnSignIn.setOnClickListener {
            if (authManager.isSignedIn()) {
                onSignOut()
            } else {
                onSignIn()
            }
        }

        // 設定ボタン
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // =============================================
    // カメラ
    // =============================================

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreview() {
        cameraHelper.startCamera(binding.previewView, this)
    }

    // =============================================
    // 撮影
    // =============================================

    private fun onCaptureClicked() {
        // 事前チェック
        if (!authManager.isSignedIn()) {
            Toast.makeText(this, getString(R.string.error_not_signed_in), Toast.LENGTH_LONG).show()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folderId = prefs.getString(SettingsActivity.KEY_FOLDER_ID, null)
        if (folderId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.error_folder_id_not_set), Toast.LENGTH_LONG).show()
            return
        }

        // 撮影実行
        binding.btnCapture.isEnabled = false
        lifecycleScope.launch {
            try {
                val photoPath = cameraHelper.takePhoto()
                val capturedAt = System.currentTimeMillis()

                // DBにPENDING登録
                repository.insertPending(photoPath, capturedAt)

                // UploadWorker起動
                enqueueUploadWork()

                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.msg_capture_success),
                    Toast.LENGTH_SHORT
                ).show()
                Log.d(TAG, "Photo captured and queued: $photoPath")

            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_capture_failed),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnCapture.isEnabled = true
            }
        }
    }

    /**
     * UploadWorkerをエンキューする。
     * ExistingWorkPolicy.KEEP で多重起動を防止。
     */
    private fun enqueueUploadWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .addTag(UploadWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            UploadWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            uploadRequest
        )

        Log.d(TAG, "UploadWorker enqueued")
    }

    // =============================================
    // Google認証
    // =============================================

    private fun onSignIn() {
        lifecycleScope.launch {
            try {
                val email = authManager.signIn()
                if (email != null) {
                    // アカウントメールをSharedPrefsに保存 (Worker用)
                    saveAccountEmail(email)

                    // Drive認可を要求
                    authManager.requestDriveAuthorization(this@MainActivity, driveAuthLauncher)

                    updateAuthUI()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.msg_sign_in_success),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_auth_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sign-in error", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_auth_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun onSignOut() {
        authManager.signOut()
        clearAccountEmail()
        updateAuthUI()
        Toast.makeText(this, getString(R.string.msg_sign_out_success), Toast.LENGTH_SHORT).show()
    }

    private fun restoreAuthState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val email = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        if (email != null) {
            // 保存されたメールアドレスがあればUI反映
            // (注: AccessTokenは再認可が必要な場合がある)
        }
        updateAuthUI()
    }

    private fun updateAuthUI() {
        val email = authManager.currentAccountEmail
            ?: getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ACCOUNT_EMAIL, null)

        if (email != null) {
            binding.tvAuthStatus.text = getString(R.string.label_signed_in, email)
            binding.btnSignIn.text = getString(R.string.btn_sign_out)
        } else {
            binding.tvAuthStatus.text = getString(R.string.label_not_signed_in)
            binding.btnSignIn.text = getString(R.string.btn_sign_in)
        }
    }

    private fun saveAccountEmail(email: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNT_EMAIL, email)
            .apply()
    }

    private fun clearAccountEmail() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACCOUNT_EMAIL)
            .apply()
    }

    // =============================================
    // アップロード状態表示
    // =============================================

    private fun observeUploadStatus() {
        lifecycleScope.launch {
            repository.countPendingFlow().collectLatest { count ->
                binding.tvPending.text = getString(R.string.label_pending_count, count)
            }
        }
        lifecycleScope.launch {
            repository.countUploadedFlow().collectLatest { count ->
                binding.tvUploaded.text = getString(R.string.label_uploaded_count, count)
            }
        }
        lifecycleScope.launch {
            repository.countFailedFlow().collectLatest { count ->
                binding.tvFailed.text = getString(R.string.label_failed_count, count)
            }
        }
    }
}
