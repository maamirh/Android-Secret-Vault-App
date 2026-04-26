package com.securevaultoffline.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.securevaultoffline.app.databinding.ItemAlbumFolderBinding

data class AlbumFolderUi(
    val name: String,
    val itemCount: Int,
    val directory: java.io.File,
)

class AlbumFolderAdapter(
    private val onOpen: (AlbumFolderUi) -> Unit,
) : ListAdapter<AlbumFolderUi, AlbumFolderAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAlbumFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onOpen)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemAlbumFolderBinding,
        private val onOpen: (AlbumFolderUi) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AlbumFolderUi) {
            binding.folderName.text = item.name
            binding.folderCount.text = binding.root.context.getString(R.string.folder_item_count, item.itemCount)
            binding.root.setOnClickListener { onOpen(item) }
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
