package me.masonasons.fastplay.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A saved radio station. */
data class RadioStation(val identifier: Int, val name: String, val url: String)

/** A station found in one of the directories. [detail]: "Germany, MP3, 128 kbps", as much as the directory gave. */
data class RadioResult(
    val name: String,
    internal val url: String,
    internal val stationId: String,
    internal val country: String,
    internal val codec: String,
    internal val bitrate: String,
    internal val source: String,
    val detail: String,
)

enum class RadioDirectory { RADIO_BROWSER, TUNE_IN, IHEART_RADIO }

/** A podcast subscribed to. */
data class Podcast(val identifier: Int, val name: String, val feedUrl: String)

/** A podcast found in the directory. */
data class PodcastResult(val name: String, val author: String, val feedUrl: String)

/** [duration] is in seconds, 0 if the feed does not say. */
data class Episode(val title: String, val date: String, val summary: String, val audioUrl: String, val duration: Int)

/** A feed's episodes, or (with none) [error] saying what went wrong. */
data class Feed(val title: String, val episodes: List<Episode>, val error: String?)

/**
 * Internet radio and podcasts, from FastPlay's engine: the iPhone's FPSources. The
 * saved stations and subscriptions (the same database as on the desktop) and the
 * directory searches, which go over the network off the main thread.
 */
object Sources {
    // ---- Radio ----

    val radioFavorites: List<RadioStation>
        get() = Native.radioFavorites().toList().chunked(3).map { RadioStation(it[0].toInt(), it[1], it[2]) }

    /** False if the address is already saved. */
    fun addRadioFavorite(name: String, url: String) = Native.addRadioFavorite(name, url)
    fun removeRadioFavorite(identifier: Int) = Native.removeRadioFavorite(identifier)

    suspend fun searchRadio(query: String, directory: RadioDirectory): List<RadioResult> = withContext(Dispatchers.IO) {
        Native.searchRadio(query, directory.ordinal).toList().chunked(8).map {
            RadioResult(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7])
        }
    }

    /** The address a found station plays from, or null if it could not be found. */
    suspend fun resolve(result: RadioResult): String? = withContext(Dispatchers.IO) {
        Native.resolveRadioResult(
            arrayOf(result.name, result.url, result.stationId, result.country, result.codec, result.bitrate,
                result.source, result.detail),
        ).ifEmpty { null }
    }

    /**
     * The stations of an M3U, M3U8 or PLS playlist file, added to the saved ones
     * (those already saved are passed over): how many were added, and skipped.
     */
    fun importRadioFavorites(path: String): Pair<Int, Int> =
        Native.importRadioFavorites(path).let { it[0] to it[1] }

    /** The saved stations, written as an M3U file. False if it could not be written. */
    fun exportRadioFavorites(path: String) = Native.exportRadioFavorites(path)

    // ---- Podcasts ----

    val podcasts: List<Podcast>
        get() = Native.podcasts().toList().chunked(3).map { Podcast(it[0].toInt(), it[1], it[2]) }

    /**
     * The feeds of an OPML file, subscribed to (those already subscribed to are
     * passed over): how many were added (-1 if the file has no feeds in it), and skipped.
     */
    fun importPodcasts(path: String): Pair<Int, Int> = Native.importPodcasts(path).let { it[0] to it[1] }

    /** The subscriptions, written as an OPML file. False if it could not be written. */
    fun exportPodcasts(path: String) = Native.exportPodcasts(path)
    fun isSubscribed(feedUrl: String) = Native.isSubscribedToFeed(feedUrl)
    fun subscribe(name: String, feedUrl: String) = Native.subscribeToPodcast(name, feedUrl)
    fun removePodcast(identifier: Int) = Native.removePodcast(identifier)

    suspend fun searchPodcasts(query: String): List<PodcastResult> = withContext(Dispatchers.IO) {
        Native.searchPodcasts(query).toList().chunked(3).map { PodcastResult(it[0], it[1], it[2]) }
    }

    /** A feed's episodes, newest first as the feed has them. */
    suspend fun loadEpisodes(feedUrl: String): Feed = withContext(Dispatchers.IO) {
        val items = Native.loadEpisodes(feedUrl).toList()
        val episodes = items.drop(2).chunked(5).map {
            Episode(it[0], it[1], it[2], it[3], it[4].toIntOrNull() ?: 0)
        }
        Feed(items.getOrElse(0) { "" }, episodes, items.getOrElse(1) { "" }.ifEmpty { null })
    }

    /** Plays the episodes as a playlist, starting with the one at `index`. */
    fun playEpisodes(episodes: List<Episode>, startingAt: Int) {
        if (episodes.isEmpty()) return
        Native.playEpisodes(episodes.map { it.title }.toTypedArray(), episodes.map { it.audioUrl }.toTypedArray(), startingAt)
    }
}
