package com.pictureuploader

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pictureuploader.databinding.ActivitySettingsBinding
import com.pictureuploader.drive.DriveUploader

/**
 * 設定画面。
 * 共有ドライブのフォルダIDを入力・保存する。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "picture_uploader_prefs"
        const val KEY_FOLDER_ID = "shared_drive_folder_id"
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupSaveButton()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folderId = prefs.getString(KEY_FOLDER_ID, "") ?: ""
        binding.etFolderId.setText(folderId)
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
