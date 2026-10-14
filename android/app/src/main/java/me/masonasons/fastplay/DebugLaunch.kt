package me.masonasons.fastplay

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.masonasons.fastplay.address.AddressScreen
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.files.BrowserScreen
import me.masonasons.fastplay.files.LocalSource
import me.masonasons.fastplay.podcasts.PodcastsScreen
import me.masonasons.fastplay.radio.RadioScreen
import me.masonasons.fastplay.remote.ServersScreen
import me.masonasons.fastplay.ui.PlayerScreen
import me.masonasons.fastplay.ui.PlaylistScreen
import me.masonasons.fastplay.ui.SettingsScreen
import me.masonasons.fastplay.youtube.YouTube
import me.masonasons.fastplay.youtube.YouTubeScreen

/**
 * For trying things from a script, in debug builds (the iPhone's launch arguments):
 *
 *   adb shell am start -n me.masonasons.fastplay/.MainActivity --es play <path>
 *   adb shell am start -n me.masonasons.fastplay/.MainActivity --es playUrl <address>
 *   adb shell am start -n me.masonasons.fastplay/.MainActivity --es playYouTube <YouTube address>
 *   adb shell am start -n me.masonasons.fastplay/.MainActivity --es show player|files|settings|playlist|radio|...
 *   adb shell am start -n me.masonasons.fastplay/.MainActivity --es settingsPage <title>
 *
 * A relative path to play is in FastPlay's folder.
 */
object DebugLaunch {
    fun run(activity: MainActivity, intent: Intent) {
        intent.getStringExtra("play")?.let { path ->
            val full = if (path.startsWith("/")) path else "${Engine.documentsPath}/$path"
            Engine.playFile(full)
            android.util.Log.i("FastPlay", "FPPlay: ${Engine.trackCount} tracks: " +
                (0 until Engine.trackCount).joinToString(" | ") { Engine.trackName(it) })
        }
        intent.getStringExtra("playUrl")?.let { Engine.playUrl(it) }
        intent.getStringExtra("playYouTube")?.let { address ->
            activity.lifecycleScope.launch {
                try {
                    YouTube.play(address)
                    android.util.Log.i("FastPlay", "FPYouTube: playing ${Engine.title}, ${Engine.trackCount} tracks")
                } catch (e: Exception) {
                    android.util.Log.i("FastPlay", "FPYouTube: failed: ${e.message}")
                }
            }
        }
        intent.getStringExtra("settingsPage")?.let { title -> SettingsScreen.page(title)?.let { activity.push(it) } }
        val screen = when (intent.getStringExtra("show")) {
            "player" -> PlayerScreen()
            "files" -> BrowserScreen(LocalSource)
            "settings" -> SettingsScreen()
            "playlist" -> PlaylistScreen()
            "radio" -> RadioScreen()
            "podcasts" -> PodcastsScreen()
            "address" -> AddressScreen()
            "servers" -> ServersScreen()
            "youtube" -> YouTubeScreen()
            else -> null
        }
        screen?.let { activity.push(it) }
    }
}
