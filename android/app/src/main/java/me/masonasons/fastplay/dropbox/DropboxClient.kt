package me.masonasons.fastplay.dropbox

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.files.FileEntry
import me.masonasons.fastplay.files.FileOperations
import me.masonasons.fastplay.files.FileSource
import me.masonasons.fastplay.files.RemoteError
import me.masonasons.fastplay.remote.Keychain
import me.masonasons.fastplay.ui.Dialogs
import me.masonasons.fastplay.ui.Screen
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

/** Signing in to Dropbox was cancelled: nothing to say. */
class DropboxCancelled : Exception("Signing in was cancelled.")

/**
 * FastPlay's connection to Dropbox: signing in (OAuth with PKCE, which needs the
 * app's key and no secret), listing folders, and getting an address a file can be
 * streamed from. What lasts between runs, the refresh token, is in the Keychain.
 *
 * Signing in is in a browser tab (a Custom Tab); Dropbox sends the browser back to
 * FastPlay's own address (db-<key>://), which MainActivity hands to [handleRedirect].
 */
object DropboxClient : FileSource {
    override val id = "dropbox"
    override val title = "Dropbox"
    override val canWrite get() = true

    private const val APP_KEY = "4f6qjnm23npa6wk"
    private const val REDIRECT_SCHEME = "db-$APP_KEY"
    private const val REDIRECT_URI = "$REDIRECT_SCHEME://2/token"
    private const val REFRESH_TOKEN_KEY = "dropbox-refresh-token"

    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpires = 0L
    private val tokenLock = Mutex()
    /** The sign in waiting for the browser to come back (main thread only). */
    private var pending: CompletableDeferred<Uri>? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isSignedIn: Boolean get() = Keychain.read(REFRESH_TOKEN_KEY) != null

    private fun notSignedIn() = RemoteError("FastPlay is not connected to Dropbox.")

    // ---- Signing in and out ----

    /**
     * Opens Dropbox's sign in page; returns once FastPlay has been let in. Throws
     * DropboxCancelled if the user comes back to FastPlay without finishing.
     */
    suspend fun signIn(activity: Activity) {
        val verifier = randomVerifier()
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val url = Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", APP_KEY)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("token_access_type", "offline")  // a refresh token, to stay connected
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .build()

        val waiter = CompletableDeferred<Uri>()
        pending?.completeExceptionally(DropboxCancelled())
        pending = waiter
        val watcher = ReturnWatcher(activity, waiter)
        val callback = try {
            try {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, url)
            } catch (e: ActivityNotFoundException) {
                throw RemoteError("The sign in page could not be opened.")
            }
            waiter.await()
        } finally {
            watcher.stop()
            if (pending === waiter) pending = null
        }

