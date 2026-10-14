package me.masonasons.fastplay.youtube

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.masonasons.fastplay.R
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.ui.Dialogs
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen
import me.masonasons.fastplay.ui.RowAction

/**
 * What the YouTube screens share: rows for results (videos, channels, playlists)
 * and what can be done with them, as the desktop's YouTube window offers.
 */
abstract class YouTubeResultsScreen : ListScreen() {
    /** Each search or listing gets a new number; what an older one finds is dropped. */
    protected var request = 0

    /** "Title - Channel [4:05]", as the desktop's lists say a result. */
    protected fun resultRow(result: YouTubeResult, index: Int, playFromHere: ((Int) -> Unit)?): ListRow.Item {
        val actions = mutableListOf<RowAction>()
        val title: String
        val subtitle: String
        val onClick: () -> Unit
        when (result.kind) {
            YouTubeKind.VIDEO -> {
                title = result.title
                subtitle = listOf(result.channel, result.duration).filter { it.isNotEmpty() }.joinToString(", ")
                onClick = if (playFromHere != null) ({ playFromHere(index) }) else ({ playVideo(result) })
                if (playFromHere != null) {
                    actions += RowAction("Play from Here") { playFromHere(index) }
                    actions += RowAction("Play Only This") { playVideo(result) }
                } else {
                    actions += RowAction("Play") { playVideo(result) }
                }
                if (result.channelId.isNotEmpty() && this !is YouTubeListScreen) {
                    actions += RowAction("Open Channel") {
                        openList(YouTubeListInfo(YouTubeKind.CHANNEL, result.channelId, result.channel))
                    }
                }
                actions += RowAction("Add Channel to Favorites") { addChannelOf(result) }
                actions += RowAction("Download Audio") { YouTube.download(result.url, result.title, playlist = false) }
            }
            YouTubeKind.CHANNEL -> {
                title = "Channel: ${result.title}"
                subtitle = ""
                val info = YouTubeListInfo(YouTubeKind.CHANNEL, result.id, result.title)
                onClick = { openList(info) }
                actions += RowAction("Open") { openList(info) }
                actions += RowAction("Play All") { playAll(info) }
                actions += RowAction("Add to Favorites") { addFavorite(info) }
            }
            YouTubeKind.PLAYLIST -> {
                title = "Playlist: ${result.title}"
                subtitle = result.channel
                val info = YouTubeListInfo(YouTubeKind.PLAYLIST, result.id, result.title, result.channel)
                onClick = { openList(info) }
                actions += RowAction("Open") { openList(info) }
                actions += RowAction("Play All") { playAll(info) }
                actions += RowAction("Add to Favorites") { addFavorite(info) }
                actions += RowAction("Download Audio") {
                    YouTube.download(result.url, "playlist ${result.title}", playlist = true)
                }
            }
        }
        actions += RowAction("Copy Link") { copyLink(result.url) }
        return ListRow.Item(
            title = title,
            subtitle = subtitle.ifEmpty { null },
            icon = when (result.kind) {
                YouTubeKind.VIDEO -> R.drawable.ic_youtube
                YouTubeKind.CHANNEL -> R.drawable.ic_folder
                YouTubeKind.PLAYLIST -> R.drawable.ic_list
            },
            disclosure = result.kind != YouTubeKind.VIDEO,
            id = "result:${result.kind}:${result.id}:$index",
            actions = actions,
            onClick = onClick,
        )
    }

    /** A video played alone; the player opens when it starts. */
    protected fun playVideo(result: YouTubeResult) = startPlaying { YouTube.playVideo(result.id) }

    /** A channel's or playlist's videos played as the playlist. */
    protected fun playAll(info: YouTubeListInfo) = startPlaying { YouTube.playList(YouTube.listUrl(info.kind, info.id), null) }

    /**
     * Playing, got ready in the background: it goes on if this screen is left, and
     * the player opens if it is still here.
     */
    protected fun startPlaying(work: suspend () -> Boolean) {
        announce("Loading")
        val activity = main
        YouTube.background.launch {
            try {
                if (work() && isAdded) activity.showPlayer()
            } catch (e: Exception) {
                val message = "Could not play the video.\n\n${e.message ?: ""}".trim()
                if (isAdded) announce("Could not play the video") else Engine.speak("Could not play the video")
                if (!activity.isFinishing) Dialogs.tell(activity, "YouTube", message)
            }
        }
    }

    /** Shows a channel's or playlist's videos, remembered among the recently viewed. */
    protected open fun openList(info: YouTubeListInfo) {
        val url = YouTube.listUrl(info.kind, info.id)
        YouTubeStore.addRecent(YouTubeRecent(info.kind, url, info.id, info.name, info.channel))
        push(YouTubeListScreen.make(url, info))
    }

    protected fun addFavorite(info: YouTubeListInfo) {
        val what = (if (info.kind == YouTubeKind.PLAYLIST) "playlist " else "channel ") + info.name
        if (!YouTubeStore.addFavorite(info)) {
            announce("$what is already in favorites")
            return
        }
        announce("Added $what to favorites")
        reload()
        // When it last uploaded, quietly
        viewLifecycleOwner.lifecycleScope.launch {
            YouTubeStore.readFeed(info.kind, info.id)?.let { feed ->
                if (feed.published > 0) YouTubeStore.setLastUpload(info.id, feed.published)
                reload()
            }
        }
    }

    /** A video's channel added to the favorites: known from the listing, or asked of yt-dlp. */
    protected fun addChannelOf(result: YouTubeResult) {
        if (result.channelId.isNotEmpty()) {
            return addFavorite(YouTubeListInfo(YouTubeKind.CHANNEL, result.channelId, result.channel))
        }
        announce("Adding")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                addFavorite(YtDlp.videoChannel(result.id))
            } catch (e: Exception) {
                announce("Could not add to favorites")
                tell("YouTube", "Could not add to favorites.\n\n${e.message ?: ""}".trim())
            }
        }
    }

    protected fun copyLink(url: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("YouTube", url))
        announce("Link copied")
    }
}
