package me.masonasons.fastplay.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.masonasons.fastplay.files.FileEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

/** The bytes of a range of a file LoopbackServer serves: from `start`, up to (not including) `end`. */
typealias LoopbackReader = suspend (start: Long, end: Long) -> ByteArray

/**
 * A tiny HTTP server on this device's loopback address, serving files that only the
 * app can read (an SMB share's, an SFTP server's) to the engine, which plays
 * addresses. It answers GET with or without a byte range, which is all FFmpeg asks,
 * so the engine streams and seeks in such a file as it does one on a web server.
 *
 * A file's address stays the same from one run of FastPlay to the next: the same
 * port where it can, and a name made of the file's source and path rather than a
 * new one each time. FastPlay remembers what it was playing, and where it was in
 * long files, by address; an address that changed every run left a remembered file
 * on a server unplayable (the engine waited out a server that was no longer there).
 * An address asked for that this run has not served yet is looked up in what
 * earlier runs served, and its server connected to again.
 *
 * Each connection is answered on a thread of its own, never the main thread: the
 * engine reopens the remembered file on the main thread as the app starts, and
 * waits there for this server's answer.
 */
object LoopbackServer {
    private class Item(val size: Long, val read: LoopbackReader)

    /** By address name, oldest first. */
    private val items = LinkedHashMap<String, Item>()
    private var listener: ServerSocket? = null
    private val threads = Executors.newCachedThreadPool { work ->
        Thread(work, "FastPlay.LoopbackServer").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Servers being connected to again, by address name: one attempt each, however many ask. */
    private val revivals = mutableMapOf<String, Deferred<Boolean>>()

    private const val KEEP = 200            // files remembered, the oldest forgotten
    private const val CHUNK = 512 * 1024L
    // The port asked for first, so that addresses outlast the run that made them
    private const val PREFERRED_PORT = 52801
    // What earlier runs served, by address name: enough to serve it again
    private const val SERVED_KEY = "LoopbackServed"
    private const val SERVED_KEEP = 1000
    // How long a server from an earlier run has to answer before it is given up on
    private const val REVIVE_TIMEOUT = 8_000L

    /**
     * An address the engine can play the file from: `path` in the source whose id is
     * `source` (FileSource.id), the same each time it is asked for.
     */
    suspend fun address(name: String, size: Long, source: String, path: String, read: LoopbackReader): String {
        val port = withContext(Dispatchers.IO) { start() }
        val id = stableId(source, path)
        synchronized(items) {
            // (Replacing one keeps its place in the order, as the iPhone's does)
            items[id] = Item(size, read)
            while (items.size > KEEP) items.remove(items.keys.first())
        }
        withContext(Dispatchers.IO) { remember(id, source, path, name) }
        // The name is only for show (the engine names a stream by its address's end)
        return "http://127.0.0.1:$port/$id/${escape(name).ifEmpty { "file" }}"
    }

    /** The name in a file's address: the same for the same file, every run. */
    private fun stableId(source: String, path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$source\n$path".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun escape(name: String): String {
        val out = StringBuilder()
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val char = c.toChar()
            if (c < 0x80 && (char.isLetterOrDigit() || char in "-._")) out.append(char) else out.append("%%%02X".format(c))
        }
        return out.toString()
    }

    // ---- What earlier runs served ----

