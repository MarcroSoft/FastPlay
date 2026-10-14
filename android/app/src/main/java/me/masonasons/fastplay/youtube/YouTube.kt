package me.masonasons.fastplay.youtube

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.FastPlayApp
import me.masonasons.fastplay.engine.Engine
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTube through yt-dlp, Android's own (the iPhone has none): what the desktop's
 * youtube.cpp does, with yt-dlp in Python (YtDlp) and videos played through the
 * address server (YouTubeServer).
 */
object YouTube {
    /** Most videos a playlist or channel plays (its first ones). */
    private const val PLAY_LIMIT = 500

    private val main = Handler(Looper.getMainLooper())
    /** Work that outlives the screen that asked for it: downloads, updates */
    internal val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** A video's or playlist's address on YouTube (youtube.com, youtu.be, music.youtube.com). */
    fun isYouTubeUrl(address: String): Boolean = address.contains("youtube.com") || address.contains("youtu.be")

    fun videoUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

    /** Channel IDs are "UC" and 22 more characters; anything else is a @handle. */
    fun channelUrl(id: String) =
        if (isChannelId(id)) "https://www.youtube.com/channel/$id/videos" else "https://www.youtube.com/@$id/videos"

    fun listUrl(kind: YouTubeKind, id: String) = if (kind == YouTubeKind.PLAYLIST) playlistUrl(id) else channelUrl(id)

    fun isChannelId(id: String) = id.length == 24 && id.startsWith("UC")

    /** What a YouTube address is: a video, a playlist (and the video it was at), or a channel. */
    sealed class Link {
        data class Video(val id: String) : Link()
        data class Playlist(val id: String, val videoId: String?) : Link()
        /** A channel ID, or a @handle without the @ */
        data class Channel(val id: String) : Link()
    }

    /** As the desktop's ParseYouTubeURL: a playlist first, then a channel, then a video. */
    fun parse(url: String): Link? {
        fun after(marker: String, ends: String): String? {
            val at = url.indexOf(marker)
            if (at < 0) return null
            val start = at + marker.length
            val end = url.indexOfAny(ends.toCharArray(), start).let { if (it < 0) url.length else it }
            return url.substring(start, end).ifEmpty { null }
        }
        val video = after("v=", "&# ") ?: after("youtu.be/", "?# ") ?: after("/shorts/", "/?# ") ?: after("/live/", "/?# ")
        after("list=", "&# ")?.let { return Link.Playlist(it, video) }
        if (url.contains("/channel/") || url.contains("/@")) {
            (after("/channel/", "/?# ") ?: after("/@", "/?# "))?.let { return Link.Channel(it) }
        }
        return video?.let { Link.Video(it) }
    }

    // ---- Playing ----

    /** What the playlist calls a video: "Channel - Title", as the desktop does. */
    private fun name(title: String, channel: String) = if (channel.isEmpty()) title else "$channel - $title"

    /** The newest thing asked to play: one asked for earlier that is ready later is dropped. */
    private val latestPlay = AtomicInteger(0)

    /**
     * Plays a YouTube address: a video, or a playlist's videos as the playlist.
     * Throws with a message for the user if it could not.
     */
    suspend fun play(address: String) {
        when (val link = parse(address)) {
            is Link.Video -> playVideo(link.id)
            is Link.Playlist -> playList(playlistUrl(link.id), link.videoId)
            is Link.Channel -> playList(channelUrl(link.id), null)
            null -> throw YouTubeException("This is not a YouTube video, playlist or channel.")
        }
    }

    /**
     * Plays one video: it becomes the playlist, as an opened file does. False if
     * something else was asked to play while it was got ready.
     */
    suspend fun playVideo(videoId: String): Boolean {
        val request = latestPlay.incrementAndGet()
        YouTubeServer.start()
        val media = withContext(Dispatchers.IO) { YouTubeServer.media(videoId) }
        if (request != latestPlay.get()) return false
        withContext(Dispatchers.Main) { Engine.playUrl(YouTubeServer.address(videoId), name(media.title, media.channel)) }
        checkForUpdate()
        return true
    }

    /** Plays the videos of a playlist or channel page (its first ones), from [startVideo] or the first. */
    suspend fun playList(url: String, startVideo: String?): Boolean {
        val request = latestPlay.incrementAndGet()
        val page = YtDlp.listVideos(url, 1, PLAY_LIMIT)
        if (request != latestPlay.get()) return false
        val videos = page.results.filter { it.kind == YouTubeKind.VIDEO }
        if (videos.isEmpty()) throw YouTubeException("There are no videos to play here.")
        val start = videos.indexOfFirst { it.id == startVideo }.coerceAtLeast(0)
        return playVideos(videos, start)
    }

