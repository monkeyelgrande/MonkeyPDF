package com.monkeypdf.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import kotlin.math.abs

/**
 * Barra de desplazamiento rápido para documentos largos: aparece al desplazarse, se puede
 * arrastrar y muestra una burbuja con la página. Se coloca encima del RecyclerView, ocupando
 * toda la pantalla; solo captura los toques que caen sobre el control.
 */
class FastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val thumbWidth = 6 * density
    private val thumbGrabWidth = 10 * density
    private val thumbHeight = 56 * density
    private val edgeMargin = 6 * density
    private val touchWidth = 44 * density
    private val bubblePadH = 14 * density
    private val bubbleHeight = 36 * density

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@FastScroller, androidx.appcompat.R.attr.colorPrimary)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = thumbPaint.color }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@FastScroller, com.google.android.material.R.attr.colorOnPrimary
        )
        textSize = 15 * density
        isFakeBoldText = true
    }
    private val rect = RectF()

    private var recyclerView: RecyclerView? = null
    private var fraction = 0f
    private var dragging = false
    private var shown = false

    /** Número de páginas del documento. */
    var pageCount: () -> Int = { 0 }

    /** Alto actual (sin zoom) de una página, para posicionar con precisión dentro de ella. */
    var pageHeight: (Int) -> Int = { 0 }

    /** Texto de la burbuja, p. ej. "12 / 40". */
    var label: () -> String = { "" }

    private val hideRunnable = Runnable { if (!dragging) setShown(false) }

    init {
        alpha = 0f
    }

    fun attachTo(rv: RecyclerView) {
        recyclerView = rv
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0 || pageCount() < MIN_PAGES) return
                if (!dragging) syncWithList()
                setShown(true)
                scheduleHide()
            }
        })
    }

    private fun syncWithList() {
        val rv = recyclerView ?: return
        val range = rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent()
        fraction = if (range > 0) (rv.computeVerticalScrollOffset().toFloat() / range).coerceIn(0f, 1f) else 0f
        invalidate()
    }

    private fun setShown(value: Boolean) {
        if (shown == value) return
        shown = value
        animate().alpha(if (value) 1f else 0f).setDuration(if (value) 120 else 300)
    }

    private fun scheduleHide() {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private val trackTop get() = paddingTop + thumbHeight / 2
    private val trackBottom get() = height - paddingBottom - thumbHeight / 2
    private val thumbCenterY get() = trackTop + fraction * (trackBottom - trackTop)

    override fun onDraw(canvas: Canvas) {
        if (pageCount() < MIN_PAGES) return
        val w = if (dragging) thumbGrabWidth else thumbWidth
        val right = width - paddingRight - edgeMargin
        val cy = thumbCenterY
        rect.set(right - w, cy - thumbHeight / 2, right, cy + thumbHeight / 2)
        canvas.drawRoundRect(rect, w / 2, w / 2, thumbPaint)

        if (dragging) {
            val text = label()
            val textWidth = textPaint.measureText(text)
            val bubbleRight = right - w - 12 * density
            rect.set(
                bubbleRight - textWidth - 2 * bubblePadH, cy - bubbleHeight / 2,
                bubbleRight, cy + bubbleHeight / 2,
            )
            canvas.drawRoundRect(rect, bubbleHeight / 2, bubbleHeight / 2, bubblePaint)
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, rect.left + bubblePadH, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!shown || pageCount() < MIN_PAGES) return false
                val onControl = event.x >= width - paddingRight - touchWidth &&
                    abs(event.y - thumbCenterY) <= thumbHeight
                if (!onControl) return false
                dragging = true
                removeCallbacks(hideRunnable)
                recyclerView?.stopScroll()
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                scrollTo(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                scrollTo(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                invalidate()
                scheduleHide()
                return true
            }
        }
        return false
    }

    private fun scrollTo(y: Float) {
        val rv = recyclerView ?: return
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
        val count = pageCount()
        val span = (trackBottom - trackTop).coerceAtLeast(1f)
        fraction = ((y - trackTop) / span).coerceIn(0f, 1f)
        val exact = fraction * count
        val page = exact.toInt().coerceAtMost(count - 1)
        val within = ((exact - page) * pageHeight(page)).toInt()
        layoutManager.scrollToPositionWithOffset(page, -within)
        invalidate()
    }

    private companion object {
        const val MIN_PAGES = 4
        const val HIDE_DELAY_MS = 1500L
    }
}
