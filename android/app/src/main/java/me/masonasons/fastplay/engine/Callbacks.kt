package me.masonasons.fastplay.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the engine asks of the app, called from libfastplay.so (android_jni.h):
 * the UI thread, speech, messages, timers and HTTP. Some arrive on the engine's
 * own threads; those say so.
 */
object Callbacks {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /** The engine has queued work for the UI thread (any thread). */
    @JvmStatic
    fun postUi() {
        main.post { Native.runUiQueue() }
    }

    /** What is playing, its state or the playlist changed (UI thread). */
    @JvmStatic
    fun stateChanged() {
        Engine.notifyStateChanged()
    }

    /** Something to tell the user: a file that will not play, say (UI thread). */
    @JvmStatic
    fun message(title: String, text: String) {
        Engine.notifyMessage(title.ifEmpty { "FastPlay" }, text)
    }

    /**
     * What FastPlay says (UI thread), through TalkBack as an announcement while a
     * screen reader is on, so it is spoken in the user's voice and settings. With
     * none on, nothing is said, as on the iPhone with VoiceOver off. Android gives
     * an app no say in whether an announcement cuts off what is being said.
     */
    @JvmStatic
    fun speak(text: String, @Suppress("UNUSED_PARAMETER") interrupt: Boolean) {
        if (text.isEmpty()) return
        val accessibility = context.getSystemService(AccessibilityManager::class.java) ?: return
        if (!accessibility.isEnabled) return
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        event.text.add(text)
        event.packageName = context.packageName
        event.className = Callbacks::class.java.name
        accessibility.sendAccessibilityEvent(event)
    }

    private val durationTimer = Runnable { Native.durationTimerFired() }

    /** A scheduled stop after a while (UI thread). */
    @JvmStatic
    fun startDurationTimer(ms: Int) {
        main.removeCallbacks(durationTimer)
        main.postDelayed(durationTimer, ms.toLong())
    }

    @JvmStatic
    fun stopDurationTimer() {
        main.removeCallbacks(durationTimer)
    }

    /** The output is about to start: ask for the sound (audio focus). Any thread. */
    @JvmStatic
    fun beforeDeviceStart() {
        Engine.notifyBeforeDeviceStart()
    }

    // ---- HTTP (http.h), on the engine's worker threads ----

    private const val MAX_REDIRECTS = 5  // six requests in all, as on the other systems

    /**
     * One GET, run to its end. `headers` are "Name: value", the user agent first
     * (and Basic credentials, when given, already among them). Redirects are
     * followed here rather than by HttpURLConnection, which will not go from https
     * to http, and the headers go along to the next host. A `progressHandle` of 0
     * means no progress is wanted; otherwise it is handed back to
     * Native.httpProgress, which says whether to carry on.
     */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun httpGet(
        url: String, headers: Array<String>, username: String, password: String,
        followRedirects: Boolean, readBody: Boolean, saveTo: String, timeoutMs: Int, progressHandle: Long,
    ): HttpResponse {
        val result = HttpResponse()
        var address = url
        var redirects = 0
        try {
            while (true) {
                val connection = URL(address).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                if (timeoutMs > 0) {
                    connection.connectTimeout = timeoutMs
                    connection.readTimeout = timeoutMs
                } else {
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 60_000
                }
                for (header in headers) {
                    val colon = header.indexOf(':')
                    if (colon <= 0) continue
                    connection.setRequestProperty(header.substring(0, colon).trim(), header.substring(colon + 1).trim())
                }
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status in 300..399 && location != null && followRedirects && redirects < MAX_REDIRECTS) {
                    redirects++
                    address = URL(URL(address), location).toString()
                    connection.disconnect()
                    continue
                }
                result.completed = true
                result.status = status
                result.finalUrl = address
                if (!readBody) {
                    connection.disconnect()
                    break
                }
                val total = connection.contentLengthLong.coerceAtLeast(0)
                val input: InputStream? = try {
                    connection.inputStream
                } catch (e: IOException) {
                    connection.errorStream  // an error page is still the answer
                }
                input?.use { stream ->
                    if (saveTo.isNotEmpty()) {
                        FileOutputStream(File(saveTo)).use { file -> copy(stream, file, total, progressHandle, result) }
                    } else {
                        val bytes = java.io.ByteArrayOutputStream()
                        copy(stream, bytes, total, progressHandle, result)
                        result.body = bytes.toByteArray()
                    }
                }
                connection.disconnect()
                if (result.cancelled) result.completed = false
                break
            }
        } catch (e: Exception) {
            result.completed = false
            result.systemError = 1
            result.errorText = e.message ?: e.javaClass.simpleName
        }
        if (result.finalUrl.isEmpty()) result.finalUrl = url
        return result
    }

    private fun copy(input: InputStream, output: OutputStream, total: Long, progressHandle: Long, result: HttpResponse) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            result.bytesReceived += n
            if (progressHandle != 0L && !Native.httpProgress(progressHandle, result.bytesReceived, total)) {
                result.cancelled = true
                break
            }
        }
    }
}

/** An answer to Callbacks.httpGet, read field by field by android_platform.cpp. */
class HttpResponse {
    @JvmField var completed = false
    @JvmField var cancelled = false
    @JvmField var status = 0
    @JvmField var body: ByteArray? = null
    @JvmField var bytesReceived = 0L
    @JvmField var finalUrl = ""
    @JvmField var systemError = 0
    @JvmField var errorText = ""
}
