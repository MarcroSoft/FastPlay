package me.masonasons.fastplay

import android.app.Application
import android.os.Environment
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.playback.PlaybackController
import java.io.File

/** Starts the engine as the app starts, as the iPhone's AppDelegate does. */
class FastPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Engine.start(this, documentsFolder().path)
        PlaybackController.init(this)
    }

    companion object {
        lateinit var instance: FastPlayApp
            private set

        /**
         * FastPlay's own folder of the device's files, as the iPhone's Documents
         * folder is: recordings, and what is copied from servers and Dropbox, go
         * there. It is in the device's shared storage, so other apps and a computer
         * see it too.
         */
        fun documentsFolder(): File = File(Environment.getExternalStorageDirectory(), "FastPlay")
    }
}
