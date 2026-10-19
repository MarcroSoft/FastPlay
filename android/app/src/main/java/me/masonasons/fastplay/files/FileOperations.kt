package me.masonasons.fastplay.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.masonasons.fastplay.FastPlayApp
import java.io.File
import java.util.UUID

/** Files and folders cut or copied, from any source, waiting to be pasted. */
object FileClipboard {
    class Item(val source: FileSource, val entry: FileEntry)

    var items: List<Item> = emptyList()
        private set
    /** Cut: pasting moves them. Copied: pasting leaves the originals. */
    var isCut = false
        private set

    fun set(items: List<Item>, cut: Boolean) {
        this.items = items
        isCut = cut
    }

    /** Something pasted, deleted or renamed: take it off the clipboard. */
    fun forget(source: FileSource, path: String) {
        items = items.filterNot { it.source.id == source.id && it.entry.path == path }
    }
}

/**
 * Copying and moving between any two sources (the browser's paste).
 *
 * A file goes by way of this device: a copy within it is a copy, anything else
 * is downloaded here (when it is not here already) and uploaded there. A move
 * within one source is that source's own move where it has one. When a name is
 * already taken, [resolver] is asked (with what to do for the rest, if wanted).
 * It runs on the main thread; the sources do their own work elsewhere.
 */
