package me.masonasons.fastplay.youtube

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.masonasons.fastplay.R
import me.masonasons.fastplay.ui.Dialogs
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.RowAction
import me.masonasons.fastplay.ui.ToolbarItem
import me.masonasons.fastplay.ui.dp
import java.io.File

/**
 * YouTube, as the desktop's YouTube window: search (or paste an address) at the
 * top; the results, where a video plays and a channel or playlist opens; and
 * without a search, the favorite channels and playlists (newest upload first, or
 * by name) and the recently viewed. yt-dlp does the work (YtDlp).
 */
class YouTubeScreen : YouTubeResultsScreen() {
    override val title = "YouTube"

    private lateinit var field: EditText
    private var query: String? = null
    private val results = mutableListOf<YouTubeResult>()
    private var more = false
    private var searching = false
    private var refreshing = false
    private var version: YtDlpVersion? = YtDlp.lastVersion

    companion object {
        private const val PAGE = 25
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val listView = super.onCreateView(inflater, container, savedInstanceState)
        val context = requireContext()
        field = EditText(context).apply {
            hint = "Search YouTube or paste an address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEARCH || enter) {
                    search(text.toString())
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // Emptied: back to the favorites and the recently viewed
                    if (s.isNullOrBlank() && query != null) {
                        query = null
                        request++
                        results.clear()
                        more = false
                        searching = false
                        reload()
                    }
                }
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(context.dp(16), context.dp(8), context.dp(16), 0)
            })
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        YouTubeServer.start()
        refreshFavorites(manual = false)
        // yt-dlp started now, so the first search is quicker; its version shown
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                version = YtDlp.version()
                reload()
                YouTube.checkForUpdate()
            } catch (e: Exception) {
                // Said when it is used
            }
        }
    }

    override fun toolbarItems(): List<ToolbarItem> = listOf(
        ToolbarItem("More", submenu = listOf(
            ToolbarItem("Refresh Favorites") { refreshFavorites(manual = true) },
            ToolbarItem(if (YouTubeStore.sortByName) "Sort Favorites by Latest Upload" else "Sort Favorites by Name") {
                YouTubeStore.sortByName = !YouTubeStore.sortByName
                reload()
                refreshChrome()
                announce(if (YouTubeStore.sortByName) "Sorted by name" else "Sorted by latest upload")
            },
            ToolbarItem("Import Channels") { importChannels() },
            ToolbarItem("Clear Recent", enabled = YouTubeStore.recent.isNotEmpty(), destructive = true) { clearRecent() },
            ToolbarItem("Sign-in Cookies") { cookies() },
            ToolbarItem("Update yt-dlp") { updateNow() },
        )),
    )

    // ---- Searching ----

    private fun hideKeyboard() {
        val manager = requireContext().getSystemService(InputMethodManager::class.java)
        manager?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun search(typed: String) {
        val text = typed.trim()
        if (text.isEmpty()) return
        hideKeyboard()

        // A YouTube address: open a playlist or channel, or play a video
        if (YouTube.isYouTubeUrl(text)) {
            when (val link = YouTube.parse(text)) {
                is YouTube.Link.Video -> return startPlaying { YouTube.playVideo(link.id) }
                is YouTube.Link.Playlist -> return openList(YouTubeListInfo(YouTubeKind.PLAYLIST, link.id, ""))
                is YouTube.Link.Channel -> {
                    // A @handle is listed by its address until its ID is known
                    pendingHandle = link.id
                    return openList(YouTubeListInfo(YouTubeKind.CHANNEL, if (YouTube.isChannelId(link.id)) link.id else "", ""))
                }
                null -> {}
            }
        }

        YouTubeStore.addRecent(YouTubeRecent(null, text, "", text, ""))
        query = text
        results.clear()
        more = false
        load(append = false)
    }

    /** A pasted channel's @handle, for its address while its ID is not known */
    private var pendingHandle: String? = null

    override fun openList(info: YouTubeListInfo) {
        val handle = pendingHandle
        pendingHandle = null
        if (info.id.isEmpty() && handle != null) {
            val url = YouTube.channelUrl(handle)
            YouTubeStore.addRecent(YouTubeRecent(YouTubeKind.CHANNEL, url, "", "@$handle", ""))
            push(YouTubeListScreen.make(url, info))
            return
        }
        super.openList(info)
    }

    private fun load(append: Boolean) {
        val text = query ?: return
        val number = ++request
        searching = true
        val start = if (append) results.size + 1 else 1
        announce(if (append) "Loading more" else "Searching")
        reload()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = YtDlp.search(text, start, PAGE)
                if (number != request) return@launch
                results += page.results
                more = page.more
                searching = false
                reload()
                announce(
                    when {
                        page.results.isEmpty() -> if (append) "No more results" else "No results"
                        append -> "${page.results.size} more loaded"
                        else -> "${page.results.size} results"
                    },
                )
            } catch (e: Exception) {
                if (number != request) return@launch
                searching = false
                reload()
                announce("Search failed")
                tell("YouTube", "The search failed.\n\n${e.message ?: ""}".trim())
            }
        }
    }

    // ---- Favorites ----

    /** "3 days ago", for when a favorite last uploaded. */
    private fun ago(time: Long): String {
        val seconds = System.currentTimeMillis() / 1000 - time
        if (seconds < 3600) return "less than an hour ago"
        val units = listOf(365 * 86400L to "year", 30 * 86400L to "month", 7 * 86400L to "week", 86400L to "day", 3600L to "hour")
        for ((length, name) in units) {
            val count = seconds / length
            if (count >= 1) return "$count $name${if (count == 1L) "" else "s"} ago"
        }
        return ""
    }

    private fun favoriteRow(favorite: YouTubeFavorite): ListRow.Item {
        val info = YouTubeListInfo(favorite.kind, favorite.id, favorite.name, favorite.channel)
        val kind = when {
            favorite.kind != YouTubeKind.PLAYLIST -> "Channel"
            favorite.channel.isEmpty() -> "Playlist"
            else -> "Playlist by ${favorite.channel}"
        }
        val subtitle = if (favorite.lastUpload > 0) "$kind, latest ${ago(favorite.lastUpload)}" else kind
        return ListRow.Item(
            title = favorite.name,
            subtitle = subtitle,
            icon = R.drawable.ic_star,
            disclosure = true,
            id = "favorite:${favorite.id}",
            actions = listOf(
                RowAction("Open") { openList(info) },
                RowAction("Play All") { playAll(info) },
                RowAction("Copy Link") { copyLink(YouTube.listUrl(favorite.kind, favorite.id)) },
                RowAction("Remove from Favorites", destructive = true) { removeFavorite(favorite) },
            ),
            onClick = { openList(info) },
        )
    }

    private fun removeFavorite(favorite: YouTubeFavorite) {
        Dialogs.confirm(requireContext(), "Remove Favorite", "Remove ${favorite.name} from favorites?", "Remove") {
            YouTubeStore.removeFavorite(favorite.id)
            reload()
            announce("Removed ${favorite.name}")
        }
    }

    /**
     * The favorites' latest uploads, from their feeds: quietly as the screen opens,
     * or asked for (says what it found).
     */
    private fun refreshFavorites(manual: Boolean) {
        val favorites = YouTubeStore.favorites
        if (refreshing) {
            if (manual) announce("Already refreshing")
            return
        }
        if (favorites.isEmpty()) {
            if (manual) announce("No favorites to refresh")
            return
        }
        refreshing = true
        if (manual) announce("Refreshing ${favorites.size} ${if (favorites.size == 1) "favorite" else "favorites"}")
        viewLifecycleOwner.lifecycleScope.launch {
            val limit = Semaphore(4)
            val found = favorites.map { favorite ->
                async { limit.withPermit { favorite to YouTubeStore.readFeed(favorite.kind, favorite.id) } }
            }.awaitAll()
            refreshing = false
            val fresh = mutableListOf<Pair<YouTubeFavorite, YouTubeFeed>>()
            var failed = 0
            for ((favorite, feed) in found) {
                if (feed == null) {
                    failed++
                    continue
                }
                if (feed.published > 0) YouTubeStore.setLastUpload(favorite.id, feed.published)
                if (favorite.lastUpload > 0 && feed.published > favorite.lastUpload) fresh += favorite to feed
            }
            reload()
            if (!manual) return@launch
            var text = "Favorites refreshed."
            val list = fresh.take(5).joinToString("") { (favorite, feed) -> " ${favorite.name}: ${feed.newestTitle}." } +
                if (fresh.size > 5) " And ${fresh.size - 5} more." else ""
            text += when (fresh.size) {
                0 -> " No new videos."
                1 -> " 1 new video:$list"
                else -> " ${fresh.size} new videos:$list"
            }
            if (failed > 0) text += " $failed could not be checked."
            announce(text)
        }
    }

    /** Adds the channels (or playlists) in a text file, one address, @handle or ID a line. */
    private fun importChannels() {
        main.pickFileCopy(arrayOf("text/*", "application/octet-stream")) { file: File? ->
            file ?: return@pickFileCopy
            val lines = try {
                file.readText().removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            } catch (e: Exception) {
                emptyList()
            }
            file.delete()
            if (lines.isEmpty()) return@pickFileCopy announce("No channels in the file")
            announce("Importing ${lines.size} ${if (lines.size == 1) "channel" else "channels"}")
            viewLifecycleOwner.lifecycleScope.launch {
                val limit = Semaphore(4)
                val found = lines.map { line ->
                    async {
                        limit.withPermit {
                            try {
                                Result.success(YouTubeStore.resolveFavorite(line))
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                    }
                }.awaitAll()
                var added = 0
                var existing = 0
                val failures = mutableListOf<String>()
                found.forEachIndexed { i, result ->
                    result.onSuccess { (info, published) ->
                        if (YouTubeStore.addFavorite(info, published)) added++ else existing++
                    }.onFailure { failures += "${lines[i]}: ${it.message}" }
                }
                var summary = "Imported $added ${if (added == 1) "channel" else "channels"}"
                if (existing > 0) summary += ", $existing already in favorites"
                if (failures.isNotEmpty()) summary += ", ${failures.size} not found"
                reload()
                announce(summary)
                if (failures.isNotEmpty()) {
                    val shown = failures.take(20).joinToString("\n") + if (failures.size > 20) "\n..." else ""
                    tell("Import YouTube Channels", "$summary.\n\nThese lines could not be imported:\n$shown")
                }
            }
        }
    }

    // ---- Recently viewed ----

    private fun recentRow(item: YouTubeRecent): ListRow.Item {
        val title = when (item.kind) {
            null -> "Search: ${item.name}"
            YouTubeKind.PLAYLIST -> "Playlist: ${item.name}" + if (item.channel.isEmpty()) "" else " - ${item.channel}"
            else -> "Channel: ${item.name}"
        }
        return ListRow.Item(
            title = title,
            icon = if (item.kind == null) R.drawable.ic_search else R.drawable.ic_list,
            id = "recent:${item.kind}:${item.target}",
            actions = listOf(RowAction("Remove", destructive = true) {
                YouTubeStore.removeRecent(item)
                reload()
                refreshChrome()
                announce("Removed ${item.name}")
            }),
            onClick = { openRecent(item) },
        )
    }

    /** Searches or opens it again. */
    private fun openRecent(item: YouTubeRecent) {
        if (item.kind == null) {
            field.setText(item.target)
            field.setSelection(item.target.length)
            search(item.target)
            return
        }
        val info = YouTubeListInfo(item.kind, item.id, if (item.name == item.target || item.name.startsWith("@")) "" else item.name, item.channel)
        YouTubeStore.addRecent(item)
        push(YouTubeListScreen.make(item.target, info))
    }

    private fun clearRecent() {
        Dialogs.confirm(requireContext(), "Clear Recent", "Clear the recently viewed list?", "Clear") {
            YouTubeStore.clearRecent()
            reload()
            refreshChrome()
            announce("Recent list cleared")
        }
    }

    // ---- Cookies and yt-dlp ----

    private fun cookies() {
        val actions = mutableListOf("Import cookies.txt")
        if (YouTube.hasCookies) actions += "Remove Cookies"
        Dialogs.actions(requireContext(), "Sign-in Cookies",
            "For videos YouTube only shows to a signed-in account: export the cookies for youtube.com from your " +
                "browser while signed in, as cookies.txt, and import that file.", actions) { which ->
            if (which == 0) {
                main.pickFileCopy(arrayOf("text/*", "application/octet-stream")) { file: File? ->
                    file ?: return@pickFileCopy
                    try {
                        YouTube.importCookies(file)
                        reload()
                        announce("Cookies imported")
                    } catch (e: Exception) {
                        tell("Import Cookies", e.message ?: "The cookies could not be imported.")
                    } finally {
                        file.delete()
                    }
                }
            } else {
                YouTube.removeCookies()
                reload()
                announce("Cookies removed")
            }
        }
    }

    private fun updateNow() {
        announce("Checking for a new yt-dlp")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (now, updated, problem) = YtDlp.update(force = true)
                version = now
                reload()
                when {
                    problem != null -> tell("yt-dlp", "Could not check for a new yt-dlp.\n\n$problem")
                    updated -> announce("yt-dlp updated to ${now.version}")
                    else -> announce("yt-dlp is up to date, version ${now.version}")
                }
            } catch (e: Exception) {
                tell("yt-dlp", e.message ?: "yt-dlp could not start.")
            }
        }
    }

    // ---- The list ----

    override fun rows(): List<ListRow> {
        val rows = mutableListOf<ListRow>()
        if (query != null) {
            rows += ListRow.Header("Results")
            results.forEachIndexed { index, result -> rows += resultRow(result, index, null) }
            if (searching && results.isEmpty()) rows += ListRow.Footer("Searching...")
            else if (results.isEmpty()) rows += ListRow.Footer("No results.")
            if (more) {
                rows += ListRow.Item(
                    title = "Load More",
                    icon = R.drawable.ic_add,
                    id = "loadMore",
                    enabled = !searching,
                    onClick = { load(append = true) },
                )
            }
            return rows
        }

        rows += ListRow.Header("Favorites")
        val favorites = YouTubeStore.favorites
        rows += favorites.map { favoriteRow(it) }
        if (favorites.isEmpty()) {
            rows += ListRow.Footer("Add channels and playlists to your favorites from their actions, or import a list of them.")
        }

        val recent = YouTubeStore.recent
        if (recent.isNotEmpty()) {
            rows += ListRow.Header("Recent")
            rows += recent.map { recentRow(it) }
        }

        rows += ListRow.Header("yt-dlp")
        rows += ListRow.Item(
            title = "Sign-in Cookies",
            value = if (YouTube.hasCookies) "Imported" else "None",
            id = "cookies",
            hint = "Imports or removes a cookies.txt",
            onClick = { cookies() },
        )
        val known = version
        rows += ListRow.Item(
            title = "yt-dlp",
            value = known?.let { if (it.downloaded) "${it.version} (updated)" else it.version } ?: "",
            id = "ytdlp",
            hint = "Checks for a new yt-dlp now",
            onClick = { updateNow() },
        )
        rows += ListRow.Footer("YouTube is searched and played with yt-dlp, which FastPlay keeps up to date. " +
            "Downloads go to the YouTube folder in FastPlay's folder.")
        return rows
    }
}
