package com.monkeypdf.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Size
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.api.PdfPasswordException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.Executors

/** El PDF pide contraseña. [wrongPassword] es true si se intentó con una y no era correcta. */
class PasswordRequiredException(val wrongPassword: Boolean) : Exception()
class InvalidPdfException : Exception()

/** Una coincidencia de búsqueda. Los rectángulos van normalizados (0..1) respecto a la página. */
data class SearchMatch(val page: Int, val rects: List<RectF>)

/**
 * Texto de una página con la caja de cada carácter, normalizada (0..1) respecto a la página.
 * [boxes] tiene un elemento por carácter de [text]; es null para los que no se dibujan
 * (saltos de línea y espacios que genera PDFium).
 */
class PageChars(val text: String, val boxes: Array<RectF?>) {
    val isEmpty: Boolean get() = boxes.all { it == null }
}

/** Entrada del índice (tabla de contenidos) del PDF. */
data class TocEntry(val title: String, val page: Int, val level: Int)

/**
 * Envuelve PDFium para usarlo desde corrutinas.
 *
 * Todo el acceso al documento pasa por un único hilo ([dispatcher]) para que renderizado,
 * extracción de texto y búsqueda nunca se pisen.
 */
class PdfDocumentRenderer private constructor(
    private val fileDescriptor: ParcelFileDescriptor,
    private val document: PdfDocument,
) : Closeable {

    /** Tamaño de cada página en puntos PDF (ya con la rotación aplicada). */
    val pageSizes: List<Size> = List(document.getPageCount()) { index ->
        val page = document.openPage(index)
        try {
            val size = page?.getPageSize(POINTS_DPI)
            Size((size?.width ?: 612).coerceAtLeast(1), (size?.height ?: 792).coerceAtLeast(1))
        } finally {
            page?.close()
        }
    }

    val pageCount: Int get() = pageSizes.size

    val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val lock = Any()
    private var closed = false
    private val textCache = HashMap<Int, String>()
    private val charsCache = android.util.LruCache<Int, PageChars>(12)

    /** Renderiza la página con el ancho indicado en píxeles. Devuelve null si el documento ya se cerró. */
    fun renderPage(index: Int, widthPx: Int): Bitmap? = withPage(index) { page ->
        val size = pageSizes[index]
        val heightPx = (widthPx.toLong() * size.height / size.width).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        page.renderPageBitmap(
            bitmap, 0, 0, widthPx, heightPx,
            renderAnnot = true,
            canvasColor = WHITE,
            pageBackgroundColor = WHITE,
        )
        bitmap
    }

    /** Texto de la página ("" si es una imagen escaneada sin capa de texto). */
    fun pageText(index: Int): String {
        synchronized(lock) { textCache[index]?.let { return it } }
        val text = withPage(index) { page ->
            page.openTextPage().use { textPage ->
                val count = textPage.textPageCountChars()
                if (count <= 0) "" else textPage.textPageGetText(0, count).orEmpty()
            }
        }.orEmpty()
        synchronized(lock) { textCache[index] = text }
        return text
    }

    /** Texto y posición de cada carácter de la página, para seleccionar texto sobre ella. */
    fun pageChars(index: Int): PageChars {
        charsCache.get(index)?.let { return it }
        val size = pageSizes[index]
        val refWidth = REFERENCE_SIZE
        val refHeight = (REFERENCE_SIZE.toLong() * size.height / size.width).toInt()
        val result = withPage(index) { page ->
            // Transformación afín de coordenadas PDF a página normalizada, a partir de tres puntos
            // (cubre páginas rotadas). Así no hace falta una llamada nativa por cada esquina.
            val o = page.mapPageCoordsToDevice(0, 0, refWidth, refHeight, 0, 0.0, 0.0)
            val ax = page.mapPageCoordsToDevice(0, 0, refWidth, refHeight, 0, 1000.0, 0.0)
            val ay = page.mapPageCoordsToDevice(0, 0, refWidth, refHeight, 0, 0.0, 1000.0)
            fun mapX(x: Float, y: Float) =
                (o.x + (ax.x - o.x) * x / 1000f + (ay.x - o.x) * y / 1000f) / refWidth
            fun mapY(x: Float, y: Float) =
                (o.y + (ax.y - o.y) * x / 1000f + (ay.y - o.y) * y / 1000f) / refHeight

            page.openTextPage().use { textPage ->
                val count = textPage.textPageCountChars()
                if (count <= 0) return@use PageChars("", emptyArray())
                val text = textPage.textPageGetText(0, count).orEmpty().padEnd(count).take(count)
                val boxes = Array(count) { i ->
                    if (text[i].isWhitespace()) return@Array null
                    val box = textPage.textPageGetCharBox(i) ?: return@Array null
                    val x1 = mapX(box.left, box.top)
                    val y1 = mapY(box.left, box.top)
                    val x2 = mapX(box.right, box.bottom)
                    val y2 = mapY(box.right, box.bottom)
                    val rect = RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
                    if (rect.width() <= 0f || rect.height() <= 0f) null else rect
                }
                PageChars(text, boxes)
            }
        } ?: PageChars("", emptyArray())
        charsCache.put(index, result)
        return result
    }

    /**
     * Busca [query] en una página, sin distinguir mayúsculas ni tildes
     * ("pagina" encuentra "Página").
     */
    fun searchPage(index: Int, query: String): List<SearchMatch> {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return emptyList()
        val haystack = fold(pageText(index))
        val starts = buildList {
            var from = 0
            while (true) {
                val found = haystack.indexOf(needle, from)
                if (found < 0) break
                add(found)
                from = found + needle.length
            }
        }
        if (starts.isEmpty()) return emptyList()

        val size = pageSizes[index]
        val refWidth = REFERENCE_SIZE
        val refHeight = (REFERENCE_SIZE.toLong() * size.height / size.width).toInt()
        return withPage(index) { page ->
            page.openTextPage().use { textPage ->
                starts.map { start ->
                    val rectCount = textPage.textPageCountRects(start, needle.length)
                    val rects = (0 until rectCount).mapNotNull { i ->
                        textPage.textPageGetRect(i)?.let { pageRect ->
                            val device = page.mapRectToDevice(0, 0, refWidth, refHeight, 0, pageRect)
                            RectF(
                                minOf(device.left, device.right).toFloat() / refWidth,
                                minOf(device.top, device.bottom).toFloat() / refHeight,
                                maxOf(device.left, device.right).toFloat() / refWidth,
                                maxOf(device.top, device.bottom).toFloat() / refHeight,
                            )
                        }
                    }
                    SearchMatch(index, rects)
                }
            }
        }.orEmpty()
    }

    /** Índice del documento aplanado, con su nivel de anidación. */
    fun tableOfContents(): List<TocEntry> = synchronized(lock) {
        if (closed) return emptyList()
        val result = mutableListOf<TocEntry>()
        fun walk(items: List<Bookmark>, level: Int) {
            for (item in items) {
                val title = item.title?.trim().orEmpty()
                if (title.isNotEmpty() && item.pageIdx in 0 until pageCount) {
                    result += TocEntry(title, item.pageIdx.toInt(), level)
                }
                walk(item.children, level + 1)
            }
        }
        try {
            walk(document.getTableOfContents(), 0)
        } catch (_: Exception) {
            // Índice dañado: se ignora.
        }
        result
    }

    private inline fun <T> withPage(index: Int, block: (PdfPage) -> T): T? = synchronized(lock) {
        if (closed) return null
        val page = document.openPage(index) ?: return null
        try {
            block(page)
        } finally {
            page.close()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            document.close()
            fileDescriptor.close()
        }
        dispatcher.close()
    }

    companion object {
        private const val POINTS_DPI = 72
        private const val REFERENCE_SIZE = 10_000
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** Minúsculas y sin tildes, carácter a carácter (conserva las posiciones del texto original). */
        fun fold(text: String): String {
            val out = StringBuilder(text.length)
            for (c in text) {
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
                out.append(base.lowercaseChar())
            }
            return out.toString()
        }

        /** Abre el PDF. Hace operaciones de E/S: llamar fuera del hilo principal. */
        @Throws(
            IOException::class,
            PasswordRequiredException::class,
            InvalidPdfException::class,
        )
        fun open(context: Context, uri: Uri, password: String? = null): PdfDocumentRenderer {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException(uri.toString())
            return open(context, fd, password)
        }

        private fun open(context: Context, fd: ParcelFileDescriptor, password: String?): PdfDocumentRenderer {
            try {
                val document = PdfiumCore(context.applicationContext).newDocument(fd, password)
                return PdfDocumentRenderer(fd, document)
            } catch (e: PdfPasswordException) {
                fd.close()
                throw PasswordRequiredException(wrongPassword = password != null)
            } catch (e: IOException) {
                fd.close()
                throw InvalidPdfException()
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }
    }
}
