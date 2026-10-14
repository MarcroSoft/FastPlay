package me.masonasons.fastplay.files

import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen

// STUB: replaced by the port of ios/FastPlay/Files/BrowserViewController.swift.
class BrowserScreen() : ListScreen() {
    constructor(source: FileSource, path: String? = null, name: String? = null) : this()
    constructor(source: FileSource, folder: FileEntry, isTop: Boolean) : this()

    override fun rows(): List<ListRow> = emptyList()
}
