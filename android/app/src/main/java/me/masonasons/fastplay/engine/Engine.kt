package me.masonasons.fastplay.engine

import android.content.Context
import android.os.Handler
import android.os.Looper

/** How the seek controls move through the audio (the desktop's slash key). */
enum class SeekMode {
    /** By the seek unit. */
    JUMP,
    /** Held: faster the longer it goes, pitch kept. */
    SPRING,
    /** Held: like a tape spooling. */
    TAPE,
}

/** A parameter the effect controls can be on: "Tempo", "Echo Delay"... [text] is it and its value: "Tempo 5%". */
data class Param(val identifier: Int, val name: String, val text: String)

/** An effect that is on or off. Its parameters are among the effect controls' while it is on. */
data class Effect(val identifier: Int, val name: String, val enabled: Boolean)

/** A unit the seek controls can move by: "5 seconds", "1 track"... */
data class SeekUnit(val identifier: Int, val name: String, val enabled: Boolean)

/**
 * FastPlay's engine, as the app's screens use it: the iPhone's FPEngine. The C++
 * core (player, effects, settings, database) is behind it. Everything here is for
 * the main thread.
 */
object Engine {
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    /** False if the audio engine could not start; the first screen says so. */
    var startedOk = false
        private set

    // ---- Listeners, as the iPhone's notifications ----

    private val stateListeners = mutableListOf<() -> Unit>()
    private val messageListeners = mutableListOf<(String, String) -> Unit>()
    private val deviceStartListeners = mutableListOf<() -> Unit>()

    /** Called on the main thread when what is playing, its state or the playlist changes. */
    fun addStateListener(listener: () -> Unit) = stateListeners.add(listener)
    fun removeStateListener(listener: () -> Unit) = stateListeners.remove(listener)

    /** The engine has something to tell the user: a title and the text. */
    fun addMessageListener(listener: (String, String) -> Unit) = messageListeners.add(listener)
    fun removeMessageListener(listener: (String, String) -> Unit) = messageListeners.remove(listener)

    /** The output is about to start (any thread): the time to ask for audio focus. */
    fun addDeviceStartListener(listener: () -> Unit) = synchronized(deviceStartListeners) { deviceStartListeners.add(listener) }

    internal fun notifyStateChanged() {
        for (listener in stateListeners.toList()) listener()
    }

    internal fun notifyMessage(title: String, text: String) {
        for (listener in messageListeners.toList()) listener(title, text)
    }

    internal fun notifyBeforeDeviceStart() {
        val listeners = synchronized(deviceStartListeners) { deviceStartListeners.toList() }
        for (listener in listeners) listener()
    }

    /** Tells the screens something changed that the engine does not know of (a setting of the app's own). */
    fun postStateChange() = main.post { notifyStateChanged() }

    // ---- Starting ----

    /**
     * Loads the settings and starts the audio engine, once. [documents] is
     * FastPlay's own folder of the device's files, where recordings go.
     */
    fun start(context: Context, documents: String): Boolean {
        if (started) return startedOk
        started = true
        this.context = context.applicationContext
        Callbacks.init(context)
        val data = context.filesDir.resolve("FastPlay").apply { mkdirs() }
        Native.setPaths(data.path, documents, context.cacheDir.path, context.applicationInfo.nativeLibraryDir)
        startedOk = Native.start()
        // The desktop's scheduler looks once a minute
        main.postDelayed(object : Runnable {
            override fun run() {
                Native.checkScheduledEvents()
                main.postDelayed(this, 60_000)
            }
        }, 60_000)
        return startedOk
    }

    /** Saves the settings, the playlist and where playback is (going to the background). */
    fun saveState() = Native.saveState()

    /** Where playback is, saved every half minute: call it while playing. */
    fun keepPlaceSaved() = Native.keepPlaceSaved()

    /** Coming back to the app with something playing: the output going again. */
    fun ensureDeviceRunning() = Native.ensureDeviceRunning()

