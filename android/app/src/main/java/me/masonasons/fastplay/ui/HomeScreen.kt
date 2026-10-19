package me.masonasons.fastplay.ui

import android.os.Bundle
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.masonasons.fastplay.R
import me.masonasons.fastplay.address.AddressScreen
import me.masonasons.fastplay.dropbox.DropboxCancelled
import me.masonasons.fastplay.dropbox.DropboxClient
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.files.AutoSync
import me.masonasons.fastplay.files.AutoSyncScreen
import me.masonasons.fastplay.files.BrowserScreen
import me.masonasons.fastplay.files.Favorite
import me.masonasons.fastplay.files.FavoritesStore
import me.masonasons.fastplay.files.FileClipboard
import me.masonasons.fastplay.files.FileSource
import me.masonasons.fastplay.files.FileSources
import me.masonasons.fastplay.files.LocalSource
import me.masonasons.fastplay.files.Pasting
import me.masonasons.fastplay.files.StorageAccess
import me.masonasons.fastplay.podcasts.PodcastsScreen
import me.masonasons.fastplay.radio.RadioScreen
import me.masonasons.fastplay.remote.ServersScreen
import me.masonasons.fastplay.youtube.YouTubeScreen

/** The first screen: where music comes from, the folders made favorites, and the settings. */
class HomeScreen : ListScreen() {
    override val title = "FastPlay"

    private class Item(val title: String, val detail: String, @DrawableRes val icon: Int, val action: (HomeScreen) -> Unit)

    private val places = listOf(
        Item("Files", "On this device", R.drawable.ic_folder) { it.push(BrowserScreen(LocalSource)) },
        Item("Dropbox", "Play from your Dropbox", R.drawable.ic_cloud) { it.openDropbox() },
        Item("Servers", "FTP, SFTP and SMB servers", R.drawable.ic_server) { it.push(ServersScreen()) },
        Item("Radio", "Saved stations and the station directories", R.drawable.ic_radio) { it.push(RadioScreen()) },
        Item("Podcasts", "Subscriptions and the podcast directory", R.drawable.ic_mic) { it.push(PodcastsScreen()) },
        Item("YouTube", "Search YouTube and play", R.drawable.ic_youtube) { it.push(YouTubeScreen()) },
        Item("Open Address", "A stream, a file or a playlist on the internet", R.drawable.ic_link) {
            it.push(AddressScreen())
        },
    )

    private val settings = Item("Settings", "Effects, seeking and speech", R.drawable.ic_settings) {
        it.push(SettingsScreen())
    }

    private var favorites: List<Favorite> = emptyList()
    /** Free space, for the Files and Dropbox rows, once known */
    private val space = mutableMapOf<String, String>()
    // The Auto Sync row, brought up to date where it is, so TalkBack stays where it was
    private val syncChanged: () -> Unit = { if (view != null) reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutoSync.addStatusListener(syncChanged)
    }

    override fun onDestroy() {
        AutoSync.removeStatusListener(syncChanged)
        super.onDestroy()
    }

    override fun onResume() {
        favorites = FavoritesStore.all
        super.onResume()
        updateSpace()
    }

    /** "12.3 GB free of 128 GB", for this device and Dropbox (if connected). */
    private fun updateSpace() {
        viewLifecycleOwner.lifecycleScope.launch {
            val sources = mutableListOf<FileSource>(LocalSource)
            if (DropboxClient.isSignedIn) sources.add(DropboxClient)
            for (source in sources) {
                val (free, total) = try {
                    source.storageSpace()
                } catch (e: Exception) {
                    null
                } ?: continue
                val text = Formatter.formatFileSize(requireContext(), free) + " free of " +
                    Formatter.formatFileSize(requireContext(), total)
                if (space[source.title] != text) {
                    space[source.title] = text
                    reload()
                }
            }
        }
    }

    /** Dropbox, signing in first if FastPlay is not connected yet; then `then`. */
    private fun withDropbox(then: () -> Unit) {
        if (DropboxClient.isSignedIn) return then()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                DropboxClient.signIn(requireActivity())
                announce("Connected to Dropbox")
                then()
            } catch (e: DropboxCancelled) {
                // Their choice: nothing to say
            } catch (e: Exception) {
                tell("Dropbox", e.message ?: "Signing in failed.")
            }
        }
    }

    private fun openDropbox() = withDropbox { push(BrowserScreen(DropboxClient)) }

    private fun open(favorite: Favorite) {
        val source = FileSources.source(favorite.sourceId)
            ?: return tell(favorite.name, "The server it was on has been deleted.")
        val browse = { push(BrowserScreen(source, favorite.folder, isTop = false)) }
        if (source.id == DropboxClient.id) withDropbox(browse) else browse()
    }

    /** What is on the clipboard pasted into FastPlay's own folder, from here. */
    private fun pasteIntoFiles() = StorageAccess.ensure(this) {
        Pasting.pasteClipboard(this, Engine.documentsPath, LocalSource) {
            reload()
            updateSpace()
        }
    }

    /** What is on the clipboard pasted into a favorite folder, from here. */
    private fun paste(into: Favorite) {
        val source = FileSources.source(into.sourceId)
            ?: return tell(into.name, "The server it was on has been deleted.")
        val paste = { Pasting.pasteClipboard(this, into.folder.path, source) { reload() } }
        if (source.id == DropboxClient.id) withDropbox(paste) else paste()
    }

    private fun remove(favorite: Favorite) {
        FavoritesStore.remove(favorite)
        favorites = FavoritesStore.all
        reload()
        announce("Removed ${favorite.name} from favorites")
    }

    private fun row(item: Item) = ListRow.Item(
        title = item.title,
        subtitle = space[item.title]?.let { "${item.detail}. $it" } ?: item.detail,
        icon = item.icon,
        disclosure = true,
        id = item.title,
        actions = if (item.title == "Files" && FileClipboard.items.isNotEmpty()) {
            listOf(RowAction("${Pasting.title} Into FastPlay's Folder") { pasteIntoFiles() })
        } else {
            emptyList()
        },
        onClick = { item.action(this) },
    )

    override fun rows(): List<ListRow> {
        val rows = mutableListOf<ListRow>()
        rows += places.map { row(it) }
        AutoSync.status?.let { status ->
            rows += ListRow.Header("Auto Sync")
            rows += ListRow.Item(
                title = if (status.running) "Syncing" else "Done",
                subtitle = status.text,
                icon = R.drawable.ic_sync,
                disclosure = true,
                id = "autoSync",
                onClick = { push(AutoSyncScreen()) },
            )
        }
        if (favorites.isNotEmpty()) {
            rows += ListRow.Header("Favorites")
            rows += favorites.map { favorite ->
                ListRow.Item(
                    title = favorite.name,
                    subtitle = FileSources.title(favorite.sourceId),
                    icon = R.drawable.ic_star,
                    disclosure = true,
                    id = "favorite:${favorite.id}",
                    actions = listOfNotNull(
                        RowAction("${Pasting.title} Into Folder") { paste(into = favorite) }.takeIf {
                            FileClipboard.items.isNotEmpty() && FileSources.source(favorite.sourceId)?.canWrite == true
                        },
                        RowAction("Remove from Favorites", destructive = true) { remove(favorite) },
                    ),
                    onClick = { open(favorite) },
                )
            }
        }
        if (AutoSync.status != null || favorites.isNotEmpty()) rows += ListRow.Header("")
        rows += row(settings)
        return rows
    }
}
