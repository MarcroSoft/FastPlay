package me.masonasons.fastplay.files

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.masonasons.fastplay.engine.Engine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where you were in each folder: the file last playing from it, and how far
 * into that file. With Play All Resumes Where You Left Off on, Play All in a
 * folder starts there again rather than at its first file.
 *
 * A folder's files are playing when they were played from it (Play All, or a
 * file opened with the rest of its folder). While they are, where playback is
 * is noted every few seconds and as the app goes to the background; playing
 * anything else (another folder, a station) stops the noting, as the playlist
 * is then no longer the folder's.
 */
object FolderResume {
    var enabled: Boolean
        get() = Engine.prefs().getBoolean("PlayAllResumes", false)
        set(value) = Engine.prefs().edit().putBoolean("PlayAllResumes", value).apply()

    class Place(
        /** As the folder's files are named (see begin). */
        val file: String,
        /** Seconds. */
        val position: Double,
    )

    private const val PLACES_KEY = "FolderResumePlaces"
    private const val KEEP = 500  // folders remembered, the oldest forgotten

    /** A folder, by its source and path. */
    fun key(source: FileSource, folder: String): String = source.id + "|" + local(folder)

    /**
     * A path in FastPlay's own folder, as below it (as on the iPhone, where that
     * folder moves when the app is restored). Others as they are.
     */
    fun local(path: String): String {
        val documents = Engine.documentsPath
        return if (documents.isNotEmpty() && path.startsWith("$documents/")) path.substring(documents.length) else path
    }

    private var key: String? = null
    private var files: List<String> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            record()
            main.postDelayed(this, 5000)
        }
    }
    private val scope = MainScope()

    /** A folder's [files] are now the playlist, in its order. */
    fun begin(key: String, files: List<String>) {
        this.key = key
        this.files = files
        if (!ticking) {
            ticking = true
            main.postDelayed(tick, 5000)
        }
    }

    /** Where Play All in this folder left off, if anywhere. */
    fun place(key: String): Place? {
        val saved = places().optJSONObject(key) ?: return null
        val file = saved.optString("file").ifEmpty { return null }
        return Place(file, saved.optDouble("position", 0.0))
    }

    /** Notes where the playing folder is. Nothing if the playlist is not its any more. */
    fun record() {
        val key = key ?: return
        val index = Engine.currentTrack
        if (Engine.trackCount != files.size || index !in files.indices) return
        // Still the folder's: the track playing is the file it was given as
        val path = Engine.trackPath(index)
        val file = files[index]
        if (!path.startsWith("http") && local(path) != file) return
        save(key, Place(file, if (Engine.loaded) Engine.position else 0.0))
    }

    /**
     * Once the file starting has loaded, to where it was left: a few seconds
     * back, to hear where you were again.
     */
    fun seek(place: Place) {
        if (place.position <= 5) return
        scope.launch {
            repeat(150) {  // up to 15 seconds for a file on a server to open
                if (Engine.loaded && Engine.length > 0) {
                    Engine.seek(maxOf(0.0, place.position - 3))
                    return@launch
                }
                delay(100)
            }
        }
    }

    private fun places(): JSONObject = try {
        JSONObject(Engine.prefs().getString(PLACES_KEY, null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun save(key: String, place: Place) {
        val prefs = Engine.prefs()
        val places = places()
        val order = try {
            JSONArray(prefs.getString(PLACES_KEY + "Order", null) ?: "[]").let { array ->
                (0 until array.length()).map { array.getString(it) }.toMutableList()
            }
        } catch (e: Exception) {
            mutableListOf()
        }
        order.remove(key)
        order.add(key)
        places.put(key, JSONObject().put("file", place.file).put("position", place.position))
        while (order.size > KEEP) places.remove(order.removeAt(0))
        prefs.edit()
            .putString(PLACES_KEY, places.toString())
            .putString(PLACES_KEY + "Order", JSONArray(order).toString())
            .apply()
    }
}
