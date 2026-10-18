package me.masonasons.fastplay.files

import android.os.SystemClock
import android.text.format.Formatter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.FastPlayApp
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong

/**
 * The bytes a copy has moved so far, counted by the sources as they read and write
 * (FileSource's download and upload), for the speed shown while copying. The one
 * for the work under way is in its coroutine context ([current]), so the sources
 * need no argument for it; work a source hands to another thread takes it along.
 */
class TransferMeter : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransferMeter> {
        /** The meter of the work under way, if it is being counted. */
        suspend fun current(): TransferMeter? = currentCoroutineContext()[Key]
    }

    private val counted = AtomicLong()

    /** All the bytes so far. */
    val bytes: Long get() = counted.get()

    fun add(count: Long) {
        if (count > 0) counted.addAndGet(count)
    }

    fun add(count: Int) = add(count.toLong())
}

/**
 * What a copy is doing, in words brought up to date every second: the file, how
 * much of it has come, how fast, and when the whole size is known, how long the
 * rest will take. On the main thread.
 *
 *     3 of 12: Song.mp3
 *     4.2 MB of 10.1 MB, 1.8 MB a second
 *     45 MB of 300 MB in all, about 2 minutes left
 */
class TransferProgress(private val report: (String) -> Unit) {
    val meter = TransferMeter()
    /** All the bytes the copy will move, for the time left, when known. */
    var total: Long? = null

    private var heading = ""
    private var stepName: String? = null
    private var fileSize = 0L
    private var fileStart = 0L
    private var counted = true
    // Recent (milliseconds, bytes) readings, for the speed
    private val readings = ArrayDeque<Pair<Long, Long>>()

    /** Runs [work] with its bytes counted, saying where it has got every second. */
    suspend fun <T> run(work: suspend () -> T): T = coroutineScope {
        val ticker = launch {
            while (isActive) {
                delay(1000)
                update()
            }
        }
        try {
            withContext(meter) { work() }
        } finally {
            ticker.cancel()
        }
    }

    /**
     * A file starting: "3 of 12: Song.mp3", [size] bytes of it to come. A file
     * copied within this device isn't [counted], and only its name is shown.
     */
    fun file(heading: String, size: Long, counted: Boolean = true) {
        this.heading = heading
        fileSize = size
        this.counted = counted
        stepName = null
        fileStart = meter.bytes
        update()
    }

    /** The file's download or upload starting ("Downloading", "Uploading"), each counted from nothing. */
    fun step(name: String) {
        stepName = name
        fileStart = meter.bytes
        update()
    }

    private fun update() {
        if (heading.isEmpty()) return  // still looking
        val bytes = meter.bytes
        val now = SystemClock.elapsedRealtime()
        readings.addLast(now to bytes)
        while (now - readings.first().first > 5000) readings.removeFirst()
        val (firstTime, firstBytes) = readings.first()
        val speed = if (now - firstTime >= 1000) (bytes - firstBytes) * 1000.0 / (now - firstTime) else null

        val lines = mutableListOf(heading)
        if (counted) {
            val done = maxOf(0, bytes - fileStart)
            var line = stepName?.let { "$it: " } ?: ""
            line += if (fileSize > 0) "${size(minOf(done, fileSize))} of ${size(fileSize)}" else size(done)
            if (speed != null) line += ", ${size(speed.toLong())} a second"
            lines.add(line)
        }
        val total = total
        if (total != null && total > 0) {
            var line = "${size(minOf(bytes, total))} of ${size(total)} in all"
            if (speed != null && speed > 0 && total > bytes) line += ", about ${duration((total - bytes) / speed)} left"
            lines.add(line)
        }
        report(lines.joinToString("\n"))
    }

    companion object {
        fun size(bytes: Long): String = Formatter.formatFileSize(FastPlayApp.instance, bytes)

        /** "40 seconds", "2 minutes", "1 hour, 5 minutes" (as the iPhone says them). */
        fun duration(seconds: Double): String {
            if (seconds < 60) {
                // To the nearest 5 seconds, so it doesn't count down every second
                val rounded = maxOf(5, (seconds / 5).roundToLong() * 5)
                return "$rounded seconds"
            }
            val minutes = maxOf(1, (seconds / 60).roundToLong())
            val hours = minutes / 60
            val left = minutes % 60
            fun unit(count: Long, name: String) = if (count == 1L) "1 $name" else "$count ${name}s"
            return when {
                hours == 0L -> unit(left, "minute")
                left == 0L -> unit(hours, "hour")
                else -> "${unit(hours, "hour")}, ${unit(left, "minute")}"
            }
        }
    }
}
