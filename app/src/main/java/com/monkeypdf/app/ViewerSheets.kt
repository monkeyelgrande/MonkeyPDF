package com.monkeypdf.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.monkeypdf.app.databinding.ItemThumbnailBinding
import com.monkeypdf.app.databinding.SheetThumbnailsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    // Android 13+ ya muestra su propio aviso al copiar.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

/** Hoja con la cuadrícula de miniaturas de todas las páginas. Tocar una salta a esa página. */
fun showThumbnailsSheet(
    activity: Activity,
    scope: CoroutineScope,
    document: PdfDocumentRenderer,
    currentPage: Int,
    bookmarks: Set<Int>,
    onPageSelected: (Int) -> Unit,
) {
    val binding = SheetThumbnailsBinding.inflate(activity.layoutInflater)
    binding.title.text = activity.getString(R.string.thumbnails_title, document.pageCount)
    val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = if (landscape) 5 else 3

    val dialog = BottomSheetDialog(activity)
    val adapter = ThumbnailAdapter(document, scope, currentPage, bookmarks) { page ->
        dialog.dismiss()
        onPageSelected(page)
    }
    binding.grid.layoutManager = GridLayoutManager(activity, columns)
    binding.grid.adapter = adapter
    binding.grid.scrollToPosition(currentPage)
    binding.root.minimumHeight = (activity.resources.displayMetrics.heightPixels * 0.85f).toInt()

    dialog.setContentView(binding.root)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    dialog.setOnDismissListener { binding.grid.adapter = null }
    dialog.show()
}

private class ThumbnailAdapter(
    private val document: PdfDocumentRenderer,
    private val scope: CoroutineScope,
    private val currentPage: Int,
    private val bookmarks: Set<Int>,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<ThumbnailAdapter.Holder>() {

    class Holder(val binding: ItemThumbnailBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
    }

    private var recyclerView: RecyclerView? = null
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        cache.evictAll()
    }

    override fun getItemCount() = document.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemThumbnailBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val b = holder.binding
        val context = b.root.context
        val columns = (recyclerView?.layoutManager as? GridLayoutManager)?.spanCount ?: 3
        val density = context.resources.displayMetrics.density
        // Ancho aproximado de la miniatura, solo para elegir la resolución de render.
        val width = (context.resources.displayMetrics.widthPixels / columns).coerceAtLeast(1)
        val size = document.pageSizes[position]
        b.image.aspectRatio = size.height.toFloat() / size.width

        b.label.text = (position + 1).toString()
        b.bookmark.isVisible = position in bookmarks
        b.card.strokeWidth = ((if (position == currentPage) 3 else 1) * density).toInt()
        b.card.strokeColor = com.google.android.material.color.MaterialColors.getColor(
            b.card,
            if (position == currentPage) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOutlineVariant,
        )
        b.root.contentDescription = context.getString(R.string.page_number, position + 1)
        b.root.setOnClickListener { onClick(position) }

        holder.job?.cancel()
        val cached = cache.get(position)
        b.image.setImageBitmap(cached)
        if (cached != null) return
        holder.job = scope.launch {
            val bitmap = withContext(document.dispatcher) {
                try {
                    document.renderPage(position, width)
                } catch (e: OutOfMemoryError) {
                    null
                }
            } ?: return@launch
            cache.put(position, bitmap)
            if (holder.bindingAdapterPosition == position) b.image.setImageBitmap(bitmap)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.binding.image.setImageDrawable(null)
    }
}
