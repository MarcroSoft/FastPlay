package me.masonasons.fastplay.files

import android.os.Bundle
import me.masonasons.fastplay.R
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen
import me.masonasons.fastplay.ui.RowAction
import me.masonasons.fastplay.ui.ToolbarItem
import java.text.DateFormat
import java.util.Date

/**
 * Settings > Auto Sync: the folders that sync to this device by themselves each
 * time FastPlay starts, with how each one's last sync went, to sync now or remove.
 */
class AutoSyncScreen : ListScreen() {
    override val title = "Auto Sync"
    override val showsMiniPlayer = false
    private var folders = AutoSyncStore.all
    private val finished: () -> Unit = { if (view != null) refresh() }
    // The folder syncing now, with how the ones before it went, brought up to date where it is
    private val statusChanged: () -> Unit = { if (view != null && AutoSync.status?.running == true) refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutoSync.addFinishListener(finished)
        AutoSync.addStatusListener(statusChanged)
    }

    override fun onDestroy() {
        AutoSync.removeFinishListener(finished)
        AutoSync.removeStatusListener(statusChanged)
        super.onDestroy()
    }

    override fun onResume() {
        folders = AutoSyncStore.all
        super.onResume()
    }

    private fun refresh() {
        folders = AutoSyncStore.all
        reload()
        refreshChrome()
    }

    override fun toolbarItems(): List<ToolbarItem> =
        if (folders.isEmpty()) emptyList() else listOf(ToolbarItem("Sync Now") { syncNow() })

    private fun syncNow() {
        StorageAccess.ensure(this) {
            announce("Syncing")
            AutoSync.runNow()
        }
    }

    private fun remove(folder: AutoSyncFolder) {
        AutoSyncStore.remove(folder)
        refresh()
        announce("Stopped auto syncing ${folder.name}")
    }

    private fun detail(folder: AutoSyncFolder): String {
        val lines = mutableListOf("${FileSources.title(folder.sourceId)}: ${folder.displayPath.ifEmpty { "/" }}")
        val status = AutoSync.status
        val date = folder.lastSynced
        if (status != null && status.running && status.folderId == folder.id) {
            lines.add("Syncing now.\n${status.detail}")
        } else if (date != null) {
            val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(date))
            lines.add("Last synced $time. ${folder.lastOutcome ?: ""}")
        } else {
            lines.add("Not synced yet.")
        }
        return lines.joinToString("\n")
    }

    override fun rows(): List<ListRow> {
        val rows = mutableListOf<ListRow>()
        rows += folders.map { folder ->
            ListRow.Item(
                title = folder.name,
                subtitle = detail(folder),
                icon = R.drawable.ic_sync,
                description = "${folder.name}. ${detail(folder)}",
                actions = listOf(RowAction("Remove", destructive = true) { remove(folder) }),
                id = folder.id,
            )
        }
        rows += ListRow.Footer(
            if (folders.isEmpty()) {
                "No folders sync by themselves yet. In Dropbox or on a server, choose Auto Sync This Folder on a folder, " +
                    "and each time FastPlay starts it will be synced to this device, like Sync Folder to This Device."
            } else {
                "Each of these is synced to this device when FastPlay starts, and when it comes back to the front after " +
                    "half an hour or more away: new and changed files are downloaded, and files no longer there are " +
                    "deleted from this device, without asking. The copies are in FastPlay's files, under the folder's name."
            },
        )
        return rows
    }
}
