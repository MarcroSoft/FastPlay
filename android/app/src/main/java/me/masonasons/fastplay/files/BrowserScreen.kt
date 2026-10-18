package me.masonasons.fastplay.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.R
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.ui.Dialogs
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen
import me.masonasons.fastplay.ui.PlayerScreen
import me.masonasons.fastplay.ui.RowAction
import me.masonasons.fastplay.ui.ToolbarItem
import me.masonasons.fastplay.ui.dp
import me.masonasons.fastplay.ui.secondaryText
import java.io.File
import java.text.Collator
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.coroutines.resume

/**
 * A folder anywhere FastPlay reaches: this device's own files, Dropbox, an FTP,
 * SFTP or SMB server. It lists the folders in it and the files FastPlay plays.
 *
 * Choosing a file plays it, with the files after it in the folder; choosing a
 * folder opens it. Each row's actions are TalkBack actions and in the menu a
 * long press brings up: Cut, Copy, Paste Into Folder, Rename, Share, Add to
 * Favorites, Properties, Select and Delete, and from elsewhere than this device,
 * Download and Sync to This Device. Select puts the list in select mode, where
 * the bar at the bottom acts on everything selected.
 *
 * Pasting goes between any two places: from a server to Dropbox, from this
 * device to a server and so on (FileOperations), asking what to do with names
 * already taken.
 */
class BrowserScreen() : ListScreen() {
    /** What the list is ordered by: one choice for every folder, kept between runs. */
    private enum class SortKey(val title: String) {
        NAME("Name"), DATE("Date Modified"), SIZE("Size"), TYPE("Type");

        companion object {
            var current: SortKey
                get() = entries.getOrNull(Engine.prefs().getInt("FilesSortKey", 0)) ?: NAME
                set(value) = Engine.prefs().edit().putInt("FilesSortKey", value.ordinal).apply()

            var reversed: Boolean
                get() = Engine.prefs().getBoolean("FilesSortReversed", false)
                set(value) = Engine.prefs().edit().putBoolean("FilesSortReversed", value).apply()
        }
    }

    lateinit var source: FileSource
        private set
    lateinit var folder: FileEntry
        private set
    private var isTop = false
    private var entries: List<FileEntry> = emptyList()
    private var hasLoaded = false
    private var loading: Job? = null
    /** What shows in place of the list (loading, nothing here, an error), or null. */
    private var status: String? = null
    private var statusView: TextView? = null
    /** This device's files cannot be read until FastPlay is given access. */
    private var needsAccess = false
    private var askedForAccess = false
    private var selecting = false
    /** The paths selected. */
    private val selected = mutableSetOf<String>()
    /** Where a range selection starts: the row last selected. */
    private var anchor: Int? = null

    /** A source's top folder, or [path] in it. */
    constructor(source: FileSource, path: String? = null, name: String? = null) : this() {
        val place = path ?: source.rootPath
        val top = place == source.rootPath
        val shown = name ?: if (top) source.title else place.trimEnd('/').substringAfterLast('/')
        setUp(source, FileEntry(shown, place, place, isFolder = true), top)
    }

    constructor(source: FileSource, folder: FileEntry, isTop: Boolean) : this() {
        setUp(source, folder, isTop)
    }

    private fun setUp(source: FileSource, folder: FileEntry, isTop: Boolean) {
        this.source = source
        this.folder = folder
        this.isTop = isTop
    }

    override val title: String
        get() = if (selecting) selectedText else folder.name

    private val selectedText get() = if (selected.size == 1) "1 selected" else "${selected.size} selected"

