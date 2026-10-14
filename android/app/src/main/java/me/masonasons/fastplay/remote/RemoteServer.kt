package me.masonasons.fastplay.remote

import android.content.Context
import android.content.SharedPreferences
import me.masonasons.fastplay.FastPlayApp
import me.masonasons.fastplay.files.AutoSyncStore
import me.masonasons.fastplay.files.FavoritesStore
import me.masonasons.fastplay.files.FileSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** An FTP, SFTP or SMB server the user has added. */
data class RemoteServer(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind = Kind.FTP,
    val name: String = "",
    val host: String = "",
    /** Null: the protocol's usual port. */
    val port: Int? = null,
    /** Empty: anonymous (FTP) or guest (SMB). SFTP needs one. */
    val user: String = "",
    /** SMB: the share to open. Empty: the server's shares are listed to choose from. */
    val share: String = "",
    /** A folder to start in, below the top (FTP), the home folder (SFTP) or the share (SMB). */
    val folder: String = "",
) {
    enum class Kind(val title: String, val key: String) {
        FTP("FTP", "ftp"),
        SFTP("SFTP", "sftp"),
        SMB("SMB", "smb"),
    }

    val displayName: String get() = name.ifEmpty { host }

    /** The password, which is kept in the Keychain (encrypted) rather than with the rest. */
    var password: String
        get() = Keychain.read("server-$id") ?: ""
        set(value) {
            if (value.isEmpty()) Keychain.delete("server-$id") else Keychain.write("server-$id", value)
        }

    /** What browses it. `password`, when given, is used rather than the one saved. */
    fun makeSource(password: String? = null): FileSource = when (kind) {
        Kind.FTP -> FTPSource(this, password)
        Kind.SFTP -> SFTPSource(this, password)
        Kind.SMB -> SMBSource(this, password)
    }

    /** Where browsing starts, as its source writes paths. */
    val startPath: String
        get() {
            val below = folder.trim('/')
            return when (kind) {
                Kind.FTP -> if (below.isEmpty()) "" else "/$below"
                Kind.SFTP -> {
                    // Below the home folder, unless it starts at the top
                    val trimmed = folder.removeSuffix("/")
                    if (trimmed.startsWith("/")) trimmed else below
                }
                Kind.SMB -> {
                    val top = share.trim('/')
                    when {
                        top.isEmpty() -> ""
                        below.isEmpty() -> "/$top"
                        else -> "/$top/$below"
                    }
                }
            }
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.key)
        .put("name", name)
        .put("host", host)
        .put("port", port ?: JSONObject.NULL)
        .put("user", user)
        .put("share", share)
        .put("folder", folder)

    companion object {
        fun fromJson(json: JSONObject) = RemoteServer(
            id = json.getString("id"),
            kind = Kind.entries.firstOrNull { it.key == json.optString("kind") } ?: Kind.FTP,
            name = json.optString("name"),
            host = json.optString("host"),
            port = if (json.isNull("port")) null else json.optInt("port"),
            user = json.optString("user"),
            share = json.optString("share"),
            folder = json.optString("folder"),
        )
    }
}

/**
 * The app's preferences (Engine.prefs()'s file), reachable before the engine has
 * started: LoopbackServer looks in them as the app opens.
 */
internal fun remotePrefs(): SharedPreferences =
    FastPlayApp.instance.getSharedPreferences("FastPlay", Context.MODE_PRIVATE)

/** The servers added, in the app's preferences (their passwords in the Keychain). */
object ServerStore {
    private const val KEY = "RemoteServers"

    var all: List<RemoteServer>
        get() {
            val text = remotePrefs().getString(KEY, null) ?: return emptyList()
            return try {
                val array = JSONArray(text)
                (0 until array.length()).map { RemoteServer.fromJson(array.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val array = JSONArray()
            for (server in value) array.put(server.toJson())
            remotePrefs().edit().putString(KEY, array.toString()).apply()
        }

    @Synchronized
    fun save(server: RemoteServer, password: String) {
        val servers = all.toMutableList()
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            // Somewhere else now: the key of the server that was there is no guide
            if (servers[index].host != server.host || servers[index].port != server.port) {
                SFTPSource.forgetHostKey(server)
            }
            servers[index] = server
        } else {
            servers.add(server)
        }
        all = servers
        server.password = password
    }

    @Synchronized
    fun remove(server: RemoteServer) {
        server.password = ""
        SFTPSource.forgetHostKey(server)
        FavoritesStore.removeAll("server-${server.id}")
        AutoSyncStore.removeAll("server-${server.id}")
        all = all.filter { it.id != server.id }
    }
}
