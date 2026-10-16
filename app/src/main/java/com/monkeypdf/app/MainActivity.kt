package com.monkeypdf.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.tabs.TabLayout
import com.monkeypdf.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recents: RecentFilesStore
    private val adapter = RecentFilesAdapter(
        onClick = { openPdf(it.uri) },
        onLongClick = { file, view -> showItemMenu(file, view) },
        onToggleFavorite = { file ->
            recents.setFavorite(file.uri, !file.favorite)
            refresh()
        },
    )
    private val showingFavorites get() = binding.tabs.selectedTabPosition == TAB_FAVORITES

    /** Selector de archivos del sistema, filtrado a PDFs (almacenamiento interno, SD, Drive, Descargas...). */
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            // Conserva el acceso para poder reabrirlo desde Recientes tras reiniciar el teléfono.
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        openPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        recents = RecentFilesStore(this)
        binding.recentList.layoutManager = LinearLayoutManager(this)
        binding.recentList.adapter = adapter
        binding.openButton.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        binding.scanButton.setOnClickListener { startScanner() }
        savedInstanceState?.getInt(STATE_TAB)?.let { binding.tabs.getTabAt(it)?.select() }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = refresh()
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        val baseListPadding = binding.recentList.paddingBottom
        val fabMargin = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.recentList.updatePadding(
                left = bars.left, right = bars.right, bottom = baseListPadding + bars.bottom
            )
            binding.fabContainer.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
                bottomMargin = fabMargin + bars.bottom
                rightMargin = fabMargin + bars.right
            }
            insets
        }
    }

    /**
     * Escáner de documentos de Google (ML Kit): cámara con detección de bordes, recorte
     * automático y filtros de mejora. Devuelve directamente un PDF.
     */
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf
            ?: return@registerForActivityResult
        saveScan(pdf.uri)
    }

    private fun startScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                Toast.makeText(this, R.string.error_scanner, Toast.LENGTH_LONG).show()
            }
    }

    /** Guarda el PDF escaneado dentro de la app y lo abre. */
    private fun saveScan(source: Uri) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault()).format(Date())
        val name = getString(R.string.scan_file_name, stamp) + ".pdf"
        Toast.makeText(this, R.string.scan_saving, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { LocalCopies.copy(this@MainActivity, source, name) }.getOrNull()
            }
            if (saved == null) {
                Toast.makeText(this@MainActivity, R.string.error_scan_save, Toast.LENGTH_LONG).show()
            } else {
                openPdf(saved)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.clearThumbnailCache()
        refresh()
        // Fuerza a redibujar por si el visor generó miniaturas nuevas.
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, binding.tabs.selectedTabPosition)
    }

    private fun refresh() {
        val all = recents.getAll()
        val files = if (showingFavorites) all.filter { it.favorite } else all
        adapter.submitList(files)
        binding.emptyState.isVisible = files.isEmpty()
        binding.recentList.isVisible = files.isNotEmpty()
        binding.emptyIcon.setImageResource(if (showingFavorites) R.drawable.ic_star_border else R.drawable.ic_pdf)
        binding.emptyTitle.setText(if (showingFavorites) R.string.empty_favorites_title else R.string.empty_title)
        binding.emptyMessage.setText(if (showingFavorites) R.string.empty_favorites_message else R.string.empty_message)
    }

    private fun openPdf(uri: Uri) {
        // Sin FLAG_GRANT_READ_URI_PERMISSION: el visor es de esta misma app y ya comparte sus permisos.
        // Pedirlo para un archivo cuyo permiso temporal caducó (p. ej. uno abierto desde WhatsApp)
        // haría que Android lanzara SecurityException.
        val intent = Intent(this, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
        startActivity(intent)
    }

    private fun showItemMenu(file: RecentFile, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(if (file.favorite) R.string.remove_favorite else R.string.add_favorite)
                .setOnMenuItemClickListener {
                    recents.setFavorite(file.uri, !file.favorite)
                    refresh()
                    true
                }
            menu.add(R.string.share).setOnMenuItemClickListener {
                shareFile(file.uri)
                true
            }
            menu.add(R.string.remove_from_recents).setOnMenuItemClickListener {
                recents.remove(file.uri)
                refresh()
                true
            }
            show()
        }
    }

    private fun shareFile(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("", uri)
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_with)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_share, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_default_app -> showDefaultAppHelp()
            R.id.action_clear_recents -> confirmClearRecents()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Android no deja que una app se imponga como predeterminada para PDFs:
     * lo elige el usuario con "Siempre" en el diálogo "Abrir con". Aquí se explica cómo.
     */
    private fun showDefaultAppHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.default_app_title)
            .setMessage(R.string.default_app_message)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.open_settings) { _, _ -> openDefaultAppsSettings() }
            .show()
    }

    private fun openDefaultAppsSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.error_no_settings, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmClearRecents() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_recents_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                recents.clearRecents()
                refresh()
            }
            .show()
    }

    private companion object {
        const val TAB_FAVORITES = 1
        const val STATE_TAB = "tab"
    }
}
