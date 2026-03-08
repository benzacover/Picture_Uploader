package com.pictureuploader

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pictureuploader.auth.AuthManager
import com.pictureuploader.databinding.ActivitySettingsBinding
import com.pictureuploader.drive.DriveUploader
import kotlinx.coroutines.launch

/**
 * 設定画面。
 * アカウント（ログイン・ログアウト）と、共有ドライブのフォルダIDを設定する。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "picture_uploader_prefs"
        const val KEY_FOLDER_ID = "shared_drive_folder_id"
        const val KEY_ACCOUNT_EMAIL = "account_email"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var authManager: AuthManager

    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val success = authManager.handleAuthorizationResult(result.data)
            if (success) {
                Toast.makeText(this, getString(R.string.msg_sign_in_success), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.error_drive_permission), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.error_drive_permission), Toast.LENGTH_LONG).show()
        }
        updateAccountUI()
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val folderId = result.data?.getStringExtra(FolderPickerActivity.EXTRA_SELECTED_FOLDER_ID)
            if (!folderId.isNullOrBlank()) {
                binding.etFolderId.setText(folderId)
                Toast.makeText(this, getString(R.string.msg_folder_id_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)
        setupToolbar()
        setupAccountSection()
        loadSettings()
        setupPickFolderButton()
        setupSaveButton()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupAccountSection() {
        updateAccountUI()
        binding.btnLoginLogout.setOnClickListener {
            if (authManager.isSignedIn()) {
                authManager.signOut()
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_ACCOUNT_EMAIL).apply()
                updateAccountUI()
                Toast.makeText(this, getString(R.string.msg_sign_out_success), Toast.LENGTH_SHORT).show()
            } else {
                lifecycleScope.launch {
                    try {
                        val email = authManager.signIn()
                        if (email != null) {
                            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putString(KEY_ACCOUNT_EMAIL, email).apply()
                            authManager.requestDriveAuthorization(this@SettingsActivity, driveAuthLauncher)
                            updateAccountUI()
                            Toast.makeText(this@SettingsActivity, getString(R.string.msg_sign_in_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@SettingsActivity, getString(R.string.error_auth_failed), Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Log.e("SettingsActivity", "Sign-in error", e)
                        Toast.makeText(this@SettingsActivity, getString(R.string.error_auth_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateAccountUI() {
        val email = authManager.currentAccountEmail
            ?: getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ACCOUNT_EMAIL, null)
        if (email != null) {
            binding.tvAccountStatus.text = getString(R.string.label_signed_in, email)
            binding.btnLoginLogout.text = getString(R.string.btn_sign_out)
        } else {
            binding.tvAccountStatus.text = getString(R.string.label_not_signed_in)
            binding.btnLoginLogout.text = getString(R.string.btn_sign_in)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folderId = prefs.getString(KEY_FOLDER_ID, "") ?: ""
        binding.etFolderId.setText(folderId)
    }

    private fun setupPickFolderButton() {
        binding.btnPickFolder.setOnClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val accountEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null)?.trim()?.ifBlank { null }
            if (accountEmail.isNullOrEmpty() || !accountEmail.contains("@")) {
                Toast.makeText(this, getString(R.string.folder_picker_not_signed_in), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            folderPickerLauncher.launch(FolderPickerActivity.createIntent(this, accountEmail))
        }
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val raw = binding.etFolderId.text.toString()
            val folderId = DriveUploader.normalizeFolderId(raw)

            if (folderId.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.msg_folder_id_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_FOLDER_ID, folderId).apply()

            Toast.makeText(this, getString(R.string.msg_folder_id_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
