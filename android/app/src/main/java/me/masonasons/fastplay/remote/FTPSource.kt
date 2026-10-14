package me.masonasons.fastplay.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.engine.Native
import me.masonasons.fastplay.files.FileEntry
import me.masonasons.fastplay.files.FileOperations
import me.masonasons.fastplay.files.FileSource
import me.masonasons.fastplay.files.RemoteError
import me.masonasons.fastplay.files.deleteContents
import java.io.File

/**
 * An FTP server: listed, streamed and copied from through FFmpeg, which the engine
 * plays an ftp:// address with as it does an http:// one.
 */
class FTPSource(private val server: RemoteServer, password: String? = null) : FileSource {
    private val given = password
    /** The password given, or the one saved for the server (read when first needed). */
    private val password by lazy { given ?: server.password }

    override val id get() = "server-${server.id}"
    override val title get() = server.displayName
    override val canWrite get() = true

    // ---- Changing things (FTPClient: FFmpeg only reads) ----

    /** `work` on a connection opened for it, and closed after. */
    private suspend fun <T> withClient(work: suspend (FTPClient) -> T): T = withContext(Dispatchers.IO) {
        FTPClient(server.host, server.port).use { client ->
            client.open(server.user, password)
            work(client)
        }
    }

    override suspend fun createFolder(name: String, inFolder: String) =
        withClient { it.makeFolder(path(name, inFolder)) }

    override suspend fun upload(file: File, name: String, toFolder: String) =
        withClient { it.upload(file, path(name, toFolder)) }

    override suspend fun rename(entry: FileEntry, to: String) {
        val target = path(to, FileOperations.parent(entry.path))
        withClient { it.rename(entry.path, target) }
    }

    override suspend fun move(entry: FileEntry, toFolder: String): Boolean {
        val target = path(entry.name, toFolder)
        withClient { it.rename(entry.path, target) }
        return true
    }

    override suspend fun delete(entry: FileEntry) = withClient { client ->
        if (entry.isFolder) {
            // A server deletes only empty folders: what is in it first
            deleteContents(entry, { client.deleteFile(it.path) }, { client.deleteEmptyFolder(it.path) })
        } else {
            client.deleteFile(entry.path)
        }
    }

    /** The address of a path on the server, the user name and password in it. */
    private fun address(path: String): String {
        // Only the name and password are escaped: FFmpeg sends the path as it is
        val text = StringBuilder("ftp://")
        if (server.user.isNotEmpty()) {
            text.append(escape(server.user))
            if (password.isNotEmpty()) text.append(':').append(escape(password))
            text.append('@')
        }
        text.append(server.host)
        server.port?.let { text.append(':').append(it) }
        return text.toString() + if (path.startsWith("/")) path else "/$path"
    }

    private fun escape(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val char = c.toChar()
            if (c < 0x80 && (char.isLetterOrDigit() || char in "-._~")) out.append(char) else out.append("%%%02X".format(c))
        }
        return out.toString()
    }

    override suspend fun list(path: String): List<FileEntry> {
        val folder = if (path.endsWith("/")) path else "$path/"
        val url = address(folder)
        // FFmpeg's calls block on the network
        val items = withContext(Dispatchers.IO) { Native.ftpList(url) }
        // Fours (name, folder, size, modified), or one string: what went wrong
        if (items.size % 4 != 0) throw RemoteError(items.firstOrNull() ?: "Could not open the folder.")
        return (items.indices step 4).map { index ->
            val name = items[index]
            val seconds = items[index + 3].toLongOrNull() ?: 0
            FileEntry(
                name = name, path = folder + name, displayPath = folder + name,
                isFolder = items[index + 1] == "1",
                size = items[index + 2].toLongOrNull() ?: 0,
                modified = if (seconds > 0) seconds * 1000 else null,
            )
        }
    }

    override suspend fun streamUrl(entry: FileEntry): String = address(entry.path)

    override suspend fun download(entry: FileEntry, destination: File) {
        val url = address(entry.path)
        val job = currentCoroutineContext()[Job]
        val error = withContext(Dispatchers.IO) {
            Native.ftpCopy(url, destination.path) { job?.isActive == false }
        }
        currentCoroutineContext().ensureActive()
        if (error == "Cancelled.") throw CancellationException(error)
        if (error.isNotEmpty()) throw RemoteError(error)
    }
}
