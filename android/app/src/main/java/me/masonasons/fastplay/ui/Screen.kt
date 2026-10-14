package me.masonasons.fastplay.ui

import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import me.masonasons.fastplay.MainActivity
import me.masonasons.fastplay.engine.Engine

/**
 * A button in a screen's toolbar: with an icon it is an icon (labelled [title]
 * for TalkBack), without one it is its title. [submenu] makes it open a menu.
 */
data class ToolbarItem(
    val title: String,
    @DrawableRes val icon: Int? = null,
    val enabled: Boolean = true,
    val submenu: List<ToolbarItem>? = null,
    val destructive: Boolean = false,
    val run: () -> Unit = {},
)

/**
 * One of the app's screens, as a view controller is on the iPhone: MainActivity
 * shows one at a time, with a back stack, its [title] and [toolbarItems] at the top,
 * and the small player at the bottom while something is loaded (unless
 * [showsMiniPlayer] is false, or the screen has a [bottomBar] of its own).
 */
abstract class Screen : Fragment() {
    open val title: String = "FastPlay"
    open fun toolbarItems(): List<ToolbarItem> = emptyList()
    open val showsMiniPlayer: Boolean = true
    /** A bar of the screen's own (the browser's, while selecting), shown instead of the small player. */
    open fun bottomBar(): View? = null

    private val stateListener: () -> Unit = { if (isResumed) engineStateChanged() }

    /** What is playing, its state or the playlist changed (while the screen is in front). */
    open fun engineStateChanged() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.addStateListener(stateListener)
    }

    override fun onDestroy() {
        Engine.removeStateListener(stateListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshChrome()
    }

    /** Asks the activity to show this screen's title, toolbar and bottom bar again. */
    fun refreshChrome() {
        if (isResumed) (activity as? MainActivity)?.screenChanged(this)
    }

    val main: MainActivity get() = requireActivity() as MainActivity

    /** Opens another screen on top of this one. */
    fun push(screen: Screen) = main.push(screen)

    /** Back to the screen before. */
    fun pop() = main.pop()

    /** Says something through TalkBack (not the engine: nothing of FastPlay's own). */
    fun announce(text: String) = view?.announceForAccessibility(text) ?: Unit

    /** A message with OK, as the iPhone's alerts. */
    fun tell(title: String, message: String) = Dialogs.tell(requireContext(), title, message)
}
