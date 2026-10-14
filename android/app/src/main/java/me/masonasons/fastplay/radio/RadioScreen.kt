package me.masonasons.fastplay.radio

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.masonasons.fastplay.R
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.engine.RadioDirectory
import me.masonasons.fastplay.engine.RadioResult
import me.masonasons.fastplay.engine.RadioStation
import me.masonasons.fastplay.engine.Sources
import me.masonasons.fastplay.ui.FileTransfer
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen
import me.masonasons.fastplay.ui.RowAction
import me.masonasons.fastplay.ui.ToolbarItem

/** Internet radio: the saved stations, and a search of the station directories. */
class RadioScreen : ListScreen() {
    override val title = "Radio"

    private var favorites: List<RadioStation> = emptyList()
    /** What a search found; null while the saved stations are showing. */
    private var results: List<RadioResult>? = null
    /** What the empty list says instead ("Searching…"), until the next show. */
    private var status: String? = null
    private var searchBar: SearchBar? = null
    private var statusLabel: TextView? = null
    private var searchState: SearchBar.State? = null
    private var searchJob: Job? = null

    override fun toolbarItems() = listOf(
        ToolbarItem("Add, import or export stations", R.drawable.ic_add, submenu = listOf(
            ToolbarItem("Add Station") { addStation() },
            ToolbarItem("Import Stations") { importStations() },
            ToolbarItem("Export Stations") { exportStations() },
        )),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        val bar = SearchBar(requireContext(), "Search stations", listOf("RadioBrowser", "TuneIn", "iHeartRadio"))
        searchState?.let { bar.restore(it) }
        bar.onSearch = { runSearch() }
        bar.onScopeChanged = { if (results != null) runSearch() }
        bar.onCancel = {
            searchJob?.cancel()
            results = null
            show()
        }
        searchBar = bar
        val label = statusLabel(requireContext())
        statusLabel = label
        return screenLayout(requireContext(), bar, list, label)
    }

    override fun onDestroyView() {
        searchState = searchBar?.state
        searchBar = null
        statusLabel = null
        super.onDestroyView()
    }

    override fun onResume() {
        favorites = Sources.radioFavorites
        super.onResume()
        updateStatus()
    }

    private fun reloadFavorites() {
        favorites = Sources.radioFavorites
        if (results == null) show()
    }

    /** Shows the list there is to show, or a line saying why it is empty. */
    private fun show(status: String? = null) {
        this.status = status
        reload()
        updateStatus()
    }

    private fun updateStatus() {
        val empty = results?.isEmpty() ?: favorites.isEmpty()
        val text = status ?: if (results != null) "No stations found."
        else "No stations saved yet.\n\nSearch for one, or add one by its address."
        statusLabel?.showStatus(if (empty || status != null) text else null)
    }

    // ---- Importing and exporting ----

    /** The stations of an M3U or PLS playlist file, as the desktop FastPlay exports. */
    private fun importStations() {
        FileTransfer.importFile(this) { file ->
            val (added, skipped) = Sources.importRadioFavorites(file.path)
            reloadFavorites()
            var message = if (added == 1) "1 station added" else "$added stations added"
            if (skipped > 0) message += ", $skipped already saved"
            tell("Import", "$message.")
        }
    }

    private fun exportStations() {
        if (Sources.radioFavorites.isEmpty()) return tell("Export", "There are no saved stations to export.")
        FileTransfer.exportFile(this, "FastPlay Radio Stations.m3u", "audio/x-mpegurl") { Sources.exportRadioFavorites(it) }
    }

    // ---- Searching ----

    private fun runSearch() {
        val query = searchBar?.text?.trim().orEmpty()
        if (query.isEmpty()) return
        val directory = RadioDirectory.entries.getOrElse(searchBar?.selectedScope ?: 0) { RadioDirectory.RADIO_BROWSER }
        results = emptyList()
        show(status = "Searching…")
        announce("Searching")
        // A newer search replaces one still going
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val found = Sources.searchRadio(query, directory)
            if (results == null) return@launch
            results = found
            show()
            announce(if (found.size == 1) "1 station" else "${found.size} stations")
        }
    }

    // ---- Adding ----

    private fun addStation() {
        val nameField = textField(requireContext(), "Name",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, imeAction = EditorInfo.IME_ACTION_NEXT)
        val addressField = textField(requireContext(), "Address", inputType = ADDRESS_INPUT)
        fieldsDialog(requireContext(), "Add Station", null, listOf(nameField, addressField), "Add") {
            val url = addressField.value.trim()
            var name = nameField.value.trim()
            if (url.isEmpty()) return@fieldsDialog
            if (name.isEmpty()) name = url
            if (!Sources.addRadioFavorite(name, if (url.contains("://")) url else "http://$url")) {
                announce("That station is already saved")
            }
            reloadFavorites()
        }
    }

    private fun save(result: RadioResult) {
        lifecycleScope.launch {
            val url = Sources.resolve(result) ?: return@launch announce("Could not find the station's stream")
            val added = Sources.addRadioFavorite(result.name, url)
            announce(if (added) "Saved ${result.name}" else "That station is already saved")
            favorites = Sources.radioFavorites
        }
    }

    // ---- The list ----

    override fun rows(): List<ListRow> {
        val results = results
        if (results != null) {
            return results.mapIndexed { index, result ->
                ListRow.Item(
                    title = result.name,
                    subtitle = result.detail,
                    icon = R.drawable.ic_radio,
                    id = "result$index:${result.name}",
                    actions = listOf(RowAction("Save") { save(result) }),
                    onClick = { open(result) },
                )
            }
        }
        return favorites.map { station ->
            ListRow.Item(
                title = station.name,
                icon = R.drawable.ic_radio,
                id = "station${station.identifier}",
                actions = listOf(RowAction("Delete", destructive = true) {
                    Sources.removeRadioFavorite(station.identifier)
                    reloadFavorites()
                }),
                onClick = {
                    Engine.playUrl(station.url, station.name)
                    main.showPlayer()
                },
            )
        }
    }

    /** A found station: its stream found, then played. */
    private fun open(result: RadioResult) {
        announce("Opening ${result.name}")
        // The activity's scope: it plays even if this screen has gone by then
        val activity = main
        activity.lifecycleScope.launch {
            val url = Sources.resolve(result) ?: return@launch announce("Could not find the station's stream")
            Engine.playUrl(url, result.name)
            if (isAdded) activity.showPlayer()
        }
    }
}
