package me.masonasons.fastplay.remote

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Closes a server connection nobody has used for a few minutes. A source is
 * dropped without being told (the browser closed, a file played long ago); on the
 * iPhone the connection goes with it, here this does it. Whatever uses the source
 * next connects again.
 */
internal class IdleCloser(private val close: () -> Unit) {
    @Volatile private var lastUse = SystemClock.elapsedRealtime()
    private var check: ScheduledFuture<*>? = null

    /** Something was done on the connection. */
    fun used() {
        lastUse = SystemClock.elapsedRealtime()
    }

    /** The connection was made: watched until it has been idle long enough. */
    @Synchronized
    fun opened() {
        used()
        if (check == null) {
            check = timer.scheduleWithFixedDelay({ look() }, 1, 1, TimeUnit.MINUTES)
        }
    }

    private fun look() {
        if (SystemClock.elapsedRealtime() - lastUse < IDLE_MILLIS) return
        synchronized(this) {
            check?.cancel(false)
            check = null
        }
        close()
    }

    private companion object {
        const val IDLE_MILLIS = 5 * 60_000L
        val timer = Executors.newSingleThreadScheduledExecutor { work ->
            Thread(work, "FastPlay.IdleCloser").apply { isDaemon = true }
        }
    }
}
