package me.masonasons.fastplay.ui

import me.masonasons.fastplay.engine.Engine

/** What is queued to play: choose a track to play it. */
class PlaylistScreen : ListScreen() {
    override val title = "Playlist"
    private var scrolled = false

    override fun engineStateChanged() = reload()

    override fun onResume() {
        super.onResume()
        // The track playing in view, the first time
        if (!scrolled) {
            scrolled = true
            val current = Engine.currentTrack
            if (current >= 0) list.post { scrollTo("item:track$current") }
        }
    }

    override fun rows(): List<ListRow> {
        val current = Engine.currentTrack
        return (0 until Engine.trackCount).map { index ->
            val name = Engine.trackName(index)
            val playing = index == current
            ListRow.Item(
                title = name,
                checked = playing,
                description = if (playing) "$name, playing" else name,
                id = "track$index",
                onClick = { Engine.playTrack(index) },
            )
        }
    }
}
