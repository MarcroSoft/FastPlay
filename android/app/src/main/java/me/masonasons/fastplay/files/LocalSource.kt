package me.masonasons.fastplay.files

import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * This device's own files: its shared storage, where music and FastPlay's own
 * folder are (the iPhone's is FastPlay's folder alone, which is all an iPhone app
 * may see). A path here is a file system path. Browsing it needs all files access,
 * which the browser asks for (StorageAccess).
 */
object LocalSource : FileSource {
    override val id = "local"
    override val title = "Files"
    override val rootPath: String get() = Environment.getExternalStorageDirectory().path
    override val isLocal = true
    override val canWrite = true

    private fun entry(file: File) = FileEntry(
        name = file.name, path = file.path, displayPath = file.path, isFolder = file.isDirectory,
        size = if (file.isDirectory) 0 else file.length(), modified = file.lastModified().takeIf { it > 0 },
    )

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val files = File(path).listFiles() ?: throw RemoteError("FastPlay cannot read this folder.")
        files.filter { !it.isHidden }.map { entry(it) }
    }

    override suspend fun listAll(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        File(path).walkTopDown()
            .onEnter { it.path == path || !it.isHidden }
            .filter { it.isFile && !it.isHidden }
            .map { entry(it) }
            .toList()
    }

    override suspend fun streamUrl(entry: FileEntry) = entry.path

    override suspend fun download(entry: FileEntry, destination: File) = withContext(Dispatchers.IO) {
        destination.delete()
        File(entry.path).copyTo(destination, overwrite = true)
        Unit
    }

    override suspend fun createFolder(name: String, inFolder: String) = withContext(Dispatchers.IO) {
        val folder = File(path(name, inFolder))
        if (folder.exists() || !folder.mkdir()) throw RemoteError("The folder $name could not be made.")
    }

    override suspend fun upload(file: File, name: String, toFolder: String) =
        copy(file, File(path(name, toFolder)))

    /** Copies within this device (a file or a folder), replacing what is there. */
    suspend fun copy(source: File, target: File) = withContext(Dispatchers.IO) {
        if (target.exists()) target.deleteRecursively()
        if (!source.copyRecursively(target, overwrite = true)) throw RemoteError("${source.name} could not be copied.")
    }

    override suspend fun rename(entry: FileEntry, to: String) = withContext(Dispatchers.IO) {
        val file = File(entry.path)
        if (!file.renameTo(File(file.parentFile, to))) throw RemoteError("${entry.name} could not be renamed.")
    }

    override suspend fun delete(entry: FileEntry) = withContext(Dispatchers.IO) {
        if (!File(entry.path).deleteRecursively()) throw RemoteError("${entry.name} could not be deleted.")
    }

    override suspend fun move(entry: FileEntry, toFolder: String): Boolean = withContext(Dispatchers.IO) {
        File(entry.path).renameTo(File(path(entry.name, toFolder)))
    }

    override suspend fun storageSpace(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val stat = StatFs(rootPath)
        stat.availableBytes to stat.totalBytes
    }
}