    /**
     * Plays videos as the playlist, from the one at [index]. Only that one is got
     * ready now; each of the others as the one before it plays (YouTubeServer).
     */
    suspend fun playVideos(videos: List<YouTubeResult>, index: Int): Boolean {
        val request = latestPlay.incrementAndGet()
        YouTubeServer.start()
        val first = videos[index]
        val media = withContext(Dispatchers.IO) { YouTubeServer.media(first.id) }
        if (request != latestPlay.get()) return false
        val names = videos.mapIndexed { i, video ->
            if (i == index) name(media.title, media.channel) else name(video.title, video.channel)
        }
        withContext(Dispatchers.Main) {
            Engine.playUrls(videos.map { YouTubeServer.address(it.id) }, names, index)
        }
        checkForUpdate()
        return true
    }

    /** yt-dlp checked for a newer release, at most once a day, in the background. */
    fun checkForUpdate() {
        background.launch {
            try {
                YtDlp.update(force = false)
            } catch (e: Exception) {
                // Tried again another day
            }
        }
    }

    // ---- Cookies ----

    private val cookiesFile get() = File(FastPlayApp.instance.filesDir, "youtube-cookies.txt")

    val hasCookies: Boolean get() = cookiesFile.exists()

    /**
     * A cookies.txt (Netscape format, as browser extensions export it) for yt-dlp to
     * use, for videos YouTube only shows to a signed-in account. Throws with what
     * is wrong with it.
     */
    fun importCookies(file: File) {
        var text = try {
            file.readText()
        } catch (e: Exception) {
            throw YouTubeException("Could not open ${file.name}.")
        }
        if (!text.contains("youtube.com\t")) {
            throw YouTubeException("This file has no YouTube cookies in cookies.txt (Netscape) format. Export the " +
                "cookies for youtube.com from your browser while signed in, and import that file.")
        }
        // yt-dlp wants the format's header line
        if (!text.startsWith("#")) text = "# Netscape HTTP Cookie File\n$text"
        try {
            cookiesFile.writeText(text)
        } catch (e: Exception) {
            throw YouTubeException("Could not save the cookies in FastPlay's data folder.")
        }
    }

    fun removeCookies() {
        cookiesFile.delete()
    }

    // ---- Downloads to keep ----

    /** Where downloads go: YouTube in FastPlay's folder. */
    val downloadFolder: File get() = File(Engine.documentsPath, "YouTube")

    @OptIn(ExperimentalCoroutinesApi::class)
    private val downloadQueue = Dispatchers.IO.limitedParallelism(1)
    private val downloadsWaiting = AtomicInteger(0)

    private fun say(text: String) = main.post { Engine.speak(text) }

    /**
     * Downloads a video's audio (or a whole playlist's) into [downloadFolder], as
     * YouTube's own M4A. Downloads run one at a time in the background; FastPlay
     * says when each starts, finishes or fails.
     */
    fun download(url: String, title: String, playlist: Boolean) {
        val ahead = downloadsWaiting.getAndIncrement()
        if (ahead > 0) Engine.speak("Queued $title, $ahead ahead")
        background.launch(downloadQueue) {
            try {
                say("Downloading $title")
                val folder = downloadFolder
                folder.mkdirs()
                if (!folder.canWrite()) {
                    throw YouTubeException("FastPlay cannot save in its folder. Allow it access to all files in the " +
                        "system's settings, and try again.")
                }
                val (files, failed) = YtDlp.download(url, folder.path, playlist)
                for (path in files) if (path.endsWith(".m4a", ignoreCase = true)) defragment(File(path))
                var done = "Downloaded $title"
                if (files.size > 1) done += ", ${files.size} files"
                if (failed > 0) done += ", $failed failed"
                say(done)
            } catch (e: Exception) {
                val why = e.message ?: ""
                main.post {
                    Engine.speak("Could not download $title")
                    Engine.notifyMessage("YouTube Download", "Could not download $title.\n\n$why")
                }
            } finally {
                downloadsWaiting.decrementAndGet()
            }
        }
    }

    /**
     * M4A straight from YouTube is fragmented, which not every player reads: written
     * again as an ordinary M4A. Left as it is if that cannot be done.
     */
    private fun defragment(file: File) {
        val fixed = File(file.path + ".fixed.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val writer = MediaMuxer(fixed.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val out = writer.addTrack(format)
            writer.start()
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocate(size)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, read, extractor.sampleTime, flags)
                writer.writeSampleData(out, buffer, info)
                extractor.advance()
            }
            writer.stop()
            writer.release()
            muxer = null
            if (fixed.length() > 0 && file.delete()) fixed.renameTo(file) else fixed.delete()
        } catch (e: Exception) {
            fixed.delete()
        } finally {
            try {
                muxer?.release()
            } catch (e: Exception) {
                // Already given up
            }
            extractor.release()
        }
    }
}
