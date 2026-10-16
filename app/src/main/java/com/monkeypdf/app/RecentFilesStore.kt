package com.monkeypdf.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: Uri,
    val name: String,
    val lastPage: Int,
    val pageCount: Int,
    val lastOpened: Long,
    val favorite: Boolean = false,
    val bookmarks: List<Int> = emptyList(),
)

/**
 * Archivos abiertos recientemente, favoritos y marcadores, guardados en SharedPreferences como JSON.
 * Los favoritos nunca se descartan al superar el límite de recientes.
 */
class RecentFilesStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    fun getAll(): List<RecentFile> {
        val json = prefs.getString(KEY_FILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val marks = o.optJSONArray("bookmarks")
                RecentFile(
                    uri = Uri.parse(o.getString("uri")),
                    name = o.getString("name"),
                    lastPage = o.optInt("lastPage"),
                    pageCount = o.optInt("pageCount"),
                    lastOpened = o.optLong("lastOpened"),
                    favorite = o.optBoolean("favorite"),
                    bookmarks = if (marks == null) emptyList() else List(marks.length()) { marks.getInt(it) },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(uri: Uri): RecentFile? = getAll().firstOrNull { it.uri == uri }

    /** Registra que se abrió el archivo, conservando favorito y marcadores si ya existía. */
    fun recordOpened(uri: Uri, name: String, page: Int, pageCount: Int) {
        val files = getAll()
        val existing = files.firstOrNull { it.uri == uri }
        val entry = (existing ?: RecentFile(uri, name, page, pageCount, 0L)).copy(
            name = name,
            lastPage = page,
            pageCount = pageCount,
            lastOpened = System.currentTimeMillis(),
        )
        val rest = files.filterNot { it.uri == uri }
        // Se descartan los recientes más antiguos que no sean favoritos.
        var nonFavorites = 0
        val kept = rest.filter { it.favorite || ++nonFavorites < MAX_RECENTS }
        rest.filterNot { it in kept }.forEach { forget(it.uri) }
        save(listOf(entry) + kept)
    }

    fun updateLastPage(uri: Uri, page: Int) = update(uri) { it.copy(lastPage = page) }

    fun setFavorite(uri: Uri, favorite: Boolean) = update(uri) { it.copy(favorite = favorite) }

    /** Añade o quita el marcador de una página. Devuelve true si la página quedó marcada. */
    fun toggleBookmark(uri: Uri, page: Int): Boolean {
        var added = false
        update(uri) {
            added = page !in it.bookmarks
            it.copy(bookmarks = if (added) (it.bookmarks + page).sorted() else it.bookmarks - page)
        }
        return added
    }

    fun remove(uri: Uri) {
        save(getAll().filterNot { it.uri == uri })
        forget(uri)
    }

    /** Borra los recientes; los favoritos se conservan. */
    fun clearRecents() {
        val (favorites, others) = getAll().partition { it.favorite }
        others.forEach { forget(it.uri) }
        save(favorites)
    }

    private fun update(uri: Uri, change: (RecentFile) -> RecentFile) {
        val files = getAll()
        if (files.none { it.uri == uri }) return
        save(files.map { if (it.uri == uri) change(it) else it })
    }

    /** Libera todo lo asociado a un archivo que sale de la lista: permiso, copia local y miniatura. */
    private fun forget(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // No había permiso persistente para este archivo.
        }
        LocalCopies.deleteIfLocal(appContext, uri)
        Thumbnails.delete(appContext, uri)
    }

    private fun save(files: List<RecentFile>) {
        val array = JSONArray()
        files.forEach {
            array.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("name", it.name)
                    .put("lastPage", it.lastPage)
                    .put("pageCount", it.pageCount)
                    .put("lastOpened", it.lastOpened)
                    .put("favorite", it.favorite)
                    .put("bookmarks", JSONArray(it.bookmarks))
            )
        }
        prefs.edit().putString(KEY_FILES, array.toString()).apply()
    }

    private companion object {
        const val KEY_FILES = "files"
        const val MAX_RECENTS = 50
    }
}

/** Nombre visible del archivo (p. ej. "factura.pdf"), o un nombre genérico si no se puede saber. */
fun Context.displayNameOf(uri: Uri): String {
    if (uri.scheme == "content") {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
        } catch (_: Exception) {
            // Algunos proveedores no permiten consultar metadatos.
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "documento.pdf"
}
