package me.masonasons.fastplay.podcasts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.engine.Episode
import me.masonasons.fastplay.engine.Sources
import me.masonasons.fastplay.radio.showStatus
import me.masonasons.fastplay.radio.statusLabel
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen
import me.masonasons.fastplay.ui.ToolbarItem
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/** A podcast's episodes, read from its feed. Choosing one plays it. */
class EpisodesScreen() : ListScreen() {
    constructor(name: String, feedUrl: String) : this() {
        this.name = name
        this.feedUrl = feedUrl
    }

    private var name = "Podcast"
    private var feedUrl = ""
    private var episodes: List<Episode> = emptyList()
    /** What the list says while it is empty: loading, or what went wrong. */
    private var status: String? = "Loading episodes…"
    private var statusLabel: TextView? = null

    override val title get() = name

    override fun toolbarItems() = listOf(
        ToolbarItem(if (Sources.isSubscribed(feedUrl)) "Unsubscribe" else "Subscribe") { toggleSubscription() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Once, not each time the view comes back from behind the player
        lifecycleScope.launch {
            val feed = Sources.loadEpisodes(feedUrl)
            if (feed.title.isNotEmpty()) {
                name = feed.title
                refreshChrome()
            }
            episodes = feed.episodes
            reload()
            status = feed.error
            statusLabel?.showStatus(status)
            if (feed.error == null) announce(if (episodes.size == 1) "1 episode" else "${episodes.size} episodes")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        val label = statusLabel(requireContext()).apply { showStatus(status) }
        statusLabel = label
        return FrameLayout(requireContext()).apply {
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        }
    }

    override fun onDestroyView() {
        statusLabel = null
        super.onDestroyView()
    }

    private fun toggleSubscription() {
        val podcast = Sources.podcasts.firstOrNull { it.feedUrl == feedUrl }
        if (podcast != null) {
            Sources.removePodcast(podcast.identifier)
            announce("Unsubscribed")
        } else {
            Sources.subscribe(name, feedUrl)
            announce("Subscribed")
        }
        refreshChrome()
    }

    override fun rows(): List<ListRow> = episodes.mapIndexed { index, episode ->
        val details = mutableListOf(shortDate(episode.date))
        if (episode.duration > 0) details += Engine.formatTime(episode.duration.toDouble())
        ListRow.Item(
            title = episode.title,
            subtitle = details.filter { it.isNotEmpty() }.joinToString(" · "),
            id = "episode$index",
            onClick = {
                Sources.playEpisodes(listOf(episode), 0)
                main.showPlayer()
            },
        )
    }

    private companion object {
        val feedFormats = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz")

        /**
         * A feed's date ("Fri, 02 Oct 2026 23:14:21 +0000") as the day alone, in the
         * user's own form; anything else as it came.
         */
        fun shortDate(feedDate: String): String {
            for (format in feedFormats) {
                val date = try {
                    SimpleDateFormat(format, Locale.US).parse(feedDate)
                } catch (e: Exception) {
                    null
                } ?: continue
                return DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
            }
            return feedDate
        }
    }
}
