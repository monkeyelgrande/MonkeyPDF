package com.monkeypdf.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RectF
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Muestra cada página del PDF como una imagen; las renderiza en segundo plano y las guarda en caché. */
class PdfPageAdapter(
    private val document: PdfDocumentRenderer,
    private val scope: CoroutineScope,
    nightMode: Boolean,
) : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {

    class PageHolder(val image: PageView) : RecyclerView.ViewHolder(image) {
        var job: Job? = null
    }

    private var recyclerView: RecyclerView? = null

    private val cache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /** Multiplicador de resolución: >1 cuando hay zoom, para que el texto se vea nítido. */
    private var quality = 1f

    var nightMode: Boolean = nightMode
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_COLORS)
        }

    /** Coincidencias de búsqueda por página, y la coincidencia seleccionada. */
    private var matchesByPage: Map<Int, List<SearchMatch>> = emptyMap()
    private var activeMatch: SearchMatch? = null

    fun setSearchMatches(matches: List<SearchMatch>) {
        matchesByPage = matches.groupBy { it.page }
        notifyItemRangeChanged(0, itemCount, PAYLOAD_HIGHLIGHTS)
    }

    fun setActiveMatch(match: SearchMatch?) {
        val previous = activeMatch
        activeMatch = match
        previous?.let { notifyItemChanged(it.page, PAYLOAD_HIGHLIGHTS) }
        match?.let { notifyItemChanged(it.page, PAYLOAD_HIGHLIGHTS) }
    }

    /** Selección de texto activa (una sola página a la vez). */
    private var selectionPage = -1
    private var selectionRects: List<RectF> = emptyList()

    fun setSelection(page: Int, rects: List<RectF>) {
        val previous = selectionPage
        selectionPage = page
        selectionRects = rects
        if (previous >= 0 && previous != page) notifyItemChanged(previous, PAYLOAD_HIGHLIGHTS)
        if (page >= 0) notifyItemChanged(page, PAYLOAD_HIGHLIGHTS)
    }

    fun setQuality(value: Float) {
        if (value == quality) return
        quality = value
        notifyItemRangeChanged(0, itemCount)
    }

    /** El ancho disponible cambió (p. ej. al girar el teléfono). */
    fun onWidthChanged() {
        cache.evictAll()
        notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemCount(): Int = document.pageCount

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        cache.evictAll()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false)
        return PageHolder(view as PageView)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_COLORS || it == PAYLOAD_HIGHLIGHTS }) {
            if (PAYLOAD_COLORS in payloads) applyColors(holder)
            if (PAYLOAD_HIGHLIGHTS in payloads) applyHighlights(holder, position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val width = availableWidth()
        val size = document.pageSizes[position]
        val height = (width.toLong() * size.height / size.width).toInt()
        holder.image.updateLayoutParams { this.height = height }
        holder.image.contentDescription = "Página ${position + 1}"
        applyColors(holder)
        applyHighlights(holder, position)

        holder.job?.cancel()
        val targetWidth = (width * quality).roundToInt().coerceAtLeast(1)
        val cached = cache.get(position)
        // Se muestra la versión en caché (aunque sea de otra resolución) para evitar parpadeos.
        holder.image.setImageBitmap(cached)
        if (cached != null && cached.width == targetWidth) return

        holder.job = scope.launch {
            val bitmap = withContext(document.dispatcher) {
                try {
                    document.renderPage(position, targetWidth)
                } catch (e: OutOfMemoryError) {
                    cache.evictAll()
                    document.renderPage(position, width)
                }
            } ?: return@launch
            cache.put(position, bitmap)
            if (holder.bindingAdapterPosition == position) {
                holder.image.setImageBitmap(bitmap)
            }
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.job?.cancel()
        holder.job = null
        holder.image.setImageDrawable(null)
    }

    private fun availableWidth(): Int {
        val rv = recyclerView ?: return 1
        return (rv.width - rv.paddingLeft - rv.paddingRight).coerceAtLeast(1)
    }

    private fun applyHighlights(holder: PageHolder, position: Int) {
        holder.image.highlights = matchesByPage[position].orEmpty().flatMap { it.rects }
        holder.image.activeHighlights = activeMatch?.takeIf { it.page == position }?.rects.orEmpty()
        holder.image.selectionRects = if (position == selectionPage) selectionRects else emptyList()
    }

    private fun applyColors(holder: PageHolder) {
        holder.image.colorFilter = if (nightMode) INVERT_FILTER else null
        holder.image.setBackgroundColor(if (nightMode) Color.BLACK else Color.WHITE)
    }

    private companion object {
        const val PAYLOAD_COLORS = "colors"
        const val PAYLOAD_HIGHLIGHTS = "highlights"

        /** Invierte los colores: fondo negro y texto claro para leer de noche. */
        val INVERT_FILTER = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
}
