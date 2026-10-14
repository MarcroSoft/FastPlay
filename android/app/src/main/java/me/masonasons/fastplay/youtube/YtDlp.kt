package me.masonasons.fastplay.youtube

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.FastPlayApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Something YouTube or yt-dlp could not do; the message is for the user. */
class YouTubeException(message: String) : Exception(message)

enum class YouTubeKind { VIDEO, CHANNEL, PLAYLIST }

/** A search result or listing entry. [duration] is "4:05", or "live" for a live stream. */
data class YouTubeResult(
    val kind: YouTubeKind,
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val duration: String,
) {
    /** Its page on YouTube. */
    val url: String
        get() = when (kind) {
            YouTubeKind.VIDEO -> YouTube.videoUrl(id)
            YouTubeKind.PLAYLIST -> YouTube.playlistUrl(id)
            YouTubeKind.CHANNEL -> YouTube.channelUrl(id)
        }
}

/** One page of a search or listing; [more] if there is another after it. */
data class YouTubePage(
    val results: List<YouTubeResult>,
    val more: Boolean,
    /** The listing's own: its title, and the channel it belongs to */
    val title: String,
    val channel: String,
    val channelId: String,
)

/** A channel or playlist. [channel] is the channel that made a playlist. */
data class YouTubeListInfo(val kind: YouTubeKind, val id: String, val name: String, val channel: String = "")

/** A video ready to play: a stream address, or a file its audio was downloaded to. */
data class YouTubeMedia(val title: String, val channel: String, val url: String?, val file: String?)

/** yt-dlp's version, and whether it is the APK's or one downloaded since. */
data class YtDlpVersion(val version: String, val downloaded: Boolean)

/**
 * yt-dlp, in Python inside the app (Chaquopy): src/main/python/fastplay_youtube.py.
 * Everything here blocks, often for seconds (the first call also starts Python),
 * so the suspend functions do their work on a worker thread.
 */
object YtDlp {
    private var module: PyObject? = null

    /** QuickJS, which yt-dlp needs to answer YouTube's challenges: built with the app. */
    private fun quickJs(): String? {
        val file = File(FastPlayApp.instance.applicationInfo.nativeLibraryDir, "libqjs.so")
        return file.path.takeIf { file.canExecute() }
    }

    @Synchronized
    private fun module(): PyObject {
        module?.let { return it }
        val context = FastPlayApp.instance
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            val loaded = Python.getInstance().getModule("fastplay_youtube")
            loaded.callAttr("setup", context.filesDir.path, context.cacheDir.path, quickJs())
            module = loaded
            return loaded
        } catch (e: Exception) {
            android.util.Log.e("FastPlay", "yt-dlp could not start", e)
            throw YouTubeException("yt-dlp could not start. ${e.message ?: ""}".trim())
        }
    }

    /** A function of fastplay_youtube.py, whose JSON comes back as an object (or its "error" thrown). */
    private fun call(name: String, vararg args: Any?): JSONObject {
        val text = try {
            module().callAttr(name, *args).toString()
        } catch (e: YouTubeException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("FastPlay", "yt-dlp: $name failed", e)
            throw YouTubeException(e.message ?: "yt-dlp failed.")
        }
        val json = JSONObject(text)
        json.optString("error").takeIf { it.isNotEmpty() }?.let { throw YouTubeException(it) }
        return json
    }

    private fun kind(text: String) = when (text) {
        "channel" -> YouTubeKind.CHANNEL
        "playlist" -> YouTubeKind.PLAYLIST
        else -> YouTubeKind.VIDEO
    }

    private fun page(json: JSONObject): YouTubePage {
        val array = json.optJSONArray("results") ?: JSONArray()
        val listChannel = json.optString("channel")
        val listChannelId = json.optString("channelId")
        val results = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val kind = kind(item.optString("kind"))
            // A channel's own videos do not say whose they are: the channel's
            val ownChannel = kind == YouTubeKind.VIDEO && item.optString("channel").isEmpty()
            YouTubeResult(
                kind = kind,
                id = item.optString("id"),
                title = item.optString("title"),
                channel = if (ownChannel) listChannel else item.optString("channel"),
                channelId = if (ownChannel && item.optString("channelId").isEmpty()) listChannelId else item.optString("channelId"),
                duration = item.optString("duration"),
            )
        }
        return YouTubePage(results, json.optBoolean("more"), json.optString("title"), listChannel, listChannelId)
    }

    private fun listInfo(json: JSONObject) =
        YouTubeListInfo(kind(json.optString("kind")), json.optString("id"), json.optString("name"), json.optString("channel"))

    /** Search YouTube: [count] results from number [start] (1 is the first). */
    suspend fun search(query: String, start: Int, count: Int): YouTubePage = withContext(Dispatchers.IO) {
        page(call("search", query, start, count))
    }

    /** The videos of a channel or playlist page, [count] from number [start]. */
    suspend fun listVideos(url: String, start: Int, count: Int): YouTubePage = withContext(Dispatchers.IO) {
        page(call("list_videos", url, start, count))
    }

    /** What channel or playlist a page is. */
    suspend fun identify(url: String): YouTubeListInfo = withContext(Dispatchers.IO) { listInfo(call("identify", url)) }

    /** The channel a video belongs to. */
    suspend fun videoChannel(videoId: String): YouTubeListInfo = withContext(Dispatchers.IO) {
        listInfo(call("video_channel", videoId))
    }

    /** A video ready to play. Blocks: for worker threads (the address server's). */
    fun resolve(videoId: String): YouTubeMedia {
        val json = call("resolve", videoId)
        return YouTubeMedia(
            json.optString("title"),
            json.optString("channel"),
            json.optString("url").ifEmpty { null },
            json.optString("file").ifEmpty { null },
        )
    }

    /** A video's audio, or a playlist's, downloaded into [folder]: the files. Blocks. */
    fun download(url: String, folder: String, playlist: Boolean): Pair<List<String>, Int> {
        val json = call("download", url, folder, playlist)
        val files = json.optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).map { files.getString(it) } to json.optInt("failed")
    }

    private fun version(json: JSONObject) = YtDlpVersion(json.optString("version"), json.optString("source") == "downloaded")

    /** The yt-dlp in use (starting it if need be). */
    suspend fun version(): YtDlpVersion = withContext(Dispatchers.IO) { version(call("version")) }

    /** Known without starting anything: null until yt-dlp has been used. */
    var lastVersion: YtDlpVersion? = null
        private set

    /**
     * Checks for a newer yt-dlp (at most once a day unless [force]) and puts it in
     * use: the version now, whether it changed, and why the check failed, if it did.
     */
    suspend fun update(force: Boolean): Triple<YtDlpVersion, Boolean, String?> = withContext(Dispatchers.IO) {
        val json = call("update", force)
        val now = version(json)
        lastVersion = now
        Triple(now, json.optBoolean("updated"), json.optString("problem").ifEmpty { null })
    }
}
