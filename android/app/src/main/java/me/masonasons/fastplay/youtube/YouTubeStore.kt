package me.masonasons.fastplay.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.engine.Engine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/** A favorite channel or playlist; [lastUpload] is when its newest video came out (Unix seconds, 0 if not known). */
data class YouTubeFavorite(
    val kind: YouTubeKind,
    val id: String,
    val name: String,
    val channel: String,
    val lastUpload: Long,
)

/** A search, channel or playlist viewed lately. [target] is the search, or the page listed. */
data class YouTubeRecent(val kind: YouTubeKind?, val target: String, val id: String, val name: String, val channel: String)

/** What a channel's or playlist's feed says: its name, and its newest video. */
data class YouTubeFeed(val name: String, val channel: String, val published: Long, val newestTitle: String)

/**
 * The favorite channels and playlists and the recently viewed, in the app's
 * settings (the desktop keeps them in its database), and the feeds that say
 * when a favorite last uploaded.
 */
object YouTubeStore {
    private const val FAVORITES = "YouTubeFavorites"
    private const val RECENT = "YouTubeRecent"
    private const val SORT = "YouTubeFavoritesSort"
    private const val RECENT_LIMIT = 50

    private fun kindName(kind: YouTubeKind?) = when (kind) {
        YouTubeKind.CHANNEL -> "channel"
        YouTubeKind.PLAYLIST -> "playlist"
        YouTubeKind.VIDEO -> "video"
        null -> "search"
    }

    private fun kind(name: String) = when (name) {
        "channel" -> YouTubeKind.CHANNEL
        "playlist" -> YouTubeKind.PLAYLIST
        "video" -> YouTubeKind.VIDEO
        else -> null
    }

