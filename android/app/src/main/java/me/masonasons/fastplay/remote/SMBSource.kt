package me.masonasons.fastplay.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.files.FileEntry
import me.masonasons.fastplay.files.FileOperations
import me.masonasons.fastplay.files.FileSource
import me.masonasons.fastplay.files.RemoteError
import me.masonasons.fastplay.files.TransferMeter
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * An SMB server (a Windows share, a NAS, a Mac's file sharing): through smbj.
 *
 * A path here is "/Share/folder/file": its first part is the share. The top, "",
 * lists the server's shares. Files are streamed through LoopbackServer, since the
 * engine plays addresses and SMB is not one FFmpeg knows.
 *
 * One connection to the server, made when first needed and made again if it has
 * dropped; one that has not been used for a few minutes is closed (it is made
 * again when next needed), so a server left behind holds nothing open.
 */
class SMBSource(private val server: RemoteServer, password: String? = null) : FileSource {
    private val given = password
    /** The password given, or the one saved for the server (read when first needed). */
    private val password by lazy { given ?: server.password }

    private var client: SMBClient? = null
    private var session: Session? = null
    private val shares = mutableMapOf<String, DiskShare>()
    private val idle = IdleCloser { close() }

    override val id get() = "server-${server.id}"
    override val title get() = server.displayName
    override val canWrite get() = true

    // ---- The connection ----

    /** Signed in to the server, connecting if need be. */
    @Synchronized
    private fun session(): Session {
        idle.used()
        session?.let { if (it.connection.isConnected) return it }
        close()
        val config = SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(20, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        try {
            val connection = client.connect(server.host, server.port ?: 445)
            val session = if (server.user.isEmpty()) {
                // No name: the server's guest access, where it has one (else an anonymous one)
                try {
                    connection.authenticate(AuthenticationContext.guest())
                } catch (e: Exception) {
                    connection.authenticate(AuthenticationContext.anonymous())
                }
            } else {
                // "DOMAIN\user" names its domain
                val (domain, user) = server.user.split('\\', limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else "" to it[0]
                }
                connection.authenticate(AuthenticationContext(user, password.toCharArray(), domain))
            }
            this.client = client
            this.session = session
            idle.opened()
            return session
        } catch (e: Exception) {
            client.close()
            throw RemoteError("Could not connect to ${server.displayName}. ${e.message ?: ""}".trim())
        }
    }

    @Synchronized
    private fun close() {
        shares.clear()
        try {
            client?.close()
        } catch (e: Exception) {
            // Gone already
        }
        client = null
        session = null
    }

    /** The connection to a share, made the first time it is needed. */
    @Synchronized
    private fun share(name: String): DiskShare {
        val session = session()
        shares[name]?.let { if (it.isConnected) return it }
        val share = session.connectShare(name) as? DiskShare
            ?: throw RemoteError("$name is not a shared folder.")
        shares[name] = share
        return share
    }

    /** "/Share/a/b" as the share and the path within it, as SMB writes it ("a\b"). */
    private fun split(path: String): Pair<String, String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val share = parts.firstOrNull() ?: return "" to ""
        return share to parts.drop(1).joinToString("\\")
    }

    /**
     * The share's connection and the path within it, for something in a share (the
     * top, the list of shares, holds none).
     */
    private fun place(path: String): Pair<DiskShare, String> {
        val (share, below) = split(path)
        if (share.isEmpty()) throw RemoteError("Open a share first.")
        return share(share) to below
    }

    /** Server work, off the main thread, its failures in words. */
    private suspend fun <T> io(work: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            work()
        } catch (e: RemoteError) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RemoteError(e.message ?: "The server did not do it.")
        } finally {
            idle.used()
        }
    }

    // ---- Reading ----

    override suspend fun list(path: String): List<FileEntry> = io {
        val (share, below) = split(path)
        if (share.isEmpty()) {
            // The shares, as folders (not the hidden administrative ones)
            val service = ServerService(SMBTransportFactories.SRVSVC.getTransport(session()))
            service.shares1
                .filter { (it.type and 0x0FFFFFFF) == 0 && !it.netName.endsWith("$") }
                .map { FileEntry(it.netName, "/" + it.netName, "/" + it.netName, isFolder = true) }
        } else {
            val folder = "/" + share + if (below.isEmpty()) "" else "/" + below.replace('\\', '/')
            share(share).list(below).mapNotNull { item ->
                val name = item.fileName
                if (name == "." || name == "..") return@mapNotNull null
                val isFolder = item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                FileEntry(
                    name = name, path = "$folder/$name", displayPath = "$folder/$name", isFolder = isFolder,
                    size = if (isFolder) 0 else item.endOfFile,
                    modified = item.lastWriteTime?.toEpochMillis()?.takeIf { it > 0 },
                )
            }
        }
    }

    override suspend fun streamUrl(entry: FileEntry): String {
        val (shareName, below) = split(entry.path)
        var size = entry.size
        if (size <= 0) {
            size = io { share(shareName).getFileInformation(below).standardInformation.endOfFile }
        }
        val opened = OpenFile(shareName, below)
        return LoopbackServer.address(entry.name, size, id, entry.path) { start, end ->
            withContext(Dispatchers.IO) { opened.read(start, end) }
        }
    }

    override suspend fun download(entry: FileEntry, destination: File) = io {
        val (share, below) = place(entry.path)
        destination.delete()
        try {
            openForReading(share, below).use { file ->
                file.inputStream.use { input ->
                    destination.outputStream().use { output -> copy(input, output) }
                }
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    // ---- Changing things ----

    override suspend fun createFolder(name: String, inFolder: String) = io {
        val (share, below) = place(path(name, inFolder))
        share.mkdir(below)
    }

    override suspend fun upload(file: File, name: String, toFolder: String) = io {
        val (share, below) = place(path(name, toFolder))
        // One there is replaced
        share.openFile(below, EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { remote ->
            remote.outputStream.use { output -> file.inputStream().use { input -> copy(input, output) } }
        }
    }

    override suspend fun rename(entry: FileEntry, to: String) = io {
        val (share, below) = place(entry.path)
        val (_, target) = place(path(to, FileOperations.parent(entry.path)))
        moveItem(share, below, target)
    }

    override suspend fun move(entry: FileEntry, toFolder: String): Boolean {
        // Within one share only
        if (split(entry.path).first != split(toFolder).first) return false
        io {
            val (share, below) = place(entry.path)
            val (_, target) = place(path(entry.name, toFolder))
            moveItem(share, below, target)
        }
        return true
    }

    override suspend fun delete(entry: FileEntry) = io {
        val (share, below) = place(entry.path)
        if (entry.isFolder) share.rmdir(below, true) else share.rm(below)
    }

    override suspend fun storageSpace(): Pair<Long, Long>? {
        val share = split(server.startPath).first
        if (share.isEmpty()) return null
        return io {
            val info = share(share).shareInformation
            if (info.totalSpace > 0) info.callerFreeSpace to info.totalSpace else null
        }
    }

    // ---- Pieces ----

    private fun moveItem(share: DiskShare, from: String, to: String) {
        share.open(from, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(to, false) }
    }

    private fun openForReading(share: DiskShare, path: String): SmbFile =
        share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null)

    private suspend fun copy(input: java.io.InputStream, output: java.io.OutputStream) {
        val meter = TransferMeter.current()
        val chunk = ByteArray(256 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(chunk)
            if (count < 0) break
            output.write(chunk, 0, count)
            idle.used()
            meter?.add(count)
        }
    }

    /** A file opened on first use and kept open while it is being played (opened again if the connection was). */
    private inner class OpenFile(private val shareName: String, private val path: String) {
        private var file: SmbFile? = null

        @Synchronized
        fun read(start: Long, end: Long): ByteArray = try {
            readOnce(start, end)
        } catch (e: Exception) {
            // The connection dropped, or was closed for being idle: once more, afresh
            file = null
            readOnce(start, end)
        }

        private fun readOnce(start: Long, end: Long): ByteArray {
            val file = file?.takeIf { session?.connection?.isConnected == true }
                ?: openForReading(share(shareName), path).also { file = it }
            idle.used()
            val data = ByteArray((end - start).toInt())
            var filled = 0
            while (filled < data.size) {
                val count = file.read(data, start + filled, filled, data.size - filled)
                if (count <= 0) break
                filled += count
            }
            return if (filled == data.size) data else data.copyOf(filled)
        }
    }
}