        val code = callback.getQueryParameter("code")
            ?: throw RemoteError(callback.getQueryParameter("error_description")
                ?: callback.getQueryParameter("error") ?: "Dropbox did not let FastPlay in.")
        val token = requestToken(mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to verifier,
            "client_id" to APP_KEY,
            "redirect_uri" to REDIRECT_URI,
        ))
        val refresh = token.optString("refresh_token").ifEmpty { null }
            ?: throw RemoteError("Dropbox gave no lasting sign in.")
        withContext(Dispatchers.IO) { Keychain.write(REFRESH_TOKEN_KEY, refresh) }
        store(token)
    }

    /** The sign in page coming back (MainActivity, on the main thread). True if it was Dropbox's. */
    fun handleRedirect(uri: Uri): Boolean {
        if (!uri.scheme.equals(REDIRECT_SCHEME, ignoreCase = true)) return false
        pending?.complete(uri)
        return true
    }

    fun signOut() {
        // Tell Dropbox the token is finished with, if it can be reached; forget it either way
        val token = accessToken
        if (token != null) {
            background.launch {
                try {
                    post("https://api.dropboxapi.com/2/auth/token/revoke",
                        mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
                        Body.Bytes("null".toByteArray()))
                } catch (e: Exception) {
                    // Forgotten here all the same
                }
            }
        }
        Keychain.delete(REFRESH_TOKEN_KEY)
        accessToken = null
        accessTokenExpires = 0
    }

    /**
     * Notices the user coming back to FastPlay without having finished signing in
     * (Back in the browser tab, or switching back): the activity resumes after having
     * left for the browser, and no answer from Dropbox came with it. Dropbox's answer
     * arrives (onNewIntent) before the activity resumes; a moment's grace covers a
     * browser that is slow to hand it over.
     */
    private class ReturnWatcher(activity: Activity, private val waiter: CompletableDeferred<Uri>) :
        DefaultLifecycleObserver {
        private val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        private val handler = Handler(Looper.getMainLooper())
        private var left = false

        init {
            lifecycle?.addObserver(this)
        }

        override fun onPause(owner: LifecycleOwner) {
            left = true
            handler.removeCallbacksAndMessages(null)
        }

        override fun onResume(owner: LifecycleOwner) {
            if (!left) return
            handler.postDelayed({
                if (!waiter.isCompleted) waiter.completeExceptionally(DropboxCancelled())
            }, 1_000)
        }

        fun stop() {
            lifecycle?.removeObserver(this)
            handler.removeCallbacksAndMessages(null)
        }
    }

    // ---- As a place to browse (FileSource) ----

    override suspend fun list(path: String): List<FileEntry> = list(path, recursive = false)

    /** Dropbox lists a folder and all below it in one request. */
    override suspend fun listAll(path: String): List<FileEntry> = list(path, recursive = true).filter { !it.isFolder }

    override suspend fun streamUrl(entry: FileEntry): String = temporaryLink(entry.path)

    override suspend fun download(entry: FileEntry, destination: File) = download(entry.path, destination)

    override val accountAction: Pair<String, (Screen) -> Unit>
        get() = "Sign Out of Dropbox" to { screen ->
            Dialogs.confirm(screen.requireContext(), "Sign Out of Dropbox",
                "FastPlay will forget its connection to your Dropbox.", "Sign Out") {
                signOut()
                // Back to the first screen
                screen.main.supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
        }

    // ---- Changing things ----

    override suspend fun createFolder(name: String, inFolder: String) {
        call("files/create_folder_v2", JSONObject().put("path", path(name, inFolder)).put("autorename", false))
    }

    override suspend fun rename(entry: FileEntry, to: String) {
        call("files/move_v2", JSONObject()
            .put("from_path", entry.path)
            .put("to_path", path(to, FileOperations.parent(entry.path)))
            .put("autorename", false))
    }

    override suspend fun move(entry: FileEntry, toFolder: String): Boolean {
        call("files/move_v2", JSONObject()
            .put("from_path", entry.path)
            .put("to_path", path(entry.name, toFolder))
            .put("autorename", false))
        return true
    }

    override suspend fun delete(entry: FileEntry) {
        call("files/delete_v2", JSONObject().put("path", entry.path))
    }

    /** Up to 150 MB in one request; beyond that, in pieces through an upload session. */
    override suspend fun upload(file: File, name: String, toFolder: String) {
        val target = path(name, toFolder)
        val size = withContext(Dispatchers.IO) { file.length() }
        val commit = JSONObject().put("path", target).put("mode", "overwrite").put("autorename", false).put("mute", true)
        val piece = 64L * 1024 * 1024
        if (size <= 150L * 1024 * 1024) {
            content("files/upload", commit, Body.Part(file, 0, size))
            return
        }
        val started = content("files/upload_session/start", JSONObject().put("close", false), Body.Part(file, 0, piece))
        val session = started.optString("session_id").ifEmpty { null }
            ?: throw RemoteError("Dropbox did not start the upload of $name.")
        var offset = piece
        while (true) {
            currentCoroutineContext().ensureActive()
            val length = minOf(piece, size - offset).coerceAtLeast(0)
            val cursor = JSONObject().put("session_id", session).put("offset", offset)
            if (length == 0L || offset + length >= size) {
                content("files/upload_session/finish", JSONObject().put("cursor", cursor).put("commit", commit),
                    Body.Part(file, offset, length))
                return
            }
            content("files/upload_session/append_v2", JSONObject().put("cursor", cursor).put("close", false),
                Body.Part(file, offset, length))
            offset += length
        }
    }

    override suspend fun storageSpace(): Pair<Long, Long>? {
        val usage = call("users/get_space_usage", null)
        val used = usage.optLong("used", -1)
        val allocated = usage.optJSONObject("allocation")?.optLong("allocated", 0) ?: 0
        if (used < 0 || allocated <= 0) return null
        return maxOf(0, allocated - used) to allocated
    }

    // ---- Files ----

    /** What is in a folder, or with `recursive` in it and every folder below it. */
    suspend fun list(path: String, recursive: Boolean): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        var page = call("files/list_folder", JSONObject().put("path", path).put("limit", 2000).put("recursive", recursive))
        while (true) {
            val items = page.optJSONArray("entries") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val name = item.optString("name").ifEmpty { null } ?: continue
                val tag = item.optString(".tag").ifEmpty { null } ?: continue
                val itemPath = item.optString("path_lower").ifEmpty { item.optString("path_display") }.ifEmpty { null }
                    ?: continue
                entries.add(FileEntry(
                    name = name, path = itemPath,
                    displayPath = item.optString("path_display").ifEmpty { itemPath },
                    isFolder = tag == "folder",
                    size = item.optLong("size", 0),
                    modified = item.optString("server_modified").ifEmpty { null }?.let {
                        try {
                            Instant.parse(it).toEpochMilli()
                        } catch (e: Exception) {
                            null
                        }
                    },
                ))
            }
            val cursor = page.optString("cursor")
            if (!page.optBoolean("has_more") || cursor.isEmpty()) break
            page = call("files/list_folder/continue", JSONObject().put("cursor", cursor))
        }
        return entries
    }

    /** An address the file can be streamed from, good for four hours. */
    suspend fun temporaryLink(path: String): String {
        val result = call("files/get_temporary_link", JSONObject().put("path", path))
        return result.optString("link").ifEmpty { null } ?: throw RemoteError("Dropbox gave no address for the file.")
    }

    /** Downloads a file to a place on this device (replacing what is there). */
    suspend fun download(path: String, destination: File) {
        val token = validAccessToken()
        withContext(Dispatchers.IO) {
            val connection = open("https://content.dropboxapi.com/2/files/download")
            try {
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Dropbox-API-Arg", headerJson(JSONObject().put("path", path)))
                // No body, as the endpoint wants
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
                val status = connection.responseCode
                if (status != 200) {
                    throw RemoteError("Dropbox answered $status for ${path.substringAfterLast('/')}.")
                }
                destination.parentFile?.mkdirs()
                val temporary = File(destination.parentFile, ".${destination.name}.download")
                try {
                    connection.inputStream.use { input ->
                        temporary.outputStream().use { output ->
                            val chunk = ByteArray(256 * 1024)
                            while (true) {
                                ensureActive()
                                val count = input.read(chunk)
                                if (count < 0) break
                                output.write(chunk, 0, count)
                            }
                        }
                    }
                    destination.delete()
                    if (!temporary.renameTo(destination)) throw IOException("${destination.name} could not be saved.")
                } catch (e: Throwable) {
                    temporary.delete()
                    throw e
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /** JSON for a request header: letters outside ASCII as \u escapes, as headers need. */
    private fun headerJson(json: JSONObject): String {
        val text = StringBuilder()
        for (char in json.toString()) {
            if (char.code < 0x7f) text.append(char) else text.append("\\u%04x".format(char.code))
        }
        return text.toString()
    }

    // ---- Requests ----

    private sealed class Body {
        class Bytes(val data: ByteArray) : Body()
        /** `length` bytes of a file from `offset`, read as they are sent. */
        class Part(val file: File, val offset: Long, val length: Long) : Body()
    }

    private class Reply(val status: Int, val data: ByteArray)

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 30_000
        readTimeout = 60_000
        useCaches = false
    }

    /** A POST and its answer, whatever its status. */
    private suspend fun post(url: String, headers: Map<String, String>, body: Body): Reply = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.doOutput = true
            when (body) {
                is Body.Bytes -> {
                    connection.setFixedLengthStreamingMode(body.data.size)
                    connection.outputStream.use { it.write(body.data) }
                }
                is Body.Part -> {
                    connection.setFixedLengthStreamingMode(body.length)
                    connection.outputStream.use { output ->
                        RandomAccessFile(body.file, "r").use { input ->
                            input.seek(body.offset)
                            val chunk = ByteArray(256 * 1024)
                            var left = body.length
                            while (left > 0) {
                                ensureActive()
                                val count = input.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                                if (count < 0) throw IOException("${body.file.name} ended early.")
                                output.write(chunk, 0, count)
                                left -= count
                            }
                        }
                    }
                }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            Reply(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    /** An API call. Null arguments: an endpoint that takes none. */
    private suspend fun call(endpoint: String, arguments: JSONObject?, retried: Boolean = false): JSONObject {
        val token = validAccessToken()
        val reply = post("https://api.dropboxapi.com/2/$endpoint",
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
            Body.Bytes((arguments?.toString() ?: "null").toByteArray(Charsets.UTF_8)))
        if (reply.status == 401 && !retried) {
            accessToken = null  // it ran out early: a new one, once
            return call(endpoint, arguments, retried = true)
        }
        return result(reply.status, reply.data)
    }

    /** A request to Dropbox's content endpoint: the arguments in a header, the bytes as the body. */
    private suspend fun content(endpoint: String, arguments: JSONObject, body: Body, retried: Boolean = false): JSONObject {
        val token = validAccessToken()
        val reply = post("https://content.dropboxapi.com/2/$endpoint", mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/octet-stream",
            "Dropbox-API-Arg" to headerJson(arguments),
        ), body)
        if (reply.status == 401 && !retried) {
            accessToken = null
            return content(endpoint, arguments, body, retried = true)
        }
        return result(reply.status, reply.data)
    }

    /** A response's JSON, or what went wrong said plainly where it is a common case. */
    private fun result(status: Int, data: ByteArray): JSONObject {
        val text = String(data, Charsets.UTF_8)
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
        if (status != 200) {
            val summary = json.optString("error_summary").ifEmpty { text }
            if ("missing_scope" in summary) {
                throw RemoteError("FastPlay's connection to Dropbox is not allowed to do this. Sign out of " +
                    "Dropbox (in the Dropbox folder's menu) and sign in again to allow it.")
            }
            if ("insufficient_space" in summary) throw RemoteError("Your Dropbox is full.")
            if ("conflict" in summary) throw RemoteError("Something of that name is already there.")
            if ("not_found" in summary) throw RemoteError("It is no longer in your Dropbox.")
            throw RemoteError("Dropbox answered $status. $summary".trim())
        }
        return json
    }

    private suspend fun validAccessToken(): String = tokenLock.withLock {
        val current = accessToken
        if (current != null && accessTokenExpires > System.currentTimeMillis() + 60_000) return current
        val refresh = withContext(Dispatchers.IO) { Keychain.read(REFRESH_TOKEN_KEY) } ?: throw notSignedIn()
        store(requestToken(mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to APP_KEY,
        )))
        accessToken ?: throw RemoteError("Dropbox gave no access.")
    }

    private fun store(token: JSONObject) {
        accessToken = token.optString("access_token").ifEmpty { null }
        val lifetime = token.optDouble("expires_in", 3600.0).takeIf { !it.isNaN() } ?: 3600.0
        accessTokenExpires = System.currentTimeMillis() + (lifetime * 1000).toLong()
    }

    private suspend fun requestToken(fields: Map<String, String>): JSONObject {
        val form = fields.entries.joinToString("&") { (name, value) ->
            URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val reply = post("https://api.dropboxapi.com/oauth2/token",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"), Body.Bytes(form.toByteArray(Charsets.UTF_8)))
        val json = try {
            JSONObject(String(reply.data, Charsets.UTF_8))
        } catch (e: Exception) {
            JSONObject()
        }
        if (reply.status != 200) {
            if (json.optString("error") == "invalid_grant") {
                // The connection was ended from Dropbox's side: signed out here too
                withContext(Dispatchers.IO) { Keychain.delete(REFRESH_TOKEN_KEY) }
                throw notSignedIn()
            }
            throw RemoteError(json.optString("error_description").ifEmpty { "Dropbox refused the sign in." })
        }
        return json
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
