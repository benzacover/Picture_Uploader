package com.pictureuploader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pictureuploader.auth.AuthManager
import com.pictureuploader.databinding.ActivityFolderPickerBinding
import com.pictureuploader.drive.DriveFolderBrowser
import com.pictureuploader.drive.DriveFolderItem
import kotlinx.coroutines.launch

/**
 * Google Drive 内のフォルダを一覧して選択する画面。
 * マイドライブ・共有ドライブをたどり、「このフォルダを選択」で ID を返す。
 */
class FolderPickerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ACCOUNT_EMAIL = "account_email"
        const val EXTRA_SELECTED_FOLDER_ID = "selected_folder_id"
        private const val PREFS_NAME = "picture_uploader_prefs"
        private const val KEY_ACCOUNT_EMAIL = "account_email"

        fun createIntent(context: Context, accountEmail: String?): Intent {
            return Intent(context, FolderPickerActivity::class.java).apply {
                putExtra(EXTRA_ACCOUNT_EMAIL, accountEmail)
            }
        }
    }

    private lateinit var binding: ActivityFolderPickerBinding
    private val browser = DriveFolderBrowser(this)
    private var accountEmail: String = ""
    /** (parentId, displayName) のスタック。空 = ルート一覧表示。 */
    private val stack = mutableListOf<Pair<String, String>>()
    private val adapter = FolderAdapter { item ->
        when (item) {
            is FolderItem.SelectHere -> {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_FOLDER_ID, item.folderId))
                finish()
            }
            is FolderItem.Folder -> {
                stack.add(item.id to item.name)
                loadCurrentLevel()
            }
        }
    }

    private lateinit var authManager: AuthManager
    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (authManager.handleAuthorizationResult(result.data)) {
                Toast.makeText(this, getString(R.string.msg_sign_in_success), Toast.LENGTH_SHORT).show()
                loadCurrentLevel()
            } else {
                Toast.makeText(this, getString(R.string.folder_picker_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        accountEmail = intent.getStringExtra(EXTRA_ACCOUNT_EMAIL)
            ?: getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ACCOUNT_EMAIL, null)
            ?: ""

        authManager = AuthManager(this)

        if (accountEmail.isBlank() || !accountEmail.contains("@")) {
            Toast.makeText(this, getString(R.string.folder_picker_not_signed_in), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener {
            if (stack.isNotEmpty()) {
                stack.removeAt(stack.lastIndex)
                loadCurrentLevel()
            } else {
                finish()
            }
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        // トークンが無い場合は先に Drive 認可を促す
        val hasToken = getSharedPreferences(AuthManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(AuthManager.KEY_DRIVE_ACCESS_TOKEN, null)?.isNotBlank() == true
        if (!hasToken) {
            requestDriveAuthAndLoad()
            return
        }
        loadCurrentLevel()
    }

    /** トークンが無いときに Drive 認可を要求してから一覧を読み込む */
    private fun requestDriveAuthAndLoad() {
        binding.progress.isVisible = true
        binding.tvEmpty.isVisible = false
        binding.recycler.isVisible = false
        lifecycleScope.launch {
            val result = authManager.requestDriveAuthorization(this@FolderPickerActivity, driveAuthLauncher)
            binding.progress.isVisible = false
            if (result != null) {
                loadCurrentLevel()
            } else {
                binding.tvEmpty.text = getString(R.string.folder_picker_need_drive_auth)
                binding.tvEmpty.isVisible = true
            }
        }
    }

    private fun loadCurrentLevel() {
        binding.progress.isVisible = true
        binding.tvEmpty.isVisible = false
        binding.recycler.isVisible = false

        lifecycleScope.launch {
            if (stack.isEmpty()) {
                browser.listDriveRoots(accountEmail)
                    .onSuccess { list ->
                        binding.progress.isVisible = false
                        adapter.submitList(list.map { FolderItem.Folder(it.id, it.name) })
                        binding.recycler.isVisible = true
                        binding.toolbar.title = getString(R.string.folder_picker_title)
                    }
                    .onFailure { e ->
                        binding.progress.isVisible = false
                        val msg = e?.message ?: getString(R.string.folder_picker_error)
                        android.util.Log.e("FolderPicker", "listDriveRoots failed", e)
                        binding.tvEmpty.text = getString(R.string.folder_picker_error) + "\n" + (e?.javaClass?.simpleName ?: "") + ": " + (e?.message ?: "")
                        binding.tvEmpty.isVisible = true
                        Toast.makeText(this@FolderPickerActivity, msg, Toast.LENGTH_LONG).show()
                    }
            } else {
                val (parentId, parentName) = stack.last()
                binding.toolbar.title = parentName
                browser.listFolders(accountEmail, parentId)
                    .onSuccess { list ->
                        binding.progress.isVisible = false
                        val items = listOf(FolderItem.SelectHere(parentId)) + list.map { FolderItem.Folder(it.id, it.name) }
                        adapter.submitList(items)
                        binding.recycler.isVisible = true
                    }
                    .onFailure { e ->
                        binding.progress.isVisible = false
                        val msg = e?.message ?: getString(R.string.folder_picker_error)
                        android.util.Log.e("FolderPicker", "listFolders failed", e)
                        binding.tvEmpty.text = getString(R.string.folder_picker_error) + "\n" + (e?.javaClass?.simpleName ?: "") + ": " + (e?.message ?: "")
                        binding.tvEmpty.isVisible = true
                        Toast.makeText(this@FolderPickerActivity, msg, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private sealed class FolderItem {
        data class SelectHere(val folderId: String) : FolderItem()
        data class Folder(val id: String, val name: String) : FolderItem()
    }

    private class FolderAdapter(private val onItemClick: (FolderItem) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<FolderItem> = emptyList()

        fun submitList(list: List<FolderItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is FolderItem.SelectHere -> 0
            is FolderItem.Folder -> 1
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> {
                    val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_picker_select_here, parent, false)
                    SelectHereHolder(v)
                }
                else -> {
                    val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_picker, parent, false)
                    FolderHolder(v)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when (holder) {
                is SelectHereHolder -> {
                    holder.bind(item as FolderItem.SelectHere)
                    holder.itemView.setOnClickListener { onItemClick(item) }
                }
                is FolderHolder -> {
                    holder.bind(item as FolderItem.Folder)
                    holder.itemView.setOnClickListener { onItemClick(item) }
                }
            }
        }

        class SelectHereHolder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(item: FolderItem.SelectHere) {
                (itemView.findViewById<TextView>(R.id.title)).text = itemView.context.getString(R.string.folder_picker_select_this)
            }
        }

        class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.title)
            fun bind(item: FolderItem.Folder) {
                title.text = item.name
            }
        }
    }
}