class FileOperations(
    private val isMove: Boolean,
    /** Asked about a name already taken: what is being pasted and what is there. Null stops the paste. */
    private val resolver: suspend (pasting: FileEntry, existing: FileEntry) -> Choice?,
    /**
     * Where the paste has got, in words, as it goes and every second
     * (TransferProgress): the file, how much of it has come and how fast.
     */
    progress: (String) -> Unit,
) {
    private val transfer = TransferProgress(progress)

    enum class Resolution(val title: String) {
        REPLACE("Replace"),
        /** Only if the one being pasted is newer or another size. */
        REPLACE_IF_DIFFERENT("Replace if Newer or Another Size"),
        KEEP_BOTH("Keep Both"),
        SKIP("Skip"),
        /** A folder: what is in the one pasted goes into the one there. */
        MERGE("Merge Into the Folder There"),
        /** A folder: merged, then what is in the one there and not in the one pasted is deleted. */
        SYNC("Sync (Make the Folder There the Same)");

        companion object {
            val forFiles = listOf(REPLACE, REPLACE_IF_DIFFERENT, KEEP_BOTH, SKIP)
            val forFolders = listOf(MERGE, SYNC, REPLACE, KEEP_BOTH, SKIP)
        }
    }

    class Choice(val resolution: Resolution, val applyToAll: Boolean)

    /** The user stopped the paste. */
    class Stopped : Exception("Stopped.")

    private var rememberedFile: Resolution? = null
    private var rememberedFolder: Resolution? = null
    var filesDone = 0
        private set
    private val temporary = File(FastPlayApp.instance.cacheDir, "Pasting-${UUID.randomUUID()}")

    /** Pastes [items] into [folder] of [destination]. */
    suspend fun paste(items: List<FileClipboard.Item>, destination: FileSource, folder: String) = transfer.run {
        try {
            val there = names(destination, folder)
            for (item in items) {
                currentCoroutineContext().ensureActive()
                if (item.source.id == destination.id && item.entry.isFolder &&
                    (same(folder, item.entry.path) || isBelow(folder, item.entry.path))
                ) {
                    throw RemoteError("${item.entry.name} cannot go inside itself.")
                }
                // Cut and pasted where it already is: nothing to do
                if (isMove && item.source.id == destination.id && same(parent(item.entry.path), folder)) continue
                process(item.source, item.entry, destination, folder, there, cascade = null)
            }
        } finally {
            temporary.deleteRecursively()
        }
    }

    // ---- One item ----

    /**
     * [there]: what is in [folder] now, by name in lower case (sources such as
     * Dropbox and SMB take two names differing only in case as the same).
     */
    private suspend fun process(
        source: FileSource, entry: FileEntry, destination: FileSource, folder: String,
        there: MutableMap<String, FileEntry>, cascade: Resolution?,
    ) {
        currentCoroutineContext().ensureActive()
        val existing = there[entry.name.lowercase()]
        if (entry.isFolder) folderItem(source, entry, destination, folder, existing, there, cascade)
        else fileItem(source, entry, destination, folder, existing, there, cascade)
    }

    private suspend fun fileItem(
        source: FileSource, entry: FileEntry, destination: FileSource, folder: String,
        existing: FileEntry?, there: MutableMap<String, FileEntry>, cascade: Resolution?,
    ) {
        if (existing == null) {
            if (fastMove(source, entry, destination, folder)) return
            copyFile(source, entry, destination, folder, entry.name, there)
            if (isMove) source.delete(entry)
            return
        }
        // Inside a folder being merged or synced, changed files are replaced without asking
        val resolution = if (cascade != null) Resolution.REPLACE_IF_DIFFERENT else rememberedFile ?: ask(entry, existing)
        when (resolution) {
            Resolution.REPLACE -> replaceFile(source, entry, destination, folder, existing, there)
            Resolution.REPLACE_IF_DIFFERENT -> {
                val newer = (entry.modified ?: 0) > (existing.modified ?: 0) + 1000
                if (entry.size != existing.size || newer) replaceFile(source, entry, destination, folder, existing, there)
            }
            Resolution.KEEP_BOTH -> {
                copyFile(source, entry, destination, folder, uniqueName(entry.name, there), there)
                if (isMove) source.delete(entry)
            }
            else -> {}  // skip (or a folder's choice)
        }
    }

    private suspend fun replaceFile(
        source: FileSource, entry: FileEntry, destination: FileSource, folder: String,
        existing: FileEntry, there: MutableMap<String, FileEntry>,
    ) {
        // Under the name already there, whatever its capitals
        copyFile(source, entry, destination, folder, existing.name, there)
        if (isMove) source.delete(entry)
    }

    private suspend fun folderItem(
        source: FileSource, entry: FileEntry, destination: FileSource, folder: String,
        existing: FileEntry?, there: MutableMap<String, FileEntry>, cascade: Resolution?,
    ) {
        if (existing == null || !existing.isFolder) {
            if (existing == null && fastMove(source, entry, destination, folder)) {
                there[entry.name.lowercase()] = destination.entry(entry.name, folder, isFolder = true)
                return
            }
            // A file holds the name: the folder alongside, under another
            val name = if (existing == null) entry.name else uniqueName(entry.name, there)
            val made = makeFolder(name, destination, folder, there)
            copyContents(source, entry, destination, made.path, cascade = null)
            if (isMove) deleteIfEmpty(source, entry)
            return
        }
        when (cascade ?: rememberedFolder ?: ask(entry, existing)) {
            Resolution.MERGE -> {
                copyContents(source, entry, destination, existing.path, cascade = Resolution.MERGE)
                if (isMove) deleteIfEmpty(source, entry)
            }
            Resolution.SYNC -> {
                val pasted = source.list(entry.path)
                val inside = names(destination, existing.path)
                for (child in pasted) process(source, child, destination, existing.path, inside, cascade = Resolution.SYNC)
                val keep = pasted.map { it.name.lowercase() }.toSet()
                for (extra in destination.list(existing.path)) {
                    if (extra.name.lowercase() in keep) continue
                    currentCoroutineContext().ensureActive()
                    destination.delete(extra)
                }
                if (isMove) deleteIfEmpty(source, entry)
            }
            Resolution.REPLACE -> {
                destination.delete(existing)
                there.remove(existing.name.lowercase())
                val made = makeFolder(entry.name, destination, folder, there)
                copyContents(source, entry, destination, made.path, cascade = null)
                if (isMove) deleteIfEmpty(source, entry)
            }
            Resolution.KEEP_BOTH -> {
                val made = makeFolder(uniqueName(entry.name, there), destination, folder, there)
                copyContents(source, entry, destination, made.path, cascade = null)
                if (isMove) deleteIfEmpty(source, entry)
            }
            else -> {}  // skip
        }
    }

    // ---- Pieces ----

    private suspend fun copyContents(
        source: FileSource, folder: FileEntry, destination: FileSource, destinationFolder: String, cascade: Resolution?,
    ) {
        val inside = names(destination, destinationFolder)
        for (child in source.list(folder.path)) process(source, child, destination, destinationFolder, inside, cascade)
    }

    private suspend fun makeFolder(
        name: String, destination: FileSource, folder: String, there: MutableMap<String, FileEntry>,
    ): FileEntry {
        destination.createFolder(name, folder)
        val made = destination.entry(name, folder, isFolder = true)
        there[name.lowercase()] = made
        return made
    }

    /** A move within one source, where it has its own. */
    private suspend fun fastMove(source: FileSource, entry: FileEntry, destination: FileSource, folder: String): Boolean {
        if (!isMove || source.id != destination.id) return false
        return source.move(entry, folder)
    }

    private suspend fun copyFile(
        source: FileSource, entry: FileEntry, destination: FileSource, folder: String, name: String,
        there: MutableMap<String, FileEntry>,
    ) {
        val heading = "${filesDone + 1}: $name"
        val target = destination.path(name, folder)
        if (source.isLocal && destination is LocalSource) {
            transfer.file(heading, entry.size, counted = false)
            destination.copy(File(entry.path), File(target))
        } else {
            transfer.file(heading, entry.size)
            // By way of this device
            var file = File(entry.path)
            var holder: File? = null
            try {
                if (!source.isLocal) {
                    holder = File(temporary, UUID.randomUUID().toString()).apply { mkdirs() }
                    file = File(holder, entry.name)
                    transfer.step("Downloading")
                    source.download(entry, file)
                }
                currentCoroutineContext().ensureActive()
                // Into this device it's a copy, which isn't counted
                if (!destination.isLocal) transfer.step("Uploading")
                destination.upload(file, name, folder)
            } finally {
                holder?.deleteRecursively()
            }
        }
        there[name.lowercase()] = destination.entry(name, folder, isFolder = false, size = entry.size)
        filesDone += 1
    }

    private suspend fun deleteIfEmpty(source: FileSource, folder: FileEntry) {
        if (source.list(folder.path).isEmpty()) source.delete(folder)
    }

    private suspend fun ask(pasting: FileEntry, existing: FileEntry): Resolution {
        val choice = resolver(pasting, existing) ?: throw Stopped()
        if (choice.applyToAll) {
            if (pasting.isFolder) rememberedFolder = choice.resolution else rememberedFile = choice.resolution
        }
        return choice.resolution
    }

    private suspend fun names(source: FileSource, folder: String): MutableMap<String, FileEntry> {
        val names = mutableMapOf<String, FileEntry>()
        for (entry in source.list(folder)) names[entry.name.lowercase()] = entry
        return names
    }

    // ---- Names and paths ----

    companion object {
        /** "Song (2).mp3", "Song (3).mp3"... the first not taken. */
        fun uniqueName(name: String, there: Map<String, FileEntry>): String {
            if (there[name.lowercase()] == null) return name
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot + 1) else ""
            var number = 2
            while (true) {
                val candidate = if (ext.isEmpty()) "$base ($number)" else "$base ($number).$ext"
                if (there[candidate.lowercase()] == null) return candidate
                number += 1
            }
        }

        /** The folder a path is in ("" for one at the top of a source whose top is ""). */
        fun parent(of: String): String {
            val trimmed = of.removeSuffix("/")
            val slash = trimmed.lastIndexOf('/')
            return if (slash < 0) "" else trimmed.substring(0, slash)
        }

        /**
         * Whether two paths of one source are the same place (ignoring a trailing
         * slash, and case). A top of "/" and one of "" are the same.
         */
        fun same(a: String, b: String): Boolean = trimmed(a).equals(trimmed(b), ignoreCase = true)

        /** Whether [path] is inside [folder]. */
        fun isBelow(path: String, folder: String): Boolean =
            trimmed(path).lowercase().startsWith(trimmed(folder).lowercase() + "/")

        private fun trimmed(path: String) = path.trimEnd('/')
    }
}
