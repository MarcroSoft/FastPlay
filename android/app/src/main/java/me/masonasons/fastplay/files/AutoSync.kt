package me.masonasons.fastplay.files

import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen

// STUB: replaced by the port of ios/FastPlay/Files/FolderSync.swift (AutoSync, AutoSyncStore).
object AutoSync {
    /** At the start, and on coming back to the front after a while: the folders that sync by themselves. */
    fun runIfDue() {}
}

// STUB: replaced by the port of ios/FastPlay/Files/FolderSync.swift.
object AutoSyncStore {
    /** The auto synced folders of a source that has gone (a server deleted). */
    fun removeAll(sourceId: String) {}
}

// STUB: replaced by the port of ios/FastPlay/Controllers/AutoSyncViewController.swift.
class AutoSyncScreen : ListScreen() {
    override val title = "Auto Sync"
    override fun rows(): List<ListRow> = emptyList()
}