    private fun read(key: String): List<JSONObject> {
        val text = Engine.prefs().getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { array.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(key: String, items: List<JSONObject>) {
        Engine.prefs().edit().putString(key, JSONArray(items).toString()).apply()
    }

    // ---- Favorites ----

    /** Sorted by the newest upload (not yet known last), or with [byName], by name. */
    var sortByName: Boolean
        get() = Engine.prefs().getBoolean(SORT, false)
        set(value) = Engine.prefs().edit().putBoolean(SORT, value).apply()

    val favorites: List<YouTubeFavorite>
        get() {
            val all = read(FAVORITES).map {
                YouTubeFavorite(kind(it.optString("kind")) ?: YouTubeKind.CHANNEL, it.optString("id"),
                    it.optString("name"), it.optString("channel"), it.optLong("lastUpload"))
            }
            return if (sortByName) all.sortedBy { it.name.lowercase() } else all.sortedByDescending { it.lastUpload }
        }

    private fun saveFavorites(favorites: List<YouTubeFavorite>) = write(FAVORITES, favorites.map {
        JSONObject().put("kind", kindName(it.kind)).put("id", it.id).put("name", it.name)
            .put("channel", it.channel).put("lastUpload", it.lastUpload)
    })

    fun isFavorite(id: String) = favorites.any { it.id == id }

    /** False if it was already a favorite. */
    fun addFavorite(info: YouTubeListInfo, lastUpload: Long = 0): Boolean {
        val all = favorites
        if (all.any { it.id == info.id }) return false
        saveFavorites(all + YouTubeFavorite(info.kind, info.id, info.name, info.channel, lastUpload))
        return true
    }

    fun removeFavorite(id: String) = saveFavorites(favorites.filter { it.id != id })

    fun setLastUpload(id: String, published: Long) =
        saveFavorites(favorites.map { if (it.id == id) it.copy(lastUpload = published) else it })

    // ---- Recently viewed ----

    val recent: List<YouTubeRecent>
        get() = read(RECENT).map {
            YouTubeRecent(kind(it.optString("kind")), it.optString("target"), it.optString("id"),
                it.optString("name"), it.optString("channel"))
        }

    /** Remembered as the most recent (once: seen again, it moves to the top). */
    fun addRecent(item: YouTubeRecent) {
        val rest = recent.filter { !(it.kind == item.kind && it.target == item.target) }
        write(RECENT, (listOf(item) + rest).take(RECENT_LIMIT).map {
            JSONObject().put("kind", kindName(it.kind)).put("target", it.target).put("id", it.id)
                .put("name", it.name).put("channel", it.channel)
        })
    }

    fun removeRecent(item: YouTubeRecent) {
        val rest = recent.filter { !(it.kind == item.kind && it.target == item.target) }
        write(RECENT, rest.map {
            JSONObject().put("kind", kindName(it.kind)).put("target", it.target).put("id", it.id)
                .put("name", it.name).put("channel", it.channel)
        })
    }

    fun clearRecent() = write(RECENT, emptyList())

    // ---- Feeds ----

    /** Text between <tag> and </tag> in [xml] from [from] on (before [before]), its escapes decoded. */
    private fun xmlText(xml: String, tag: String, from: Int, before: Int): String {
        val start = xml.indexOf("<$tag>", from)
        if (start < 0 || (before >= 0 && start > before)) return ""
        val stop = xml.indexOf("</$tag>", start)
        if (stop < 0) return ""
        return xml.substring(start + tag.length + 2, stop)
            .replace(Regex("&#(x?)([0-9a-fA-F]+);")) { match ->
                val code = match.groupValues[2].toIntOrNull(if (match.groupValues[1].isEmpty()) 10 else 16)
                code?.let { String(Character.toChars(it)) } ?: match.value
            }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    /** A channel's or playlist's feed, or null if it could not be read. */
    suspend fun readFeed(kind: YouTubeKind, id: String): YouTubeFeed? = withContext(Dispatchers.IO) {
        val param = if (kind == YouTubeKind.PLAYLIST) "playlist_id" else "channel_id"
        try {
            val connection = URL("https://www.youtube.com/feeds/videos.xml?$param=$id").openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            val xml = try {
                if (connection.responseCode != 200) return@withContext null
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                connection.disconnect()
            }
            // The feed's own title and author come before the first <entry>; each
            // entry has its video's date
            val firstEntry = xml.indexOf("<entry>")
            val name = xmlText(xml, "title", 0, firstEntry)
            val channel = if (kind == YouTubeKind.PLAYLIST) xmlText(xml, "name", 0, firstEntry) else ""
            var published = 0L
            var newest = ""
            var entry = firstEntry
            while (entry >= 0) {
                val end = xml.indexOf("</entry>", entry)
                val date = xmlText(xml, "published", entry, end)
                val time = try {
                    OffsetDateTime.parse(date).toEpochSecond()
                } catch (e: Exception) {
                    0L
                }
                if (time > published) {
                    published = time
                    newest = xmlText(xml, "title", entry, end)
                }
                entry = xml.indexOf("<entry>", entry + 7)
            }
            YouTubeFeed(name, channel, published, newest)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A channel or playlist from a line of text, as in a list of channels to import:
     * a channel or playlist address, an @handle or a channel ID. Throws with why not.
     */
    suspend fun resolveFavorite(text: String): Pair<YouTubeListInfo, Long> {
        val line = text.trim { it.isWhitespace() || it in "\"'<>" }
        if (line.isEmpty()) throw YouTubeException("The line is empty.")

        // An ID can be read straight from the line: then the feed says the rest
        fun after(marker: String): String? {
            val at = line.indexOf(marker)
            if (at < 0) return null
            val start = at + marker.length
            val end = line.indexOfAny("/?&#".toCharArray(), start).let { if (it < 0) line.length else it }
            return line.substring(start, end).ifEmpty { null }
        }
        var kind = YouTubeKind.CHANNEL
        val id = if (YouTube.isChannelId(line)) {
            line
        } else {
            after("list=")?.also { kind = YouTubeKind.PLAYLIST } ?: after("/channel/")
        }
        if (id != null && (kind == YouTubeKind.PLAYLIST || YouTube.isChannelId(id))) {
            val feed = readFeed(kind, id)
            if (feed != null && feed.name.isNotEmpty()) return YouTubeListInfo(kind, id, feed.name, feed.channel) to feed.published
        }

        // A @handle or custom address: yt-dlp finds the channel
        var url = line
        if (url.startsWith("@")) url = "https://www.youtube.com/$url" else if (!url.contains("://")) url = "https://$url"
        if (!YouTube.isYouTubeUrl(url)) throw YouTubeException("Not a YouTube channel or playlist.")
        if (url.contains("/@") || url.contains("/c/") || url.contains("/user/")) {
            url = url.substringBefore('?').substringBefore('#').trimEnd('/')
            if (!url.endsWith("/videos")) url += "/videos"
        }
        val info = YtDlp.identify(url)
        return info to (readFeed(info.kind, info.id)?.published ?: 0L)
    }
}
