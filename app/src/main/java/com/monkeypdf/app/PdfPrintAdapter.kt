package com.monkeypdf.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.FileOutputStream
import kotlin.concurrent.thread

/** Envía el PDF tal cual al sistema de impresión de Android (impresoras o "Guardar como PDF"). */
class PdfPrintAdapter(
    context: Context,
    private val uri: Uri,
    private val name: String,
) : PrintDocumentAdapter() {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        // La copia se hace fuera del hilo principal y el resultado se notifica en él.
        thread(name = "pdf-print") {
            try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("No se pudo leer el archivo")
                input.use { source ->
                    FileOutputStream(destination.fileDescriptor).use { source.copyTo(it) }
                }
                mainHandler.post {
                    if (cancellationSignal.isCanceled) {
                        callback.onWriteCancelled()
                    } else {
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { callback.onWriteFailed(e.message) }
            }
        }
    }
}
