package me.masonasons.fastplay.files

import android.content.Context
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.masonasons.fastplay.ui.Screen

/**
 * Pasting into a folder, with a progress dialog that can stop it and the question
 * about names already taken: the browser's paste, and Home's Paste Into
 * FastPlay's Folder.
 */
object Pasting {
    /** "Paste Song.mp3", "Paste 3 Items" */
    val title: String
        get() = FileClipboard.items.let { if (it.size == 1) "Paste ${it[0].entry.name}" else "Paste ${it.size} Items" }

    /**
     * Moves (after Cut) or copies (after Copy) what is on the clipboard into [path]
     * of [destination], saying so when done. [finished] is told after, however it
     * went (unless the screen has gone meanwhile).
     */
    fun pasteClipboard(screen: Screen, path: String, destination: FileSource, finished: () -> Unit = {}) {
        val items = FileClipboard.items
        if (items.isEmpty()) return
        val move = FileClipboard.isCut
        transferItems(screen, items, move, path, destination, if (move) "Moving" else "Pasting") { done ->
            if (done) {
                if (move) FileClipboard.set(emptyList(), cut = false)  // moved: nothing left to paste
                screen.announce(if (items.size == 1) "Pasted ${items[0].entry.name}" else "Pasted ${items.size} items")
            }
            finished()
        }
    }

    /**
     * Copies or moves [items] into [path] of [destination], with a progress dialog
     * that can stop it, and the question about names already taken. [done] is told
     * whether it all went (unless the screen has gone meanwhile); [cleanUp] runs
     * whatever happens.
     */
    fun transferItems(
        screen: Screen, items: List<FileClipboard.Item>, move: Boolean, path: String, destination: FileSource,
        title: String, cleanUp: () -> Unit = {}, done: (Boolean) -> Unit,
    ) {
        val context = screen.context ?: return cleanUp()
        if (screen.view == null) return cleanUp()
        val scope = screen.viewLifecycleOwner.lifecycleScope
        var job: Job? = null
        val progress = ProgressDialog(context, title, "Starting…") { job?.cancel() }
        job = scope.launch {
            val operations = FileOperations(move, resolver = { pasting, existing ->
                // The progress is in the way: aside while asking
                progress.paused = true
                progress.dismiss()
                val choice = ConflictDialog.ask(context, pasting, existing)
                progress.paused = false
                if (choice != null && currentCoroutineContext().isActive) progress.show()
                choice
            }, progress = { progress.setMessage(it) })
            var problem: String? = null
            try {
                operations.paste(items, destination, path)
            } catch (e: Exception) {
                problem = FolderSync.describe(e)  // "Stopped." for Stop and Stop Pasting
            } finally {
                cleanUp()
            }
            progress.finished = true
            progress.dismiss()
            if (screen.view == null) return@launch
            problem?.let { screen.tell(if (title == "Moving") "Move" else "Paste", it) }
            done(problem == null)
        }
        progress.showSoon(scope)
    }
}

/** A piece of work's progress, with Stop (the iPhone's progress alerts). */
class ProgressDialog(context: Context, title: String, message: String, stop: () -> Unit) {
    var finished = false
    /** Something else is being asked meanwhile. */
    var paused = false
    private val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("Stop") { _, _ -> stop() }
        .setCancelable(false)
        .create()

    fun setMessage(text: String) = dialog.setMessage(text)
    fun show() {
        if (!dialog.isShowing) dialog.show()
    }
    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    /** Shown once the work has taken more than a moment: quick work flashes nothing. */
    fun showSoon(scope: CoroutineScope) {
        scope.launch {
            delay(300)
            if (!finished && !paused) show()
        }
    }
}
