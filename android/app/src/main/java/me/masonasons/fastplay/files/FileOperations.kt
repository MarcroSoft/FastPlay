package me.masonasons.fastplay.files

// STUB: replaced by the port of ios/FastPlay/Files/FileOperations.swift (the clipboard,
// pasting, and these path helpers, which the remote sources use too).
object FileOperations {
    /** The folder a path is in. */
    fun parent(of: String): String {
        val trimmed = of.trimEnd('/')
        val slash = trimmed.lastIndexOf('/')
        return if (slash <= 0) "/" else trimmed.substring(0, slash)
    }

    /** Whether two paths of one source are the same place (ignoring a trailing slash, and case). */
    fun same(a: String, b: String): Boolean = a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)
}
