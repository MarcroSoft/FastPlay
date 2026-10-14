package me.masonasons.fastplay.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.masonasons.fastplay.dropbox.DropboxClient
import me.masonasons.fastplay.remote.ServerStore
import me.masonasons.fastplay.ui.Screen
import java.io.File

/**
 * A file or folder in a [FileSource]. [path] is the path as the source wants it in
 * requests; [displayPath] is the path as the user knows it, the names with their
 * own capitals. The part of it below a folder is where a download of that folder
 * puts the file. [modified] is milliseconds since 1970, or null.
 */
data class FileEntry(
    val name: String,
    val path: String,
    val displayPath: String,
    val isFolder: Boolean,
    val size: Long = 0,
    val modified: Long? = null,
)

/** Something a source could not do, said in words for the user. */
class RemoteError(message: String) : Exception(message)

/**
 * Somewhere files are: this device's own (LocalSource), Dropbox, an FTP, SFTP or
 * SMB server. The browser (BrowserScreen) and pasting (FileOperations) are the
 * same for all of them; what a source cannot do, it says so by throwing.
 */
interface FileSource {
    /** Who it is, for the clipboard and favorites: "local", "dropbox", "server-<id>". */
    val id: String
    /** What it is called ("Files", "Dropbox", a server's name). */
    val title: String
    /** Its top folder. */
    val rootPath: String get() = ""
    /** This device's own files: read and written where they are, nothing copied. */
    val isLocal: Boolean get() = false
    /** Whether files can be added, renamed and deleted here. */
    val canWrite: Boolean get() = false

    /** What is in a folder, in any order. */
    suspend fun list(path: String): List<FileEntry>

    /** Every file in a folder and the folders below it. Folder by folder, unless the source can do better. */
    suspend fun listAll(path: String): List<FileEntry> {
        val files = mutableListOf<FileEntry>()
        val folders = ArrayDeque(listOf(path))
        while (folders.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            for (entry in list(folders.removeLast())) {
                if (entry.isFolder) folders.addLast(entry.path) else files.add(entry)
            }
        }
        return files
    }

    /** An address (or a path, here) the engine can play the file from. */
    suspend fun streamUrl(entry: FileEntry): String

    /** Copies the file to a place on this device (replacing what is there). */
    suspend fun download(entry: FileEntry, destination: File)

    /** Something for the top folder's menu ("Sign Out"). */
    val accountAction: Pair<String, (Screen) -> Unit>? get() = null

    /** The path of `name` in `folder`. */
    fun path(of: String, inFolder: String): String =
        if (inFolder.endsWith("/")) inFolder + of else "$inFolder/$of"

    suspend fun createFolder(name: String, inFolder: String): Unit = throw cannot("make folders")
    /** Copies a file on this device into `folder` as `name`, replacing one there. */
    suspend fun upload(file: File, name: String, toFolder: String): Unit = throw cannot("add files")
    suspend fun rename(entry: FileEntry, to: String): Unit = throw cannot("rename")
    /** Deletes a file, or a folder and everything in it. */
    suspend fun delete(entry: FileEntry): Unit = throw cannot("delete")
    /**
     * Moves within this source, nothing in the way. False if it cannot (it is then
     * copied and the original deleted).
     */
    suspend fun move(entry: FileEntry, toFolder: String): Boolean = false
    /** Free and total space in bytes, where the source can say. */
    suspend fun storageSpace(): Pair<Long, Long>? = null

    private fun cannot(what: String) = RemoteError("FastPlay cannot $what in $title.")

    /** The entry for a path just made here (a folder made, a file uploaded). */
    fun entry(named: String, inFolder: String, isFolder: Boolean, size: Long = 0): FileEntry {
        val full = path(named, inFolder)
        return FileEntry(named, full, full, isFolder, size, System.currentTimeMillis())
    }
}

/**
 * Deletes a folder's contents, deepest first, then the folder: for a source whose
 * server deletes only empty folders.
 */
suspend fun FileSource.deleteContents(
    folder: FileEntry,
    file: suspend (FileEntry) -> Unit,
    emptyFolder: suspend (FileEntry) -> Unit,
) {
    for (entry in list(folder.path)) {
        currentCoroutineContext().ensureActive()
        if (entry.isFolder) deleteContents(entry, file, emptyFolder) else file(entry)
    }
    emptyFolder(folder)
}

/** The sources there are, by id: for favorites and the clipboard, which keep only the id. */
object FileSources {
    fun source(id: String): FileSource? = when {
        id == LocalSource.id -> LocalSource
        id == DropboxClient.id -> DropboxClient
        id.startsWith("server-") -> ServerStore.all.firstOrNull { "server-${it.id}" == id }?.makeSource()
        else -> null
    }

    /** "Files", "Dropbox", a server's name, for a source that may be gone. */
    fun title(id: String): String = source(id)?.title ?: "A server no longer added"
}
