package me.masonasons.fastplay.remote

import me.masonasons.fastplay.files.FileSource
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen

// STUB: replaced by the port of ios/FastPlay/Remote/RemoteServer.swift.
data class RemoteServer(val id: String = java.util.UUID.randomUUID().toString()) {
    fun makeSource(password: String? = null): FileSource = throw NotImplementedError()
}

object ServerStore {
    val all: List<RemoteServer> get() = emptyList()
}

// STUB: replaced by the port of ios/FastPlay/Remote/ServersViewController.swift.
class ServersScreen : ListScreen() {
    override val title = "Servers"
    override fun rows(): List<ListRow> = emptyList()
}
