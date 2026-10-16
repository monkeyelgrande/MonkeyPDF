package com.monkeypdf.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * RecyclerView vertical con zoom.
 *
 * Con zoom 1x se comporta como un RecyclerView normal. Al hacer zoom, el contenido se
 * dibuja escalado y trasladado; los gestos de arrastre mueven primero la "ventana"
 * ampliada y, cuando llega al borde, desplazan la lista.
 */
class ZoomableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    var scale = 1f
        private set
    /** Desplazamiento en pantalla del contenido ampliado. */
    var transX = 0f
        private set
    var transY = 0f
        private set

    /** Tras una pulsación larga, el arrastre extiende la selección en vez de desplazar. */
    private var longPressActive = false
    private var pendingScrollY = 0f
    private var zoomAnimator: ValueAnimator? = null

    var maxScale = 5f
    var doubleTapScale = 2.5f

    /** Toque simple (sin doble toque): útil para mostrar/ocultar la barra. */
    var onSingleTap: (() -> Unit)? = null

    /** Pulsación larga, con coordenadas del contenido (sin zoom). */
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null

    /** Arrastre después de la pulsación larga (coordenadas de pantalla) y su final. */
    var onLongPressDrag: ((x: Float, y: Float) -> Unit)? = null
    var onLongPressDragEnd: (() -> Unit)? = null

    /** Cambió la zona visible (zoom o desplazamiento de la ventana ampliada). */
    var onViewportChanged: (() -> Unit)? = null

    /** El zoom terminó de cambiar (fin del pellizco o de la animación). */
    var onZoomSettled: ((Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
                stopScroll()
                cancelListTouch()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onZoomSettled?.invoke(scale)
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (scaleDetector.isInProgress) return
                longPressActive = true
                stopScroll()
                cancelListTouch()
                onLongPress?.invoke((e.x - transX) / scale, (e.y - transY) / scale)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (scale > 1.05f) 1f else doubleTapScale
                animateZoom(target, e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
            ): Boolean {
                if (scale <= 1f || scaleDetector.isInProgress) return false
                panBy(distanceX, distanceY)
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
            ): Boolean {
                if (scale <= 1f || scaleDetector.isInProgress) return false
                fling(0, (-velocityY / scale).roundToInt())
                return true
            }
        },
    )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (longPressActive) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> onLongPressDrag?.invoke(e.x, e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressActive = false
                    onLongPressDragEnd?.invoke()
                }
            }
            gestureDetector.onTouchEvent(e)
            return true
        }
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        if (scale > 1f || scaleDetector.isInProgress) {
            // Con zoom, el arrastre lo gestionamos nosotros.
            if (e.actionMasked == MotionEvent.ACTION_DOWN) stopScroll()
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(scale, scale)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampTranslation()
    }

    /** Convierte coordenadas de pantalla (de esta vista) a coordenadas del contenido sin zoom. */
    fun screenToContentX(x: Float) = (x - transX) / scale
    fun screenToContentY(y: Float) = (y - transY) / scale
    fun contentToScreenX(x: Float) = transX + x * scale
    fun contentToScreenY(y: Float) = transY + y * scale

    /** Coordenada Y (sin zoom) que está en el centro de la pantalla. */
    fun contentCenterY(): Float = (height / 2f - transY) / scale

    fun resetZoom() {
        zoomAnimator?.cancel()
        val changed = scale != 1f
        scale = 1f
        transX = 0f
        transY = 0f
        invalidate()
        if (changed) onZoomSettled?.invoke(scale)
    }

    private fun zoomTo(newScale: Float, focusX: Float, focusY: Float) {
        val s = newScale.coerceIn(1f, maxScale)
        val contentX = (focusX - transX) / scale
        val contentY = (focusY - transY) / scale
        scale = s
        transX = focusX - contentX * s
        transY = focusY - contentY * s
        clampTranslation()
        invalidate()
        onViewportChanged?.invoke()
    }

    private fun animateZoom(target: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(scale, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomTo(it.animatedValue as Float, focusX, focusY) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onZoomSettled?.invoke(scale)
                }
            })
            start()
        }
    }

    private fun panBy(dx: Float, dy: Float) {
        transX -= dx
        val wantedY = transY - dy
        transY = wantedY
        clampTranslation()
        // Lo que no cabe en la ventana ampliada se convierte en desplazamiento de la lista.
        pendingScrollY += (transY - wantedY) / scale
        val scrollPx = pendingScrollY.toInt()
        if (scrollPx != 0) {
            pendingScrollY -= scrollPx
            scrollBy(0, scrollPx)
        }
        invalidate()
        onViewportChanged?.invoke()
    }

    private fun clampTranslation() {
        transX = transX.coerceIn(width * (1 - scale), 0f)
        transY = transY.coerceIn(height * (1 - scale), 0f)
    }

    /** Cancela el arrastre que el RecyclerView pudiera haber empezado antes del pellizco. */
    private fun cancelListTouch() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.onTouchEvent(cancel)
        cancel.recycle()
    }
}
