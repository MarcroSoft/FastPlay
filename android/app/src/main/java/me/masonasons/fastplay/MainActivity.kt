package me.masonasons.fastplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import me.masonasons.fastplay.dropbox.DropboxClient
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.files.AutoSync
import me.masonasons.fastplay.files.FolderResume
import me.masonasons.fastplay.ui.DirectTouchGate
import me.masonasons.fastplay.ui.Dialogs
import me.masonasons.fastplay.ui.HomeScreen
import me.masonasons.fastplay.ui.PlayerScreen
import me.masonasons.fastplay.ui.Screen
import me.masonasons.fastplay.ui.ToolbarItem
import me.masonasons.fastplay.ui.dp
import java.io.File

/**
 * The one window, as the iPhone's navigation controller: the screen in front with
 * its title and toolbar at the top, the ones under it on a back stack, and the
 * small player at the bottom while something is loaded.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomSlot: FrameLayout
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniPlayPause: ImageButton
    private var current: Screen? = null
    /** Files opened from other apps, kept open for as long as FastPlay runs (see openIncoming). */
    private val openFiles = mutableListOf<ParcelFileDescriptor>()

    private val stateListener: () -> Unit = { updateBottom() }
    private val messageListener: (String, String) -> Unit = { title, text -> Dialogs.tell(this, title, text) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always from Home: the screens are made in code with what they show, and
        // are not rebuilt from saved state after the system has let the app go
        super.onCreate(null)
        buildWindow()
        supportFragmentManager.beginTransaction().replace(R.id.screen, HomeScreen()).commitNow()
        supportFragmentManager.addOnBackStackChangedListener {
            (supportFragmentManager.findFragmentById(R.id.screen) as? Screen)?.let { screenChanged(it) }
        }
        Engine.addStateListener(stateListener)
        Engine.addMessageListener(messageListener)
        if (!Engine.startedOk) Dialogs.tell(this, "FastPlay", "FastPlay could not start its audio engine.")
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // For the media notification: play and pause from the lock screen and the shade
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        DirectTouchGate.attach(this)
        if (savedInstanceState == null) handle(intent)
    }

    override fun onDestroy() {
        Engine.removeStateListener(stateListener)
        Engine.removeMessageListener(messageListener)
        DirectTouchGate.detach(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // Going to the background: where everything is, kept
    override fun onStop() {
        FolderResume.record()
        Engine.saveState()
        super.onStop()
    }

    // At the start, and on coming back to the front: the folders that sync by themselves.
    // Back in the app with something playing: the output going again.
    override fun onStart() {
        super.onStart()
        AutoSync.runIfDue()
        if (Engine.playing) Engine.ensureDeviceRunning()
    }

    private fun buildWindow() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(FrameLayout(this).apply { id = R.id.screen },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomSlot = FrameLayout(this)
        root.addView(bottomSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The small player: what is playing (which opens the player) and play or pause
        miniPlayer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(8), dp(4))
        }
        miniTitle = TextView(this).apply {
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { push(PlayerScreen()) }
            ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                    info.hintText = "Opens the player"
                }
            })
        }
        miniPlayer.addView(miniTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        miniPlayPause = ImageButton(this).apply {
            background = null
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { Engine.playPause() }
        }
        miniPlayer.addView(miniPlayPause)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
    }

    // ---- Screens ----

    fun push(screen: Screen) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.screen, screen)
            .addToBackStack(null)
            .commit()
    }

    fun pop() = onBackPressedDispatcher.onBackPressed()

    /** The screen in front is [screen]: its title, toolbar and bottom bar. */
    fun screenChanged(screen: Screen) {
        current = screen
        toolbar.title = screen.title
        val back = supportFragmentManager.backStackEntryCount > 0
        if (back) {
            toolbar.setNavigationIcon(R.drawable.ic_chevron_left)
            toolbar.navigationContentDescription = "Back"
        } else {
            toolbar.navigationIcon = null
        }
        toolbar.menu.clear()
        for (item in screen.toolbarItems()) addMenuItem(toolbar.menu, item)
        updateBottom()
    }

    private fun addMenuItem(menu: android.view.Menu, item: ToolbarItem) {
        val submenu = item.submenu
        if (submenu != null) {
            val sub = menu.addSubMenu(item.title)
            sub.item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            item.icon?.let { sub.item.setIcon(it) } ?: sub.item.setIcon(R.drawable.ic_more)
            sub.item.contentDescription = item.title
            for (child in submenu) addMenuItem(sub, child)
            return
        }
        val menuItem = menu.add(item.title)
        menuItem.isEnabled = item.enabled
        if (item.icon != null) {
            menuItem.setIcon(item.icon)
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menuItem.contentDescription = item.title
        } else {
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        menuItem.setOnMenuItemClickListener {
            item.run()
            true
        }
    }

    /** The screen's own bar, or the small player while something is loaded, or nothing. */
    private fun updateBottom() {
        val screen = current ?: return
        val own = screen.bottomBar()
        val wanted: View? = own ?: if (screen.showsMiniPlayer && Engine.loaded) miniPlayer else null
        if (bottomSlot.childCount != 1 || bottomSlot.getChildAt(0) !== wanted) {
            bottomSlot.removeAllViews()
            (wanted?.parent as? ViewGroup)?.removeView(wanted)
            if (wanted != null) bottomSlot.addView(wanted)
        }
        if (wanted === miniPlayer) {
            val title = Engine.title
            miniTitle.text = title
            miniTitle.contentDescription = "Now playing: $title"
            val playing = Engine.playing
            miniPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            miniPlayPause.contentDescription = if (playing) "Pause" else "Play"
        }
    }

    /** Asks the screen in front for its title and toolbar again (they depend on its state). */
    fun refreshChrome() = current?.let { screenChanged(it) }

    // ---- What other apps hand over ----

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.data?.let { uri ->
            if (DropboxClient.handleRedirect(uri)) return
            if (intent.action == Intent.ACTION_VIEW) openIncoming(uri)
        }
        if (BuildConfig.DEBUG) DebugLaunch.run(this, intent)
    }

    /**
     * A file handed over by another app ("Open with FastPlay"): played where it is.
     * One with a path in the device's storage plays as itself, with its folder; one
     * without is played through its descriptor, held open while FastPlay runs.
     */
    private fun openIncoming(uri: Uri) {
        val path = localPath(uri)
        if (path != null) {
            Engine.playFile(path)
        } else {
            val descriptor = try {
                contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            } ?: return Dialogs.tell(this, "FastPlay", "That file could not be opened.")
            openFiles.add(descriptor)
            Engine.playUrl("/proc/self/fd/${descriptor.fd}", displayName(uri))
        }
        showPlayer()
    }

    private fun localPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        // A document in the device's own storage ("primary:Music/a.mp3")
        try {
            if (DocumentsContract.isDocumentUri(this, uri) && uri.authority == "com.android.externalstorage.documents") {
                val (volume, rest) = DocumentsContract.getDocumentId(uri).split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val base = if (volume.equals("primary", true)) android.os.Environment.getExternalStorageDirectory().path
                else "/storage/$volume"
                return File(base, rest).path.takeIf { File(it).canRead() }
            }
        } catch (e: Exception) {
            // Not one of those
        }
        // MediaStore still says where its files are, for those that can read them
        return try {
            contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { File(it).canRead() }
        } catch (e: Exception) {
            null
        }
    }

    fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "File"
    } catch (e: Exception) {
        uri.lastPathSegment ?: "File"
    }

    fun showPlayer() {
        if (current is PlayerScreen) return
        push(PlayerScreen())
    }

    // ---- Picking files, as the iPhone's document picker ----

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private var openCallback: ((Uri?) -> Unit)? = null
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        openCallback?.invoke(uri)
        openCallback = null
    }

    /** A file chosen in the system's picker, of [mimeTypes] (any type: star slash star). */
    fun pickFile(mimeTypes: Array<String>, then: (Uri?) -> Unit) {
        openCallback = then
        openDocument.launch(mimeTypes)
    }

    private var createCallback: ((Uri?) -> Unit)? = null
    private var createMime = "application/octet-stream"
    private val createDocument = registerForActivityResult(object : ActivityResultContracts.CreateDocument("*/*") {
        override fun createIntent(context: android.content.Context, input: String): Intent =
            super.createIntent(context, input).setType(createMime)
    }) { uri ->
        createCallback?.invoke(uri)
        createCallback = null
    }

    /** Where to save a new file called [name], chosen in the system's picker. */
    fun saveFile(name: String, mimeType: String, then: (Uri?) -> Unit) {
        createCallback = then
        createMime = mimeType
        createDocument.launch(name)
    }

    /**
     * A file chosen in the picker, copied to FastPlay's cache under its own name,
     * for code that wants a path (the engine's imports). [then] gets null if none.
     */
    fun pickFileCopy(mimeTypes: Array<String>, then: (File?) -> Unit) {
        pickFile(mimeTypes) { uri ->
            if (uri == null) return@pickFile then(null)
            val target = File(cacheDir, "Picked").apply { mkdirs() }.resolve(displayName(uri))
            val copied = try {
                contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                true
            } catch (e: Exception) {
                false
            }
            then(if (copied) target else null)
        }
    }
}
