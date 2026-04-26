package com.securevaultoffline.app

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.securevaultoffline.app.databinding.ItemAlbumSubfolderCellBinding
import com.securevaultoffline.app.databinding.ItemVaultFileBinding
import java.io.File
import java.util.LinkedHashSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class AlbumBrowseItem {
    abstract val stableKey: String

    data class SubfolderRow(
        val dir: File,
        val displayName: String,
        val itemCount: Int,
    ) : AlbumBrowseItem() {
        override val stableKey: String get() = "d:${dir.absolutePath}"
    }

    data class VaultFileRow(val file: File) : AlbumBrowseItem() {
        override val stableKey: String get() = "f:${file.absolutePath}"
    }
}

/**
 * Unified album browser: subfolders and vault files in one list so grid layout matches
 * (folders and files sit side‑by‑side like a file manager). Supports multi‑select for both.
 */
class AlbumBrowseAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val thumbnailGenerator: VaultThumbnailGenerator,
    private var vaultUnlocked: Boolean,
    private var viewMode: ViewMode,
    private val onOpenSubfolder: (File) -> Unit,
    private val onRequestDeleteFolder: (File) -> Unit,
    private val onOpenFromGrid: (File) -> Unit,
    private val onViewImage: (File) -> Unit,
    private val onPlayVideo: (File) -> Unit,
    private val onExport: (File) -> Unit,
    private val onDeleteFile: (File) -> Unit,
    private val onSelectionChanged: () -> Unit,
) : ListAdapter<AlbumBrowseItem, RecyclerView.ViewHolder>(DIFF) {

    private var selectionActive = false
    private val selectedFolders = LinkedHashSet<File>()
    private val selectedFiles = LinkedHashSet<File>()

    enum class ViewMode { LIST, GRID }

    fun setVaultUnlocked(unlocked: Boolean) {
        vaultUnlocked = unlocked
        if (!unlocked) clearSelection()
        notifyDataSetChanged()
    }

    fun setViewMode(mode: ViewMode) {
        viewMode = mode
        notifyDataSetChanged()
    }

    fun submitBrowse(folderRows: List<AlbumFolderUi>, files: List<File>) {
        val rows = ArrayList<AlbumBrowseItem>(folderRows.size + files.size)
        folderRows.forEach { f ->
            rows.add(AlbumBrowseItem.SubfolderRow(f.directory, f.name, f.itemCount))
        }
        files.forEach { rows.add(AlbumBrowseItem.VaultFileRow(it)) }
        submitList(rows)
    }

    fun isSelectionActive(): Boolean = selectionActive

    fun getSelectedVaultFiles(): List<File> = selectedFiles.toList()

    fun getSelectedSubfolders(): List<File> = selectedFolders.toList()

    fun totalSelectedCount(): Int = selectedFiles.size + selectedFolders.size

    fun browseItemCount(): Int = currentList.size

    fun setSelectionActive(active: Boolean) {
        selectionActive = active
        if (!active) {
            selectedFiles.clear()
            selectedFolders.clear()
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun clearSelection() {
        selectionActive = false
        selectedFiles.clear()
        selectedFolders.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectAllVisible() {
        if (!selectionActive) return
        selectedFiles.clear()
        selectedFolders.clear()
        currentList.forEach { row ->
            when (row) {
                is AlbumBrowseItem.SubfolderRow -> selectedFolders.add(row.dir)
                is AlbumBrowseItem.VaultFileRow -> selectedFiles.add(row.file)
            }
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun toggleFile(file: File) {
        if (!selectedFiles.remove(file)) selectedFiles.add(file)
        val idx = currentList.indexOfFirst { it is AlbumBrowseItem.VaultFileRow && it.file == file }
        if (idx >= 0) notifyItemChanged(idx) else notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun toggleFolder(dir: File) {
        if (!selectedFolders.remove(dir)) selectedFolders.add(dir)
        val idx = currentList.indexOfFirst { it is AlbumBrowseItem.SubfolderRow && it.dir == dir }
        if (idx >= 0) notifyItemChanged(idx) else notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun startSelectionWithFile(file: File) {
        selectionActive = true
        selectedFiles.clear()
        selectedFolders.clear()
        selectedFiles.add(file)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun startSelectionWithFolder(dir: File) {
        selectionActive = true
        selectedFiles.clear()
        selectedFolders.clear()
        selectedFolders.add(dir)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is AlbumBrowseItem.SubfolderRow -> VT_FOLDER
        is AlbumBrowseItem.VaultFileRow -> VT_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VT_FOLDER -> {
                val binding = ItemAlbumSubfolderCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                FolderVH(binding)
            }
            else -> {
                val binding = ItemVaultFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                FileVH(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is AlbumBrowseItem.SubfolderRow -> (holder as FolderVH).bind(item, vaultUnlocked, viewMode)
            is AlbumBrowseItem.VaultFileRow -> (holder as FileVH).bind(item.file, vaultUnlocked, viewMode)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is FileVH) holder.cancelThumbJob()
        super.onViewRecycled(holder)
    }

    private inner class FolderVH(
        private val binding: ItemAlbumSubfolderCellBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: AlbumBrowseItem.SubfolderRow, vaultUnlocked: Boolean, viewMode: ViewMode) {
            binding.root.alpha = if (vaultUnlocked) 1f else 0.45f
            binding.folderCellName.text = row.displayName
            binding.folderCellCount.text = binding.root.context.getString(R.string.folder_item_count, row.itemCount)

            val heroPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                if (viewMode == ViewMode.GRID) 112f else 72f,
                binding.root.resources.displayMetrics,
            ).toInt()
            binding.folderHero.layoutParams = binding.folderHero.layoutParams.apply { height = heroPx }

            val sel = selectionActive
            binding.folderSelectCheck.visibility = if (vaultUnlocked && sel) View.VISIBLE else View.GONE
            binding.folderSelectCheck.isChecked = selectedFolders.contains(row.dir)
            binding.folderMoreButton.visibility = if (vaultUnlocked && !sel) View.VISIBLE else View.GONE

            binding.folderMoreButton.setOnClickListener { anchor ->
                PopupMenu(anchor.context, binding.folderMoreButton).apply {
                    menu.add(Menu.NONE, MENU_DELETE_FOLDER, Menu.NONE, anchor.context.getString(R.string.delete_folder))
                    setOnMenuItemClickListener { mi ->
                        if (mi.itemId == MENU_DELETE_FOLDER) {
                            onRequestDeleteFolder(row.dir)
                            true
                        } else {
                            false
                        }
                    }
                    show()
                }
            }

            binding.root.setOnClickListener {
                if (!vaultUnlocked) return@setOnClickListener
                if (sel) {
                    toggleFolder(row.dir)
                    binding.folderSelectCheck.isChecked = selectedFolders.contains(row.dir)
                } else {
                    onOpenSubfolder(row.dir)
                }
            }

            binding.root.setOnLongClickListener {
                if (!vaultUnlocked || sel) return@setOnLongClickListener false
                startSelectionWithFolder(row.dir)
                true
            }

            binding.folderSelectCheck.setOnClickListener {
                if (!vaultUnlocked || !sel) return@setOnClickListener
                toggleFolder(row.dir)
                binding.folderSelectCheck.isChecked = selectedFolders.contains(row.dir)
            }
        }
    }

    private inner class FileVH(
        private val binding: ItemVaultFileBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var thumbJob: Job? = null

        fun cancelThumbJob() {
            thumbJob?.cancel()
            thumbJob = null
        }

        fun bind(file: File, vaultUnlocked: Boolean, viewMode: ViewMode) {
            cancelThumbJob()
            binding.fileName.text = VaultMedia.displayName(file)
            binding.root.alpha = if (vaultUnlocked) 1f else 0.45f

            val sel = selectionActive
            binding.selectCheck.visibility = if (vaultUnlocked && sel) View.VISIBLE else View.GONE
            binding.selectCheck.isChecked = selectedFiles.contains(file)

            val thumbPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                if (viewMode == ViewMode.GRID) 112f else 72f,
                binding.root.resources.displayMetrics,
            ).toInt()
            binding.thumbnail.layoutParams = binding.thumbnail.layoutParams.apply { height = thumbPx }

            val kind = VaultMedia.kindForVaultFile(file)
            binding.fileKind.text = when (kind) {
                VaultMediaKind.IMAGE -> binding.root.context.getString(R.string.file_kind_image)
                VaultMediaKind.VIDEO -> binding.root.context.getString(R.string.file_kind_video)
                VaultMediaKind.OTHER -> binding.root.context.getString(R.string.file_kind_other)
            }

            when (kind) {
                VaultMediaKind.IMAGE -> {
                    binding.viewButton.visibility = View.VISIBLE
                    binding.playButton.visibility = View.GONE
                }
                VaultMediaKind.VIDEO -> {
                    binding.viewButton.visibility = View.GONE
                    binding.playButton.visibility = View.VISIBLE
                }
                VaultMediaKind.OTHER -> {
                    binding.viewButton.visibility = View.GONE
                    binding.playButton.visibility = View.GONE
                }
            }

            binding.thumbnail.setImageDrawable(null)
            binding.thumbnail.setBackgroundResource(R.drawable.bg_thumb_placeholder)
            val path = file.absolutePath
            if (vaultUnlocked && (kind == VaultMediaKind.IMAGE || kind == VaultMediaKind.VIDEO)) {
                thumbJob = lifecycleOwner.lifecycleScope.launch {
                    val thumb = withContext(Dispatchers.IO) {
                        thumbnailGenerator.buildThumbnail(file)
                    }
                    if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@launch
                    val still = currentList.getOrNull(bindingAdapterPosition) as? AlbumBrowseItem.VaultFileRow
                    if (still?.file?.absolutePath != path) return@launch
                    if (thumb != null) {
                        binding.thumbnail.load(thumb) {
                            crossfade(true)
                            placeholder(R.drawable.bg_thumb_placeholder)
                            error(R.drawable.bg_thumb_placeholder)
                        }
                    }
                }
            }

            val inGrid = viewMode == ViewMode.GRID
            binding.actionScroll.visibility = when {
                sel -> View.GONE
                inGrid -> View.GONE
                else -> View.VISIBLE
            }

            binding.root.setOnClickListener {
                if (!vaultUnlocked) return@setOnClickListener
                if (sel) {
                    toggleFile(file)
                    binding.selectCheck.isChecked = selectedFiles.contains(file)
                } else if (inGrid) {
                    onOpenFromGrid(file)
                }
            }

            binding.root.setOnLongClickListener {
                if (!vaultUnlocked || sel) return@setOnLongClickListener false
                startSelectionWithFile(file)
                true
            }

            binding.selectCheck.setOnClickListener {
                if (!vaultUnlocked || !sel) return@setOnClickListener
                toggleFile(file)
                binding.selectCheck.isChecked = selectedFiles.contains(file)
            }

            binding.viewButton.setOnClickListener {
                if (!vaultUnlocked) return@setOnClickListener
                onViewImage(file)
            }
            binding.playButton.setOnClickListener {
                if (!vaultUnlocked) return@setOnClickListener
                onPlayVideo(file)
            }
            binding.exportButton.setOnClickListener {
                if (!vaultUnlocked) return@setOnClickListener
                onExport(file)
            }
            binding.deleteButton.setOnClickListener {
                if (!vaultUnlocked) return@setOnClickListener
                onDeleteFile(file)
            }
        }
    }

    companion object {
        private const val MENU_DELETE_FOLDER = 1001
        private const val VT_FOLDER = 0
        private const val VT_FILE = 1

        private val DIFF = object : DiffUtil.ItemCallback<AlbumBrowseItem>() {
            override fun areItemsTheSame(a: AlbumBrowseItem, b: AlbumBrowseItem) = a.stableKey == b.stableKey

            override fun areContentsTheSame(a: AlbumBrowseItem, b: AlbumBrowseItem): Boolean = when {
                a is AlbumBrowseItem.SubfolderRow && b is AlbumBrowseItem.SubfolderRow ->
                    a.displayName == b.displayName && a.itemCount == b.itemCount
                a is AlbumBrowseItem.VaultFileRow && b is AlbumBrowseItem.VaultFileRow ->
                    a.file.name == b.file.name && a.file.length() == b.file.length()
                else -> false
            }
        }
    }
}
