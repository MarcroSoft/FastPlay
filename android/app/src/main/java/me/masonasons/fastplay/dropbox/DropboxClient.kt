package me.masonasons.fastplay.dropbox

import android.app.Activity
import android.net.Uri
import me.masonasons.fastplay.files.FileEntry
import me.masonasons.fastplay.files.FileSource
import java.io.File

// STUB: replaced by the port of ios/FastPlay/Dropbox/DropboxClient.swift.
object DropboxClient : FileSource {
    override val id = "dropbox"
    override val title = "Dropbox"
    val isSignedIn: Boolean get() = false

    /** Opens Dropbox's sign in page; returns once FastPlay has been let in. Throws DropboxCancelled if not. */
    suspend fun signIn(activity: Activity) {}

    /** The sign in page coming back (MainActivity). True if it was Dropbox's. */
    fun handleRedirect(uri: Uri): Boolean = false

    override suspend fun list(path: String): List<FileEntry> = emptyList()
    override suspend fun streamUrl(entry: FileEntry): String = ""
    override suspend fun download(entry: FileEntry, destination: File) {}
}

/** Signing in to Dropbox was cancelled: nothing to say. */
class DropboxCancelled : Exception("Cancelled")
