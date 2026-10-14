package me.masonasons.fastplay.files

import me.masonasons.fastplay.engine.Engine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A folder pinned to the Home screen: on this device, in Dropbox or on a server. */
data class Favorite(
    val id: String = UUID.randomUUID().toString(),
    /** Its source's id (FileSources). */
    val sourceId: String,
    val path: String,
    val displayPath: String,
    val name: String,
) {
    val folder get() = FileEntry(name, path, displayPath, isFolder = true)
}

/** The favorites, in the app's settings, in the order they were added. */
object FavoritesStore {
    private const val KEY = "Favorites"

    var all: List<Favorite>
        get() {
            val text = Engine.prefs().getString(KEY, null) ?: return emptyList()
            return try {
                val array = JSONArray(text)
                (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    Favorite(item.getString("id"), item.getString("sourceId"), item.getString("path"),
                        item.getString("displayPath"), item.getString("name"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val array = JSONArray()
            for (favorite in value) {
                array.put(JSONObject().put("id", favorite.id).put("sourceId", favorite.sourceId)
                    .put("path", favorite.path).put("displayPath", favorite.displayPath).put("name", favorite.name))
            }
            Engine.prefs().edit().putString(KEY, array.toString()).apply()
        }

    fun find(source: FileSource, path: String): Favorite? =
        all.firstOrNull { it.sourceId == source.id && FileOperations.same(it.path, path) }

    fun add(source: FileSource, folder: FileEntry) {
        if (find(source, folder.path) != null) return
        all = all + Favorite(sourceId = source.id, path = folder.path, displayPath = folder.displayPath, name = folder.name)
    }

    fun remove(favorite: Favorite) {
        all = all.filter { it.id != favorite.id }
    }

    /** The favorites of a source that has gone (a server deleted). */
    fun removeAll(sourceId: String) {
        all = all.filter { it.sourceId != sourceId }
    }
}
