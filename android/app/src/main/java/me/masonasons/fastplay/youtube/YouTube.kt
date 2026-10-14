package me.masonasons.fastplay.youtube

// STUB: YouTube through yt-dlp, Android's own (the iPhone has none).
object YouTube {
    /** A video's or playlist's address on YouTube (youtube.com, youtu.be, music.youtube.com). */
    fun isYouTubeUrl(address: String): Boolean = false

    /**
     * Plays a YouTube address: a video, or a playlist's videos as the playlist.
     * Throws with a message for the user if it could not.
     */
    suspend fun play(address: String) {}
}