    /** The system's picker for files to bring in (the iPhone's document picker). */
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        imported(uris)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val listView = super.onCreateView(inflater, container, savedInstanceState)
        val context = requireContext()
        val label = TextView(context).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(32), context.dp(32), context.dp(32), context.dp(32))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(context.secondaryText())
            setOnClickListener { if (needsAccess) askForAccess() }
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (needsAccess) {
                        info.className = android.widget.Button::class.java.name
                        info.hintText = "Opens the setting to allow it"
                    }
                }
            })
        }
        statusView = label
        applyStatus()
        return FrameLayout(context).apply {
            addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
        }
    }

    override fun onDestroyView() {
        statusView = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (source.isLocal) {
            // This device's files may have changed in another app meanwhile
            if (StorageAccess.granted()) {
                needsAccess = false
                StorageAccess.prepare()
                load()
            } else {
                needsAccess = true
                entries = emptyList()
                show(StorageAccess.reason)
                if (!askedForAccess) {
                    askedForAccess = true
                    askForAccess()
                }
            }
        } else if (!hasLoaded && loading?.isActive != true) {
            load()
        }
    }

    /** Asks for access to the device's files; coming back with it, the list loads (onResume). */
    private fun askForAccess() = StorageAccess.ensure(this) {}

    // ---- Listing ----

    private fun load() {
        if (view == null) return
        if (entries.isEmpty()) show("Loading…")
        loading = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Folders, and the files FastPlay plays
                val listed = source.list(folder.path)
                entries = sorted(listed.filter { it.isFolder || Engine.isPlayableFile(it.name) })
                // What was selected stays so
                selected.retainAll(entries.map { it.path }.toSet())
                show(if (entries.isEmpty()) emptyText else null)
                if (!hasLoaded && !source.isLocal) announce(if (entries.size == 1) "1 item" else "${entries.size} items")
                hasLoaded = true
                if (selecting) selectionChanged(announcing = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entries = emptyList()
                show(FolderSync.describe(e))
            }
        }
    }

    private val emptyText: String
        get() = if (source.isLocal && isTop) "Nothing here yet.\n\nAdd files with the Add button, or with a file manager."
        else "Nothing here that FastPlay plays."

    private fun show(status: String?) {
        this.status = status
        reload()
        applyStatus()
    }

    private fun applyStatus() {
        val label = statusView ?: return
        label.text = status
        label.visibility = if (status == null) View.GONE else View.VISIBLE
        label.isClickable = needsAccess
    }

    /**
     * Folders first whatever the order. Names as a person orders them ("2" before
     * "10"), and the name decides between two of the same date, size or type.
     * Folders have no size or type of their own, so they go by name for those.
     */
    private fun sorted(list: List<FileEntry>): List<FileEntry> {
        val key = SortKey.current
        val reversed = SortKey.reversed
        return list.sortedWith { a, b ->
            if (a.isFolder != b.isFolder) return@sortedWith if (a.isFolder) -1 else 1
            var order = when (key) {
                SortKey.NAME -> 0
                SortKey.DATE -> (a.modified ?: Long.MIN_VALUE).compareTo(b.modified ?: Long.MIN_VALUE)
                SortKey.SIZE -> if (!a.isFolder) a.size.compareTo(b.size) else 0
                SortKey.TYPE -> if (!a.isFolder) collator.compare(extension(a.name), extension(b.name)) else 0
            }
            if (order == 0) order = compareNames(a.name, b.name)
            if (reversed) -order else order
        }
    }

    private fun sortChanged() {
        entries = sorted(entries)
        reload()
        announce("Sorted by ${SortKey.current.title}" + if (SortKey.reversed) ", reversed" else "")
    }

    /** The date or size of an entry, shown (and spoken) while the list is in that order. */
    private fun detail(entry: FileEntry): String? = when (SortKey.current) {
        SortKey.DATE -> entry.modified?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
        SortKey.SIZE -> if (entry.isFolder) null else Formatter.formatFileSize(requireContext(), entry.size)
        SortKey.NAME, SortKey.TYPE -> null
    }

    // ---- The bar at the top ----

    override fun toolbarItems(): List<ToolbarItem> {
        if (selecting) return listOf(ToolbarItem("Done selecting", R.drawable.ic_check) { endSelecting() })
        // Left to right as the iPhone shows them. The menus are made again each
        // time the bar is (refreshChrome), so Paste is there only with something to paste.
        val items = mutableListOf<ToolbarItem>()
        items += ToolbarItem("Play all", R.drawable.ic_play_circle) { playAll() }
        items += ToolbarItem("Sort", R.drawable.ic_sort) { chooseSort() }
        if (source.canWrite) items += ToolbarItem("Add", R.drawable.ic_add, submenu = addMenu())
        items += ToolbarItem("Folder actions", R.drawable.ic_more, submenu = folderMenu())
        return items
    }

    private fun addMenu(): List<ToolbarItem> {
        val items = mutableListOf(
            ToolbarItem("Import Files") { importFiles() },
            ToolbarItem("New Folder") { newFolder() },
        )
        if (FileClipboard.items.isNotEmpty()) items += ToolbarItem(pasteTitle) { paste(folder.path) }
        return items
    }

    /** The menu for the folder shown. */
    private fun folderMenu(): List<ToolbarItem> {
        val items = mutableListOf(ToolbarItem("Select") { startSelecting(null) })
        val favorite = FavoritesStore.find(source, folder.path)
        items += ToolbarItem(if (favorite == null) "Add to Favorites" else "Remove from Favorites") { toggleFavorite(folder) }
        items += ToolbarItem("Properties") { showProperties(folder) }
        if (!source.isLocal) {
            items += ToolbarItem("Download This Folder") { transfer(folder, sync = false) }
            items += ToolbarItem("Sync This Folder") { transfer(folder, sync = true) }
            val autoSyncing = AutoSyncStore.find(source, folder.path) != null
            items += ToolbarItem(if (autoSyncing) "Stop Auto Syncing This Folder" else "Auto Sync This Folder") {
                toggleAutoSync(folder)
            }
        }
        // (The iPhone's list is pulled down to refresh it)
        items += ToolbarItem("Refresh") { load() }
        val account = source.accountAction
        if (isTop && account != null) items += ToolbarItem(account.first, destructive = true) { account.second(this) }
        return items
    }

    /** Sort By, with the order as it is ticked, and Reversed. */
    private fun chooseSort() {
        val context = requireContext()
        lateinit var dialog: AlertDialog
        val keys = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        for (key in SortKey.entries) {
            keys.addView(MaterialRadioButton(context).apply {
                id = View.generateViewId()
                text = key.title
                isChecked = key == SortKey.current
                minHeight = context.dp(48)
                setOnClickListener {
                    dialog.dismiss()
                    SortKey.current = key
                    sortChanged()
                }
            })
        }
        val reverse = MaterialCheckBox(context).apply {
            text = "Reversed"
            isChecked = SortKey.reversed
            minHeight = context.dp(48)
            setOnClickListener {
                dialog.dismiss()
                SortKey.reversed = !SortKey.reversed
                sortChanged()
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(8), context.dp(20), 0)
            addView(keys)
            addView(reverse)
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Sort By")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Playing ----

    private fun playAll() {
        val key = FolderResume.key(source, folder.path)
        val place = if (FolderResume.enabled) FolderResume.place(key) else null
        if (source.isLocal) {
            StorageAccess.ensure(this) {
                Engine.playFolder(folder.path)
                if (Engine.trackCount <= 0) return@ensure
                val files = (0 until Engine.trackCount).map { FolderResume.local(Engine.trackPath(it)) }
                if (place != null) {
                    val at = files.indexOf(place.file)
                    if (at >= 0 && at != Engine.currentTrack) Engine.playTrack(at)
                }
                FolderResume.begin(key, files)
                place?.let { FolderResume.seek(it) }
                openPlayer()
            }
            return
        }
        val files = entries.filter { !it.isFolder }.take(PLAYLIST_LIMIT)
        if (files.isEmpty()) return announce("No files here to play")
        val start = place?.let { place -> files.indexOfFirst { it.path == place.file }.takeIf { it >= 0 } } ?: 0
        announce("Opening ${files[start].name}")
        launch {
            try {
                val urls = files.map { source.streamUrl(it) }
                Engine.playUrls(urls, files.map { it.name }, start)
                FolderResume.begin(key, files.map { it.path })
                if (place != null && files[start].path == place.file) FolderResume.seek(place)
                openPlayer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tell(folder.name, FolderSync.describe(e))
            }
        }
    }

    private fun play(index: Int) {
        val entry = entries[index]
        if (source.isLocal) {
            StorageAccess.ensure(this) {
                // With its folder, the folder plays on in the order it is shown in here
                val isPlaylist = PlaylistText.isPlaylistName(entry.name)
                val files = entries.filter { !it.isFolder }
                val start = files.indexOfFirst { it.path == entry.path }
                if (!isPlaylist && Engine.number("loadFolder") != 0.0 && start >= 0) {
                    Engine.playUrls(files.map { it.path }, emptyList(), start)
                    FolderResume.begin(FolderResume.key(source, folder.path), files.map { FolderResume.local(it.path) })
                } else {
                    Engine.playFile(entry.path)
                }
                openPlayer()
            }
            return
        }
        if (PlaylistText.isPlaylistName(entry.name)) return playRemotePlaylist(entry)
        // The file chosen and those after it in the folder
        val files = entries.drop(index).filter { !it.isFolder }.take(PLAYLIST_LIMIT)
        announce("Opening ${entry.name}")
        launch {
            try {
                val urls = files.map { source.streamUrl(it) }
                Engine.playUrls(urls, files.map { it.name }, 0)
                FolderResume.begin(FolderResume.key(source, folder.path), files.map { it.path })
                openPlayer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tell(folder.name, FolderSync.describe(e))
            }
        }
    }

    /**
     * A playlist file on a server or in Dropbox: its entries, played from there.
     * (Given to the engine as an address, a playlist is taken for a radio station's,
     * and only its first entry plays.) An address plays as it is; a file is looked
     * for under the playlist's folder, by as much of the end of its written path as
     * matches, down to its name alone, since a playlist written on a computer
     * names files by that computer's paths.
     */
    private fun playRemotePlaylist(playlist: FileEntry) {
        announce("Opening ${playlist.name}")
        val copy = File(requireContext().cacheDir, UUID.randomUUID().toString())
        launch {
            try {
                source.download(playlist, copy)
                val text = PlaylistText.decode(withContext(Dispatchers.IO) { copy.readBytes() })
                val written = PlaylistText.entries(text, isPls = playlist.name.lowercase().endsWith(".pls"))
                if (written.isEmpty()) return@launch tell(playlist.name, "The playlist has no entries.")
                // Every file under the folder, found by the end of its path or its name
                val below = source.listAll(folder.path).filter { !it.isFolder }
                val prefix = if (folder.displayPath.isEmpty()) "" else folder.displayPath + "/"
                val byPath = mutableMapOf<String, FileEntry>()
                val byName = mutableMapOf<String, FileEntry>()
                for (file in below) {
                    var relative = file.displayPath
                    if (relative.lowercase().startsWith(prefix.lowercase())) relative = relative.drop(prefix.length)
                    byPath[relative.lowercase()] = file
                    byName[file.name.lowercase()] = file
                }
                val urls = mutableListOf<String>()
                val names = mutableListOf<String>()
                var missing = 0
                for (item in written.take(PLAYLIST_LIMIT)) {
                    if (item.contains("://")) {
                        urls.add(item)
                        names.add(Uri.decode(item.trimEnd('/').substringAfterLast('/')))
                        continue
                    }
                    val parts = item.replace('\\', '/').split('/').filter { it.isNotEmpty() && !it.endsWith(":") }  // a Windows drive
                    var found: FileEntry? = null
                    for (first in parts.indices) {
                        found = byPath[parts.drop(first).joinToString("/").lowercase()]
                        if (found != null) break
                    }
                    if (found == null) parts.lastOrNull()?.let { found = byName[it.lowercase()] }
                    val file = found
                    if (file == null) {
                        missing += 1
                        continue
                    }
                    urls.add(source.streamUrl(file))
                    names.add(file.name)
                }
                if (urls.isEmpty()) return@launch tell(playlist.name, "None of the playlist's files are here.")
                Engine.playUrls(urls, names, 0)
                openPlayer()
                if (missing > 0) {
                    announce(if (missing == 1) "1 file of the playlist is not here" else "$missing files of the playlist are not here")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tell(playlist.name, FolderSync.describe(e))
            } finally {
                copy.delete()
            }
        }
    }

    private fun openPlayer() {
        if (view != null) push(PlayerScreen())
    }

    // ---- Rows ----

    /** What can be done with a row, besides playing it or going into it. */
    private fun actions(entry: FileEntry, row: Int): List<RowAction> {
        if (selecting) {
            val isSelected = entry.path in selected
            return listOf(
                RowAction(if (isSelected) "Deselect" else "Select") { toggleSelection(row) },
                RowAction("Select Up to Here") { selectRange(row) },
                RowAction("Done Selecting") { endSelecting() },
            )
        }
        val list = mutableListOf(
            RowAction("Cut") { putOnClipboard(listOf(entry), cut = true) },
            RowAction("Copy") { putOnClipboard(listOf(entry), cut = false) },
        )
        if (entry.isFolder && source.canWrite && FileClipboard.items.isNotEmpty()) {
            list += RowAction("Paste Into Folder") { paste(entry.path) }
        }
        if (source.canWrite) list += RowAction("Rename") { askToRename(entry) }
        // (The iPhone shares folders of its own files too; Android shares files)
        if (!entry.isFolder) list += RowAction("Share") { share(listOf(entry)) }
        if (entry.isFolder) {
            val isFavorite = FavoritesStore.find(source, entry.path) != null
            list += RowAction(if (isFavorite) "Remove from Favorites" else "Add to Favorites") { toggleFavorite(entry) }
        }
        list += RowAction("Properties") { showProperties(entry) }
        if (!source.isLocal) {
            list += RowAction(if (entry.isFolder) "Download Folder to This Device" else "Download to This Device") {
                transfer(entry, sync = false)
            }
            if (entry.isFolder) {
                list += RowAction("Sync Folder to This Device") { transfer(entry, sync = true) }
                val autoSyncing = AutoSyncStore.find(source, entry.path) != null
                list += RowAction(if (autoSyncing) "Stop Auto Syncing" else "Auto Sync This Folder") { toggleAutoSync(entry) }
            }
        }
        list += RowAction("Select") { startSelecting(row) }
        if (source.canWrite) list += RowAction("Delete", destructive = true) { confirmDelete(listOf(entry)) }
        return list
    }

    override fun rows(): List<ListRow> = entries.mapIndexed { index, entry ->
        val detail = detail(entry)
        ListRow.Item(
            title = entry.name,
            subtitle = detail,
            icon = if (entry.isFolder) R.drawable.ic_folder else R.drawable.ic_music_note,
            disclosure = entry.isFolder,
            selected = selecting && entry.path in selected,
            description = (if (entry.isFolder) "${entry.name}, folder" else entry.name) + (detail?.let { ", $it" } ?: ""),
            actions = actions(entry, index),
            id = entry.path,
            onClick = { chosen(index) },
        )
    }

    private fun chosen(index: Int) {
        if (index >= entries.size) return
        if (selecting) {
            toggleSelection(index)
            return
        }
        val entry = entries[index]
        if (entry.isFolder) push(BrowserScreen(source, entry, isTop = false)) else play(index)
    }

    // ---- Selecting ----

    private val selectedEntries get() = entries.filter { it.path in selected }

    private fun startSelecting(row: Int?) {
        if (selecting) {
            row?.let { toggleSelection(it) }
            return
        }
        selecting = true
        if (row != null) {
            selected.add(entries[row].path)
            anchor = row
        }
        // The rows' actions are the selecting ones now
        selectionChanged(announcing = false)
        announce(if (row == null) "Selecting. Choose items." else "Selecting. ${entries[row].name} selected.")
    }

    private fun endSelecting() {
        selecting = false
        selected.clear()
        anchor = null
        refreshChrome()
        reload()
        announce("Done selecting")
    }

    private fun toggleSelection(row: Int) {
        val path = entries.getOrNull(row)?.path ?: return
        if (!selected.remove(path)) {
            selected.add(path)
            anchor = row
        }
        selectionChanged(announcing = true)
    }

    /** Everything from the row last selected to this one. */
    private fun selectRange(row: Int) {
        val from = anchor ?: row
        for (index in minOf(from, row)..maxOf(from, row)) entries.getOrNull(index)?.let { selected.add(it.path) }
        anchor = row
        selectionChanged(announcing = true)
    }

    private fun toggleSelectAll() {
        if (selected.size == entries.size) selected.clear() else selected.addAll(entries.map { it.path })
        selectionChanged(announcing = true)
    }

    /** The count in the title and the bar for it; the rows' actions follow. */
    private fun selectionChanged(announcing: Boolean) {
        refreshChrome()
        reload()
        if (announcing) announce(selectedText)
    }

    // The bar at the bottom while selecting (the iPhone's toolbar)
    private var bar: View? = null
    private var cutButton: MaterialButton? = null
    private var copyButton: MaterialButton? = null
    private var shareButton: MaterialButton? = null
    private var deleteButton: MaterialButton? = null
    private var allButton: MaterialButton? = null

    override fun bottomBar(): View? {
        if (!selecting) return null
        val context = context ?: return null
        val view = bar ?: makeBar(context).also { bar = it }
        val any = selected.isNotEmpty()
        cutButton?.isEnabled = any
        copyButton?.isEnabled = any
        shareButton?.isEnabled = any
        shareButton?.visibility = if (selectedEntries.any { !it.isFolder }) View.VISIBLE else View.GONE
        deleteButton?.isEnabled = any
        deleteButton?.visibility = if (source.canWrite) View.VISIBLE else View.GONE
        allButton?.text = if (selected.size == entries.size && selected.isNotEmpty()) "Deselect All" else "Select All"
        return view
    }

    private fun makeBar(context: Context): View {
        fun button(title: String, run: () -> Unit) =
            MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = title
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(context.dp(10), paddingTop, context.dp(10), paddingBottom)
                setOnClickListener { run() }
            }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(6), 0, context.dp(6), 0)
        }
        cutButton = button("Cut") { putOnClipboard(selectedEntries, cut = true) }.also { row.addView(it) }
        copyButton = button("Copy") { putOnClipboard(selectedEntries, cut = false) }.also { row.addView(it) }
        shareButton = button("Share") { share(selectedEntries) }.also { row.addView(it) }
        deleteButton = button("Delete") { confirmDelete(selectedEntries) }.also { row.addView(it) }
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        allButton = button("Select All") { toggleSelectAll() }.also { row.addView(it) }
        return HorizontalScrollView(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---- Clipboard and pasting ----

    private fun putOnClipboard(list: List<FileEntry>, cut: Boolean) {
        if (list.isEmpty()) return
        FileClipboard.set(list.map { FileClipboard.Item(source, it) }, cut)
        val what = if (list.size == 1) list[0].name else "${list.size} items"
        announce((if (cut) "Cut " else "Copied ") + what)
        if (selecting) endSelecting()
        // Paste is offered now
        reload()
        refreshChrome()
    }

    private val pasteTitle: String get() = Pasting.title

    /** Moves (after Cut) or copies (after Copy) what is on the clipboard into a folder here. */
    private fun paste(path: String) = Pasting.pasteClipboard(this, path, source) {
        load()
        refreshChrome()
    }

    /**
     * Copies or moves [items] into [path], with a progress dialog that can stop it,
     * and the question about names already taken. [done] is told whether it all
     * went (unless the screen has gone meanwhile); [cleanUp] runs whatever happens.
     */
    private fun transferItems(
        items: List<FileClipboard.Item>, move: Boolean, path: String, title: String,
        cleanUp: () -> Unit = {}, done: (Boolean) -> Unit,
    ) = Pasting.transferItems(this, items, move, path, source, title, cleanUp) { finished ->
        load()
        done(finished)
    }

    // ---- Adding ----

    private fun importFiles() = importPicker.launch(arrayOf("*/*"))

    /** The files chosen, copied here and moved (or uploaded) into this folder. */
    private fun imported(uris: List<Uri>) {
        if (uris.isEmpty() || view == null) return
        val context = requireContext()
        val holder = File(context.cacheDir, "Importing-${UUID.randomUUID()}")
        launch {
            val unread = mutableListOf<String>()
            val items = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { index, uri ->
                    val name = main.displayName(uri)
                    // Each in a folder of its own, in case two have one name
                    val file = File(File(holder, index.toString()).apply { mkdirs() }, name)
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        } ?: throw RemoteError("$name could not be read.")
                        FileClipboard.Item(LocalSource, FileEntry(name, file.path, file.path, isFolder = false,
                            size = file.length(), modified = System.currentTimeMillis()))
                    } catch (e: Exception) {
                        unread.add(name)
                        null
                    }
                }
            }
            if (unread.isNotEmpty()) tell("Import Files", unread.joinToString(", ") + " could not be read.")
            if (items.isEmpty()) {
                holder.deleteRecursively()
                return@launch
            }
            transferItems(items, move = true, path = folder.path, title = "Importing",
                cleanUp = { holder.deleteRecursively() }) { finished ->
                if (finished) announce(if (items.size == 1) "1 file added" else "${items.size} files added")
            }
        }
    }

    private fun newFolder() {
        askForName("New Folder", "", "Create") { name ->
            launch {
                try {
                    source.createFolder(name, folder.path)
                    load()
                    announce("Made $name")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tell("New Folder", FolderSync.describe(e))
                }
            }
        }
    }

    private fun askForName(title: String, initial: String, action: String, named: (String) -> Unit) {
        val context = requireContext()
        val field = EditText(context).apply {
            setText(initial)
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
            // The name without its extension selected, ready to type over
            val dot = initial.lastIndexOf('.')
            setSelection(0, if (dot > 0) dot else initial.length)
        }
        val frame = FrameLayout(context).apply {
            setPadding(context.dp(20), context.dp(10), context.dp(20), 0)
            addView(field)
        }
        fun submit() {
            val name = field.text.toString().trim()
            if (name.isEmpty() || name.contains("/") || name == initial) return
            named(name)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(frame)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action) { _, _ -> submit() }
            .create()
        field.setOnEditorActionListener { _, _, _ ->
            dialog.dismiss()
            submit()
            true
        }
        dialog.setOnShowListener { field.requestFocus() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    // ---- Renaming, deleting, sharing ----

    private fun askToRename(entry: FileEntry) {
        askForName("Rename", entry.name, "Rename") { name ->
            launch {
                try {
                    source.rename(entry, name)
                    FileClipboard.forget(source, entry.path)
                    if (entry.isFolder) {
                        FavoritesStore.find(source, entry.path)?.let { favorite ->
                            // The favorite follows the folder
                            FavoritesStore.remove(favorite)
                            val renamed = source.entry(name, FileOperations.parent(entry.path), isFolder = true)
                            FavoritesStore.all = FavoritesStore.all +
                                favorite.copy(path = renamed.path, displayPath = renamed.displayPath, name = name)
                        }
                    }
                    load()
                    announce("Renamed to $name")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tell("Rename", FolderSync.describe(e))
                }
            }
        }
    }

    private fun confirmDelete(list: List<FileEntry>) {
        if (list.isEmpty()) return
        val what = if (list.size == 1) {
            if (list[0].isFolder) "the folder ${list[0].name} and everything in it" else list[0].name
        } else {
            "${list.size} items" + if (list.any { it.isFolder }) " and everything in the folders" else ""
        }
        val place = if (source.isLocal) "" else " from ${source.title}"
        Dialogs.confirm(requireContext(), "Delete", "Delete $what$place? This cannot be undone.", "Delete") {
            performDelete(list)
        }
    }

    private fun performDelete(list: List<FileEntry>) {
        val context = context ?: return
        var job: Job? = null
        val progress = ProgressDialog(context, "Deleting", "") { job?.cancel() }
        job = launch {
            var deleted = 0
            var problem: String? = null
            for ((index, entry) in list.withIndex()) {
                if (!currentCoroutineContext().isActive) break
                progress.setMessage("${index + 1} of ${list.size}: ${entry.name}")
                try {
                    source.delete(entry)
                    FileClipboard.forget(source, entry.path)
                    FavoritesStore.find(source, entry.path)?.let { FavoritesStore.remove(it) }
                    deleted += 1
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    problem = "${entry.name} could not be deleted. ${FolderSync.describe(e)}"
                    break
                }
            }
            progress.finished = true
            progress.dismiss()
            if (view == null) return@launch
            if (selecting) endSelecting()
            load()
            problem?.let { tell("Delete", it) }
                ?: announce(if (deleted == 1) "Deleted ${list[0].name}" else "Deleted $deleted items")
        }
        if (job != null) progress.showSoon(viewLifecycleOwner.lifecycleScope)
    }

    /**
     * The system's share sheet for files. From elsewhere, they are downloaded
     * first, to FastPlay's cache (cleared at the next share, since Android does not
     * say when the app shared to has finished with them).
     */
    private fun share(list: List<FileEntry>) {
        val files = list.filter { !it.isFolder }
        if (files.isEmpty()) {
            return tell("Share", if (source.isLocal) "Folders cannot be shared." else "Folders from ${source.title} cannot be shared.")
        }
        if (source.isLocal) return presentShareSheet(files.map { File(it.path) })
        val context = requireContext()
        val sharing = File(context.cacheDir, "Sharing")
        val holder = File(sharing, UUID.randomUUID().toString())
        var job: Job? = null
        val progress = ProgressDialog(context, "Getting Ready to Share", "") { job?.cancel() }
        job = launch {
            try {
                withContext(Dispatchers.IO) {
                    sharing.deleteRecursively()
                    holder.mkdirs()
                }
                val copies = mutableListOf<File>()
                for ((index, file) in files.withIndex()) {
                    progress.setMessage("${index + 1} of ${files.size}: ${file.name}")
                    val copy = File(holder, file.name)
                    source.download(file, copy)
                    copies.add(copy)
                }
                progress.finished = true
                progress.dismiss()
                presentShareSheet(copies)
            } catch (e: Exception) {
                progress.finished = true
                progress.dismiss()
                holder.deleteRecursively()
                if (view != null) tell("Share", FolderSync.describe(e))
            }
        }
        if (job != null) progress.showSoon(viewLifecycleOwner.lifecycleScope)
    }

    private fun presentShareSheet(files: List<File>) {
        val context = context ?: return
        val uris = try {
            files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        } catch (e: IllegalArgumentException) {
            return tell("Share", "FastPlay cannot share files from there.")
        }
        val types = files.map { mimeType(it.name) }.distinct()
        val type = types.singleOrNull() ?: if (types.all { it.startsWith("audio/") }) "audio/*" else "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = type
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, null))
    }

    private fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name).lowercase()) ?: "application/octet-stream"

    // ---- Favorites and properties ----

    private fun toggleFavorite(entry: FileEntry) {
        val favorite = FavoritesStore.find(source, entry.path)
        if (favorite != null) {
            FavoritesStore.remove(favorite)
            announce("Removed ${entry.name} from favorites")
        } else {
            FavoritesStore.add(source, entry)
            announce("Added ${entry.name} to favorites")
        }
        reload()
        refreshChrome()
    }

    /** Where it is, as the user knows it: "Files/Music/Album", "Dropbox/Music". */
    private fun location(entry: FileEntry): String {
        var below = entry.displayPath
        if (source.isLocal && below.startsWith(source.rootPath)) below = below.drop(source.rootPath.length)
        below = below.trim('/')
        val parent = below.substringBeforeLast('/', "")
        return if (parent.isEmpty()) source.title else source.title + "/" + parent
    }

    private fun showProperties(entry: FileEntry) {
        val context = requireContext()
        val lines = mutableListOf<String>()
        val ext = extension(entry.name)
        lines += "Kind: " + if (entry.isFolder) "Folder" else if (ext.isEmpty()) "File" else "${ext.uppercase()} file"
        if (!entry.isFolder) lines += "Size: " + Formatter.formatFileSize(context, entry.size)
        entry.modified?.let {
            lines += "Modified: " + DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(it))
        }
        val isThisTop = entry.path == source.rootPath
        if (!isThisTop) lines += "Where: " + location(entry)
        val counting = "Size: counting…"
        if (entry.isFolder) lines += counting
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(entry.name)
            .setMessage(lines.joinToString("\n"))
            .setNegativeButton("Close", null)
            .show()
        if (!entry.isFolder) return
        val job = launch {
            val more = mutableListOf<String>()
            try {
                val files = source.listAll(entry.path)
                val total = files.sumOf { it.size }
                more += "Size: " + Formatter.formatFileSize(context, total) +
                    if (files.size == 1) ", 1 file" else ", ${files.size} files"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                more += "Size: could not be counted"
            }
            if (isThisTop) {
                try {
                    source.storageSpace()?.let { (free, total) ->
                        more += "Space: ${Formatter.formatFileSize(context, free)} free of " +
                            Formatter.formatFileSize(context, total)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not said
                }
            }
            if (!dialog.isShowing) return@launch
            dialog.setMessage(lines.joinToString("\n") { if (it == counting) more.joinToString("\n") else it })
        }
        // Closed before the counting is done: no more counting
        dialog.setOnDismissListener { job?.cancel() }
    }

    // ---- Downloading and syncing (from elsewhere to this device) ----

    /**
     * Copies a file, or a folder with everything in it that FastPlay plays, into
     * FastPlay's own files, keeping the folders as they are there.
     *
     * Download leaves alone what is already here. Sync makes the folder here match
     * the one there: files that changed are fetched again, and files here that are
     * no longer there are deleted (after asking).
     */
    private fun transfer(entry: FileEntry, sync: Boolean) {
        // FastPlay's folder is in the device's storage, which needs access
        StorageAccess.ensure(this) {
            val context = context ?: return@ensure
            if (view == null) return@ensure
            var job: Job? = null
            val progress = ProgressDialog(context, "${if (sync) "Syncing" else "Downloading"} ${entry.name}", "Looking…") {
                job?.cancel()
            }
            job = launch {
                val outcome = (try {
                    FolderSync.run(source, entry, sync,
                        progress = { progress.setMessage(it) },
                        confirmDeleting = { count, name ->
                            progress.dismiss()
                            val yes = askBeforeDeleting(count, name)
                            if (yes) progress.show()
                            yes
                        })
                } catch (e: Exception) {
                    FolderSync.describe(e)  // "Stopped." for Stop
                }) ?: return@launch  // deleting refused: nothing done
                progress.dismiss()
                if (view != null) tell(entry.name, outcome)
            }
            if (job != null) progress.show()
        }
    }

    /** Turns syncing this folder by itself, each time FastPlay starts, on or off. */
    private fun toggleAutoSync(entry: FileEntry) {
        val existing = AutoSyncStore.find(source, entry.path)
        if (existing != null) {
            AutoSyncStore.remove(existing)
            announce("Stopped auto syncing ${entry.name}")
            reload()
            refreshChrome()
            return
        }
        Dialogs.confirm(requireContext(), "Auto Sync ${entry.name}",
            "Each time FastPlay starts, this folder is synced to this device: new and changed files are " +
                "downloaded, and files no longer in it are deleted from this device, without asking. " +
                "The folders that auto sync are listed in Settings. It is synced now too.",
            "Auto Sync") {
            AutoSyncStore.add(source, entry)
            reload()
            refreshChrome()
            transfer(entry, sync = true)
        }
    }

    /** Asks before a sync deletes anything. False if the user says no. */
    private suspend fun askBeforeDeleting(count: Int, name: String): Boolean = suspendCancellableCoroutine { continuation ->
        val what = if (count == 1) "1 file" else "$count files"
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sync $name")
            .setMessage("$what on this device ${if (count == 1) "is" else "are"} no longer in that folder and will be deleted from this device.")
            .setNegativeButton("Cancel") { _, _ -> if (continuation.isActive) continuation.resume(false) }
            .setPositiveButton("Sync and Delete") { _, _ -> if (continuation.isActive) continuation.resume(true) }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
            .show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }

    // ---- Telling ----

    /** Work for this screen, while its view is there (stopped if the screen goes). */
    private fun launch(block: suspend CoroutineScope.() -> Unit): Job? =
        if (view == null) null else viewLifecycleOwner.lifecycleScope.launch(block = block)

    companion object {
        /** How many files one choice queues from elsewhere: each may need an address asked for. */
        private const val PLAYLIST_LIMIT = 50

        private val collator: Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

        private fun extension(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(dot + 1) else ""
        }

        /**
         * Names as a person orders them: runs of digits by their value ("2" before
         * "10"), the rest by the language's order, ignoring case.
         */
        private fun compareNames(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val digits = a[i].isDigit()
                var iEnd = i
                while (iEnd < a.length && a[iEnd].isDigit() == digits) iEnd++
                var jEnd = j
                val otherDigits = b[j].isDigit()
                while (jEnd < b.length && b[jEnd].isDigit() == otherDigits) jEnd++
                val partA = a.substring(i, iEnd)
                val partB = b.substring(j, jEnd)
                val order = if (digits && otherDigits) {
                    val numberA = partA.trimStart('0')
                    val numberB = partB.trimStart('0')
                    if (numberA.length != numberB.length) numberA.length.compareTo(numberB.length)
                    else numberA.compareTo(numberB)
                } else {
                    collator.compare(partA, partB)
                }
                if (order != 0) return order
                i = iEnd
                j = jEnd
            }
            return (a.length - i).compareTo(b.length - j)
        }
    }
}
