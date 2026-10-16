package com.monkeypdf.app

import android.graphics.RectF
import kotlin.math.max

/** Texto seleccionado en una página: caracteres [start]..[end], ambos incluidos. */
data class TextSelection(val page: Int, val start: Int, val end: Int)

/** Cálculos de selección sobre las cajas de caracteres de una página (coordenadas 0..1). */
object SelectionGeometry {

    /**
     * Índice del carácter en el punto (x, y), o el más cercano si ninguno lo contiene.
     * Devuelve -1 si el más cercano está a más de [maxDistance].
     */
    fun charAt(chars: PageChars, x: Float, y: Float, maxDistance: Float = Float.MAX_VALUE): Int {
        var best = -1
        var bestScore = Float.MAX_VALUE
        chars.boxes.forEachIndexed { i, box ->
            if (box == null) return@forEachIndexed
            val dx = when {
                x < box.left -> box.left - x
                x > box.right -> x - box.right
                else -> 0f
            }
            val dy = when {
                y < box.top -> box.top - y
                y > box.bottom -> y - box.bottom
                else -> 0f
            }
            if (dx == 0f && dy == 0f) return i
            // La distancia vertical pesa más: se prefiere la misma línea.
            val score = dx + 4 * dy
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return if (bestScore <= maxDistance) best else -1
    }

    /** Palabra que contiene el carácter [index]. */
    fun wordAt(chars: PageChars, index: Int): IntRange {
        val text = chars.text
        if (!isWordChar(text[index])) return index..index
        var start = index
        var end = index
        while (start > 0 && isWordChar(text[start - 1])) start--
        while (end < text.length - 1 && isWordChar(text[end + 1])) end++
        return start..end
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '_'

    /** Primer y último carácter visible de la página (para "Seleccionar todo"). */
    fun allRange(chars: PageChars): IntRange? {
        val first = chars.boxes.indexOfFirst { it != null }
        val last = chars.boxes.indexOfLast { it != null }
        return if (first < 0) null else first..last
    }

    /** Une las cajas de los caracteres seleccionados en un rectángulo por línea. */
    fun lineRects(chars: PageChars, start: Int, end: Int): List<RectF> {
        val rects = mutableListOf<RectF>()
        var current: RectF? = null
        for (i in start..end) {
            val box = chars.boxes.getOrNull(i) ?: continue
            val cur = current
            if (cur != null && sameLine(cur, box) && box.left >= cur.left - cur.height()) {
                cur.union(box)
            } else {
                current = RectF(box).also { rects += it }
            }
        }
        return rects
    }

    private fun sameLine(a: RectF, b: RectF): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0.5f * minOf(a.height(), b.height())
    }

    /** Caja visible más cercana a [index] en la dirección indicada (para colocar los tiradores). */
    fun visibleBox(chars: PageChars, index: Int, forward: Boolean): RectF? {
        var i = index
        while (i in chars.boxes.indices) {
            chars.boxes[i]?.let { return it }
            i += if (forward) 1 else -1
        }
        return null
    }

    /** Texto seleccionado, con los saltos de línea normalizados. */
    fun text(chars: PageChars, start: Int, end: Int): String =
        chars.text.substring(start, end + 1)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

    fun bounds(rects: List<RectF>): RectF? {
        if (rects.isEmpty()) return null
        return RectF(rects[0]).apply { rects.forEach { union(it) } }
    }
}