    /** One of the desktop's commands (commands.h), as the media buttons send them. */
    fun runCommand(command: Int, param: Int = 0) = Native.runCommand(command, param)

    // ---- Files ----

    /** FastPlay's own folder of the device's files, where recordings and copies from servers go. */
    val documentsPath: String get() = Native.documentsPath()
    fun isPlayableFile(path: String) = Native.isPlayableFile(path)
    /** Plays a file, with the other files of its folder as the playlist. */
    fun playFile(path: String) = Native.playFile(path)
    /** Plays everything in a folder and the folders inside it. */
    fun playFolder(path: String) = Native.playFolder(path)
    /** Plays an address (a stream, a file on a server). `name` is what to call it. */
    fun playUrl(url: String, name: String? = null) = Native.playUrl(url, name ?: "")
    /** Plays several addresses as a playlist, each under its name, from the one at `index`. */
    fun playUrls(urls: List<String>, names: List<String>, startingAt: Int) =
        Native.playUrls(urls.toTypedArray(), names.toTypedArray(), startingAt)

    // ---- The playlist ----

    val trackCount: Int get() = Native.trackCount()
    /** -1: none */
    val currentTrack: Int get() = Native.currentTrack()
    fun trackName(index: Int): String = Native.trackName(index)
    /** The file or address of a track ("" for none). */
    fun trackPath(index: Int): String = Native.trackPath(index)
    fun playTrack(index: Int) = Native.playTrack(index)

    // ---- Playback ----

    fun playPause() = Native.playPause()
    fun play() = Native.play()
    fun pause() = Native.pause()
    fun stop() = Native.stop()
    fun nextTrack() = Native.nextTrack()
    fun previousTrack() = Native.previousTrack()
    val loaded: Boolean get() = Native.loaded()
    val playing: Boolean get() = Native.playing()
    val live: Boolean get() = Native.live()
    /** Seconds */
    val position: Double get() = Native.position()
    /** Seconds; 0 when unknown */
    val length: Double get() = Native.length()
    fun seek(to: Double) = Native.seekTo(to)
    /** What is playing, or "" with nothing loaded. */
    val title: String get() = Native.title()
    val artist: String get() = Native.artist()
    /** The chapter playing ("" if the file has none). */
    val chapterTitle: String get() = Native.chapterTitle()
    /** "Playing | 128 kbps" */
    val statusText: String get() = Native.statusText()
    fun formatTime(seconds: Double): String = Native.formatTime(seconds)

    // ---- Seeking, as the desktop's arrows, comma and period do ----

    /** One step back (-1) or forward (1) by the seek unit. */
    fun seekStep(direction: Int) = Native.seekStep(direction)
    /** The next or previous seek unit; in spring and tape seeking, the speed. */
    fun changeSeekUnit(direction: Int) = Native.changeSeekUnit(direction)
    /** "5 seconds", "1 track", "1 chapter"; in spring and tape seeking, "8 times". */
    val seekUnitText: String get() = Native.seekUnitText()
    var seekMode: SeekMode
        get() = SeekMode.entries.getOrElse(Native.seekMode()) { SeekMode.JUMP }
        set(value) = Native.setSeekMode(value.ordinal)
    val seekModeName: String get() = Native.seekModeName()
    fun cycleSeekMode() = Native.cycleSeekMode()
    /** Spring and tape seeking: scrub from now until stopScrubbing. */
    val scrubSeekMode: Boolean get() = Native.scrubSeekMode()
    fun startScrubbing(direction: Int) = Native.startScrubbing(direction)
    fun stopScrubbing() = Native.stopScrubbing()

    // ---- Reverse and random ----

    /**
     * Plays the file backward from here, or forward again; says which. Seeking
     * carries on in reverse; at the start it stops there, paused, and another
     * file loading ends it. Not for live streams.
     */
    fun toggleReverse() = Native.toggleReverse()
    val reverse: Boolean get() = Native.reverse()
    /** Somewhere at random in the file, said as a time. Not for live streams. */
    fun jumpToRandomPoint() = Native.jumpToRandomPoint()

