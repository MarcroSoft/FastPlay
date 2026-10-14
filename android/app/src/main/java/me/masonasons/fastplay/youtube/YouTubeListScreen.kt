package me.masonasons.fastplay.youtube

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.masonasons.fastplay.R
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ToolbarItem

/**
 * A channel's or playlist's videos, newest first as YouTube lists them, a page at
 * a time. Choosing a video plays the videos from there as the playlist.
 */
class YouTubeListScreen : YouTubeResultsScreen() {
    private var url = ""
    /** Which channel or playlist, when known (its id is empty otherwise: a pasted address) */
    private var info = YouTubeListInfo(YouTubeKind.CHANNEL, "", "")
    private val videos = mutableListOf<YouTubeResult>()
    private var more = false
    private var loading = false
    private var failed: String? = null

    companion object {
        private const val PAGE = 50

        fun make(url: String, info: YouTubeListInfo) = YouTubeListScreen().apply {
            this.url = url
            this.info = info
        }
    }

    override val title: String
        get() = info.name.ifEmpty { if (info.kind == YouTubeKind.PLAYLIST) "Playlist" else "Channel" }

    override fun toolbarItems(): List<ToolbarItem> {
        val items = mutableListOf(
            ToolbarItem("Play All", R.drawable.ic_play, enabled = videos.isNotEmpty()) { playFrom(0) },
        )
        val more = mutableListOf(ToolbarItem("Add to Favorites") { addThisToFavorites() })
        if (info.kind == YouTubeKind.PLAYLIST) {
            more += ToolbarItem("Download Audio") { YouTube.download(url, "playlist $title", playlist = true) }
        }
        more += ToolbarItem("Copy Link") { copyLink(url) }
        items += ToolbarItem("More", submenu = more)
        return items
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        load(append = false)
    }

    private fun load(append: Boolean) {
        if (loading) return
        loading = true
        val number = ++request
        val start = if (append) videos.size + 1 else 1
        if (append) announce("Loading more") else announce(
            if (info.kind == YouTubeKind.PLAYLIST) "Loading playlist" else "Loading channel",
        )
        lifecycleScope.launch {
            try {
                val page = YtDlp.listVideos(url, start, PAGE)
                if (number != request) return@launch
                val found = page.results.filter { it.kind == YouTubeKind.VIDEO }
                videos += found
                more = page.more
                failed = null
                // A pasted address's name, now known
                if (info.name.isEmpty()) {
                    val name = if (info.kind == YouTubeKind.CHANNEL) page.channel.ifEmpty { page.title } else page.title
                    info = info.copy(name = name, id = info.id.ifEmpty {
                        if (info.kind == YouTubeKind.CHANNEL) page.channelId else ""
                    })
                }
                reload()
                refreshChrome()
                announce(
                    when {
                        found.isEmpty() -> if (append) "No more videos" else "No videos"
                        append -> "${found.size} more loaded"
                        else -> "${found.size} videos"
                    },
                )
            } catch (e: Exception) {
                if (number != request) return@launch
                failed = e.message
                reload()
                announce("Could not load the videos")
                tell("YouTube", "Could not load the videos.\n\n${e.message ?: ""}".trim())
            } finally {
                loading = false
            }
        }
    }

    /** The videos listed, played as the playlist from the one at [index]. */
    private fun playFrom(index: Int) {
        if (videos.isEmpty()) return
        val list = videos.toList()
        startPlaying { YouTube.playVideos(list, index) }
    }

    /** This channel or playlist added to the favorites: identified by yt-dlp first if need be. */
    private fun addThisToFavorites() {
        if (info.id.isNotEmpty() && info.name.isNotEmpty()) return addFavorite(info)
        announce("Adding")
        lifecycleScope.launch {
            try {
                addFavorite(YtDlp.identify(url))
            } catch (e: Exception) {
                announce("Could not add to favorites")
                tell("YouTube", "Could not add to favorites.\n\n${e.message ?: ""}".trim())
            }
        }
    }

    override fun rows(): List<ListRow> {
        val rows = mutableListOf<ListRow>()
        if (info.kind == YouTubeKind.PLAYLIST && info.channel.isNotEmpty()) rows += ListRow.Header("By ${info.channel}")
        videos.forEachIndexed { index, video -> rows += resultRow(video, index) { playFrom(it) } }
        when {
            loading && videos.isEmpty() -> rows += ListRow.Footer("Loading...")
            failed != null && videos.isEmpty() -> rows += ListRow.Footer("Could not load the videos.")
            !loading && videos.isEmpty() -> rows += ListRow.Footer("No videos.")
        }
        if (more) {
            rows += ListRow.Item(
                title = "Load More",
                icon = R.drawable.ic_add,
                id = "loadMore",
                enabled = !loading,
                onClick = { load(append = true) },
            )
        }
        return rows
    }
}
