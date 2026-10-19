package me.masonasons.fastplay.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.engine.Callbacks
import me.masonasons.fastplay.engine.Engine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * A folder in Dropbox or on a server that is synced to this device by itself,
 * each time FastPlay starts. [lastSynced] is milliseconds since 1970.
 */
data class AutoSyncFolder(
    val id: String = UUID.randomUUID().toString(),
    /** Its source's id (FileSources). */
    val sourceId: String,
    val path: String,
    val displayPath: String,
    val name: String,
    val lastSynced: Long? = null,
    val lastOutcome: String? = null,
) {
    val folder get() = FileEntry(name, path, displayPath, isFolder = true)
}

/** The folders that sync by themselves, in the app's settings, in the order added. */
object AutoSyncStore {
    private const val KEY = "AutoSyncFolders"

    var all: List<AutoSyncFolder>
        get() {
            val text = Engine.prefs().getString(KEY, null) ?: return emptyList()
            return try {
                val array = JSONArray(text)
                (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    AutoSyncFolder(
                        id = item.getString("id"), sourceId = item.getString("sourceId"), path = item.getString("path"),
                        displayPath = item.getString("displayPath"), name = item.getString("name"),
                        lastSynced = if (item.has("lastSynced")) item.getLong("lastSynced") else null,
                        lastOutcome = if (item.has("lastOutcome")) item.getString("lastOutcome") else null,
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val array = JSONArray()
            for (folder in value) {
                array.put(JSONObject().put("id", folder.id).put("sourceId", folder.sourceId).put("path", folder.path)
                    .put("displayPath", folder.displayPath).put("name", folder.name)
                    .apply {
                        folder.lastSynced?.let { put("lastSynced", it) }
                        folder.lastOutcome?.let { put("lastOutcome", it) }
                    })
            }
            Engine.prefs().edit().putString(KEY, array.toString()).apply()
        }

    fun find(source: FileSource, path: String): AutoSyncFolder? =
        all.firstOrNull { it.sourceId == source.id && FileOperations.same(it.path, path) }

    fun add(source: FileSource, folder: FileEntry) {
        if (find(source, folder.path) != null) return
        all = all + AutoSyncFolder(sourceId = source.id, path = folder.path, displayPath = folder.displayPath,
            name = folder.name)
    }

    fun remove(folder: AutoSyncFolder) {
        all = all.filter { it.id != folder.id }
    }

    /** The folders of a source that has gone (a server deleted). */
    fun removeAll(sourceId: String) {
        all = all.filter { it.sourceId != sourceId }
    }

    fun record(id: String, outcome: String) {
        all = all.map { if (it.id == id) it.copy(lastSynced = System.currentTimeMillis(), lastOutcome = outcome) else it }
    }
}

/**
 * Syncing the folders that sync by themselves: when FastPlay starts, and when it
 * comes back to the front after half an hour or more away.
 */
object AutoSync {
    private var running = false
    private var lastRun: Long? = null
    private val scope = MainScope()
    private val finishListeners = mutableListOf<() -> Unit>()
    private val statusListeners = mutableListOf<() -> Unit>()

    /** Told (on the main thread) when an automatic sync of every folder has finished. */
    fun addFinishListener(listener: () -> Unit) = finishListeners.add(listener)
    fun removeFinishListener(listener: () -> Unit) = finishListeners.remove(listener)

    /** Where an automatic sync has got, for the Home screen and Auto Sync. */
    class Status(
        /** Syncing now, or done. */
        val running: Boolean,
        /** The folder being synced, while running. */
        val folderId: String?,
        /** "Music, 1 of 2 folders" while running; "Finished at 10:42" when done. */
        val heading: String,
        /** How the folder's sync is going (TransferProgress), or what each folder's did. */
        val detail: String,
    ) {
        val text get() = if (detail.isEmpty()) heading else "$heading\n$detail"
    }

    /** Null until an automatic sync has run since FastPlay started. */
    var status: Status? = null
        private set(value) {
            field = value
            for (listener in statusListeners.toList()) listener()
        }

    /** Told (on the main thread) when [status] changes: every second or so while it syncs, and when it is done. */
    fun addStatusListener(listener: () -> Unit) = statusListeners.add(listener)
    fun removeStatusListener(listener: () -> Unit) = statusListeners.remove(listener)

    fun runIfDue() {
        lastRun?.let { if (System.currentTimeMillis() - it < 30 * 60 * 1000L) return }
        // Without access to the device's files there is nowhere to sync to; the
        // browser asks for it, and the next start syncs
        if (!StorageAccess.granted()) return
        runNow()
    }

    /** Syncs every folder now (Settings, Auto Sync, Sync Now). */
    fun runNow() {
        scope.launch { runAll() }
    }

    /**
     * Syncs every folder, one after another, saying what happened through TalkBack.
     * Files here that are no longer there are deleted without asking: that is what
     * the user chose for the folder.
     */
    suspend fun runAll(announce: Boolean = true) {
        val folders = AutoSyncStore.all
        if (running || folders.isEmpty()) return
        running = true
        lastRun = System.currentTimeMillis()
        try {
            StorageAccess.prepare()
            if (announce) {
                say(if (folders.size == 1) "Auto syncing ${folders[0].name}" else "Auto syncing ${folders.size} folders")
            }
            val summary = mutableListOf<String>()
            for ((index, folder) in folders.withIndex()) {
                val heading = if (folders.size == 1) folder.name else "${folder.name}, ${index + 1} of ${folders.size} folders"
                status = Status(running = true, folderId = folder.id, heading = heading, detail = "Looking…")
                val source = FileSources.source(folder.sourceId)
                val outcome = if (source != null) {
                    try {
                        FolderSync.run(source, folder.folder, sync = true, progress = { detail ->
                            status = Status(running = true, folderId = folder.id, heading = heading, detail = detail)
                        }, confirmDeleting = null) ?: "Stopped."
                    } catch (e: Exception) {
                        FolderSync.describe(e)
                    }
                } else {
                    "Its server is no longer added."
                }
                AutoSyncStore.record(folder.id, outcome)
                summary.add("${folder.name}: $outcome")
            }
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            status = Status(running = false, folderId = null, heading = "Finished at $time",
                detail = summary.joinToString("\n"))
            if (announce) say("Auto sync done. " + summary.joinToString(" "))
        } finally {
            running = false
        }
        for (listener in finishListeners.toList()) listener()
    }

    /** An announcement through TalkBack, as the iPhone's to VoiceOver (not the engine's speech). */
    private fun say(text: String) = Callbacks.speak(text, false)
}

/** Copying files from elsewhere into FastPlay's own files. */
object FolderSync {
    /**
     * Copies a file, or a folder with everything in it that FastPlay plays, into
     * FastPlay's own files, keeping the folders as they are there.
     *
     * Download leaves alone what is already here. Sync makes the folder here match
     * the one there: files that changed are fetched again, and files here that are
     * no longer there are deleted, after [confirmDeleting] says yes (with none
     * given, without asking). [progress] is told where it has got, as it goes and
     * every second: the file, how much of it has come and how fast, and how long
     * the rest will take (TransferProgress). The outcome, in words; null if
     * deleting was refused, in which case nothing was done.
     */
    suspend fun run(
        source: FileSource, entry: FileEntry, sync: Boolean,
        progress: (String) -> Unit,
        confirmDeleting: (suspend (Int, String) -> Boolean)?,
    ): String? {
        val transfer = TransferProgress(progress)
        return transfer.run { run(source, entry, sync, transfer, confirmDeleting) }
    }

    private suspend fun run(
        source: FileSource, entry: FileEntry, sync: Boolean, transfer: TransferProgress,
        confirmDeleting: (suspend (Int, String) -> Boolean)?,
    ): String? {
        val documents = File(Engine.documentsPath)
        val root = File(documents, entry.name)

        // What is there, and where each file goes here
        val wanted = mutableListOf<Pair<FileEntry, File>>()
        val sizes = mutableMapOf<String, Long>()  // by destination path
        if (entry.isFolder) {
            val prefix = entry.displayPath.length
            for (item in source.listAll(entry.path)) {
                if (item.isFolder || !Engine.isPlayableFile(item.name)) continue
                // The path below the folder being fetched
                val relative = item.displayPath.drop(prefix).trim('/')
                val destination = File(root, relative)
                wanted.add(item to destination)
                sizes[destination.absoluteFile.normalize().path] = item.size
            }
        } else {
            wanted.add(entry to File(documents, entry.name))
        }

        // Syncing: what is here that is no longer there
        val stale = if (sync && entry.isFolder) withContext(Dispatchers.IO) {
            root.walkTopDown().filter { it.isFile && sizes[it.absoluteFile.normalize().path] == null }.toList()
        } else emptyList()
        if (stale.isNotEmpty() && confirmDeleting != null) {
            if (!confirmDeleting(stale.size, entry.name)) return null
        }

        // What to fetch, first, so that the time left can be told
        var kept = 0
        val fetching = withContext(Dispatchers.IO) {
            wanted.filter { (file, destination) ->
                if (!destination.exists()) return@filter true
                // Download keeps what is here. Sync fetches it again if the
                // one there is another size, or changed after this copy was made.
                val localSize = destination.length()
                val localDate = destination.lastModified()
                val changed = (file.size > 0 && localSize != file.size) ||
                    (file.modified?.let { it > localDate + 1000 } ?: false)
                (sync && changed).also { if (!it) kept += 1 }
            }
        }
        transfer.total = fetching.sumOf { maxOf(0, it.first.size) }

        var fetched = 0
        for ((index, item) in fetching.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (file, destination) = item
            transfer.file("${index + 1} of ${fetching.size}: ${destination.name}", file.size)
            withContext(Dispatchers.IO) { destination.parentFile?.mkdirs() }
            source.download(file, destination)
            // Dated as it is there, so a later sync can tell if it changed
            file.modified?.let { destination.setLastModified(it) }
            fetched += 1
        }

        var removed = 0
        withContext(Dispatchers.IO) {
            for (file in stale) if (file.delete()) removed += 1
            if (removed > 0) removeEmptyFolders(root)
        }

        if (wanted.isEmpty() && removed == 0) return "Nothing there that FastPlay plays."
        val parts = mutableListOf(if (fetched == 1) "1 file downloaded" else "$fetched files downloaded")
        if (kept > 0) parts.add(if (sync) "$kept up to date" else "$kept already here")
        if (removed > 0) parts.add(if (removed == 1) "1 deleted from this device" else "$removed deleted from this device")
        return parts.joinToString(", ") + "."
    }

    /** What went wrong, in words for the user. */
    fun describe(error: Throwable): String = when (error) {
        is CancellationException -> "Stopped."
        is FileOperations.Stopped -> "Stopped."
        else -> error.message?.ifEmpty { null } ?: "Something went wrong."
    }

    /** Folders left with nothing in them after a sync deleted their files. */
    fun removeEmptyFolders(root: File) {
        val folders = root.walkTopDown().filter { it.isDirectory && it != root }.toList()
        // Deepest first, so a folder emptied of its empty folders goes too
        for (folder in folders.sortedByDescending { it.path.length }) {
            if (folder.list()?.isEmpty() == true) folder.delete()
        }
    }
}
