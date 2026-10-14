package me.masonasons.fastplay.ui

import java.io.File
import java.util.UUID

/**
 * Choosing a file to import from, and saving a file exported, through the system's
 * own pickers (the iPhone's FileTransfer).
 */
object FileTransfer {
    /**
     * Asks for a file (any file can be chosen, since lists like these come with all
     * sorts of types); it is handed over as a copy in FastPlay's cache, which goes
     * once [picked] returns.
     */
    fun importFile(screen: Screen, picked: (File) -> Unit) {
        screen.main.pickFileCopy(arrayOf("*/*")) { file ->
            file ?: return@pickFileCopy
            try {
                picked(file)
            } finally {
                file.delete()
            }
        }
    }

    /**
     * Writes a file with [write] (given the path to write to) and asks where to save
     * it. [write] returns false if it could not make the file.
     */
    fun exportFile(screen: Screen, name: String, mimeType: String, write: (String) -> Boolean) {
        val context = screen.requireContext()
        val folder = File(context.cacheDir, "Export/${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(folder, name)
        if (!write(file.path)) {
            folder.deleteRecursively()
            return Dialogs.tell(context, "Export", "The file could not be written.")
        }
        screen.main.saveFile(name, mimeType) { uri ->
            try {
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    screen.view?.announceForAccessibility("Exported")
                        ?: screen.main.window.decorView.announceForAccessibility("Exported")
                }
            } catch (e: Exception) {
                Dialogs.tell(context, "Export", "The file could not be saved there.")
            } finally {
                folder.deleteRecursively()
            }
        }
    }
}
