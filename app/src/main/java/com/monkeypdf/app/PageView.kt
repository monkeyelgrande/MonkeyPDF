package com.monkeypdf.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Imagen de una página con los resultados de búsqueda resaltados encima. */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    /** Rectángulos normalizados (0..1) de todas las coincidencias de la página. */
    var highlights: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Rectángulos de la coincidencia seleccionada actualmente. */
    var activeHighlights: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Rectángulos del texto seleccionado por el usuario. */
    var selectionRects: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val selectionPaint = Paint().apply {
        color = com.google.android.material.color.MaterialColors.getColor(
            this@PageView, androidx.appcompat.R.attr.colorPrimary
        )
        alpha = 0x50
    }
    private val matchPaint = Paint().apply { color = 0x66FFD54F }
    private val activePaint = Paint().apply { color = 0x88FF6D00.toInt() }
    private val drawRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        highlights.forEach { draw(canvas, it, matchPaint) }
        activeHighlights.forEach { draw(canvas, it, activePaint) }
        selectionRects.forEach { draw(canvas, it, selectionPaint, padDp = 0.5f) }
    }

    private fun draw(canvas: Canvas, normalized: RectF, paint: Paint, padDp: Float = 1.5f) {
        val pad = padDp * resources.displayMetrics.density
        drawRect.set(
            normalized.left * width - pad,
            normalized.top * height - pad,
            normalized.right * width + pad,
            normalized.bottom * height + pad,
        )
        canvas.drawRect(drawRect, paint)
    }
}
