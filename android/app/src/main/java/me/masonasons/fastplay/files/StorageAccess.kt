package me.masonasons.fastplay.files

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import me.masonasons.fastplay.FastPlayApp
import me.masonasons.fastplay.ui.Dialogs
import me.masonasons.fastplay.ui.Screen
import java.util.UUID

/**
 * FastPlay's access to the device's files, which the iPhone has no need of (an
 * iPhone app has its own folder and nothing else). Browsing the device's files,
 * and copying into FastPlay's folder, need it: on Android 11 and later all files
 * access, given in the system's settings; before that, the storage permissions.
 */
object StorageAccess {
    /** What the user is told FastPlay needs, and why. */
    const val reason = "FastPlay needs access to all files to browse and play your music where it is."

    /** Whether FastPlay can read and write the device's files. */
    fun granted(context: Context = FastPlayApp.instance): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        }

    private val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** FastPlay's own folder made, once FastPlay may. */
    fun prepare() {
        if (granted()) FastPlayApp.documentsFolder().mkdirs()
    }

    /**
     * Runs [then] once FastPlay can read and write the device's files: at once if
     * it can, otherwise after asking (and after the user comes back from the
     * system's settings with access given). Nothing if the user says no.
     */
    fun ensure(screen: Screen, then: () -> Unit) {
        if (granted()) {
            prepare()
            return then()
        }
        val activity = screen.main
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // The permissions, asked for here
            var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
            launcher = activity.activityResultRegistry.register(
                "storage-${UUID.randomUUID()}", ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                launcher?.unregister()
                if (granted()) {
                    prepare()
                    then()
                }
            }
            launcher.launch(permissions)
            return
        }
        Dialogs.confirm(activity, "Access to All Files",
            "$reason In the settings that open, turn on access for FastPlay, then come back.", "Open Settings") {
            // Back from the settings: carry on if access was given. (A new observer is
            // brought up to the activity's state at once, so it waits for a pause first.)
            var left = false
            val lifecycle = activity.lifecycle
            val observer = object : LifecycleEventObserver {
                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> left = true
                        Lifecycle.Event.ON_RESUME -> if (left) {
                            lifecycle.removeObserver(this)
                            if (granted()) {
                                prepare()
                                then()
                            }
                        }
                        Lifecycle.Event.ON_DESTROY -> lifecycle.removeObserver(this)
                        else -> {}
                    }
                }
            }
            lifecycle.addObserver(observer)
            val forFastPlay = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${activity.packageName}"))
            try {
                activity.startActivity(forFastPlay)
            } catch (e: ActivityNotFoundException) {
                // Some devices have only the list of every app
                try {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e: ActivityNotFoundException) {
                    lifecycle.removeObserver(observer)
                    Dialogs.tell(activity, "Access to All Files", "The setting could not be opened.")
                }
            }
        }
    }
}
