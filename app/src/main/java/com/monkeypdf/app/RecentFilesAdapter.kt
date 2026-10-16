package com.monkeypdf.app

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.monkeypdf.app.databinding.ItemRecentBinding

class RecentFilesAdapter(
    private val onClick: (RecentFile) -> Unit,
    private val onLongClick: (RecentFile, View) -> Unit,
    private val onToggleFavorite: (RecentFile) -> Unit,
) : ListAdapter<RecentFile, RecentFilesAdapter.Holder>(Diff) {

    class Holder(val binding: ItemRecentBinding) : RecyclerView.ViewHolder(binding.root)

    /** Miniaturas ya leídas de disco (son pequeñas: 160 px de ancho). */
    private val thumbnails = LruCache<String, Bitmap>(64)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRecentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = getItem(position)
        val b = holder.binding
        val context = b.root.context
        val whenOpened = DateUtils.getRelativeTimeSpanString(
            file.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        b.name.text = file.name
        b.details.text = if (file.pageCount > 0) {
            context.getString(R.string.recent_subtitle, file.lastPage + 1, file.pageCount, whenOpened)
        } else {
            whenOpened
        }

        val key = file.uri.toString()
        val thumb = thumbnails.get(key) ?: Thumbnails.load(context, file.uri)?.also { thumbnails.put(key, it) }
        if (thumb != null) {
            b.thumb.setImageBitmap(thumb)
            b.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            b.thumb.imageTintList = null
        } else {
            b.thumb.setImageResource(R.drawable.ic_pdf)
            b.thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.thumb.imageTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(b.thumb, androidx.appcompat.R.attr.colorPrimary)
            )
        }

        b.star.setImageResource(if (file.favorite) R.drawable.ic_star else R.drawable.ic_star_border)
        b.star.imageTintList = android.content.res.ColorStateList.valueOf(
            MaterialColors.getColor(
                b.star,
                if (file.favorite) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        )
        b.star.contentDescription = context.getString(
            if (file.favorite) R.string.remove_favorite else R.string.add_favorite
        )
        b.star.setOnClickListener { onToggleFavorite(file) }
        b.root.setOnClickListener { onClick(file) }
        b.root.setOnLongClickListener { onLongClick(file, it); true }
    }

    /** Olvida las miniaturas en memoria (p. ej. al volver del visor, que pudo crear nuevas). */
    fun clearThumbnailCache() = thumbnails.evictAll()

    private object Diff : DiffUtil.ItemCallback<RecentFile>() {
        override fun areItemsTheSame(oldItem: RecentFile, newItem: RecentFile) = oldItem.uri == newItem.uri
        override fun areContentsTheSame(oldItem: RecentFile, newItem: RecentFile) = oldItem == newItem
    }
}
