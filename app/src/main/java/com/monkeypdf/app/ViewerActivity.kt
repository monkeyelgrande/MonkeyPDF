package com.monkeypdf.app

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.text.InputType
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.monkeypdf.app.databinding.ActivityViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var recents: RecentFilesStore
    private val prefs by lazy { getSharedPreferences("viewer", MODE_PRIVATE) }
    private val layoutManager by lazy { LinearLayoutManager(this) }

    /** URI desde el que se lee el documento: el original o la copia guardada en la app. */
    private var docUri: Uri? = null
    private var displayName = ""
    private var startPage = 0
    private var document: PdfDocumentRenderer? = null
    private var adapter: PdfPageAdapter? = null
    private var currentPage = 0
    private var chromeVisible = true
    private var nightMode = false
    private var menu: Menu? = null
    private lateinit var selection: TextSelectionController

    private var searchJob: Job? = null
    private var searchDebounce: Job? = null
    private val searchMatches = mutableListOf<SearchMatch>()
    private var activeMatch = -1
    private var searching = false

    private val hideIndicator = Runnable {
        if (!chromeVisible) binding.pageIndicator.animate().alpha(0f).setDuration(250)
    }

    /** "Guardar una copia…": el usuario elige carpeta y nombre (Descargas, Drive...). */
    private val saveCopy = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { target -> if (target != null) writeCopyTo(target) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        recents = RecentFilesStore(this)
        nightMode = prefs.getBoolean(KEY_NIGHT_MODE, false)
        applyWindowInsets()
        setupPages()
        setupSearchBar()

        val incoming = savedInstanceState
            ?.let { BundleCompat.getParcelable(it, STATE_DOC_URI, Uri::class.java) }
            ?: extractUri(intent)
        if (incoming == null) {
            showError(getString(R.string.error_no_file))
            return
        }
        // Si ya no hay acceso, el proveedor no da el nombre: se usa el guardado en Recientes.
        displayName = recents.find(incoming)?.name ?: displayNameOf(incoming)
        title = displayName
        startPage = savedInstanceState?.getInt(STATE_PAGE) ?: recents.find(incoming)?.lastPage ?: 0

        binding.progress.isVisible = true
        lifecycleScope.launch {
            val uri = resolveReadableUri(incoming)
            docUri = uri
            // Recientes guarda el URI de la copia, no el que mandó la otra app.
            if (savedInstanceState == null) recents.find(uri)?.let { startPage = it.lastPage }
            loadDocument(uri, password = null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentPage)
        docUri?.let { outState.putParcelable(STATE_DOC_URI, it) }
    }

    override fun onPause() {
        super.onPause()
        if (document != null) docUri?.let { recents.updateLastPage(it, currentPage) }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.pages.adapter = null
        document?.close()
        document = null
    }

    // region Origen del archivo y carga

    private fun extractUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> intent.data
    }

    private suspend fun resolveReadableUri(uri: Uri): Uri =
        LocalCopies.keepReadable(this, uri, intent, displayName, "pdf")

    private fun loadDocument(uri: Uri, password: String?) {
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PdfDocumentRenderer.open(this@ViewerActivity, uri, password) }
            }
            binding.progress.isVisible = false
            result
                .onSuccess { doc -> if (isDestroyed) doc.close() else showDocument(uri, doc) }
                .onFailure { error ->
                    when (error) {
                        is PasswordRequiredException -> askPassword(uri, error.wrongPassword)
                        is FileNotFoundException, is SecurityException -> {
                            // Si ya no hay acceso al archivo, no tiene sentido mantenerlo en Recientes.
                            recents.remove(uri)
                            showError(getString(R.string.error_access))
                        }
                        is InvalidPdfException -> showError(getString(R.string.error_invalid))
                        else -> showError(getString(R.string.error_generic))
                    }
                }
        }
    }

    private fun askPassword(uri: Uri, wrongPassword: Boolean) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val field = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.DEFAULT // el tipo contraseña fuerza letra monoespaciada
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.password_hint)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            errorIconDrawable = null // para que el error no oculte el botón de mostrar contraseña
            if (wrongPassword) error = getString(R.string.password_wrong)
            addView(field)
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.password_title)
            .setMessage(R.string.password_message)
            .setView(container)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.open, null)
            .setOnCancelListener { finish() }
            .create()

        fun submit() {
            val password = field.text?.toString().orEmpty()
            if (password.isEmpty()) return
            dialog.setOnCancelListener(null)
            dialog.dismiss()
            loadDocument(uri, password)
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            field.requestFocus()
        }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun showDocument(uri: Uri, doc: PdfDocumentRenderer) {
        document = doc
        if (doc.pageCount == 0) {
            showError(getString(R.string.error_invalid))
            return
        }
        val pageAdapter = PdfPageAdapter(doc, lifecycleScope, nightMode)
        adapter = pageAdapter
        binding.pages.adapter = pageAdapter

        currentPage = startPage.coerceIn(0, doc.pageCount - 1)
        layoutManager.scrollToPositionWithOffset(currentPage, 0)
        binding.pageIndicator.isVisible = true
        updatePageIndicator()

        recents.recordOpened(uri, displayName, currentPage, doc.pageCount)
        refreshMenuState()
        saveListThumbnail(uri, doc)
        lifecycleScope.launch {
            cachedToc = withContext(doc.dispatcher) { doc.tableOfContents() }
            refreshMenuState()
        }
    }

    private fun saveListThumbnail(uri: Uri, doc: PdfDocumentRenderer) {
        if (Thumbnails.exists(this, uri)) return
        lifecycleScope.launch {
            val bitmap = withContext(doc.dispatcher) { doc.renderPage(0, Thumbnails.WIDTH_PX) } ?: return@launch
            withContext(Dispatchers.IO) { Thumbnails.save(this@ViewerActivity, uri, bitmap) }
        }
    }

    private fun showError(message: String) {
        binding.progress.isVisible = false
        binding.pageIndicator.isVisible = false
        binding.errorText.text = message
        binding.errorText.isVisible = true
    }

    // endregion

    // region Páginas, zoom y barra superior

    private fun setupPages() {
        val pages = binding.pages
        pages.layoutManager = layoutManager
        pages.itemAnimator = null
        // Sin el velo gris de foco que Android pinta al usar teclado.
        pages.defaultFocusHighlightEnabled = false
        pages.setHasFixedSize(true)
        selection = TextSelectionController(
            activity = this,
            pages = pages,
            overlay = binding.selectionOverlay,
            scope = lifecycleScope,
            document = { document },
            adapter = { adapter },
            onSearch = { text -> searchFor(text) },
        )
        // Un toque quita la selección si la hay; si no, muestra u oculta las barras.
        pages.onSingleTap = { if (selection.isActive) selection.clear() else setChromeVisible(!chromeVisible) }
        pages.onViewportChanged = {
            onPositionChanged()
            selection.onViewportChanged()
        }
        pages.onLongPress = { x, y -> selection.startAt(x, y) }
        pages.onZoomSettled = { scale ->
            // Re-renderiza con más resolución cuando hay zoom (máximo 2x para cuidar la memoria).
            val quality = when {
                scale < 1.25f -> 1f
                scale < 1.75f -> 1.5f
                else -> 2f
            }
            adapter?.setQuality(quality)
        }
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onPositionChanged()
                selection.onViewportChanged()
            }
        })
        pages.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && oldRight - oldLeft > 0) {
                val page = currentPage
                pages.resetZoom()
                selection.clear()
                adapter?.onWidthChanged()
                pages.post { layoutManager.scrollToPositionWithOffset(page, 0) }
            }
        }

        binding.fastScroller.attachTo(pages)
        binding.fastScroller.pageCount = { document?.pageCount ?: 0 }
        binding.fastScroller.pageHeight = { pageHeight(it) }
        binding.fastScroller.label = { pageLabel() }

        binding.pageIndicator.setOnClickListener { showThumbnails() }
    }

    /** Alto en píxeles (sin zoom) con el que se muestra una página. */
    private fun pageHeight(page: Int): Int {
        val size = document?.pageSizes?.getOrNull(page) ?: return 0
        val pages = binding.pages
        val width = pages.width - pages.paddingLeft - pages.paddingRight
        return (width.toLong() * size.height / size.width).toInt()
    }

    private fun pageLabel() = getString(R.string.page_indicator, currentPage + 1, document?.pageCount ?: 0)

    private fun onPositionChanged() {
        val pages = binding.pages
        val child = pages.findChildViewUnder(pages.width / 2f, pages.contentCenterY())
        val position = child?.let { pages.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        if (position != RecyclerView.NO_POSITION && position != currentPage) {
            currentPage = position
            updatePageIndicator()
        }
        if (!chromeVisible) {
            binding.pageIndicator.animate().cancel()
            binding.pageIndicator.alpha = 1f
            binding.pageIndicator.removeCallbacks(hideIndicator)
            binding.pageIndicator.postDelayed(hideIndicator, 1500)
        }
    }

    private fun updatePageIndicator() {
        if (document == null) return
        // Se aplaza: si llega durante el reacomodo de la lista, Android ignoraría el nuevo ancho.
        binding.pageIndicator.post {
            binding.pageIndicator.text = pageLabel()
            refreshBookmarkIcon()
        }
    }

    private fun goToPage(index: Int, offsetPx: Int = 0) {
        binding.pages.resetZoom()
        binding.pages.stopScroll()
        layoutManager.scrollToPositionWithOffset(index, offsetPx)
        currentPage = index
        updatePageIndicator()
    }

    /** Modo lectura: oculta la barra superior y las barras del sistema con un toque. */
    private fun setChromeVisible(visible: Boolean) {
        if (document == null) return
        chromeVisible = visible
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val appBar = binding.appBar
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            appBar.animate().translationY(0f).alpha(1f).setDuration(200)
            binding.pageIndicator.removeCallbacks(hideIndicator)
            binding.pageIndicator.animate().alpha(1f).setDuration(200)
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            appBar.animate().translationY(-appBar.height.toFloat()).alpha(0f).setDuration(200)
            binding.pageIndicator.animate().alpha(0f).setDuration(200)
        }
    }

    private fun applyWindowInsets() {
        val actionBarHeight = TypedValue().let {
            theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, it, true)
            TypedValue.complexToDimensionPixelSize(it.data, resources.displayMetrics)
        }
        val gap = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            // Se usan los insets "ignorando visibilidad" para que el contenido no salte
            // al ocultar/mostrar las barras del sistema.
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.appBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.pages.updatePadding(
                left = bars.left,
                top = bars.top + actionBarHeight + gap,
                right = bars.right,
                bottom = bars.bottom + gap,
            )
            binding.fastScroller.updatePadding(
                top = bars.top + actionBarHeight + gap,
                right = bars.right,
                bottom = bars.bottom + gap,
            )
            binding.pageIndicator.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bars.bottom + 2 * gap
            }
            binding.searchBar.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bars.bottom + 8 * gap
            }
            insets
        }
    }

    // endregion

    // region Búsqueda

    private fun setupSearchBar() {
        binding.searchPrev.setOnClickListener { stepMatch(-1) }
        binding.searchNext.setOnClickListener { stepMatch(+1) }
    }

    /** Abre la búsqueda con un texto ya escrito (p. ej. desde "Buscar" en la selección). */
    private fun searchFor(text: String) {
        val item = menu?.findItem(R.id.action_search) ?: return
        if (!chromeVisible) setChromeVisible(true)
        item.expandActionView()
        (item.actionView as SearchView).apply {
            setQuery(text.replace(Regex("\\s+"), " ").take(100), true)
            clearFocus()
        }
    }

    private fun setupSearchView(item: MenuItem) {
        val searchView = item.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.maxWidth = Int.MAX_VALUE
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchDebounce?.cancel()
                startSearch(query)
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                searchDebounce?.cancel()
                searchDebounce = lifecycleScope.launch {
                    delay(400)
                    startSearch(newText)
                }
                return true
            }
        })
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchDebounce?.cancel()
                clearSearch()
                return true
            }
        })
    }

    private fun startSearch(rawQuery: String) {
        val doc = document ?: return
        val query = rawQuery.trim()
        searchJob?.cancel()
        searchMatches.clear()
        activeMatch = -1
        adapter?.setSearchMatches(emptyList())
        adapter?.setActiveMatch(null)
        if (query.length < 2) {
            binding.searchBar.isVisible = false
            return
        }
        binding.searchBar.isVisible = true
        searching = true
        updateSearchStatus()

        val firstPage = currentPage
        searchJob = lifecycleScope.launch {
            for (page in 0 until doc.pageCount) {
                val found = withContext(doc.dispatcher) { doc.searchPage(page, query) }
                if (found.isEmpty()) continue
                searchMatches += found
                // Salta al primer resultado a partir de la página actual en cuanto aparece.
                if (activeMatch < 0 && page >= firstPage) selectMatch(searchMatches.size - found.size)
                if (page % 10 == 0) adapter?.setSearchMatches(searchMatches.toList())
                updateSearchStatus()
            }
            searching = false
            adapter?.setSearchMatches(searchMatches.toList())
            if (activeMatch < 0 && searchMatches.isNotEmpty()) selectMatch(0)
            updateSearchStatus()
        }
    }

    private fun clearSearch() {
        searchJob?.cancel()
        searching = false
        searchMatches.clear()
        activeMatch = -1
        adapter?.setSearchMatches(emptyList())
        adapter?.setActiveMatch(null)
        binding.searchBar.isVisible = false
    }

    private fun stepMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        val next = ((if (activeMatch < 0) 0 else activeMatch + delta) + searchMatches.size) % searchMatches.size
        selectMatch(next)
    }

    private fun selectMatch(index: Int) {
        val match = searchMatches.getOrNull(index) ?: return
        activeMatch = index
        adapter?.setActiveMatch(match)
        // Coloca la coincidencia a un tercio de la pantalla.
        val top = match.rects.minOfOrNull { it.top } ?: 0f
        val pages = binding.pages
        val visibleHeight = pages.height - pages.paddingTop - pages.paddingBottom
        goToPage(match.page, -(top * pageHeight(match.page)).toInt() + visibleHeight / 3)
        updateSearchStatus()
    }

    private fun updateSearchStatus() {
        binding.searchProgress.isVisible = searching
        val total = searchMatches.size
        binding.searchStatus.text = when {
            total > 0 -> getString(R.string.search_count, activeMatch + 1, total)
            searching -> getString(R.string.searching)
            else -> getString(R.string.search_none)
        }
        binding.searchPrev.isEnabled = total > 1
        binding.searchNext.isEnabled = total > 1
        binding.searchPrev.alpha = if (total > 1) 1f else 0.38f
        binding.searchNext.alpha = if (total > 1) 1f else 0.38f
    }

    // endregion

    // region Menú

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        this.menu = menu
        setupSearchView(menu.findItem(R.id.action_search))
        refreshMenuState()
        return true
    }

    /** Actualiza el menú sin recrearlo (recrearlo cerraría la búsqueda abierta). */
    private fun refreshMenuState() {
        val menu = menu ?: return
        val loaded = document != null
        val hasFile = docUri != null
        val entry = docUri?.let { recents.find(it) }
        val hasToc = loaded && !cachedToc.isNullOrEmpty()
        listOf(
            R.id.action_search, R.id.action_bookmark, R.id.action_thumbnails, R.id.action_go_to_page,
            R.id.action_bookmarks, R.id.action_copy_text, R.id.action_favorite, R.id.action_night_mode,
            R.id.action_print,
        ).forEach { menu.findItem(it).isVisible = loaded }
        menu.findItem(R.id.action_toc).isVisible = hasToc
        listOf(R.id.action_share, R.id.action_open_with, R.id.action_save_copy)
            .forEach { menu.findItem(it).isVisible = hasFile }
        menu.findItem(R.id.action_favorite).isChecked = entry?.favorite == true
        menu.findItem(R.id.action_night_mode).isChecked = nightMode
        refreshBookmarkIcon()
    }

    private fun refreshBookmarkIcon() {
        val item = menu?.findItem(R.id.action_bookmark) ?: return
        val marked = docUri?.let { recents.find(it) }?.bookmarks?.contains(currentPage) == true
        item.setIcon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        item.setTitle(if (marked) R.string.remove_bookmark else R.string.add_bookmark)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_bookmark -> toggleBookmark()
            R.id.action_thumbnails -> showThumbnails()
            R.id.action_go_to_page -> showGoToPageDialog()
            R.id.action_bookmarks -> showBookmarks()
            R.id.action_toc -> showTableOfContents()
            R.id.action_copy_text -> selection.selectAll(currentPage)
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

    private fun toggleBookmark() {
        val uri = docUri ?: return
        val added = recents.toggleBookmark(uri, currentPage)
        refreshBookmarkIcon()
        val message = if (added) R.string.bookmark_added else R.string.bookmark_removed
        Toast.makeText(this, getString(message, currentPage + 1), Toast.LENGTH_SHORT).show()
    }

    private fun bookmarks(): List<Int> = docUri?.let { recents.find(it) }?.bookmarks.orEmpty()

    private fun showThumbnails() {
        val doc = document ?: return
        showThumbnailsSheet(this, lifecycleScope, doc, currentPage, bookmarks().toSet()) { goToPage(it) }
    }

    private fun showBookmarks() {
        val doc = document ?: return
        val pages = bookmarks().filter { it < doc.pageCount }
        if (pages.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bookmarks)
                .setMessage(R.string.no_bookmarks)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        lifecycleScope.launch {
            // Cada marcador muestra el comienzo del texto de su página.
            val labels = pages.map { page ->
                val snippet = withContext(doc.dispatcher) { doc.pageText(page) }
                    .replace(Regex("\\s+"), " ").trim().take(60)
                val title = getString(R.string.page_number, page + 1)
                if (snippet.isEmpty()) title else "$title · $snippet…"
            }
            MaterialAlertDialogBuilder(this@ViewerActivity)
                .setTitle(R.string.bookmarks)
                .setItems(labels.toTypedArray()) { _, which -> goToPage(pages[which]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private var cachedToc: List<TocEntry>? = null

    private fun showTableOfContents() {
        val doc = document ?: return
        lifecycleScope.launch {
            val toc = cachedToc ?: withContext(doc.dispatcher) { doc.tableOfContents() }.also { cachedToc = it }
            if (toc.isEmpty()) {
                refreshMenuState()
                return@launch
            }
            val labels = toc.map { " ".repeat(it.level) + it.title + "  ·  " + (it.page + 1) }
            MaterialAlertDialogBuilder(this@ViewerActivity)
                .setTitle(R.string.table_of_contents)
                .setItems(labels.toTypedArray()) { _, which -> goToPage(toc[which].page) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun toggleFavorite() {
        val uri = docUri ?: return
        val favorite = recents.find(uri)?.favorite != true
        recents.setFavorite(uri, favorite)
        refreshMenuState()
    }

    private fun share() {
        val uri = docUri ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(displayName, uri)
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_with)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_share, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWithOtherApp() {
        val uri = docUri ?: return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, getString(R.string.open_with))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, ViewerActivity::class.java)))
        try {
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_other_apps, Toast.LENGTH_SHORT).show()
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
                this@ViewerActivity,
                if (ok) R.string.saved_copy else R.string.error_save,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showGoToPageDialog() {
        val total = document?.pageCount ?: return
        val padding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = getString(R.string.page_hint, total)
            setText((currentPage + 1).toString())
            selectAll()
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.go, null)
            .create()

        fun submit() {
            val page = input.text.toString().toIntOrNull()
            if (page == null || page !in 1..total) {
                input.error = getString(R.string.error_page, total)
                return
            }
            goToPage(page - 1)
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.requestFocus()
        }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun toggleNightMode() {
        nightMode = !nightMode
        prefs.edit().putBoolean(KEY_NIGHT_MODE, nightMode).apply()
        adapter?.nightMode = nightMode
        refreshMenuState()
    }

    private fun print() {
        val uri = docUri ?: return
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        printManager.print(displayName, PdfPrintAdapter(this, uri, displayName), null)
    }

    // endregion

    private companion object {
        const val STATE_PAGE = "page"
        const val STATE_DOC_URI = "doc_uri"
        const val KEY_NIGHT_MODE = "night_mode"
    }
}
