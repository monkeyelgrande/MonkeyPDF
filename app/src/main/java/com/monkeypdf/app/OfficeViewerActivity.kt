package com.monkeypdf.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.monkeypdf.app.databinding.ActivityOfficeViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** Textos que los conversores de Office insertan en el HTML. */
class OfficeLabels(context: Context) {
    val chart: String = context.getString(R.string.office_chart)
    val emptySheet: String = context.getString(R.string.office_empty_sheet)
    val trueText: String = context.getString(R.string.office_true)
    val falseText: String = context.getString(R.string.office_false)
    private val truncatedFormat = context.getString(R.string.office_truncated)
    private val slideFormat = context.getString(R.string.office_slide)

    fun truncated(rows: Int, cols: Int) = String.format(truncatedFormat, rows, cols)
    fun slide(number: Int, total: Int) = String.format(slideFormat, number, total)
}

/**
 * Visor de documentos de Word, Excel y PowerPoint. El documento se convierte a HTML dentro del
 * teléfono (sin internet) y se muestra en un WebView, que aporta zoom, selección de texto,
 * búsqueda e impresión.
 */
class OfficeViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfficeViewerBinding
    private lateinit var recents: RecentFilesStore
    private val prefs by lazy { getSharedPreferences("viewer", MODE_PRIVATE) }

    private var docUri: Uri? = null
    private var displayName = ""
    private var format = OfficeFormat.WORD
    private var html: String? = null
    private var nightMode = false
    private var pendingScrollY = 0
    private var menu: Menu? = null

    private val saveCopy = registerForActivityResult(object : ActivityResultContracts.CreateDocument("*/*") {
        override fun createIntent(context: Context, input: String): Intent =
            super.createIntent(context, input).setType(format.mime)
    }) { target -> if (target != null) writeCopyTo(target) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityOfficeViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.appBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.content.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        recents = RecentFilesStore(this)
        nightMode = prefs.getBoolean(KEY_NIGHT_MODE, false)
        setupWebView()
        binding.searchPrev.setOnClickListener { binding.web.findNext(false) }
        binding.searchNext.setOnClickListener { binding.web.findNext(true) }

        val incoming = savedInstanceState
            ?.let { BundleCompat.getParcelable(it, STATE_DOC_URI, Uri::class.java) }
            ?: when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                else -> intent.data
            }
        if (incoming == null) {
            showError(getString(R.string.error_no_file))
            return
        }
        displayName = recents.find(incoming)?.name ?: displayNameOf(incoming)
        title = displayName
        format = OfficeFormat.fromName(displayName)
            ?: OfficeFormat.fromMime(intent.type)
            ?: OfficeFormat.fromMime(contentResolver.getType(incoming))
            ?: OfficeFormat.WORD
        pendingScrollY = savedInstanceState?.getInt(STATE_SCROLL) ?: 0

        lifecycleScope.launch {
            val extension = displayName.substringAfterLast('.', "").lowercase()
                .takeIf { OfficeFormat.fromName(displayName) != null } ?: format.extension
            val uri = LocalCopies.keepReadable(this@OfficeViewerActivity, incoming, intent, displayName, extension)
            docUri = uri
            refreshMenuState()
            load(uri)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        docUri?.let { outState.putParcelable(STATE_DOC_URI, it) }
        outState.putInt(STATE_SCROLL, binding.web.scrollY)
    }

    override fun onDestroy() {
        binding.web.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.web.settings.apply {
            // El HTML lo genera la app y no lleva scripts: se desactiva JavaScript por seguridad.
            javaScriptEnabled = false
            allowFileAccess = true // solo para leer la página generada en la caché de la app
            allowContentAccess = false
            blockNetworkLoads = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
        }
        binding.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "file") return false // enlaces internos (#marcador, notas al pie)
                // Enlaces del documento: se abren en el navegador u otra app.
                if (url.scheme in listOf("http", "https", "mailto", "tel")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.progress.isVisible = false
                view.isVisible = true
                val y = pendingScrollY
                if (y > 0) {
                    pendingScrollY = 0
                    view.postDelayed({ view.scrollTo(0, y) }, 150)
                }
            }
        }
        binding.web.setFindListener { active, count, done ->
            if (!done) return@setFindListener
            binding.searchStatus.text = if (count > 0) getString(R.string.search_count, active + 1, count)
            else getString(R.string.search_none)
            binding.searchPrev.isEnabled = count > 1
            binding.searchNext.isEnabled = count > 1
            binding.searchPrev.alpha = if (count > 1) 1f else 0.38f
            binding.searchNext.alpha = if (count > 1) 1f else 0.38f
        }
    }

    private fun load(uri: Uri) {
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val labels = OfficeLabels(this@OfficeViewerActivity)
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val pkg = contentResolver.openInputStream(uri)?.use { OfficePackage.read(it) }
                        ?: throw FileNotFoundException(uri.toString())
                    // El contenido manda sobre el nombre: un .docx renombrado se abre bien igual.
                    val main = pkg.mainPart().orEmpty()
                    val actual = when {
                        main.startsWith("word/") -> OfficeFormat.WORD
                        main.startsWith("xl/") -> OfficeFormat.EXCEL
                        main.startsWith("ppt/") -> OfficeFormat.POWERPOINT
                        else -> format
                    }
                    actual to when (actual) {
                        OfficeFormat.WORD -> DocxToHtml(pkg, labels).convert()
                        OfficeFormat.EXCEL -> XlsxToHtml(pkg, labels).convert()
                        OfficeFormat.POWERPOINT -> PptxToHtml(pkg, labels).convert()
                    }
                }
            }
            if (isDestroyed) return@launch
            result
                .onSuccess { (actual, page) ->
                    format = actual
                    html = page
                    recents.recordOpened(uri, displayName, 0, 0)
                    refreshMenuState()
                    show()
                }
                .onFailure { error ->
                    when (error) {
                        is FileNotFoundException, is SecurityException -> {
                            recents.remove(uri)
                            showError(getString(R.string.error_access))
                        }
                        is LegacyOfficeException -> showError(getString(R.string.error_office_legacy))
                        is OutOfMemoryError -> showError(getString(R.string.error_office_too_big))
                        else -> showError(getString(R.string.error_office_invalid))
                    }
                }
        }
    }

    /** Escribe la página en la caché y la carga (más fiable que pasar decenas de MB por loadData). */
    private fun show() {
        val page = html ?: return
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "office").apply { mkdirs() }
                val target = File(dir, "view.html")
                val content = if (nightMode) page.replaceFirst("<html>", "<html class=\"night\">") else page
                target.writeText(content)
                target
            }
            if (isDestroyed) return@launch
            binding.web.loadUrl(Uri.fromFile(file).toString())
        }
    }

    private fun showError(message: String) {
        binding.progress.isVisible = false
        binding.web.isVisible = false
        binding.errorText.text = message
        binding.errorText.isVisible = true
    }

    // region Búsqueda

    private fun setupSearchView(item: MenuItem) {
        val searchView = item.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.maxWidth = Int.MAX_VALUE
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                search(query)
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                search(newText)
                return true
            }
        })
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                binding.web.clearMatches()
                binding.searchBar.isVisible = false
                return true
            }
        })
    }

    private fun search(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            binding.web.clearMatches()
            binding.searchBar.isVisible = false
            return
        }
        binding.searchBar.isVisible = true
        binding.searchStatus.text = getString(R.string.searching)
        binding.web.findAllAsync(query)
    }

    // endregion

    // region Menú

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_office, menu)
        this.menu = menu
        setupSearchView(menu.findItem(R.id.action_search))
        refreshMenuState()
        return true
    }

    private fun refreshMenuState() {
        val menu = menu ?: return
        val loaded = html != null
        val hasFile = docUri != null
        listOf(R.id.action_search, R.id.action_favorite, R.id.action_night_mode, R.id.action_print)
            .forEach { menu.findItem(it).isVisible = loaded }
        listOf(R.id.action_share, R.id.action_open_with, R.id.action_save_copy)
            .forEach { menu.findItem(it).isVisible = hasFile }
        menu.findItem(R.id.action_favorite).isChecked = docUri?.let { recents.find(it) }?.favorite == true
        menu.findItem(R.id.action_night_mode).isChecked = nightMode
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_favorite -> toggleFavorite()
            R.id.action_share -> share()
            R.id.action_open_with -> openWithOtherApp()
            R.id.action_save_copy -> saveCopy.launch(displayName)
            R.id.action_night_mode -> toggleNightMode()
            R.id.action_print -> print()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggleFavorite() {
        val uri = docUri ?: return
        recents.setFavorite(uri, recents.find(uri)?.favorite != true)
        refreshMenuState()
    }

    private fun share() {
        val uri = docUri ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(format.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(displayName, uri)
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_document)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_share, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWithOtherApp() {
        val uri = docUri ?: return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, format.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, getString(R.string.open_with))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, OfficeViewerActivity::class.java)))
        try {
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_other_apps_document, Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeCopyTo(target: Uri) {
        val source = docUri ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(source)!!.use { input ->
                        contentResolver.openOutputStream(target, "w")!!.use { input.copyTo(it) }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(
                this@OfficeViewerActivity,
                if (ok) R.string.saved_copy else R.string.error_save,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun toggleNightMode() {
        nightMode = !nightMode
        prefs.edit().putBoolean(KEY_NIGHT_MODE, nightMode).apply()
        refreshMenuState()
        pendingScrollY = binding.web.scrollY
        show()
    }

    private fun print() {
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        printManager.print(displayName, binding.web.createPrintDocumentAdapter(displayName), null)
    }

    // endregion

    private companion object {
        const val STATE_DOC_URI = "doc_uri"
        const val STATE_SCROLL = "scroll"
        const val KEY_NIGHT_MODE = "night_mode"
    }
}
