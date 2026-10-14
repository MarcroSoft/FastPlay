package me.masonasons.fastplay.ui

import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import me.masonasons.fastplay.R
import me.masonasons.fastplay.engine.Engine
import me.masonasons.fastplay.engine.SeekMode

/**
 * The player: what is playing, the transport, and the controls for seeking and
 * the effects, in one of two modes.
 *
 * Sliders: four adjustable controls, as the desktop's keys are paired. Seek (the
 * arrows) and Seek By (comma and period); Effect (the brackets) and Adjust (up
 * and down).
 *
 * Touch: one area that takes swipes for the same four things (DirectTouchView).
 */
class PlayerScreen : Screen() {
    override val title = "Player"
    override val showsMiniPlayer = false  // this is the player

    private enum class Mode { SLIDERS, TOUCH }

    private val handler = Handler(Looper.getMainLooper())
    private var draggingPosition = false

    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var stack: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var artistLabel: TextView
    private lateinit var chapterLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var positionSlider: SeekBar
    private lateinit var elapsedLabel: TextView
    private lateinit var remainingLabel: TextView
    private lateinit var previousButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var recordButton: MaterialButton
    private lateinit var modeControl: MaterialButtonToggleGroup
    private lateinit var seekRow: AdjustableRow
    private lateinit var seekUnitRow: AdjustableRow
    private lateinit var effectRow: AdjustableRow
    private lateinit var adjustRow: AdjustableRow
    private lateinit var seekModeButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var slidersStack: LinearLayout
    private lateinit var touchView: DirectTouchView
    /** What the position slider reads as: "1:23 of 4:56" (not said as it ticks on). */
    private var positionSpoken = ""

    override fun toolbarItems() = listOf(
        ToolbarItem("Playlist", R.drawable.ic_list) { push(PlaylistScreen()) },
        ToolbarItem("Settings", R.drawable.ic_settings) { push(SettingsScreen()) },
    )

