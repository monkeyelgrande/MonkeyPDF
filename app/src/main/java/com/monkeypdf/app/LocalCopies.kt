package com.monkeypdf.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID

/**
 * Copias de los documentos (PDF, Word, Excel, PowerPoint) que llegan desde otras apps (WhatsApp, Gmail...).
 *
 * Esas apps solo dan permiso temporal: sin una copia, el archivo dejaría de abrirse desde
 * Recientes al cerrar MonkeyPDF. Las copias se exponen con un FileProvider propio, así que
 * también se pueden reenviar a otras apps.
 */
object LocalCopies {

    private fun authority(context: Context) = "${context.packageName}.files"

    private fun directory(context: Context) = File(context.filesDir, "documents").apply { mkdirs() }

    fun isLocal(context: Context, uri: Uri): Boolean = uri.authority == authority(context)

    /**
     * Devuelve un URI que se podrá volver a abrir más adelante. Si la app de origen solo dio
     * permiso temporal (WhatsApp, Gmail...), guarda una copia del archivo dentro de MonkeyPDF.
     */
    suspend fun keepReadable(context: Context, uri: Uri, intent: Intent, displayName: String, extension: String): Uri {
        if (isLocal(context, uri)) return uri
        val resolver = context.contentResolver
        val persisted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (persisted) return uri
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                return uri
            } catch (_: SecurityException) {
            }
        }
        return withContext(Dispatchers.IO) {
            try {
                copy(context, uri, displayName, extension)
            } catch (e: Exception) {
                uri // Sin copia: al menos se intenta abrir el original.
            }
        }
    }

    /** Copia [source] al almacenamiento de la app y devuelve el URI de la copia. Hace E/S. */
    fun copy(context: Context, source: Uri, displayName: String, extension: String = "pdf"): Uri {
        val dir = directory(context)
        val base = displayName.substringBeforeLast('.', displayName)
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .trim().ifEmpty { "documento" }.take(120)
        val sourceSize = sizeOf(context, source)

        var target = File(dir, "$base.$extension")
        if (target.exists() && sourceSize >= 0 && target.length() == sourceSize) {
            return uriFor(context, target) // Ya se había guardado este mismo archivo.
        }
        var n = 1
        while (target.exists()) target = File(dir, "$base (${n++}).$extension")

        val temp = File(dir, ".tmp-${UUID.randomUUID()}")
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw FileNotFoundException(source.toString())
            input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }
            if (!temp.renameTo(target)) throw FileNotFoundException(target.path)
        } finally {
            temp.delete()
        }
        return uriFor(context, target)
    }

    fun deleteIfLocal(context: Context, uri: Uri) {
        if (!isLocal(context, uri)) return
        val name = uri.lastPathSegment ?: return
        val file = File(directory(context), name)
        if (file.parentFile == directory(context)) file.delete()
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    private fun sizeOf(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            val index = it.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && it.moveToFirst() && !it.isNull(index)) it.getLong(index) else -1L
        } ?: -1L
    } catch (_: Exception) {
        -1L
    }
}

/** Miniatura de la primera página de cada documento, para la lista de la pantalla principal. */
object Thumbnails {

    const val WIDTH_PX = 160

    private fun fileFor(context: Context, uri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "thumbs").apply { mkdirs() }, "$name.png")
    }

    fun exists(context: Context, uri: Uri) = fileFor(context, uri).exists()

    fun save(context: Context, uri: Uri, bitmap: Bitmap) {
        try {
            fileFor(context, uri).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
            // Sin miniatura se muestra el icono genérico.
        }
    }

    fun load(context: Context, uri: Uri): Bitmap? {
        val file = fileFor(context, uri)
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }

    fun delete(context: Context, uri: Uri) {
        fileFor(context, uri).delete()
    }
}
