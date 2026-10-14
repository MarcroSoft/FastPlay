package me.masonasons.fastplay.address

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.files.PlaylistText
import me.masonasons.fastplay.radio.ADDRESS_INPUT
import me.masonasons.fastplay.radio.hideKeyboard
import me.masonasons.fastplay.radio.onKeyboardAction
import me.masonasons.fastplay.radio.showKeyboard
import me.masonasons.fastplay.radio.textField
import me.masonasons.fastplay.radio.value
import me.masonasons.fastplay.ui.Dialogs
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen
import me.masonasons.fastplay.ui.RowAction
import me.masonasons.fastplay.ui.ToolbarItem
import me.masonasons.fastplay.ui.dp
import me.masonasons.fastplay.youtube.YouTube
import org.json.JSONArray

/**
 * Open Address: a field for the address of a stream, an audio file or a playlist
 * on the internet, and the last ten addresses opened, to open again.
 */
class AddressScreen : ListScreen() {
    override val title = "Open Address"

    private companion object {
        const val RECENT_KEY = "RecentAddresses"
        const val KEEP = 10
    }

    private var field: TextInputLayout? = null
    /** What was typed, kept while the view is gone (with the player in front). */
    private var typed = ""
    private var recent: MutableList<String> = mutableListOf()

    override fun toolbarItems() = if (recent.isEmpty()) emptyList() else listOf(ToolbarItem("Clear") { clearRecent() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recent = loadRecent()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        val context = requireContext()
        val box = textField(context, "Address", placeholder = "https://", inputType = ADDRESS_INPUT,
            imeAction = EditorInfo.IME_ACTION_GO, clearButton = true)
        box.editText?.setText(typed)
        box.editText?.onKeyboardAction { open(box.value) }
        field = box
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(context.dp(16), context.dp(12), context.dp(16), 0)
            })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onDestroyView() {
        typed = field?.value.orEmpty()
        field = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (recent.isEmpty()) field?.editText?.let { edit ->
            edit.requestFocus()
            edit.post { edit.showKeyboard() }
        }
    }

    // ---- Opening ----

    private fun open(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val address = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        remember(address)
        field?.editText?.let { edit ->
            edit.hideKeyboard()
            edit.clearFocus()
            edit.setText("")
        }
        announce("Opening")
        // The activity's scope: it plays even if this screen has gone by then
        val activity = main
        activity.lifecycleScope.launch {
            if (YouTube.isYouTubeUrl(address)) {
                // Android's own: a YouTube video or playlist through yt-dlp
                try {
                    YouTube.play(address)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch Dialogs.tell(activity, "YouTube", e.message ?: "That could not be played.")
                }
            } else {
                PlaylistText.play(address)
            }
            if (Engine.trackCount > 0 && isAdded) activity.showPlayer()
        }
    }

    // ---- Recent addresses ----

    private fun loadRecent(): MutableList<String> {
        val saved = Engine.prefs().getString(RECENT_KEY, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(saved)
            MutableList(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun remember(address: String) {
        recent.remove(address)
        recent.add(0, address)
        while (recent.size > KEEP) recent.removeAt(recent.lastIndex)
        save()
    }

    private fun forget(index: Int) {
        if (index in recent.indices) recent.removeAt(index)
        save()
    }

    private fun clearRecent() {
        Dialogs.confirm(requireContext(), "Clear Recent Addresses", "Removes the ${recent.size} addresses listed here.",
            "Clear") {
            recent.clear()
            save()
        }
    }

    private fun save() {
        Engine.prefs().edit().putString(RECENT_KEY, JSONArray(recent).toString()).apply()
        refreshChrome()
        reload()
    }

    // ---- The list ----

    override fun rows(): List<ListRow> {
        val rows = mutableListOf<ListRow>(
            ListRow.Item(
                title = "Play",
                hint = "Plays the address typed above",
                id = "play",
                onClick = { open(field?.value.orEmpty()) },
            ),
            ListRow.Footer("A stream, an audio file, or a playlist (M3U or PLS), whose tracks all play."),
        )
        if (recent.isNotEmpty()) {
            rows += ListRow.Header("Recent")
            rows += recent.mapIndexed { index, address ->
                ListRow.Item(
                    title = address,
                    hint = "Plays it",
                    id = "recent:$address",
                    actions = listOf(RowAction("Remove", destructive = true) { forget(index) }),
                    onClick = { open(address) },
                )
            }
        }
        return rows
    }
}
