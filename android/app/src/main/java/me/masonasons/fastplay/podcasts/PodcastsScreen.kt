package me.masonasons.fastplay.podcasts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.masonasons.fastplay.R
import me.masonasons.fastplay.engine.Podcast
import me.masonasons.fastplay.engine.PodcastResult
import me.masonasons.fastplay.engine.Sources
import me.masonasons.fastplay.radio.ADDRESS_INPUT
import me.masonasons.fastplay.radio.SearchBar
import me.masonasons.fastplay.radio.fieldsDialog
import me.masonasons.fastplay.radio.screenLayout
import me.masonasons.fastplay.radio.showStatus
import me.masonasons.fastplay.radio.statusLabel
import me.masonasons.fastplay.radio.textField
import me.masonasons.fastplay.radio.value
import me.masonasons.fastplay.ui.FileTransfer
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen
import me.masonasons.fastplay.ui.RowAction
import me.masonasons.fastplay.ui.ToolbarItem

/** Podcasts: the ones subscribed to, and a search of the podcast directory. */
class PodcastsScreen : ListScreen() {
    override val title = "Podcasts"

    private var podcasts: List<Podcast> = emptyList()
    /** What a search found; null while the subscriptions are showing. */
    private var results: List<PodcastResult>? = null
    /** What the empty list says instead ("Searching…"), until the next show. */
    private var status: String? = null
    private var searchBar: SearchBar? = null
    private var statusLabel: TextView? = null
    private var searchState: SearchBar.State? = null
    private var searchJob: Job? = null

    override fun toolbarItems() = listOf(
        ToolbarItem("Add, import or export podcasts", R.drawable.ic_add, submenu = listOf(
            ToolbarItem("Add by Feed Address") { addFeed() },
            ToolbarItem("Import OPML") { importSubscriptions() },
            ToolbarItem("Export OPML") { exportSubscriptions() },
        )),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        val bar = SearchBar(requireContext(), "Search podcasts")
        searchState?.let { bar.restore(it) }
        bar.onSearch = { runSearch() }
        bar.onCancel = {
            searchJob?.cancel()
            results = null
            podcasts = Sources.podcasts
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
        podcasts = Sources.podcasts
        super.onResume()
        updateStatus()
    }

    private fun show(status: String? = null) {
        this.status = status
        reload()
        updateStatus()
    }

    private fun updateStatus() {
        val empty = results?.isEmpty() ?: podcasts.isEmpty()
        val text = status ?: if (results != null) "No podcasts found."
        else "No podcasts yet.\n\nSearch for one, or add one by its feed address."
        statusLabel?.showStatus(if (empty || status != null) text else null)
    }

    // ---- Importing and exporting ----

    /** The feeds of an OPML file, as podcast apps (and the desktop FastPlay) export. */
    private fun importSubscriptions() {
        FileTransfer.importFile(this) { file ->
            val (added, skipped) = Sources.importPodcasts(file.path)
            podcasts = Sources.podcasts
            if (results == null) show()
            var message: String
            if (added < 0) {
                message = "That file has no podcasts in it"
            } else {
                message = if (added == 1) "1 podcast added" else "$added podcasts added"
                if (skipped > 0) message += ", $skipped already subscribed to"
            }
            tell("Import", "$message.")
        }
    }

    private fun exportSubscriptions() {
        if (Sources.podcasts.isEmpty()) return tell("Export", "There are no podcasts to export.")
        FileTransfer.exportFile(this, "FastPlay Podcasts.opml", "text/x-opml") { Sources.exportPodcasts(it) }
    }

    // ---- Searching ----

    private fun runSearch() {
        val query = searchBar?.text?.trim().orEmpty()
        if (query.isEmpty()) return
        results = emptyList()
        show(status = "Searching…")
        announce("Searching")
        // A newer search replaces one still going
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val found = Sources.searchPodcasts(query)
            if (results == null) return@launch
            results = found
            show()
            announce(if (found.size == 1) "1 podcast" else "${found.size} podcasts")
        }
    }

    private fun addFeed() {
        val field = textField(requireContext(), "Feed address", placeholder = "https://", inputType = ADDRESS_INPUT,
            imeAction = EditorInfo.IME_ACTION_GO)
        fieldsDialog(requireContext(), "Add Podcast", "The address of the podcast's feed.", listOf(field), "Open") {
            val url = field.value.trim()
            if (url.isEmpty()) return@fieldsDialog
            val feed = if (url.contains("://")) url else "https://$url"
            push(EpisodesScreen("Podcast", feed))
        }
    }

    // ---- The list ----

    override fun rows(): List<ListRow> {
        val results = results
        if (results != null) {
            return results.mapIndexed { index, result ->
                ListRow.Item(
                    title = result.name,
                    subtitle = result.author,
                    icon = R.drawable.ic_mic,
                    disclosure = true,
                    id = "result$index:${result.feedUrl}",
                    onClick = { push(EpisodesScreen(result.name, result.feedUrl)) },
                )
            }
        }
        return podcasts.map { podcast ->
            ListRow.Item(
                title = podcast.name,
                icon = R.drawable.ic_mic,
                disclosure = true,
                id = "podcast${podcast.identifier}",
                actions = listOf(RowAction("Unsubscribe", destructive = true) {
                    Sources.removePodcast(podcast.identifier)
                    podcasts = Sources.podcasts
                    show()
                }),
                onClick = { push(EpisodesScreen(podcast.name, podcast.feedUrl)) },
            )
        }
    }
}
