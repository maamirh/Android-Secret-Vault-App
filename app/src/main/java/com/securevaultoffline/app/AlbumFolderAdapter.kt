package com.securevaultoffline.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.securevaultoffline.app.databinding.ItemAlbumFolderBinding
import java.io.File
import java.util.LinkedHashSet

data class AlbumFolderUi(
    val name: String,
    val itemCount: Int,
    val directory: File,
)

/**
 * Top-level album grid on the library screen. Supports multi-select (overflow menu or long-press)
 * for bulk delete / move / copy into another album.
 */
class AlbumFolderAdapter(
    private val onOpen: (AlbumFolderUi) -> Unit,
    private val onSelectionChanged: () -> Unit,
) : ListAdapter<AlbumFolderUi, AlbumFolderAdapter.VH>(DIFF) {

    private var selectionActive = false
    private val selectedDirs = LinkedHashSet<File>()

    fun setSelectionActive(active: Boolean) {
        selectionActive = active
        selectedDirs.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun clearSelection() {
        if (!selectionActive && selectedDirs.isEmpty()) return
        selectionActive = false
        selectedDirs.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun isSelectionActive(): Boolean = selectionActive

    fun getSelectedAlbumDirs(): List<File> = selectedDirs.toList()

    fun totalSelectedCount(): Int = selectedDirs.size

    fun selectAllVisible() {
        if (!selectionActive) return
        selectedDirs.clear()
        currentList.forEach { selectedDirs.add(it.directory) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun submitAlbums(items: List<AlbumFolderUi>) {
        submitList(items) {
            if (selectionActive && itemCount > 0) {
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }

    private fun toggleDir(dir: File) {
        if (!selectedDirs.remove(dir)) selectedDirs.add(dir)
        val idx = currentList.indexOfFirst { it.directory == dir }
        if (idx >= 0) notifyItemChanged(idx) else notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun startSelectionWith(album: AlbumFolderUi) {
        selectionActive = true
        selectedDirs.clear()
        selectedDirs.add(album.directory)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAlbumFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    private fun applyFolderRowA11y(binding: ItemAlbumFolderBinding, item: AlbumFolderUi, sel: Boolean) {
        val ctx = binding.root.context
        binding.folderIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        binding.folderName.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        binding.folderCount.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        binding.root.contentDescription = if (sel) {
            ctx.getString(R.string.a11y_root_album_select_mode, item.name, item.itemCount)
        } else {
            ctx.getString(R.string.a11y_album_folder_row, item.name, item.itemCount)
        }
        if (sel) {
            binding.folderSelectCheck.contentDescription =
                ctx.getString(R.string.a11y_toggle_select_album, item.name)
        } else {
            binding.folderSelectCheck.contentDescription = null
        }
    }

    inner class VH(
        private val binding: ItemAlbumFolderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AlbumFolderUi) {
            binding.folderName.text = item.name
            binding.folderCount.text = binding.root.context.getString(R.string.folder_item_count, item.itemCount)

            val sel = selectionActive
            binding.folderSelectCheck.visibility = if (sel) View.VISIBLE else View.GONE
            binding.folderSelectCheck.isChecked = selectedDirs.contains(item.directory)

            binding.root.setOnClickListener {
                if (sel) {
                    toggleDir(item.directory)
                    binding.folderSelectCheck.isChecked = selectedDirs.contains(item.directory)
                } else {
                    onOpen(item)
                }
            }
            binding.root.setOnLongClickListener {
                if (sel) return@setOnLongClickListener false
                startSelectionWith(item)
                true
            }
            binding.folderSelectCheck.setOnClickListener {
                if (!sel) return@setOnClickListener
                toggleDir(item.directory)
                binding.folderSelectCheck.isChecked = selectedDirs.contains(item.directory)
            }

            applyFolderRowA11y(binding, item, sel)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AlbumFolderUi>() {
            override fun areItemsTheSame(a: AlbumFolderUi, b: AlbumFolderUi) =
                a.directory.absolutePath == b.directory.absolutePath

            override fun areContentsTheSame(a: AlbumFolderUi, b: AlbumFolderUi) =
                a.name == b.name && a.itemCount == b.itemCount
        }
    }
}
