package me.masonasons.fastplay.youtube

import android.os.SystemClock
import me.masonasons.fastplay.engine.Engine
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * YouTube videos as addresses the engine can play, and keep in its playlist: each
 * video is http://127.0.0.1:<port>/youtube/<id> on this little server, which gets
 * the video ready with yt-dlp when it is asked for and sends FFmpeg on to the
 * stream (a redirect), or serves the file its audio was downloaded to.
 *
 * So a playlist of any length plays without getting every video ready first, a
 * video's address does not expire (YouTube's do, in hours), and where each video
 * was is remembered as for any other track. The engine opens a track on the main
 * thread, so a video is got ready before it is wanted: the first before playing
 * starts, and the next one while the one before it plays.
 */
object YouTubeServer {
    private const val PORT_KEY = "YouTubeServerPort"
    /** YouTube's stream addresses last about six hours; these are used for less. */
    private const val FRESH_MS = 4 * 60 * 60 * 1000L

    private var server: ServerSocket? = null
    private val connections = Executors.newCachedThreadPool()
    private val resolver = Executors.newFixedThreadPool(2)

    private class Ready(val media: YouTubeMedia, val at: Long)
    private val ready = ConcurrentHashMap<String, Ready>()
    private val pending = ConcurrentHashMap<String, Future<YouTubeMedia>>()
    private val idPattern = Regex("^[A-Za-z0-9_-]{11}$")

    val port: Int get() = server?.localPort ?: Engine.prefs().getInt(PORT_KEY, 0)

    /**
     * Listens, on the port of last time if it can (the saved playlist has its
     * addresses), and gets ready the video the restored playlist would play.
     */
    @Synchronized
    fun start() {
        if (server != null) return
        val loopback = InetAddress.getByName("127.0.0.1")
        val saved = Engine.prefs().getInt(PORT_KEY, 0)
        val socket = try {
            ServerSocket(saved, 16, loopback)
        } catch (e: Exception) {
            ServerSocket(0, 16, loopback)
        }
        server = socket
        if (socket.localPort != saved) Engine.prefs().edit().putInt(PORT_KEY, socket.localPort).apply()
        Thread({
            while (true) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                connections.execute { serve(client) }
            }
        }, "YouTube server").apply { isDaemon = true }.start()
        Engine.addStateListener { prepareAround() }
        prepareAround()
    }

    /** The address a video plays from. */
    fun address(videoId: String) = "http://127.0.0.1:$port/youtube/$videoId"

    /** The video of one of these addresses, or null. */
    fun videoId(address: String): String? {
        val match = Regex("^http://127\\.0\\.0\\.1:(\\d+)/youtube/([A-Za-z0-9_-]{11})$").find(address) ?: return null
        return match.groupValues[2]
    }

    // ---- Getting videos ready ----

    private fun fresh(videoId: String): YouTubeMedia? {
        val known = ready[videoId] ?: return null
        if (known.media.file != null) {
            if (File(known.media.file).exists()) return known.media
        } else if (SystemClock.elapsedRealtime() - known.at < FRESH_MS) {
            return known.media
        }
        ready.remove(videoId)
        return null
    }

    /** Getting a video ready, started if it is not already under way. */
    private fun getReady(videoId: String): Future<YouTubeMedia> = pending.computeIfAbsent(videoId) {
        resolver.submit<YouTubeMedia> {
            try {
                YtDlp.resolve(videoId).also { ready[videoId] = Ready(it, SystemClock.elapsedRealtime()) }
            } finally {
                pending.remove(videoId)
            }
        }
    }

    /** A video ready to play, got ready now if it is not yet. Blocks. */
    fun media(videoId: String, timeoutSeconds: Long = 120): YouTubeMedia {
        fresh(videoId)?.let { return it }
        return try {
            getReady(videoId).get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Gets a video ready in the background, if it is not already. */
    fun prepare(videoId: String) {
        if (fresh(videoId) == null) getReady(videoId)
    }

    /** What is playing, and the track after it, ready (the main thread opens them). */
    private fun prepareAround() {
        val current = Engine.currentTrack
        if (current < 0) return
        for (index in listOf(current, current + 1)) {
            if (index >= Engine.trackCount) continue
            videoId(Engine.trackPath(index))?.let { prepare(it) }
        }
    }

    // ---- Serving ----

    private fun serve(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 30_000
                val input = BufferedInputStream(socket.getInputStream())
                val request = readLine(input) ?: return
                var range: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                val parts = request.split(' ')
                val method = parts.getOrElse(0) { "" }
                val path = parts.getOrElse(1) { "" }
                val id = path.removePrefix("/youtube/")
                val output = socket.getOutputStream()
                if (!path.startsWith("/youtube/") || !idPattern.matches(id)) {
                    return respond(output, "404 Not Found")
                }
                val media = try {
                    media(id)
                } catch (e: Exception) {
                    android.util.Log.w("FastPlay", "YouTube $id: ${e.message}")
                    return respond(output, "502 Bad Gateway")
                }
                when {
                    media.url != null -> respond(output, "302 Found", "Location: ${media.url}\r\n")
                    media.file != null -> sendFile(output, File(media.file), range, method == "HEAD")
                    else -> respond(output, "502 Bad Gateway")
                }
            } catch (e: Exception) {
                // The player went away
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
            if (line.length > 8192) return null
        }
    }

    private fun respond(output: OutputStream, status: String, headers: String = "") {
        output.write("HTTP/1.1 $status\r\n${headers}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    /** A downloaded file, all of it or the range asked for (FFmpeg seeks with ranges). */
    private fun sendFile(output: OutputStream, file: File, range: String?, headOnly: Boolean) {
        val length = file.length()
        var start = 0L
        var end = length - 1
        val partial = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (partial != null) {
            val (from, to) = partial.destructured
            if (from.isNotEmpty()) {
                start = from.toLong()
                if (to.isNotEmpty()) end = minOf(to.toLong(), length - 1)
            } else if (to.isNotEmpty()) {
                start = maxOf(0, length - to.toLong())
            }
            if (start >= length) {
                return respond(output, "416 Range Not Satisfiable", "Content-Range: bytes */$length\r\n")
            }
        }
        val count = end - start + 1
        val head = StringBuilder()
        head.append(if (partial != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: audio/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: $count\r\n")
        if (partial != null) head.append("Content-Range: bytes $start-$end/$length\r\n")
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray())
        if (!headOnly) {
            RandomAccessFile(file, "r").use { data ->
                data.seek(start)
                val buffer = ByteArray(64 * 1024)
                var left = count
                while (left > 0) {
                    val read = data.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    left -= read
                }
            }
        }
        output.flush()
    }
}