    // ---- Recording ----

    /**
     * Records what is playing, as it is heard, into the Recordings folder of
     * FastPlay's files, until toggled again. Says so, and why not if it cannot.
     */
    fun toggleRecording() = Native.toggleRecording()
    val recording: Boolean get() = Native.recording()

    // ---- The effect controls, as the desktop's brackets and up and down ----

    fun cycleParam(direction: Int) = Native.cycleParam(direction)
    fun adjustParam(direction: Int) = Native.adjustParam(direction)
    fun resetParam() = Native.resetParam()
    val paramName: String get() = Native.paramName()
    /** "Tempo 5%" */
    val paramText: String get() = Native.paramText()
    val params: List<Param>
        get() = Native.params().toList().chunked(3).map { Param(it[0].toInt(), it[1], it[2]) }
    val currentParam: Int get() = Native.currentParam()
    fun selectParam(identifier: Int) = Native.selectParam(identifier)

    // ---- Settings ----

    val effects: List<Effect>
        get() = Native.effects().toList().chunked(3).map { Effect(it[0].toInt(), it[1], it[2] == "1") }
    fun setEffect(identifier: Int, enabled: Boolean) = Native.setEffect(identifier, enabled)
    val seekUnits: List<SeekUnit>
        get() = Native.seekUnits().toList().chunked(3).map { SeekUnit(it[0].toInt(), it[1], it[2] == "1") }
    fun setSeekUnit(identifier: Int, enabled: Boolean) = Native.setSeekUnit(identifier, enabled)

    /**
     * Plays alongside other apps' sound instead of stopping it. Off unless turned
     * on. While on, FastPlay does not ask for audio focus, so the lock screen and
     * headphone controls stay with the other app.
     */
    var mixWithOthers: Boolean
        get() = prefs().getBoolean("MixWithOthers", false)
        set(value) {
            prefs().edit().putBoolean("MixWithOthers", value).apply()
            postStateChange()
        }

    /** While FastPlay is in front, the screen does not dim and lock by itself. Off unless turned on. */
    var keepScreenOn: Boolean
        get() = prefs().getBoolean("KeepScreenOn", false)
        set(value) {
            prefs().edit().putBoolean("KeepScreenOn", value).apply()
            postStateChange()
        }

    val engineInfo: String get() = Native.engineInfo()
    val version: String get() = Native.version()

    /**
     * A numeric or yes/no option (yes is 1), by the name the settings screens use
     * (settings_jni.cpp has them): the desktop Options window's options, saved in
     * the same settings file.
     */
    fun number(forSetting: String): Double = Native.numberSetting(forSetting)
    fun setNumber(value: Double, forSetting: String) = Native.setNumberSetting(forSetting, value)
    /** An option that is a file ("convolutionIR", "midiSoundFont"): its name, or "" if none is chosen. */
    fun fileName(forSetting: String): String = Native.fileSetting(forSetting)
    /** Chooses the file at `path` (copied into FastPlay's own data), or with null, none. */
    fun setFile(path: String?, forSetting: String): Boolean = Native.setFileSetting(forSetting, path)

    // ---- Speech ----

    /** Through TalkBack, when it is on. */
    fun speak(text: String) = Native.speak(text)
    /**
     * While set, what the engine would say is dropped: for a control TalkBack reads
     * the new value of by itself, which would otherwise be said twice.
     */
    var speechMuted: Boolean
        get() = Native.speechMuted()
        set(value) = Native.setSpeechMuted(value)

    /** The app's own settings, apart from FastPlay.ini (the iPhone's user defaults). Ready before [start]. */
    fun prefs() = me.masonasons.fastplay.FastPlayApp.instance.getSharedPreferences("FastPlay", Context.MODE_PRIVATE)
}
