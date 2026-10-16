package com.monkeypdf.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.hypot

/**
 * Dibuja los dos tiradores de la selección de texto y permite arrastrarlos.
 * Ocupa toda la pantalla encima de las páginas, pero solo captura los toques sobre un tirador;
 * el resto pasa a la lista de páginas.
 */
class SelectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Handle { START, END }

    private val radius = 11 * resources.displayMetrics.density
    private val touchRadius = 30 * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SelectionOverlay, androidx.appcompat.R.attr.colorPrimary)
    }

    /** Punto inferior izquierdo del primer carácter (pantalla), o null si no está visible. */
    var startAnchor: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Punto inferior derecho del último carácter (pantalla), o null si no está visible. */
    var endAnchor: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Se arrastra un tirador: posición a la que debe ir su punto de anclaje. */
    var onHandleDrag: ((Handle, Float, Float) -> Unit)? = null
    var onHandleDragStart: (() -> Unit)? = null
    var onHandleDragEnd: (() -> Unit)? = null

    private var dragging: Handle? = null
    private var offsetX = 0f
    private var offsetY = 0f

    fun clear() {
        startAnchor = null
        endAnchor = null
        dragging = null
    }

    override fun onDraw(canvas: Canvas) {
        startAnchor?.let { drawHandle(canvas, it, Handle.START) }
        endAnchor?.let { drawHandle(canvas, it, Handle.END) }
    }

    /** Gota con la punta en el anclaje: hacia la izquierda la de inicio, hacia la derecha la de fin. */
    private fun drawHandle(canvas: Canvas, anchor: PointF, handle: Handle) {
        val dir = if (handle == Handle.START) -1 else 1
        val cx = anchor.x + dir * radius
        val cy = anchor.y + radius
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawRect(
            minOf(anchor.x, cx), anchor.y,
            maxOf(anchor.x, cx), cy,
            paint,
        )
    }

    private fun handleCenter(anchor: PointF, handle: Handle): PointF {
        val dir = if (handle == Handle.START) -1 else 1
        return PointF(anchor.x + dir * radius, anchor.y + radius)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = listOfNotNull(
                    startAnchor?.let { Handle.START to it },
                    endAnchor?.let { Handle.END to it },
                ).minByOrNull { (handle, anchor) ->
                    val c = handleCenter(anchor, handle)
                    hypot(event.x - c.x, event.y - c.y)
                }?.takeIf { (handle, anchor) ->
                    val c = handleCenter(anchor, handle)
                    hypot(event.x - c.x, event.y - c.y) <= touchRadius
                } ?: return false
                dragging = hit.first
                offsetX = hit.second.x - event.x
                offsetY = hit.second.y - event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                onHandleDragStart?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val handle = dragging ?: return false
                onHandleDrag?.invoke(handle, event.x + offsetX, event.y + offsetY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging == null) return false
                dragging = null
                onHandleDragEnd?.invoke()
                return true
            }
        }
        return false
    }
}
