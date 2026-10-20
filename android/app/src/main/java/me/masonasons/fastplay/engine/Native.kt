package me.masonasons.fastplay.engine

/**
 * libfastplay.so's entry points (src/main/cpp). The app uses them through
 * [Engine], [Sources] and the rest, never directly: lists come back as flat
 * string arrays, a fixed number of strings an item, which those turn into objects.
 *
 * Everything but the network lookups (searches, feeds, FTP) is for the main
 * thread, as the iPhone's FPEngine is.
 */
object Native {
    init {
        System.loadLibrary("fastplay")
    }

    // ---- The system (android_platform.cpp) ----
    @JvmStatic external fun setPaths(data: String, documents: String, temp: String, libraries: String)
    @JvmStatic external fun runUiQueue()
    @JvmStatic external fun httpProgress(handle: Long, received: Long, total: Long): Boolean

    // ---- The engine (engine_jni.cpp) ----
    @JvmStatic external fun start(): Boolean
    @JvmStatic external fun saveState()
    @JvmStatic external fun ensureDeviceRunning(): Boolean
    @JvmStatic external fun checkScheduledEvents()
    @JvmStatic external fun durationTimerFired()
    @JvmStatic external fun runCommand(command: Int, param: Int)

    @JvmStatic external fun documentsPath(): String
    @JvmStatic external fun isPlayableFile(path: String): Boolean
    @JvmStatic external fun playFile(path: String)
    @JvmStatic external fun playFolder(path: String)
    @JvmStatic external fun playUrl(url: String, name: String)
    @JvmStatic external fun playUrls(urls: Array<String>, names: Array<String>, index: Int)

    @JvmStatic external fun trackCount(): Int
    @JvmStatic external fun currentTrack(): Int
    @JvmStatic external fun trackName(index: Int): String
    @JvmStatic external fun trackPath(index: Int): String
    @JvmStatic external fun playTrack(index: Int)

    @JvmStatic external fun playPause()
    @JvmStatic external fun play()
    @JvmStatic external fun pause()
    @JvmStatic external fun stop()
    @JvmStatic external fun nextTrack()
    @JvmStatic external fun previousTrack()
    @JvmStatic external fun loaded(): Boolean
    @JvmStatic external fun playing(): Boolean
    @JvmStatic external fun live(): Boolean
    @JvmStatic external fun position(): Double
    @JvmStatic external fun length(): Double
    @JvmStatic external fun seekTo(seconds: Double)
    @JvmStatic external fun title(): String
    @JvmStatic external fun artist(): String
    @JvmStatic external fun chapterTitle(): String
    @JvmStatic external fun statusText(): String
    @JvmStatic external fun formatTime(seconds: Double): String

    @JvmStatic external fun seekStep(direction: Int)
    @JvmStatic external fun changeSeekUnit(direction: Int)
    @JvmStatic external fun seekUnitText(): String
    @JvmStatic external fun seekMode(): Int
    @JvmStatic external fun seekModeName(): String
    @JvmStatic external fun setSeekMode(mode: Int)
    @JvmStatic external fun cycleSeekMode()
    @JvmStatic external fun scrubSeekMode(): Boolean
    @JvmStatic external fun startScrubbing(direction: Int)
    @JvmStatic external fun stopScrubbing()

    @JvmStatic external fun toggleReverse()
    @JvmStatic external fun reverse(): Boolean
    @JvmStatic external fun jumpToRandomPoint()
    @JvmStatic external fun toggleRecording()
    @JvmStatic external fun recording(): Boolean

    @JvmStatic external fun cycleParam(direction: Int)
    @JvmStatic external fun adjustParam(direction: Int)
    @JvmStatic external fun resetParam()
    @JvmStatic external fun paramName(): String
    @JvmStatic external fun paramText(): String
    @JvmStatic external fun params(): Array<String>
    @JvmStatic external fun currentParam(): Int
    @JvmStatic external fun selectParam(identifier: Int)

    @JvmStatic external fun effects(): Array<String>
    @JvmStatic external fun setEffect(identifier: Int, enabled: Boolean)
    @JvmStatic external fun seekUnits(): Array<String>
    @JvmStatic external fun setSeekUnit(identifier: Int, enabled: Boolean)
    @JvmStatic external fun engineInfo(): String
    @JvmStatic external fun version(): String

    @JvmStatic external fun speak(text: String)
    @JvmStatic external fun speechMuted(): Boolean
    @JvmStatic external fun setSpeechMuted(muted: Boolean)

    // ---- Options by name (settings_jni.cpp) ----
    @JvmStatic external fun numberSetting(name: String): Double
    @JvmStatic external fun setNumberSetting(name: String, value: Double)
    @JvmStatic external fun fileSetting(name: String): String
    @JvmStatic external fun setFileSetting(name: String, path: String?): Boolean

    // ---- Radio and podcasts (sources_jni.cpp) ----
    @JvmStatic external fun radioFavorites(): Array<String>
    @JvmStatic external fun addRadioFavorite(name: String, url: String): Boolean
    @JvmStatic external fun removeRadioFavorite(identifier: Int)
    @JvmStatic external fun searchRadio(query: String, directory: Int): Array<String>
    @JvmStatic external fun resolveRadioResult(fields: Array<String>): String
    @JvmStatic external fun importRadioFavorites(path: String): IntArray
    @JvmStatic external fun exportRadioFavorites(path: String): Boolean
    @JvmStatic external fun podcasts(): Array<String>
    @JvmStatic external fun importPodcasts(path: String): IntArray
    @JvmStatic external fun exportPodcasts(path: String): Boolean
    @JvmStatic external fun isSubscribedToFeed(feedUrl: String): Boolean
    @JvmStatic external fun subscribeToPodcast(name: String, feedUrl: String)
    @JvmStatic external fun removePodcast(identifier: Int)
    @JvmStatic external fun searchPodcasts(query: String): Array<String>
    @JvmStatic external fun loadEpisodes(feedUrl: String): Array<String>
    @JvmStatic external fun playEpisodes(titles: Array<String>, urls: Array<String>, index: Int)

    // ---- FTP through FFmpeg (remote_jni.cpp) ----
    @JvmStatic external fun ftpList(url: String): Array<String>
    @JvmStatic external fun ftpCopy(url: String, path: String, cancel: Cancellation?, copied: Copied?): String
}

/** Asked now and then by a long job in the engine: true gives it up. */
fun interface Cancellation {
    fun isCancelled(): Boolean
}

/** Told of each piece a long copy in the engine writes, in bytes. */
fun interface Copied {
    fun copied(count: Int)
}
