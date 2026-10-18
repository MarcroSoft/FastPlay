package me.masonasons.fastplay.remote

import android.util.Base64
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.files.FileEntry
import me.masonasons.fastplay.files.FileOperations
import me.masonasons.fastplay.files.FileSource
import me.masonasons.fastplay.files.RemoteError
import me.masonasons.fastplay.files.TransferMeter
import me.masonasons.fastplay.files.deleteContents
import java.io.File
import java.io.InputStream

/**
 * An SFTP server (files over SSH): through JSch. Files are streamed through
 * LoopbackServer, as SMB's are, since FFmpeg does not speak SFTP.
 *
 * The server's key is remembered the first time FastPlay connects, and a server
 * that later shows another one is refused, as ssh does: it may not be the same
 * server. Deleting the server forgets its key.
 *
 * One SSH connection, made when first needed (and again if it has dropped, or was
 * closed for being idle): a channel on it for browsing, one for the file being
 * played, and one for each copy while it runs.
 */
class SFTPSource(private val server: RemoteServer, password: String? = null) : FileSource {
    private val given = password
    /** The password given, or the one saved for the server (read when first needed). */
    private val password by lazy { given ?: server.password }

    @Volatile private var session: Session? = null
    @Volatile private var browsing: ChannelSftp? = null
    private val browseLock = Any()
    /** The folder the server puts FastPlay in, for the top (""). */
    @Volatile private var home: String? = null
    private val idle = IdleCloser { close() }

    // The file being played: read on, from where the last piece ended, until asked
    // for another place or another file
    private val streamLock = Any()
    private var streamChannel: ChannelSftp? = null
    private var streamInput: InputStream? = null
    private var streamPath: String? = null
    private var streamPosition = 0L

    override val id get() = "server-${server.id}"
    override val title get() = server.displayName
    override val canWrite get() = true

    // ---- The connection ----

    @Synchronized
    private fun session(): Session {
        idle.used()
        session?.let { if (it.isConnected) return it }
        close()
        val known = KnownHost(server)
        val jsch = JSch()
        jsch.hostKeyRepository = known
        try {
            val session = jsch.getSession(server.user, server.host, server.port ?: 22)
            session.setPassword(password)
            session.userInfo = PasswordInfo(password)
            // KnownHost decides: the first key kept, another refused
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            session.setServerAliveInterval(30_000)
            session.setServerAliveCountMax(3)
            session.connect(TIMEOUT)
            this.session = session
            idle.opened()
            return session
        } catch (e: Exception) {
            if (known.refused) {
                throw RemoteError("${server.displayName} identified itself with a different key than before, so " +
                    "FastPlay did not connect: it may not be the same server. If the server was set up again, " +
                    "delete it from Servers and add it again.")
            }
            throw RemoteError("Could not connect to ${server.displayName}. ${e.message ?: ""}".trim())
        }
    }

    /** Ends the connection (its channels go with it); the next use connects again. */
    @Synchronized
    private fun close() {
        session?.disconnect()
        session = null
        browsing = null
    }

    private fun openChannel(): ChannelSftp {
        val channel = session().openChannel("sftp") as ChannelSftp
        channel.connect(TIMEOUT)
        return channel
    }

    /** `work` on the browsing channel, one request at a time (a channel is one conversation). */
    private fun <T> browse(work: (ChannelSftp) -> T): T = synchronized(browseLock) {
        val channel = browsing?.takeIf { it.isConnected && session?.isConnected == true }
            ?: openChannel().also { browsing = it }
        idle.used()
        work(channel)
    }