    /** Address name to [source, path, name]. */
    private fun served(): JSONObject = try {
        JSONObject(remotePrefs().getString(SERVED_KEY, null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    @Synchronized
    private fun remember(id: String, source: String, path: String, name: String) {
        val prefs = remotePrefs()
        val served = served()
        val order = try {
            JSONArray(prefs.getString(SERVED_KEY + "Order", null) ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val ids = (0 until order.length()).map { order.getString(it) }.toMutableList()
        if (!served.has(id)) ids.add(id)
        served.put(id, JSONArray(listOf(source, path, name)))
        while (ids.size > SERVED_KEEP) served.remove(ids.removeAt(0))
        prefs.edit()
            .putString(SERVED_KEY, served.toString())
            .putString(SERVED_KEY + "Order", JSONArray(ids).toString())
            .apply()
    }

    /**
     * An address served in an earlier run, served again: its server connected to, as
     * its saved details say. False if it cannot be. Blocks (a connection's thread).
     */
    private fun revive(id: String): Boolean {
        val attempt = synchronized(revivals) {
            revivals[id]?.takeIf { it.isActive } ?: scope.async { reconnect(id) }.also { revivals[id] = it }
        }
        // A server that does not answer soon is given up on, as the engine may be
        // waiting on this while the app opens
        val revived = runBlocking { withTimeoutOrNull(REVIVE_TIMEOUT) { attempt.await() } } ?: false
        synchronized(revivals) { if (revivals[id] === attempt && !attempt.isActive) revivals.remove(id) }
        return revived || synchronized(items) { items.containsKey(id) }
    }

    private suspend fun reconnect(id: String): Boolean {
        val known = served().optJSONArray(id) ?: return false
        if (known.length() != 3) return false
        val server = ServerStore.all.firstOrNull { "server-${it.id}" == known.getString(0) } ?: return false
        val entry = FileEntry(known.getString(2), known.getString(1), known.getString(1), isFolder = false)
        // Registers it under the same name again
        try {
            server.makeSource().streamUrl(entry)
        } catch (e: Exception) {
            return false
        }
        return synchronized(items) { items.containsKey(id) }
    }

    /**
     * Listens now, if a file on a server has ever been played: FastPlay reopens what
     * it was playing as it starts, and its address must be answered. For the app's
     * start, before the engine's; it only opens a socket, so it is quick.
     */
    fun startForRestore() {
        if (served().length() == 0) return
        try {
            start()
        } catch (e: Exception) {
            // Then the remembered file cannot be reached; nothing else is lost
        }
    }

    // ---- Listening ----

    /** Starts listening if it is not already; the port it is on. */
    @Synchronized
    private fun start(): Int {
        listener?.let { if (!it.isClosed) return it.localPort }
        // The same port as last time where it is free, so remembered addresses still work
        val socket = try {
            listen(PREFERRED_PORT)
        } catch (e: IOException) {
            listen(0)
        }
        listener = socket
        Thread({ accept(socket) }, "FastPlay.LoopbackServer.accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    private fun listen(port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            // This device only
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    private fun accept(socket: ServerSocket) {
        while (true) {
            val connection = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            threads.execute { serve(connection) }
        }
        // Closed under it (it is started again when next needed)
        synchronized(this) { if (listener === socket) listener = null }
    }

    // ---- Answering ----

    private fun serve(connection: Socket) {
        try {
            connection.use {
                it.soTimeout = 30_000
                val request = readRequest(it) ?: return
                respond(it, request)
            }
        } catch (e: Exception) {
            // The engine went (as it does to seek), or the file could not be had: the connection ends
        }
    }

    /** Reads up to the blank line that ends a request's headers. */
    private fun readRequest(connection: Socket): String? {
        val input = connection.getInputStream()
        val received = StringBuilder()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return null
            received.append(String(buffer, 0, count, Charsets.ISO_8859_1))
            val end = received.indexOf("\r\n\r\n")
            if (end >= 0) return received.substring(0, end)
            if (received.length > 64 * 1024) return null
        }
    }

    private fun respond(connection: Socket, request: String) {
        val lines = request.split("\r\n")
        val parts = lines.first().split(" ").filter { it.isNotEmpty() }
        // "GET /<id>/<name> HTTP/1.1"
        val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        val id = parts.getOrNull(1)?.split("/")?.firstOrNull { it.isNotEmpty() }
        if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD") || id == null) {
            return send(connection, notFound)
        }
        var item = synchronized(items) { items[id] }
        if (item == null) {
            // From an earlier run (a remembered file): connect to its server again
            item = if (revive(id)) synchronized(items) { items[id] } else null
            if (item == null) return send(connection, notFound)
        }
        var start = 0L
        var end = item.size - 1
        var partial = false
        for (line in lines.drop(1)) {
            if (!line.lowercase().startsWith("range:")) continue
            // "Range: bytes=100-" or "bytes=100-199"
            val value = line.substring(6).trim()
            if (!value.startsWith("bytes=")) continue
            val bounds = value.substring(6).split("-")
            bounds.firstOrNull()?.trim()?.toLongOrNull()?.let { start = it }
            if (bounds.size > 1) bounds[1].trim().toLongOrNull()?.let { end = minOf(it, item.size - 1) }
            partial = true
        }
        if (start > end || start < 0) {
            return send(connection, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${item.size}\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n")
        }
        var head = if (partial) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${item.size}\r\n"
        else "HTTP/1.1 200 OK\r\n"
        head += "Accept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\n" +
            "Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n"
        send(connection, head)
        if (parts[0] == "GET") stream(connection, item, start, end)
    }

    private fun send(connection: Socket, head: String) {
        val output = connection.getOutputStream()
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /**
     * Sends the range a piece at a time, each read as the last has gone out, until it
     * is all sent or the engine closes the connection (as it does to seek).
     */
    private fun stream(connection: Socket, item: Item, start: Long, end: Long) {
        val output = connection.getOutputStream()
        var offset = start
        while (offset <= end) {
            val upper = minOf(offset + CHUNK, end + 1)
            val data = try {
                runBlocking { item.read(offset, upper) }
            } catch (e: Exception) {
                break
            }
            if (data.isEmpty()) break
            output.write(data)  // throws when the engine has gone: the end
            output.flush()
            offset += data.size
        }
    }
}
