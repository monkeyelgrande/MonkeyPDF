package com.monkeypdf.app

import android.app.Activity
import android.content.Intent
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Selección de texto directamente sobre el documento: pulsación larga para seleccionar una
 * palabra, tiradores para ajustar y el menú flotante del sistema (Copiar, Seleccionar todo,
 * Compartir, Buscar).
 */
class TextSelectionController(
    private val activity: Activity,
    private val pages: ZoomableRecyclerView,
    private val overlay: SelectionOverlay,
    private val scope: CoroutineScope,
    private val document: () -> PdfDocumentRenderer?,
    private val adapter: () -> PdfPageAdapter?,
    private val onSearch: (String) -> Unit,
) {
    private var selection: TextSelection? = null
    private var chars: PageChars? = null
    private var actionMode: ActionMode? = null
    private var finishingInternally = false

    val isActive: Boolean get() = selection != null

    init {
        overlay.onHandleDragStart = { actionMode?.hide(HIDE_WHILE_DRAGGING_MS) }
        overlay.onHandleDrag = { handle, x, y -> dragHandle(handle, x, y) }
        overlay.onHandleDragEnd = { actionMode?.hide(0) }
        pages.onLongPressDrag = { x, y -> dragHandle(SelectionOverlay.Handle.END, x, y, liftFinger = true) }
        pages.onLongPressDragEnd = { actionMode?.hide(0) }
    }

    /** Pulsación larga en (x, y), coordenadas del contenido sin zoom: selecciona la palabra. */
    fun startAt(contentX: Float, contentY: Float) {
        val doc = document() ?: return
        val child = pages.findChildViewUnder(contentX, contentY) ?: return
        val page = pages.getChildAdapterPosition(child)
        if (page == RecyclerView.NO_POSITION) return
        val nx = (contentX - child.left) / child.width
        val ny = (contentY - child.top) / child.height
        scope.launch {
            val pageChars = withContext(doc.dispatcher) { doc.pageChars(page) }
            if (pageChars.isEmpty) {
                Toast.makeText(activity, R.string.no_text_on_page, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val index = SelectionGeometry.charAt(pageChars, nx, ny, maxDistance = MAX_TOUCH_DISTANCE)
            if (index < 0) return@launch
            pages.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val word = SelectionGeometry.wordAt(pageChars, index)
            chars = pageChars
            apply(TextSelection(page, word.first, word.last))
        }
    }

    /** Selecciona todo el texto de una página. */
    fun selectAll(page: Int) {
        val doc = document() ?: return
        scope.launch {
            val pageChars = withContext(doc.dispatcher) { doc.pageChars(page) }
            val range = SelectionGeometry.allRange(pageChars)
            if (range == null) {
                Toast.makeText(activity, R.string.no_text_on_page, Toast.LENGTH_SHORT).show()
                return@launch
            }
            chars = pageChars
            apply(TextSelection(page, range.first, range.last))
        }
    }

    fun clear() {
        selection = null
        chars = null
        adapter()?.setSelection(-1, emptyList())
        overlay.clear()
        finishingInternally = true
        actionMode?.finish()
        finishingInternally = false
        actionMode = null
    }

    /** La vista se desplazó o cambió el zoom: recoloca tiradores y menú. */
    fun onViewportChanged() {
        if (selection == null) return
        updateHandles()
        actionMode?.invalidateContentRect()
    }

    private fun apply(newSelection: TextSelection) {
        val pageChars = chars ?: return
        selection = newSelection
        adapter()?.setSelection(
            newSelection.page,
            SelectionGeometry.lineRects(pageChars, newSelection.start, newSelection.end),
        )
        updateHandles()
        if (actionMode == null) {
            actionMode = overlay.startActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidateContentRect()
        }
    }

    private fun dragHandle(handle: SelectionOverlay.Handle, x: Float, y: Float, liftFinger: Boolean = false) {
        val current = selection ?: return
        val pageChars = chars ?: return
        val child = pages.layoutManager?.findViewByPosition(current.page) ?: return
        val contentX = pages.screenToContentX(x)
        var contentY = pages.screenToContentY(y)
        val nx = (contentX - child.left) / child.width
        // El anclaje está en la base de la línea: se busca el carácter media línea más arriba.
        val box = if (handle == SelectionOverlay.Handle.START) {
            SelectionGeometry.visibleBox(pageChars, current.start, forward = true)
        } else {
            SelectionGeometry.visibleBox(pageChars, current.end, forward = false)
        }
        val lineHeight = (box?.height() ?: 0.01f) * child.height
        if (!liftFinger) contentY -= lineHeight / 2
        val ny = (contentY - child.top) / child.height
        val index = SelectionGeometry.charAt(pageChars, nx, ny)
        if (index < 0) return
        val updated = when (handle) {
            SelectionOverlay.Handle.START -> current.copy(start = minOf(index, current.end))
            SelectionOverlay.Handle.END -> current.copy(end = maxOf(index, current.start))
        }
        if (updated != current) apply(updated)
    }

    private fun updateHandles() {
        val current = selection ?: return
        val pageChars = chars ?: return
        val child = pages.layoutManager?.findViewByPosition(current.page)
        if (child == null) {
            overlay.clear()
            return
        }
        val first = SelectionGeometry.visibleBox(pageChars, current.start, forward = true)
        val last = SelectionGeometry.visibleBox(pageChars, current.end, forward = false)
        overlay.startAnchor = first?.let { toScreen(child, it.left, it.bottom) }
        overlay.endAnchor = last?.let { toScreen(child, it.right, it.bottom) }
    }

    private fun toScreen(child: View, nx: Float, ny: Float) = PointF(
        pages.contentToScreenX(child.left + nx * child.width),
        pages.contentToScreenY(child.top + ny * child.height),
    )

    private fun selectionScreenBounds(): Rect? {
        val current = selection ?: return null
        val pageChars = chars ?: return null
        val child = pages.layoutManager?.findViewByPosition(current.page) ?: return null
        val bounds = SelectionGeometry.bounds(
            SelectionGeometry.lineRects(pageChars, current.start, current.end)
        ) ?: return null
        val topLeft = toScreen(child, bounds.left, bounds.top)
        val bottomRight = toScreen(child, bounds.right, bounds.bottom)
        return Rect(
            topLeft.x.toInt(), topLeft.y.toInt(),
            bottomRight.x.toInt(), bottomRight.y.toInt(),
        )
    }

    private fun selectedText(): String {
        val current = selection ?: return ""
        val pageChars = chars ?: return ""
        return SelectionGeometry.text(pageChars, current.start, current.end)
    }

    private val actionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, ID_COPY, 0, android.R.string.copy)
            menu.add(Menu.NONE, ID_SELECT_ALL, 1, android.R.string.selectAll)
            menu.add(Menu.NONE, ID_SHARE, 2, R.string.share)
            menu.add(Menu.NONE, ID_SEARCH, 3, R.string.search)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val text = selectedText()
            when (item.itemId) {
                ID_COPY -> {
                    copyToClipboard(activity, text)
                    clear()
                }
                ID_SELECT_ALL -> selection?.let { selectAll(it.page) }
                ID_SHARE -> {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text)
                    activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share)))
                    clear()
                }
                ID_SEARCH -> {
                    clear()
                    onSearch(text)
                }
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            // Si el sistema cerró el menú (p. ej. con "Atrás"), también se quita la selección.
            if (!finishingInternally) clear()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val bounds = selectionScreenBounds()
            if (bounds != null) outRect.set(bounds) else outRect.set(0, 0, view.width, 0)
        }
    }

    private companion object {
        const val ID_COPY = 1
        const val ID_SELECT_ALL = 2
        const val ID_SHARE = 3
        const val ID_SEARCH = 4
        const val MAX_TOUCH_DISTANCE = 0.05f
        const val HIDE_WHILE_DRAGGING_MS = 60_000L
    }
}