    /** Server work, off the main thread, its failures in words. */
    private suspend fun <T> io(work: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            work()
        } catch (e: RemoteError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RemoteError(e.message ?: "The server did not do it.")
        } finally {
            idle.used()
        }
    }

    /** The path FastPlay asks for: the top is the server's home folder. */
    private fun resolved(path: String): String {
        if (path.isNotEmpty()) return path
        home?.let { return it }
        val found = browse { it.realpath(".") }
        home = found
        return found
    }

    override fun path(of: String, inFolder: String): String {
        val base = inFolder.ifEmpty { home ?: "." }
        return if (base.endsWith("/")) base + of else "$base/$of"
    }

    // ---- Reading ----

    override suspend fun list(path: String): List<FileEntry> = io {
        val folder = resolved(path)
        val items = browse { it.ls(quote(folder)) }
        items.mapNotNull { item ->
            if (item.filename == "." || item.filename == "..") return@mapNotNull null
            val attributes = item.attrs
            // The kind is in the permissions; failing those, ls's long form
            val isFolder = if (attributes.flags and SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS != 0) attributes.isDir
            else item.longname?.startsWith("d") == true
            val modified = if (attributes.flags and SftpATTRS.SSH_FILEXFER_ATTR_ACMODTIME != 0) {
                attributes.mTime.toLong() * 1000
            } else {
                null
            }
            val full = path(item.filename, folder)
            FileEntry(item.filename, full, full, isFolder, attributes.size, modified)
        }
    }

    override suspend fun streamUrl(entry: FileEntry): String {
        var size = entry.size
        if (size <= 0) size = io { browse { it.stat(entry.path).size } }
        return LoopbackServer.address(entry.name, size, id, entry.path) { start, end ->
            withContext(Dispatchers.IO) { read(entry.path, start, end) }
        }
    }

    /** Bytes `start` up to `end` of the file at `path`, for LoopbackServer. */
    private fun read(path: String, start: Long, end: Long): ByteArray = synchronized(streamLock) {
        try {
            readOnce(path, start, end)
        } catch (e: Exception) {
            // The connection dropped, or was closed for being idle: once more, afresh
            closeStream()
            readOnce(path, start, end)
        }
    }

    private fun readOnce(path: String, start: Long, end: Long): ByteArray {
        idle.used()
        var input = streamInput
        if (input == null || streamPath != path || streamPosition != start || streamChannel?.isConnected != true) {
            closeStream()
            val channel = streamChannel?.takeIf { it.isConnected && session?.isConnected == true }
                ?: openChannel().also { streamChannel = it }
            input = channel.get(quote(path), null, start)
            streamInput = input
            streamPath = path
            streamPosition = start
        }
        val data = ByteArray((end - start).toInt())
        var filled = 0
        while (filled < data.size) {
            val count = input!!.read(data, filled, data.size - filled)
            if (count < 0) break
            filled += count
        }
        streamPosition += filled
        return if (filled == data.size) data else data.copyOf(filled)
    }

    private fun closeStream() {
        try {
            streamInput?.close()
        } catch (e: Exception) {
            // Its channel has gone
        }
        streamInput = null
        streamPath = null
    }

    override suspend fun download(entry: FileEntry, destination: File) = io {
        val watch = Watch(currentCoroutineContext()[Job], idle, TransferMeter.current())
        destination.delete()
        val channel = openChannel()
        try {
            destination.outputStream().use { output -> channel.get(quote(entry.path), output, watch) }
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            destination.delete()
            throw e
        } finally {
            channel.disconnect()
        }
    }

    // ---- Changing things ----

    override suspend fun createFolder(name: String, inFolder: String) = io {
        val parent = resolved(inFolder)
        browse { it.mkdir(path(name, parent)) }
    }

    override suspend fun upload(file: File, name: String, toFolder: String) = io {
        val parent = resolved(toFolder)
        val watch = Watch(currentCoroutineContext()[Job], idle, TransferMeter.current())
        val channel = openChannel()
        try {
            file.inputStream().use { input -> channel.put(input, quote(path(name, parent)), watch, ChannelSftp.OVERWRITE) }
            currentCoroutineContext().ensureActive()
        } finally {
            channel.disconnect()
        }
    }

    override suspend fun rename(entry: FileEntry, to: String) = io {
        val target = path(to, FileOperations.parent(entry.path))
        browse { it.rename(quote(entry.path), quote(target)) }
    }

    override suspend fun move(entry: FileEntry, toFolder: String): Boolean {
        io {
            val parent = resolved(toFolder)
            browse { it.rename(quote(entry.path), quote(path(entry.name, parent))) }
        }
        return true
    }

    override suspend fun delete(entry: FileEntry) = io {
        if (entry.isFolder) {
            // A server deletes only empty folders: what is in it first
            deleteContents(entry, { file -> browse { it.rm(quote(file.path)) } },
                { folder -> browse { it.rmdir(quote(folder.path)) } })
        } else {
            browse { it.rm(quote(entry.path)) }
        }
    }

    // ---- Pieces ----

    /** A copy's progress: going on while its task is, and the connection kept from looking idle. */
    /** Told of each piece sent or received: keeps the connection, counts it, and stops it if asked. */
    private class Watch(
        private val job: Job?, private val idle: IdleCloser, private val meter: TransferMeter?,
    ) : SftpProgressMonitor {
        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
        override fun count(count: Long): Boolean {
            idle.used()
            meter?.add(count)
            return job?.isActive != false
        }
        override fun end() {}
    }

    /** The password, for servers that ask for it as a question (keyboard-interactive) too. */
    private class PasswordInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<String>?, echo: BooleanArray?,
        ): Array<String>? {
            val prompts = prompt ?: return null
            // A question shown as it is typed is not the password: nothing to answer it with
            if (echo != null && echo.any { it }) return null
            return Array(prompts.size) { password }
        }
    }

    /** Trust on first use: the server's key is kept in the preferences, by server. */
    private class KnownHost(server: RemoteServer) : HostKeyRepository {
        private val name = hostKeyName(server)
        /** The server showed a key other than the one kept. */
        @Volatile var refused = false

        override fun check(host: String?, key: ByteArray?): Int {
            val presented = Base64.encodeToString(key ?: return HostKeyRepository.NOT_INCLUDED, Base64.NO_WRAP)
            val prefs = remotePrefs()
            val known = prefs.getString(name, null)
            return when (known) {
                null -> {
                    prefs.edit().putString(name, presented).apply()
                    HostKeyRepository.OK
                }
                presented -> HostKeyRepository.OK
                else -> {
                    refused = true
                    HostKeyRepository.CHANGED
                }
            }
        }

        // The key kept, so that the server is asked for that kind of key first
        private fun known(host: String?): Array<HostKey> {
            val stored = remotePrefs().getString(name, null) ?: return emptyArray()
            return try {
                arrayOf(HostKey(host ?: "", Base64.decode(stored, Base64.NO_WRAP)))
            } catch (e: Exception) {
                emptyArray()
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID() = "FastPlay"
        override fun getHostKey(): Array<HostKey> = known(null)
        override fun getHostKey(host: String?, type: String?): Array<HostKey> =
            known(host).filter { type == null || it.type == type }.toTypedArray()
    }

    companion object {
        private const val TIMEOUT = 20_000

        init {
            // Ed25519 keys and Curve25519 key exchange through Bouncy Castle's classes
            // (smbj brings them), as Android's Java cannot be counted on for them
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        }

        /**
         * A path as JSch takes it where it would read * and ? as wildcards (listing,
         * copying, renaming, deleting): those, and backslashes, escaped.
         */
        private fun quote(path: String) = path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")

        fun hostKeyName(server: RemoteServer) = "sftp-host-key-${server.id}"

        /** Forgets a server's key (it was deleted, or now is somewhere else). */
        fun forgetHostKey(server: RemoteServer) {
            remotePrefs().edit().remove(hostKeyName(server)).apply()
        }
    }
}