    // ---- Building the screen ----

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        fun label(size: Float, secondary: Boolean = false) = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            gravity = Gravity.CENTER
            if (secondary) setTextColor(context.secondaryText())
        }

        titleLabel = label(22f).apply {
            maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        artistLabel = label(16f, secondary = true)
        chapterLabel = label(15f, secondary = true).apply { maxLines = 2 }
        statusLabel = label(13f, secondary = true)

        positionSlider = SeekBar(context).apply {
            contentDescription = "Position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    elapsedLabel.text = Engine.formatTime(progress / 10.0)
                    // TalkBack's adjusting changes it without a touch: seek at once
                    if (!draggingPosition) Engine.seek(progress / 10.0)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    draggingPosition = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    draggingPosition = false
                    Engine.seek(bar.progress / 10.0)
                }
            })
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.stateDescription = positionSpoken
                }
            })
        }
        elapsedLabel = label(13f, secondary = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        remainingLabel = label(13f, secondary = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val times = LinearLayout(context).apply {
            addView(elapsedLabel)
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(remainingLabel)
        }

        fun transport(icon: Int, label: String, action: () -> Unit) = ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = label
            background = context.selectableBackground()
            scaleX = 1.6f
            scaleY = 1.6f
            minimumHeight = context.dp(60)
            setOnClickListener { action() }
        }
        previousButton = transport(R.drawable.ic_skip_previous, "Previous track") { Engine.previousTrack() }
        playPauseButton = transport(R.drawable.ic_play, "Play") { Engine.playPause() }
        nextButton = transport(R.drawable.ic_skip_next, "Next track") { Engine.nextTrack() }
        val transportRow = LinearLayout(context).apply {
            for (button in listOf(previousButton, playPauseButton, nextButton)) {
                addView(button, LinearLayout.LayoutParams(0, context.dp(64), 1f))
            }
        }

        recordButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setOnClickListener { Engine.toggleRecording() }
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.hintText = "Records what is playing into the Recordings folder of FastPlay's files"
                }
            })
        }

        // Sliders or touch, as the iPhone's segmented control
        modeControl = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            contentDescription = "Controls"
            for ((index, name) in listOf("Sliders", "Touch").withIndex()) {
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = name
                    tag = index
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            check(getChildAt(Engine.prefs().getInt(MODE_KEY, 0).coerceIn(0, 1)).id)
            addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) modeChanged() }
        }

        // The sliders. TalkBack reads each one's new value itself (AdjustableRow), so
        // what the engine would say about the change is dropped.
        seekRow = AdjustableRow(context, "Seek", "Adjust to seek forward or back", R.drawable.ic_fast_rewind, R.drawable.ic_fast_forward)
        seekUnitRow = AdjustableRow(context, "Seek By", "Adjust to change how far Seek moves")
        effectRow = AdjustableRow(context, "Effect", "Adjust to choose what Adjust changes", R.drawable.ic_chevron_left, R.drawable.ic_chevron_right)
        adjustRow = AdjustableRow(context, "Adjust", "Adjust to change the effect")
        seekRow.onStep = { step -> quietly { Engine.seekStep(step) } }
        seekUnitRow.onStep = { step -> quietly { Engine.changeSeekUnit(step) } }
        effectRow.onStep = { step -> quietly { Engine.cycleParam(step) } }
        adjustRow.onStep = { step -> quietly { Engine.adjustParam(step) } }

        // The seek mode belongs to touch mode, above the touch area
        seekModeButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setOnClickListener {
                Engine.cycleSeekMode()
                Engine.prefs().edit().putInt(TOUCH_SEEK_MODE_KEY, Engine.seekMode.ordinal).apply()
                refresh()
            }
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.hintText = "Jump: a swipe moves by the seek unit. Spring and tape: hold and drag to scrub through the audio."
                }
            })
        }
        resetButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Reset Effect"
            setOnClickListener {
                Engine.resetParam()
                refresh()
            }
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.hintText = "Puts the effect chosen back to its normal value"
                }
            })
        }

        slidersStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            for (row in listOf(seekRow, seekUnitRow, effectRow, adjustRow, resetButton)) addView(row)
        }

        // The touch area says what it did, through the engine's own speech
        touchView = DirectTouchView(context).apply {
            onSeek = { step -> Engine.seekStep(step); refresh() }
            onSeekUnit = { step -> Engine.changeSeekUnit(step); refresh() }
            onParam = { step -> Engine.cycleParam(step); refresh() }
            onAdjust = { step -> Engine.adjustParam(step); refresh() }
            onPlayPause = { Engine.playPause() }
            onScrubStart = { direction -> Engine.startScrubbing(direction) }
            onScrubStop = { Engine.stopScrubbing(); refresh() }
            minimumHeight = context.dp(220)
            // In touch mode a swipe in from the side is the touch area's, not Back's
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                ViewCompat.setSystemGestureExclusionRects(view, listOf(Rect(0, 0, view.width, view.height)))
            }
        }

        stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(16))
            fun add(view: View, gapAfter: Int, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                    bottomMargin = context.dp(gapAfter)
                })
            add(titleLabel, 2)
            add(artistLabel, 2)
            add(chapterLabel, 2)
            add(statusLabel, 10)
            add(positionSlider, 2)
            add(times, 10)
            add(transportRow, 4)
            add(recordButton, 12)
            add(modeControl, 10)
            add(slidersStack, 10)
            add(seekModeButton, 10)
            add(touchView, 0)
        }
        scroll = ScrollView(context).apply { isFillViewport = true }
        root = FrameLayout(context)
        applyMode()
        refresh()
        return root
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(tick)
        applyMode()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            refreshPosition()
            handler.postDelayed(this, 500)
        }
    }

    override fun engineStateChanged() = refresh()

    // ---- Actions ----

    /** Runs a step of one of the sliders with the engine's speech off. */
    private fun quietly(action: () -> Unit) {
        Engine.speechMuted = true
        action()
        Engine.speechMuted = false
        refresh()
    }

    private fun modeChanged() {
        Engine.prefs().edit().putInt(MODE_KEY, mode.ordinal).apply()
        applyMode()
        refresh()
        // Take TalkBack to the controls that just appeared
        val target: View = if (mode == Mode.TOUCH) touchView else seekRow
        target.post { target.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
        target.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    // ---- Showing what is so ----

    private val mode: Mode
        get() = if (modeControl.checkedButtonId == modeControl.getChildAt(1).id) Mode.TOUCH else Mode.SLIDERS

    private fun applyMode() {
        val touch = mode == Mode.TOUCH
        slidersStack.visibility = if (touch) View.GONE else View.VISIBLE
        touchView.visibility = if (touch) View.VISIBLE else View.GONE
        seekModeButton.visibility = if (touch) View.VISIBLE else View.GONE
        // Touch mode seeks as it last did; the sliders jump
        val touchSeekMode = SeekMode.entries.getOrElse(Engine.prefs().getInt(TOUCH_SEEK_MODE_KEY, 0)) { SeekMode.JUMP }
        Engine.seekMode = if (touch) touchSeekMode else SeekMode.JUMP
        // In touch mode every swipe belongs to the touch area: the screen does not
        // scroll, and the touch area takes whatever of the screen is left
        (stack.parent as? ViewGroup)?.removeView(stack)
        root.removeAllViews()
        val touchParams = touchView.layoutParams as LinearLayout.LayoutParams
        if (touch) {
            touchParams.height = 0
            touchParams.weight = 1f
            root.addView(stack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            touchParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            touchParams.weight = 0f
            scroll.removeAllViews()
            scroll.addView(stack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(scroll)
        }
        touchView.layoutParams = touchParams
    }

    private fun refresh() {
        if (view == null) return
        val loaded = Engine.loaded
        val title = Engine.title
        titleLabel.text = if (loaded) title else "Nothing playing"
        // The title often has the artist in it already ("Artist - Title")
        val artist = Engine.artist
        artistLabel.text = artist
        artistLabel.visibility = if (artist.isEmpty() || title.contains(artist, ignoreCase = true)) View.GONE else View.VISIBLE
        val status = Engine.statusText
        statusLabel.text = status
        statusLabel.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        refreshChapter()

        val playing = Engine.playing
        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playPauseButton.contentDescription = if (playing) "Pause" else "Play"
        val several = Engine.trackCount > 1
        previousButton.isEnabled = several
        nextButton.isEnabled = several
        previousButton.alpha = if (several) 1f else 0.4f
        nextButton.alpha = if (several) 1f else 0.4f
        playPauseButton.isEnabled = loaded || Engine.trackCount > 0

        seekUnitRow.value = Engine.seekUnitText
        val paramText = Engine.paramText
        val paramName = Engine.paramName
        effectRow.value = paramName.ifEmpty { "No effects are on" }
        adjustRow.value = paramText.ifEmpty { "Nothing to adjust" }
        seekModeButton.text = "Seek Mode: ${Engine.seekModeName}"
        resetButton.isEnabled = paramText.isNotEmpty()

        val recording = Engine.recording
        recordButton.text = if (recording) "Stop Recording" else "Record"
        recordButton.setIconResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
        recordButton.iconTint = android.content.res.ColorStateList.valueOf(
            if (recording) requireContext().getColor(R.color.recording)
            else requireContext().themeColor(androidx.appcompat.R.attr.colorPrimary),
        )
        recordButton.isEnabled = loaded || recording

        touchView.scrubMode = Engine.scrubSeekMode
        touchView.seekLabel.text = if (Engine.scrubSeekMode) "${Engine.seekModeName} seeking, ${Engine.seekUnitText}"
        else "Seek by ${Engine.seekUnitText}"
        touchView.effectLabel.text = paramText.ifEmpty { "No effects are on" }
        refreshPosition()
    }

    /** The chapter playing, under the title; hidden for a file without chapters. */
    private fun refreshChapter() {
        val chapter = Engine.chapterTitle
        if (chapterLabel.text.toString() == chapter) return
        chapterLabel.text = chapter
        chapterLabel.contentDescription = if (chapter.isEmpty()) null else "Chapter: $chapter"
        chapterLabel.visibility = if (chapter.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshPosition() {
        if (view == null) return
        refreshChapter()
        val length = Engine.length
        val position = Engine.position
        val loaded = Engine.loaded
        val seekable = loaded && length > 0
        positionSlider.isEnabled = seekable
        positionSlider.max = (maxOf(length, 1.0) * 10).toInt()
        if (!draggingPosition) positionSlider.progress = (minOf(position, maxOf(length, 1.0)) * 10).toInt()
        val elapsed = Engine.formatTime(position)
        elapsedLabel.text = if (loaded) elapsed else ""
        remainingLabel.text = if (seekable) Engine.formatTime(length) else if (Engine.live) "Live" else ""
        val spoken = if (seekable) "$elapsed of ${Engine.formatTime(length)}" else if (Engine.live) "Live" else elapsed
        positionSpoken = spoken
        seekRow.value = if (loaded) spoken else "Nothing playing"
    }

    companion object {
        private const val MODE_KEY = "PlayerMode"
        /**
         * The seek mode touch mode was last in. The sliders always jump: spring and
         * tape seek while something is held, and a slider has nothing to hold.
         */
        private const val TOUCH_SEEK_MODE_KEY = "TouchSeekMode"
    }
}
