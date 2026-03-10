package com.pictureuploader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pictureuploader.databinding.ActivityWatchFolderPickerBinding
import com.pictureuploader.databinding.ItemWatchFolderBinding

/**
 * MediaStore の画像バケット（フォルダ）一覧を表示し、
 * 「監視するフォルダ」を 1 つ選択する画面。
 */
class WatchFolderPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WATCH_BUCKET_ID = "watch_bucket_id"
        const val EXTRA_WATCH_BUCKET_DISPLAY_NAME = "watch_bucket_display_name"

        fun createIntent(context: Context): Intent {
            return Intent(context, WatchFolderPickerActivity::class.java)
        }
    }

    private lateinit var binding: ActivityWatchFolderPickerBinding

    data class BucketItem(val bucketId: String, val displayName: String)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            loadBuckets()
        } else {
            Toast.makeText(this, getString(R.string.watch_folder_picker_permission_required), Toast.LENGTH_LONG).show()
            binding.progress.isVisible = false
            binding.tvEmpty.isVisible = true
            binding.tvEmpty.text = getString(R.string.watch_folder_picker_permission_required)
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES))
        } else {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = WatchFolderAdapter { item ->
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_WATCH_BUCKET_ID, item.bucketId)
                putExtra(EXTRA_WATCH_BUCKET_DISPLAY_NAME, item.displayName)
            })
            finish()
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.progress.isVisible = true
        binding.tvEmpty.isVisible = false

        if (hasStoragePermission()) {
            loadBuckets()
        } else {
            requestStoragePermission()
        }
    }

    private fun loadBuckets() {
        binding.progress.isVisible = true
        binding.tvEmpty.isVisible = false

        val list = mutableListOf<BucketItem>()
        list.add(BucketItem("", getString(R.string.label_watch_folder_none)))

        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val seen = mutableSetOf<String>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            if (idIdx >= 0 && nameIdx >= 0) {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx).toString()
                    val name = cursor.getString(nameIdx) ?: ""
                    if (id.isNotEmpty() && id !in seen) {
                        seen.add(id)
                        list.add(BucketItem(id, name.ifEmpty { id }))
                    }
                }
            }
        }

        binding.progress.isVisible = false
        binding.recycler.isVisible = list.isNotEmpty()
        binding.tvEmpty.isVisible = list.isEmpty()
        if (list.isEmpty()) {
            binding.tvEmpty.text = getString(R.string.watch_folder_picker_loading)
        }

        (binding.recycler.adapter as? WatchFolderAdapter)?.submitList(list)
    }

    private class WatchFolderAdapter(
        private val onItemClick: (BucketItem) -> Unit
    ) : RecyclerView.Adapter<WatchFolderAdapter.VH>() {

        private var items: List<BucketItem> = emptyList()

        fun submitList(list: List<BucketItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemWatchFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.title.text = item.displayName
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemWatchFolderBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
