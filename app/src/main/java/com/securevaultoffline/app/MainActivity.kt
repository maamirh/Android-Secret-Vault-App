package com.securevaultoffline.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.securevaultoffline.app.databinding.ActivityMainBinding
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXTERNAL_FLOW_GRACE_MS = 120_000L
        private const val PREFS = "alpha_prefs"
        private const val KEY_CURRENT_ALBUM = "current_album"
        private const val DEFAULT_ALBUM = "General"
        private const val STATE_BROWSE_MODE = "browse_mode"
        private const val STATE_ALBUM_SUB = "album_sub_path"
    }

    private enum class BrowseMode { FOLDER_BROWSER, ALBUM_CONTENT }

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val vaultDir: File by lazy {
        File(filesDir, "vault").apply { mkdirs() }
    }
    private val albumsRoot: File
        get() = File(vaultDir, "albums").apply { mkdirs() }

    private var currentAlbumName: String
        get() = prefs.getString(KEY_CURRENT_ALBUM, DEFAULT_ALBUM) ?: DEFAULT_ALBUM
        set(value) {
            prefs.edit { putString(KEY_CURRENT_ALBUM, value) }
        }

    private fun currentAlbumDir(): File = File(albumsRoot, currentAlbumName).apply { mkdirs() }

    private fun currentAlbumBrowseDir(): File {
        val root = currentAlbumDir()
        val raw = albumSubPath.trim().replace('\\', '/').trim('/')
        if (raw.isEmpty()) return root
        val merged = File(root, raw)
        val rootCanon = runCatching { root.canonicalFile }.getOrNull() ?: root
        val targetCanon = runCatching { merged.canonicalFile }.getOrNull() ?: return root
        val prefix = rootCanon.path.trimEnd('/') + "/"
        if (targetCanon != rootCanon && !targetCanon.path.startsWith(prefix)) {
            albumSubPath = ""
            return root
        }
        targetCanon.mkdirs()
        return targetCanon
    }

    private lateinit var albumBrowseAdapter: AlbumBrowseAdapter
    private lateinit var folderAdapter: AlbumFolderAdapter
    private val thumbGen by lazy { VaultThumbnailGenerator(applicationContext) }

    private var browseMode = BrowseMode.FOLDER_BROWSER
    /** Relative path under [currentAlbumDir]; empty = album root. */
    private var albumSubPath: String = ""
    private var allVaultFiles: List<File> = emptyList()
    private var currentViewMode = AlbumBrowseAdapter.ViewMode.GRID

    private data class PendingImport(val uri: Uri, val vaultFileName: String, val targetDir: File)

    private val pendingImportQueue = ArrayDeque<PendingImport>()
    private var pendingExportFile: File? = null
    private var pendingExportSuggestedName: String? = null
    private var pendingBatchExportFiles: List<File>? = null

    private var importProgressDialog: AlertDialog? = null
    private var importProgressTitle: TextView? = null
    private var importProgressBar: ProgressBar? = null
    private var importProgressPercent: TextView? = null
    private var importProgressDetail: TextView? = null
    private var importBatchTotal: Int = 0
    private var importBatchDone: Int = 0

    private val pickMultipleLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isEmpty()) return@registerForActivityResult
            SessionGate.allowExternalFlowFor(EXTERNAL_FLOW_GRACE_MS)
            runCatching {
                enqueueImportsFromUris(uris)
            }.onFailure { e ->
                showAppSnackbar(e.message ?: getString(R.string.import_queue_failed), longDuration = true)
                clearImportPending()
            }
        }

    private val openImportTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
            if (treeUri == null) return@registerForActivityResult
            SessionGate.allowExternalFlowFor(EXTERNAL_FLOW_GRACE_MS)
            lifecycleScope.launch {
                showImportProgressDialog(scanning = true)
                val pairs = try {
                    SafFolderScanner.listFilesRecursive(this@MainActivity, treeUri) { count ->
                        updateImportScanningProgress(count)
                    }
                } catch (e: Exception) {
                    dismissImportProgress()
                    showAppSnackbar(
                        e.message ?: getString(R.string.import_queue_failed),
                        longDuration = true,
                    )
                    return@launch
                }
                if (pairs.isEmpty()) {
                    dismissImportProgress()
                    showAppSnackbar(getString(R.string.import_folder_empty))
                    return@launch
                }
                val pending = withContext(Dispatchers.IO) {
                    val base = currentAlbumBrowseDir()
                    pairs.map { (uri, rel) ->
                        val norm = rel.replace('\\', '/')
                        val slash = norm.lastIndexOf('/')
                        val subRel = if (slash < 0) "" else norm.substring(0, slash)
                        val fileName = if (slash < 0) norm else norm.substring(slash + 1)
                        val targetDir = if (subRel.isEmpty()) base else File(base, subRel).apply { mkdirs() }
                        val stem = sanitizeFileName(fileName).ifEmpty { "file" }.take(120)
                        val vaultName = uniqueVaultOutputNameInDir(targetDir, stem)
                        PendingImport(uri, vaultName, targetDir)
                    }
                }
                enqueuePendingImports(pending)
            }
        }

    private val exportFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
            val files = pendingBatchExportFiles
            pendingBatchExportFiles = null
            if (treeUri == null || files.isNullOrEmpty()) return@registerForActivityResult
            warmDecryptAuthExportToTree(treeUri, files)
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            val sourceFile = pendingExportFile
            val suggested = pendingExportSuggestedName
            pendingExportFile = null
            pendingExportSuggestedName = null
            if (uri == null || sourceFile == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    val cipher = VaultCrypto.newCipher()
                    sourceFile.inputStream().use { input ->
                        val iv = ByteArray(12)
                        if (input.read(iv) != 12) error("Invalid vault file")
                        VaultCrypto.initDecryptCipher(cipher, iv)
                        VaultCrypto.decryptStream(input, output, cipher)
                    }
                } ?: error("Could not open export destination")
                showAppSnackbar(getString(R.string.export_success_named, suggested ?: ""))
            }.onFailure { e ->
                showAppSnackbar(e.message ?: getString(R.string.error_export_failed), longDuration = true)
            }
        }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (browseMode == BrowseMode.FOLDER_BROWSER && folderAdapter.isSelectionActive()) {
                folderAdapter.clearSelection()
                refreshAlbumToolbarUi()
                return
            }
            if (browseMode == BrowseMode.ALBUM_CONTENT && albumBrowseAdapter.isSelectionActive()) {
                albumBrowseAdapter.clearSelection()
                refreshAlbumToolbarUi()
                return
            }
            if (browseMode == BrowseMode.ALBUM_CONTENT && navigateAlbumUp()) {
                refreshAlbumFiles()
                refreshAlbumToolbarUi()
                return
            }
            if (browseMode == BrowseMode.ALBUM_CONTENT) {
                exitAlbumFolder()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainCoordinator) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
        applySecureWindow()
        setSupportActionBar(binding.topToolbar)
        binding.topToolbar.setNavigationOnClickListener {
            if (browseMode == BrowseMode.FOLDER_BROWSER && folderAdapter.isSelectionActive()) {
                folderAdapter.clearSelection()
                refreshAlbumToolbarUi()
            } else if (browseMode == BrowseMode.ALBUM_CONTENT && albumBrowseAdapter.isSelectionActive()) {
                albumBrowseAdapter.clearSelection()
                refreshAlbumToolbarUi()
            } else if (browseMode == BrowseMode.ALBUM_CONTENT && navigateAlbumUp()) {
                refreshAlbumFiles()
                refreshAlbumToolbarUi()
            } else if (browseMode == BrowseMode.ALBUM_CONTENT) {
                exitAlbumFolder()
            }
        }

        savedInstanceState?.getString(STATE_BROWSE_MODE)?.let {
            runCatching { browseMode = BrowseMode.valueOf(it) }
        }
        savedInstanceState?.getString(STATE_ALBUM_SUB)?.let { albumSubPath = it }

        VaultCrypto.createVaultKeyIfNeeded()

        folderAdapter = AlbumFolderAdapter(
            onOpen = { album -> openAlbum(album) },
            onSelectionChanged = { refreshAlbumToolbarUi() },
        )
        binding.folderList.layoutManager = GridLayoutManager(this, 3)
        binding.folderList.adapter = folderAdapter

        albumBrowseAdapter = AlbumBrowseAdapter(
            lifecycleOwner = this,
            thumbnailGenerator = thumbGen,
            vaultUnlocked = SessionGate.isUnlocked,
            viewMode = currentViewMode,
            onOpenSubfolder = { dir ->
                albumSubPath = if (albumSubPath.isEmpty()) dir.name else "$albumSubPath/${dir.name}"
                albumBrowseAdapter.clearSelection()
                refreshAlbumFiles()
                refreshAlbumToolbarUi()
            },
            onRequestDeleteFolder = { dir -> confirmDeleteFolder(dir) },
            onOpenFromGrid = { file -> openFromGrid(file) },
            onViewImage = { file -> openImagePreview(file) },
            onPlayVideo = { file -> openVideoPreview(file) },
            onExport = { file -> prepareExport(file) },
            onDeleteFile = { file -> confirmDelete(file) },
            onSelectionChanged = { refreshAlbumToolbarUi() },
        )
        binding.fileList.adapter = albumBrowseAdapter
        ViewCompat.setAccessibilityPaneTitle(binding.folderList, getString(R.string.a11y_pane_folder_list))
        ViewCompat.setAccessibilityPaneTitle(binding.fileList, getString(R.string.a11y_pane_album_list))
        ViewCompat.setAccessibilityPaneTitle(binding.statusCard, getString(R.string.a11y_pane_status))
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.main_album_menu, menu)
                }

                override fun onPrepareMenu(menu: Menu) {
                    if (!::albumBrowseAdapter.isInitialized || !::folderAdapter.isInitialized) return
                    val unlocked = SessionGate.isUnlocked
                    val inAlbum = unlocked && browseMode == BrowseMode.ALBUM_CONTENT
                    val inLibrary = unlocked && browseMode == BrowseMode.FOLDER_BROWSER
                    val albumSel = inAlbum && albumBrowseAdapter.isSelectionActive()
                    val libSel = inLibrary && folderAdapter.isSelectionActive()
                    val nAlbumTotal = albumBrowseAdapter.totalSelectedCount()
                    val nAlbumFiles = albumBrowseAdapter.getSelectedVaultFiles().size
                    val nAlbumFolders = albumBrowseAdapter.getSelectedSubfolders().size
                    val nLib = folderAdapter.totalSelectedCount()
                    menu.findItem(R.id.menu_select_items)?.apply {
                        isVisible = (inAlbum && !albumSel) || (inLibrary && !libSel)
                        isEnabled = when {
                            inAlbum -> albumBrowseAdapter.browseItemCount() > 0
                            inLibrary -> folderAdapter.itemCount > 0
                            else -> false
                        }
                    }
                    menu.findItem(R.id.menu_select_all)?.apply {
                        isVisible = (inAlbum && albumSel) || (inLibrary && libSel)
                        isEnabled = when {
                            inAlbum -> albumBrowseAdapter.browseItemCount() > 0
                            inLibrary -> folderAdapter.itemCount > 0
                            else -> false
                        }
                    }
                    val bulkAlbum = inAlbum && albumSel
                    val bulkLib = inLibrary && libSel
                    val bulk = bulkAlbum || bulkLib
                    val canDelete = when {
                        bulkLib -> nLib > 0
                        bulkAlbum -> nAlbumTotal > 0
                        else -> false
                    }
                    val canExportFiles = bulkAlbum && nAlbumFiles > 0
                    val canMoveCopy = (bulkAlbum && (nAlbumFiles > 0 || nAlbumFolders > 0)) || (bulkLib && nLib > 0)
                    menu.findItem(R.id.menu_save_selected)?.apply {
                        isVisible = bulk
                        isEnabled = canExportFiles
                        title = if (nAlbumFiles > 0) {
                            getString(R.string.save_copies_count, nAlbumFiles)
                        } else {
                            getString(R.string.save_copies)
                        }
                    }
                    menu.findItem(R.id.menu_delete_selected)?.apply {
                        isVisible = bulk
                        isEnabled = canDelete
                        title = when {
                            bulkLib && nLib > 0 -> getString(R.string.bulk_delete_count, nLib)
                            bulkAlbum && nAlbumTotal > 0 -> getString(R.string.bulk_delete_count, nAlbumTotal)
                            else -> getString(R.string.bulk_delete)
                        }
                    }
                    menu.findItem(R.id.menu_move_selected)?.apply {
                        isVisible = bulk
                        isEnabled = canMoveCopy
                    }
                    menu.findItem(R.id.menu_copy_selected)?.apply {
                        isVisible = bulk
                        isEnabled = canMoveCopy
                    }
                    menu.findItem(R.id.menu_done_selection)?.apply {
                        isVisible = bulk
                        isEnabled = true
                    }
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    if (!::albumBrowseAdapter.isInitialized || !::folderAdapter.isInitialized) return false
                    when (menuItem.itemId) {
                        R.id.menu_select_items -> {
                            when (browseMode) {
                                BrowseMode.FOLDER_BROWSER -> folderAdapter.setSelectionActive(true)
                                BrowseMode.ALBUM_CONTENT -> albumBrowseAdapter.setSelectionActive(true)
                            }
                            refreshAlbumToolbarUi()
                            return true
                        }
                        R.id.menu_select_all -> {
                            when (browseMode) {
                                BrowseMode.FOLDER_BROWSER -> folderAdapter.selectAllVisible()
                                BrowseMode.ALBUM_CONTENT -> albumBrowseAdapter.selectAllVisible()
                            }
                            refreshAlbumToolbarUi()
                            return true
                        }
                        R.id.menu_save_selected -> {
                            when (browseMode) {
                                BrowseMode.FOLDER_BROWSER ->
                                    showAppSnackbar(getString(R.string.bulk_files_only_export))
                                BrowseMode.ALBUM_CONTENT -> {
                                    val sel = albumBrowseAdapter.getSelectedVaultFiles()
                                    if (sel.isNotEmpty()) {
                                        prepareBatchExport(sel)
                                    } else {
                                        showAppSnackbar(getString(R.string.bulk_files_only_export))
                                    }
                                }
                            }
                            return true
                        }
                        R.id.menu_delete_selected -> {
                            when (browseMode) {
                                BrowseMode.FOLDER_BROWSER -> {
                                    val dirs = folderAdapter.getSelectedAlbumDirs()
                                    if (dirs.isNotEmpty()) confirmBulkDelete(emptyList(), dirs)
                                }
                                BrowseMode.ALBUM_CONTENT -> {
                                    val files = albumBrowseAdapter.getSelectedVaultFiles()
                                    val dirs = albumBrowseAdapter.getSelectedSubfolders()
                                    if (files.isNotEmpty() || dirs.isNotEmpty()) confirmBulkDelete(files, dirs)
                                }
                            }
                            return true
                        }
                        R.id.menu_move_selected -> {
                            when (browseMode) {
                                BrowseMode.FOLDER_BROWSER -> {
                                    val dirs = folderAdapter.getSelectedAlbumDirs()
                                    if (dirs.isNotEmpty()) promptBulkMoveOrCopyToAlbum(emptyList(), dirs, move = true)
                                }
                                BrowseMode.ALBUM_CONTENT -> {
                                    val files = albumBrowseAdapter.getSelectedVaultFiles()
                                    val dirs = albumBrowseAdapter.getSelectedSubfolders()
                                    if (files.isNotEmpty() || dirs.isNotEmpty()) {
                                        promptBulkMoveOrCopyToAlbum(files, dirs, move = true)
                                    }
                                }
                            }
                            return true
                        }
                        R.id.menu_copy_selected -> {
                            when (browseMode) {
                                BrowseMode.FOLDER_BROWSER -> {
                                    val dirs = folderAdapter.getSelectedAlbumDirs()
                                    if (dirs.isNotEmpty()) promptBulkMoveOrCopyToAlbum(emptyList(), dirs, move = false)
                                }
                                BrowseMode.ALBUM_CONTENT -> {
                                    val files = albumBrowseAdapter.getSelectedVaultFiles()
                                    val dirs = albumBrowseAdapter.getSelectedSubfolders()
                                    if (files.isNotEmpty() || dirs.isNotEmpty()) {
                                        promptBulkMoveOrCopyToAlbum(files, dirs, move = false)
                                    }
                                }
                            }
                            return true
                        }
                        R.id.menu_done_selection -> {
                            when (browseMode) {
                                BrowseMode.FOLDER_BROWSER -> folderAdapter.clearSelection()
                                BrowseMode.ALBUM_CONTENT -> albumBrowseAdapter.clearSelection()
                            }
                            refreshAlbumToolbarUi()
                            return true
                        }
                    }
                    return false
                }
            },
            this,
            Lifecycle.State.STARTED,
        )
        applyViewMode(currentViewMode)
        binding.viewModeToggle.check(binding.gridModeButton.id)
        binding.viewModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == binding.listModeButton.id) {
                AlbumBrowseAdapter.ViewMode.LIST
            } else {
                AlbumBrowseAdapter.ViewMode.GRID
            }
            applyViewMode(mode)
        }

        binding.newAlbumButton.setOnClickListener { promptCreateAlbum() }
        binding.newSubfolderButton.setOnClickListener { promptCreateSubfolder() }

        applyBrowseLayout()
        if (browseMode == BrowseMode.FOLDER_BROWSER) {
            refreshFolderBrowser()
        } else {
            refreshAlbumFiles()
        }

        binding.unlockButton.setOnClickListener { showSessionUnlockPrompt() }

        binding.importButton.setOnClickListener {
            if (!SessionGate.isUnlocked) {
                Toast.makeText(this, R.string.unlock_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (browseMode != BrowseMode.ALBUM_CONTENT) {
                Toast.makeText(this, R.string.open_folder_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!canUseBiometricOrDeviceCredential()) {
                showAppSnackbar(getString(R.string.error_screen_lock_setup), longDuration = true)
                return@setOnClickListener
            }
            SessionGate.allowExternalFlowFor(EXTERNAL_FLOW_GRACE_MS)
            showImportChooserDialog()
        }

        updateLockUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_BROWSE_MODE, browseMode.name)
        outState.putString(STATE_ALBUM_SUB, albumSubPath)
    }

    override fun onResume() {
        super.onResume()
        updateLockUi()
    }

    private fun updateLockUi() {
        val unlocked = SessionGate.isUnlocked
        binding.lockOverlay.isVisible = !unlocked
        setMainChromeVisible(unlocked)
        if (!unlocked && ::folderAdapter.isInitialized) folderAdapter.clearSelection()
        albumBrowseAdapter.setVaultUnlocked(unlocked)
        binding.importButton.isEnabled = unlocked && browseMode == BrowseMode.ALBUM_CONTENT
        binding.newSubfolderButton.isEnabled = unlocked && browseMode == BrowseMode.ALBUM_CONTENT
        binding.newAlbumButton.isEnabled = unlocked && browseMode == BrowseMode.FOLDER_BROWSER
        binding.viewModeToggle.isEnabled = unlocked && browseMode == BrowseMode.ALBUM_CONTENT
        binding.listModeButton.isEnabled = unlocked && browseMode == BrowseMode.ALBUM_CONTENT
        binding.gridModeButton.isEnabled = unlocked && browseMode == BrowseMode.ALBUM_CONTENT
        refreshStatusText()
        refreshAlbumToolbarUi()
    }

    private fun refreshToolbarSubtitle() {
        if (!SessionGate.isUnlocked) {
            binding.topToolbar.subtitle = ""
            return
        }
        binding.topToolbar.subtitle = when {
            browseMode == BrowseMode.ALBUM_CONTENT && albumBrowseAdapter.isSelectionActive() ->
                getString(R.string.bulk_selected_count, albumBrowseAdapter.totalSelectedCount())
            browseMode == BrowseMode.FOLDER_BROWSER && ::folderAdapter.isInitialized &&
                folderAdapter.isSelectionActive() ->
                getString(R.string.bulk_selected_count, folderAdapter.totalSelectedCount())
            else -> getString(R.string.alpha_tagline)
        }
    }

    private fun refreshAlbumToolbarUi() {
        if (browseMode == BrowseMode.FOLDER_BROWSER && ::folderAdapter.isInitialized) {
            supportActionBar?.setDisplayHomeAsUpEnabled(folderAdapter.isSelectionActive())
        }
        if (::folderAdapter.isInitialized) {
            backPressedCallback.isEnabled =
                browseMode == BrowseMode.ALBUM_CONTENT ||
                    (browseMode == BrowseMode.FOLDER_BROWSER && folderAdapter.isSelectionActive())
        } else {
            backPressedCallback.isEnabled = browseMode == BrowseMode.ALBUM_CONTENT
        }
        refreshToolbarSubtitle()
        invalidateMenu()
    }

    private fun showAppSnackbar(message: CharSequence, longDuration: Boolean = false) {
        val snack = Snackbar.make(
            binding.mainCoordinator,
            message,
            if (longDuration) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT,
        )
        snack.setBackgroundTint(getColor(R.color.alpha_surface_variant))
        snack.setTextColor(getColor(R.color.alpha_on_surface))
        snack.setActionTextColor(getColor(R.color.alpha_gold))
        when {
            binding.viewModeToggle.isVisible -> snack.setAnchorView(binding.viewModeToggle)
            binding.importButton.isVisible -> snack.setAnchorView(binding.importButton)
        }
        snack.show()
    }

    private fun setMainChromeVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.statusCard.visibility = v
        binding.importButton.visibility = v
        if (visible) {
            applyBrowseLayout()
        } else {
            binding.libraryHeaderRow.visibility = View.GONE
            binding.browserFrame.visibility = View.GONE
            binding.viewModeToggle.visibility = View.GONE
            binding.albumSecondaryBlock.visibility = View.GONE
        }
    }

    private fun applyBrowseLayout() {
        when (browseMode) {
            BrowseMode.FOLDER_BROWSER -> {
                binding.libraryHeaderRow.visibility = View.VISIBLE
                binding.browserFrame.visibility = View.VISIBLE
                binding.folderList.visibility = View.VISIBLE
                binding.fileList.visibility = View.GONE
                binding.viewModeToggle.visibility = View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
                supportActionBar?.title = getString(R.string.app_name)
            }
            BrowseMode.ALBUM_CONTENT -> {
                binding.libraryHeaderRow.visibility = View.GONE
                binding.browserFrame.visibility = View.VISIBLE
                binding.folderList.visibility = View.GONE
                binding.fileList.visibility = View.VISIBLE
                binding.viewModeToggle.visibility = View.VISIBLE
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                updateAlbumToolbarTitle()
            }
        }
        refreshAlbumActionsHint()
        refreshAlbumToolbarUi()
    }

    private fun openAlbum(album: AlbumFolderUi) {
        folderAdapter.clearSelection()
        albumBrowseAdapter.clearSelection()
        currentAlbumName = album.name
        albumSubPath = ""
        browseMode = BrowseMode.ALBUM_CONTENT
        applyBrowseLayout()
        updateLockUi()
        refreshAlbumFiles()
    }

    private fun exitAlbumFolder() {
        albumBrowseAdapter.clearSelection()
        albumSubPath = ""
        browseMode = BrowseMode.FOLDER_BROWSER
        applyBrowseLayout()
        updateLockUi()
        refreshFolderBrowser()
    }

    private fun refreshFolderBrowser() {
        ensureVaultLayout()
        val dirs = albumsRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val items = dirs.map { dir ->
            val n = dir.name
            val count = dir.walkTopDown().count { it.isFile && it.name.endsWith(".vault", ignoreCase = true) }
            AlbumFolderUi(name = n, itemCount = count, directory = dir)
        }
        folderAdapter.submitAlbums(items)
        refreshStatusText()
    }

    private fun refreshAlbumFiles() {
        ensureVaultLayout()
        if (currentAlbumName.isBlank()) currentAlbumName = DEFAULT_ALBUM
        val browse = currentAlbumBrowseDir()
        val subdirs = browse.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val rows = subdirs.map { dir ->
            val count = dir.walkTopDown().count { it.isFile && it.name.endsWith(".vault", ignoreCase = true) }
            AlbumFolderUi(name = dir.name, itemCount = count, directory = dir)
        }
        allVaultFiles = browse.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".vault", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        albumBrowseAdapter.submitBrowse(rows, allVaultFiles)
        updateAlbumToolbarTitle()
        refreshStatusText()
    }

    private fun navigateAlbumUp(): Boolean {
        if (albumSubPath.isEmpty()) return false
        val idx = albumSubPath.lastIndexOf('/')
        albumSubPath = if (idx < 0) "" else albumSubPath.substring(0, idx)
        return true
    }

    private fun updateAlbumToolbarTitle() {
        if (browseMode != BrowseMode.ALBUM_CONTENT) return
        supportActionBar?.title = if (albumSubPath.isEmpty()) {
            currentAlbumName
        } else {
            "$currentAlbumName · ${albumSubPath.replace("/", " · ")}"
        }
    }

    private fun ensureVaultLayout() {
        albumsRoot.mkdirs()
        File(albumsRoot, DEFAULT_ALBUM).mkdirs()
        vaultDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".vault", ignoreCase = true)) {
                val dest = File(File(albumsRoot, DEFAULT_ALBUM), f.name)
                if (!dest.exists()) {
                    f.renameTo(dest)
                } else {
                    f.delete()
                }
            }
        }
    }

    private fun promptCreateAlbum() {
        if (!SessionGate.isUnlocked) {
            Toast.makeText(this, R.string.unlock_first, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.new_album_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_album_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString().orEmpty()
                val name = sanitizeAlbumName(raw)
                if (name.isBlank()) {
                    showAppSnackbar(getString(R.string.error_enter_album_name))
                    return@setPositiveButton
                }
                val dir = File(albumsRoot, name)
                if (dir.exists()) {
                    showAppSnackbar(getString(R.string.error_album_exists))
                    return@setPositiveButton
                }
                dir.mkdirs()
                refreshFolderBrowser()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sanitizeAlbumName(name: String): String {
        val trimmed = name.trim().ifEmpty { return "" }
        return trimmed.replace(Regex("""[\\/:*?"<>|.]"""), "_").take(48)
    }

    /** Single path segment under the current album; no nested slashes. */
    private fun sanitizeSubfolderName(raw: String): String {
        val oneLine = raw.trim().replace('\\', '/').trim('/').ifEmpty { return "" }
        val segment = oneLine.substringAfterLast('/').trim().trimEnd('.')
        if (segment.isEmpty() || segment == "." || segment == "..") return ""
        return segment.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80).trim().trimEnd('.')
    }

    private fun promptCreateSubfolder() {
        if (!SessionGate.isUnlocked) {
            Toast.makeText(this, R.string.unlock_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (browseMode != BrowseMode.ALBUM_CONTENT) return
        val input = EditText(this).apply {
            hint = getString(R.string.new_subfolder_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_subfolder_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = sanitizeSubfolderName(input.text?.toString().orEmpty())
                if (name.isBlank()) {
                    showAppSnackbar(getString(R.string.error_enter_subfolder_name))
                    return@setPositiveButton
                }
                val parent = currentAlbumBrowseDir()
                val dir = File(parent, name)
                if (dir.exists()) {
                    showAppSnackbar(getString(R.string.error_subfolder_exists))
                    return@setPositiveButton
                }
                dir.mkdirs()
                refreshAlbumFiles()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshStatusText() {
        if (!SessionGate.isUnlocked) {
            binding.statusText.text = getString(R.string.vault_locked_status)
            return
        }
        binding.statusText.text = when (browseMode) {
            BrowseMode.FOLDER_BROWSER -> getString(R.string.library_hint)
            BrowseMode.ALBUM_CONTENT -> {
                val subCount = currentAlbumBrowseDir().listFiles()?.count { it.isDirectory } ?: 0
                when {
                    allVaultFiles.isEmpty() && subCount == 0 -> getString(R.string.status_empty_album_ready)
                    allVaultFiles.isEmpty() -> getString(R.string.album_subfolders_only_hint)
                    else -> getString(R.string.status_ready_short)
                }
            }
        }
        refreshAlbumActionsHint()
    }

    private fun refreshAlbumActionsHint() {
        val inAlbum = browseMode == BrowseMode.ALBUM_CONTENT && SessionGate.isUnlocked
        if (!inAlbum) {
            binding.albumSecondaryBlock.visibility = View.GONE
            return
        }
        binding.albumSecondaryBlock.visibility = View.VISIBLE
        binding.albumActionsHint.text = if (allVaultFiles.isEmpty()) {
            getString(R.string.album_empty_hint)
        } else {
            getString(R.string.album_menu_hint)
        }
    }

    private fun applyViewMode(mode: AlbumBrowseAdapter.ViewMode) {
        currentViewMode = mode
        albumBrowseAdapter.setViewMode(mode)
        binding.fileList.layoutManager = when (mode) {
            AlbumBrowseAdapter.ViewMode.LIST -> LinearLayoutManager(this)
            AlbumBrowseAdapter.ViewMode.GRID -> GridLayoutManager(this, 2)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use null
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i < 0) null else c.getString(i)
            }
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().ifEmpty { "file" }
        return trimmed.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
    }

    /** When the picker gives a name without an extension, infer one so vault files keep type (image/video). */
    private fun extensionForMime(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        return when {
            mime.equals("video/mp4", ignoreCase = true) ||
                mime.equals("video/mpeg", ignoreCase = true) ||
                mime.equals("video/quicktime", ignoreCase = true) -> "mp4"
            mime.equals("video/webm", ignoreCase = true) -> "webm"
            mime.equals("video/3gpp", ignoreCase = true) -> "3gp"
            mime.startsWith("video/", ignoreCase = true) ->
                mime.substringAfterLast('/').take(12).lowercase().ifEmpty { "bin" }
            mime.equals("image/jpeg", ignoreCase = true) -> "jpg"
            mime.equals("image/png", ignoreCase = true) -> "png"
            mime.equals("image/webp", ignoreCase = true) -> "webp"
            mime.equals("image/gif", ignoreCase = true) -> "gif"
            mime.startsWith("image/", ignoreCase = true) -> "jpg"
            mime.equals("application/pdf", ignoreCase = true) -> "pdf"
            else -> null
        }
    }

    private fun canUseBiometricOrDeviceCredential(): Boolean {
        val authenticators = allowedAuthenticators()
        val r = BiometricManager.from(this).canAuthenticate(authenticators)
        return r == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun allowedAuthenticators(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
    }

    private fun showSessionUnlockPrompt() {
        if (!canUseBiometricOrDeviceCredential()) {
            showAppSnackbar(getString(R.string.error_screen_lock_setup), longDuration = true)
            return
        }
        // Keystore may allow cipher init without a prompt for AUTH_VALIDITY_SECONDS after the last
        // CryptoObject auth — but sign-in must still ask the user every time.
        // If init fails with user-auth required, BiometricPrompt + CryptoObject authorizes the key
        // (needed for thumbnails / decrypt in the background).
        val cipher = VaultCrypto.newCipher()
        try {
            VaultCrypto.initEncryptCipher(cipher)
            showBiometricIdentityOnly(
                title = getString(R.string.unlock_sign_in),
                subtitle = getString(R.string.unlock_sign_in_sub),
                onSuccess = {
                    SessionGate.unlock()
                    updateLockUi()
                    showAppSnackbar(getString(R.string.unlock_success))
                },
            )
            return
        } catch (e: Exception) {
            if (handleKeystoreInvalidated(e)) return
            if (!e.isUserAuthRequired()) {
                showAppSnackbar(e.message ?: getString(R.string.error_unlock_failed), longDuration = true)
                return
            }
        }
        showBiometric(
            title = getString(R.string.unlock_sign_in),
            subtitle = getString(R.string.unlock_sign_in_sub),
            cipher = cipher,
            onSuccess = {
                SessionGate.unlock()
                updateLockUi()
                showAppSnackbar(getString(R.string.unlock_success))
            },
            onCancelled = { },
        )
    }

    /** Fingerprint / face / device PIN without binding a Keystore CryptoObject. */
    private fun showBiometricIdentityOnly(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        showAppSnackbar(errString, longDuration = true)
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowedAuthenticators())
            .build()
        prompt.authenticate(info)
    }

    private fun showImportChooserDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_choose_title)
            .setItems(
                arrayOf(
                    getString(R.string.import_pick_files),
                    getString(R.string.import_pick_folder),
                ),
            ) { _, which ->
                SessionGate.allowExternalFlowFor(EXTERNAL_FLOW_GRACE_MS)
                when (which) {
                    0 -> pickMultipleLauncher.launch(arrayOf("*/*"))
                    1 -> openImportTreeLauncher.launch(null)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun enqueueImportsFromUris(uris: List<Uri>) {
        pendingImportQueue.clear()
        for (uri in uris) {
            var disp = queryDisplayName(uri) ?: "imported"
            disp = sanitizeFileName(disp.trim()).ifEmpty { "imported" }
            if (!disp.contains('.')) {
                extensionForMime(contentResolver.getType(uri))?.let { ext ->
                    disp = "$disp.$ext"
                }
            }
            val base = currentAlbumBrowseDir()
            val vaultName = uniqueVaultOutputNameInDir(base, disp)
            pendingImportQueue.addLast(PendingImport(uri, vaultName, base))
        }
        startImportBatchFromQueue()
    }

    private fun enqueuePendingImports(items: List<PendingImport>) {
        pendingImportQueue.clear()
        for (item in items) {
            pendingImportQueue.addLast(item)
        }
        if (pendingImportQueue.isEmpty()) {
            dismissImportProgress()
            return
        }
        importBatchTotal = pendingImportQueue.size
        importBatchDone = 0
        showImportProgressDialog(scanning = false)
        updateImportEncryptProgress(lastFileName = null)
        authenticateForEncrypt()
    }

    private fun startImportBatchFromQueue() {
        if (pendingImportQueue.isEmpty()) return
        importBatchTotal = pendingImportQueue.size
        importBatchDone = 0
        showImportProgressDialog(scanning = false)
        updateImportEncryptProgress(lastFileName = null)
        authenticateForEncrypt()
    }

    private fun showImportProgressDialog(scanning: Boolean) {
        if (importProgressDialog?.isShowing == true) {
            applyImportProgressMode(scanning)
            return
        }
        val v = layoutInflater.inflate(R.layout.dialog_import_progress, null)
        importProgressTitle = v.findViewById(R.id.importProgressTitle)
        importProgressBar = v.findViewById(R.id.importProgressBar)
        importProgressPercent = v.findViewById(R.id.importProgressPercent)
        importProgressDetail = v.findViewById(R.id.importProgressDetail)
        importProgressDialog = MaterialAlertDialogBuilder(this)
            .setView(v)
            .setCancelable(false)
            .create()
        importProgressDialog?.show()
        applyImportProgressMode(scanning)
    }

    private fun applyImportProgressMode(scanning: Boolean) {
        if (scanning) {
            importProgressTitle?.setText(R.string.import_scanning_title)
            importProgressBar?.isIndeterminate = true
            importProgressPercent?.text = "—"
            importProgressDetail?.text = ""
        } else {
            importProgressTitle?.setText(R.string.import_encrypting_title)
            importProgressBar?.isIndeterminate = false
            importProgressBar?.max = 100
            importProgressBar?.progress = 0
        }
    }

    private fun updateImportScanningProgress(fileCount: Int) {
        importProgressDetail?.text = getString(R.string.import_scanning_found, fileCount)
    }

    private fun updateImportEncryptProgress(lastFileName: String?) {
        if (importBatchTotal <= 0) return
        val pct = ((importBatchDone * 100) / importBatchTotal).coerceIn(0, 100)
        importProgressBar?.isIndeterminate = false
        importProgressBar?.max = 100
        importProgressBar?.progress = pct
        importProgressPercent?.text = getString(R.string.import_progress_percent, pct)
        val line1 = getString(R.string.import_progress_count, importBatchDone, importBatchTotal)
        importProgressDetail?.text = if (lastFileName != null) {
            "$line1\n" + getString(R.string.import_progress_current, lastFileName)
        } else {
            line1
        }
    }

    private fun dismissImportProgress() {
        importProgressDialog?.dismiss()
        importProgressDialog = null
        importProgressTitle = null
        importProgressBar = null
        importProgressPercent = null
        importProgressDetail = null
    }

    private fun uniqueVaultOutputNameInDir(dir: File, stemWithoutVaultExt: String): String {
        dir.mkdirs()
        val stem = if (stemWithoutVaultExt.endsWith(".vault", ignoreCase = true)) {
            stemWithoutVaultExt.substring(0, stemWithoutVaultExt.length - 6)
        } else {
            stemWithoutVaultExt
        }.ifBlank { "file" }
        var candidate = "${sanitizeFileName(stem)}.vault"
        var i = 2
        while (File(dir, candidate).exists()) {
            candidate = "${sanitizeFileName(stem).take(100)}_$i.vault"
            i++
        }
        return candidate
    }

    private fun authenticateForEncrypt() {
        val item = pendingImportQueue.firstOrNull() ?: return
        if (!SessionGate.isUnlocked) {
            Toast.makeText(this, R.string.unlock_first, Toast.LENGTH_SHORT).show()
            clearImportPending()
            return
        }
        val cipher = VaultCrypto.newCipher()
        try {
            VaultCrypto.initEncryptCipher(cipher)
            finishEncrypt(item.uri, item.vaultFileName, item.targetDir, cipher)
            return
        } catch (e: Exception) {
            if (handleKeystoreInvalidated(e)) {
                clearImportPending()
                return
            }
            if (!e.isUserAuthRequired()) {
                showAppSnackbar(e.message ?: getString(R.string.error_crypto_generic), longDuration = true)
                clearImportPending()
                return
            }
        }
        showBiometric(
            title = getString(R.string.add_encrypted_file),
            subtitle = item.vaultFileName,
            cipher = cipher,
            onSuccess = { c -> finishEncrypt(item.uri, item.vaultFileName, item.targetDir, c) },
            onCancelled = { clearImportPending() },
        )
    }

    private fun finishEncrypt(uri: Uri, outName: String, targetDir: File, cipher: Cipher) {
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    targetDir.mkdirs()
                    contentResolver.openInputStream(uri)?.use { input ->
                        File(targetDir, outName).outputStream().use { output ->
                            VaultCrypto.encryptStream(input, output, cipher)
                        }
                    } ?: error("Cannot open input file")
                }.exceptionOrNull()
            }
            if (err != null) {
                showAppSnackbar(
                    getString(R.string.error_encryption_failed, err.message ?: ""),
                    longDuration = true,
                )
                clearImportPending()
                return@launch
            }
            pendingImportQueue.removeFirst()
            importBatchDone++
            updateImportEncryptProgress(lastFileName = outName)
            if (pendingImportQueue.isNotEmpty()) {
                authenticateForEncrypt()
            } else {
                refreshAlbumFiles()
                dismissImportProgress()
                importBatchTotal = 0
                importBatchDone = 0
                showAppSnackbar(getString(R.string.import_finished))
            }
        }
    }

    /** Top-level album names usable as move/copy destinations (excludes current album and, on the library screen, selected albums). */
    private fun destinationsForAlbumMoveOrCopy(): List<String> {
        val exclude = when (browseMode) {
            BrowseMode.FOLDER_BROWSER -> folderAdapter.getSelectedAlbumDirs().map { it.name }.toSet()
            BrowseMode.ALBUM_CONTENT -> setOf(currentAlbumName)
        }
        return albumsRoot.listFiles()
            ?.filter { it.isDirectory && it.name !in exclude }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    private fun uniqueVaultFileNameInDir(dir: File, desiredName: String): String {
        if (!File(dir, desiredName).exists()) return desiredName
        val base = if (desiredName.endsWith(".vault", ignoreCase = true)) {
            desiredName.substring(0, desiredName.length - ".vault".length)
        } else {
            desiredName
        }.ifEmpty { "file" }
        var i = 2
        while (true) {
            val cand = "${sanitizeFileName(base.take(100))}_$i.vault"
            if (!File(dir, cand).exists()) return cand
            i++
        }
    }

    private fun moveVaultToAlbumDir(file: File, destDir: File): Boolean {
        val name = uniqueVaultFileNameInDir(destDir, file.name)
        val dest = File(destDir, name)
        if (file.renameTo(dest)) return true
        return runCatching {
            file.copyTo(dest, overwrite = false)
            file.delete()
        }.isSuccess
    }

    private fun copyVaultToAlbumDir(file: File, destDir: File): Boolean {
        val name = uniqueVaultFileNameInDir(destDir, file.name)
        val dest = File(destDir, name)
        return runCatching { file.copyTo(dest, overwrite = false) }.isSuccess
    }

    private fun uniqueDirNameInParent(parent: File, baseName: String): File {
        var dest = File(parent, baseName)
        var i = 2
        while (dest.exists()) {
            dest = File(parent, "${baseName}_$i")
            i++
        }
        return dest
    }

    private fun moveDirToAlbumDir(sourceDir: File, destAlbumDir: File): Boolean {
        val dest = uniqueDirNameInParent(destAlbumDir, sourceDir.name)
        if (sourceDir.renameTo(dest)) return true
        return runCatching {
            sourceDir.copyRecursively(dest, overwrite = false)
            sourceDir.deleteRecursively()
        }.isSuccess
    }

    private fun copyDirToAlbumDir(sourceDir: File, destAlbumDir: File): Boolean {
        val dest = uniqueDirNameInParent(destAlbumDir, sourceDir.name)
        return runCatching { sourceDir.copyRecursively(dest, overwrite = false) }.isSuccess
    }

    private fun confirmDeleteFolder(dir: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_folder)
            .setMessage(getString(R.string.delete_folder_confirm, dir.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                dir.deleteRecursively()
                albumBrowseAdapter.clearSelection()
                refreshAlbumFiles()
                refreshFolderBrowser()
                refreshAlbumToolbarUi()
                showAppSnackbar(getString(R.string.bulk_remove_done, 1))
            }
            .show()
    }

    private fun confirmBulkDelete(files: List<File>, dirs: List<File>) {
        if (files.isEmpty() && dirs.isEmpty()) return
        val message = when {
            dirs.isEmpty() -> getString(R.string.bulk_delete_confirm, files.size)
            files.isEmpty() -> getString(R.string.bulk_delete_folder_only_confirm, dirs.size)
            else -> getString(R.string.bulk_delete_mixed_confirm, files.size, dirs.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bulk_delete)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                var okFiles = 0
                var okDirs = 0
                files.forEach { if (it.delete()) okFiles++ }
                dirs.forEach { if (it.deleteRecursively()) okDirs++ }
                if (dirs.any { it.parentFile == albumsRoot && it.name == currentAlbumName }) {
                    currentAlbumName = DEFAULT_ALBUM
                }
                albumBrowseAdapter.clearSelection()
                if (::folderAdapter.isInitialized) folderAdapter.clearSelection()
                refreshAlbumFiles()
                refreshFolderBrowser()
                refreshAlbumToolbarUi()
                val msgDone = when {
                    dirs.isEmpty() -> getString(R.string.bulk_remove_done, okFiles)
                    files.isEmpty() -> getString(R.string.bulk_remove_done, okDirs)
                    else -> getString(R.string.bulk_remove_done_mixed, okFiles + okDirs, okFiles, okDirs)
                }
                showAppSnackbar(msgDone)
            }
            .show()
    }

    private fun promptBulkMoveOrCopyToAlbum(files: List<File>, dirs: List<File>, move: Boolean) {
        if (files.isEmpty() && dirs.isEmpty()) return
        val others = destinationsForAlbumMoveOrCopy()
        if (others.isEmpty()) {
            showAppSnackbar(getString(R.string.bulk_no_other_folders), longDuration = true)
            return
        }
        val title = if (move) R.string.bulk_pick_album_move else R.string.bulk_pick_album_copy
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(others.toTypedArray()) { _, which ->
                val target = others[which]
                val destDir = File(albumsRoot, target).apply { mkdirs() }
                var ok = 0
                files.forEach { f ->
                    if (move) {
                        if (moveVaultToAlbumDir(f, destDir)) ok++
                    } else {
                        if (copyVaultToAlbumDir(f, destDir)) ok++
                    }
                }
                dirs.forEach { d ->
                    if (move) {
                        if (moveDirToAlbumDir(d, destDir)) ok++
                    } else {
                        if (copyDirToAlbumDir(d, destDir)) ok++
                    }
                }
                albumBrowseAdapter.clearSelection()
                if (::folderAdapter.isInitialized) folderAdapter.clearSelection()
                refreshAlbumFiles()
                refreshFolderBrowser()
                refreshAlbumToolbarUi()
                val msg = if (move) {
                    getString(R.string.bulk_move_done, ok)
                } else {
                    getString(R.string.bulk_copy_done, ok)
                }
                showAppSnackbar(msg)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun prepareBatchExport(files: List<File>) {
        if (!SessionGate.isUnlocked) {
            Toast.makeText(this, R.string.unlock_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!canUseBiometricOrDeviceCredential()) {
            showAppSnackbar(getString(R.string.settings_biometrics_required), longDuration = true)
            return
        }
        pendingBatchExportFiles = files
        SessionGate.allowExternalFlowFor(EXTERNAL_FLOW_GRACE_MS)
        exportFolderLauncher.launch(null)
    }

    private fun warmDecryptAuthExportToTree(treeUri: Uri, files: List<File>) {
        val first = files.first()
        val cipher = VaultCrypto.newCipher()
        try {
            VaultIo.tryInitDecryptCipher(first, cipher)
            runBatchDecryptToTree(treeUri, files)
        } catch (e: Exception) {
            if (handleKeystoreInvalidated(e)) return
            if (!e.isUserAuthRequired()) {
                showAppSnackbar(e.message ?: getString(R.string.error_export_failed), longDuration = true)
                return
            }
            showBiometric(
                title = getString(R.string.export_decrypt),
                subtitle = getString(R.string.export_pick_folder_sub),
                cipher = cipher,
                onSuccess = { runBatchDecryptToTree(treeUri, files) },
                onCancelled = { },
            )
        }
    }

    private fun runBatchDecryptToTree(treeUri: Uri, files: List<File>) {
        lifecycleScope.launch {
            val parentDocumentUri = withContext(Dispatchers.IO) {
                val parentId = DocumentsContract.getTreeDocumentId(treeUri)
                DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            }
            val results = withContext(Dispatchers.IO) {
                var o = 0
                var f = 0
                val resolver = contentResolver
                for (vault in files) {
                    val baseName = if (vault.name.endsWith(".vault", ignoreCase = true)) {
                        vault.name.substring(0, vault.name.length - 6)
                    } else {
                        vault.name
                    }.ifEmpty { "file" }
                    val mime = guessMimeForExport(baseName)
                    val childUri = createUniqueExportDocumentUri(resolver, parentDocumentUri, mime, baseName)
                    if (childUri == null) {
                        f++
                        continue
                    }
                    val r = runCatching {
                        resolver.openOutputStream(childUri)?.use { output ->
                            vault.inputStream().use { input ->
                                val iv = ByteArray(12)
                                if (input.read(iv) != 12) error("Invalid vault file")
                                val c = VaultCrypto.newCipher()
                                VaultCrypto.initDecryptCipher(c, iv)
                                VaultCrypto.decryptStream(input, output, c)
                            }
                        } ?: error("Could not open export destination")
                    }
                    if (r.isSuccess) o++ else f++
                }
                o to f
            }
            val ok = results.first
            showAppSnackbar(
                getString(R.string.export_batch_done, ok, files.size),
                longDuration = true,
            )
            albumBrowseAdapter.clearSelection()
            refreshAlbumToolbarUi()
        }
    }

    /** Creates a new document under a SAF tree folder; tries alternate names if the name exists. */
    private fun createUniqueExportDocumentUri(
        resolver: android.content.ContentResolver,
        parentDocumentUri: Uri,
        mime: String,
        baseName: String,
    ): Uri? {
        val clean = sanitizeFileName(baseName).ifEmpty { "file" }
        var name = clean
        var attempt = 0
        while (attempt < 5000) {
            val created = DocumentsContract.createDocument(resolver, parentDocumentUri, mime, name)
            if (created != null) return created
            attempt++
            val dot = clean.lastIndexOf('.')
            name = if (dot > 0) {
                "${clean.substring(0, dot)}_${attempt + 1}${clean.substring(dot)}"
            } else {
                "${clean}_${attempt + 1}"
            }
        }
        return null
    }

    private fun guessMimeForExport(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun prepareExport(file: File) {
        if (!SessionGate.isUnlocked) {
            Toast.makeText(this, R.string.unlock_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!canUseBiometricOrDeviceCredential()) {
            showAppSnackbar(getString(R.string.settings_biometrics_required), longDuration = true)
            return
        }
        val suggested = file.name.removeSuffix(".vault").ifEmpty { "decrypted.bin" }
        pendingExportFile = file
        pendingExportSuggestedName = suggested
        SessionGate.allowExternalFlowFor(EXTERNAL_FLOW_GRACE_MS)
        createDocumentLauncher.launch(suggested)
    }

    private fun openImagePreview(file: File) {
        if (!SessionGate.isUnlocked) {
            Toast.makeText(this, R.string.unlock_first, Toast.LENGTH_SHORT).show()
            return
        }
        decryptVaultFileToTemp(
            file = file,
            title = getString(R.string.preview_image),
            subtitle = file.name,
            onCancelled = { },
        ) { tempFile ->
            startActivity(
                Intent(this, AlphaImageActivity::class.java)
                    .putExtra(AlphaImageActivity.EXTRA_PATH, tempFile.absolutePath)
                    .putExtra(AlphaImageActivity.EXTRA_DISPLAY_NAME, VaultMedia.displayName(file)),
            )
        }
    }

    private fun openVideoPreview(file: File) {
        if (!SessionGate.isUnlocked) {
            Toast.makeText(this, R.string.unlock_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!canUseBiometricOrDeviceCredential()) {
            showAppSnackbar(getString(R.string.settings_biometrics_required), longDuration = true)
            return
        }
        SessionGate.allowExternalFlowFor(EXTERNAL_FLOW_GRACE_MS)
        startActivity(
            Intent(this, AlphaVideoActivity::class.java)
                .putExtra(AlphaVideoActivity.EXTRA_VAULT_PATH, file.absolutePath)
                .putExtra(AlphaVideoActivity.EXTRA_DISPLAY_NAME, VaultMedia.displayName(file)),
        )
    }

    private fun openFromGrid(file: File) {
        when (VaultMedia.kindForVaultFile(file)) {
            VaultMediaKind.IMAGE -> openImagePreview(file)
            VaultMediaKind.VIDEO -> openVideoPreview(file)
            VaultMediaKind.OTHER -> prepareExport(file)
        }
    }

    private fun decryptVaultFileToTemp(
        file: File,
        title: String,
        subtitle: String,
        onCancelled: () -> Unit,
        consumer: (File) -> Unit,
    ) {
        val cipher = VaultCrypto.newCipher()

        fun performDecryption(c: Cipher) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        PreviewCache.dir(this@MainActivity).mkdirs()
                        val inner = VaultMedia.displayName(file)
                        val out = File(PreviewCache.dir(this@MainActivity), "${UUID.randomUUID()}_$inner")
                        VaultIo.decryptVaultFileToPlainFile(file, out, c)
                        out
                    }
                }
                result.exceptionOrNull()?.let { e ->
                    showAppSnackbar(
                        getString(R.string.error_decrypt_failed, e.message ?: ""),
                        longDuration = true,
                    )
                    return@launch
                }
                val out = result.getOrNull() ?: return@launch
                if (isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    out.delete()
                    return@launch
                }
                consumer(out)
            }
        }

        try {
            VaultIo.tryInitDecryptCipher(file, cipher)
            performDecryption(cipher)
            return
        } catch (e: Exception) {
            if (handleKeystoreInvalidated(e)) return
            if (!e.isUserAuthRequired()) {
                showAppSnackbar(e.message ?: getString(R.string.error_decrypt_generic), longDuration = true)
                return
            }
        }

        showBiometric(
            title = title,
            subtitle = subtitle,
            cipher = cipher,
            onSuccess = { c -> performDecryption(c) },
            onCancelled = onCancelled,
        )
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(file.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                file.delete()
                if (browseMode == BrowseMode.ALBUM_CONTENT) {
                    refreshAlbumFiles()
                } else {
                    refreshFolderBrowser()
                }
            }
            .show()
    }

    private fun showBiometric(
        title: String,
        subtitle: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onCancelled: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c == null) {
                    showAppSnackbar(getString(R.string.error_internal_auth))
                    onCancelled()
                    return
                }
                onSuccess(c)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    showAppSnackbar(errString, longDuration = true)
                }
                onCancelled()
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowedAuthenticators())
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun clearImportPending() {
        pendingImportQueue.clear()
        importBatchTotal = 0
        importBatchDone = 0
        dismissImportProgress()
    }

    private fun handleKeystoreInvalidated(e: Throwable): Boolean {
        val invalidated = e is KeyPermanentlyInvalidatedException ||
            generateSequence(e as Throwable?) { it.cause }
                .any { it is KeyPermanentlyInvalidatedException }
        if (invalidated) {
            AlertDialog.Builder(this)
                .setTitle("Vault key reset")
                .setMessage(
                    "Biometric enrollment changed or the hardware key was invalidated. " +
                        "Old vault files can no longer be decrypted. Delete .vault files and import again.",
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return true
        }
        return false
    }
}
