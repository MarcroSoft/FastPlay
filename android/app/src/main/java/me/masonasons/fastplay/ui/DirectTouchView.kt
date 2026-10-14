package me.masonasons.fastplay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The player's touch mode: the whole area takes swipes.
 *
 *   one finger left or right     seek back or forward
 *   one finger up or down        the next or previous seek unit
 *                                (in spring and tape seeking: the speed)
 *   one finger held and dragged  in spring and tape seeking: scrubs back (to the
 *   left or right                left) or forward for as long as it is held
 *   two fingers left or right    the previous or next effect parameter
 *   two fingers up or down       the parameter up or down
 *   two finger tap               play or pause
 *
 * With BackTalk's direct touch (DirectTouchGate) the touches come straight here
 * while a screen reader runs, and FastPlay says what each one did. Under plain
 * TalkBack, which passes no touches through, the same commands are this area's
 * actions in TalkBack's actions menu.
 */
@SuppressLint("ViewConstructor")
class DirectTouchView(context: Context) : LinearLayout(context) {
    /** Spring or tape seeking: a drag left or right scrubs while it is held, in place of the swipe that jumps. */
    var scrubMode = false
        set(value) {
            if (field == value) return
            field = value
            guide.text = guideText
        }

    /** A drag began (or turned round): scrub back (-1) or forward (1) until onScrubStop. */
    var onScrubStart: ((Int) -> Unit)? = null
    var onScrubStop: (() -> Unit)? = null
    var onSeek: ((Int) -> Unit)? = null
    var onSeekUnit: ((Int) -> Unit)? = null
    var onParam: ((Int) -> Unit)? = null
    var onAdjust: ((Int) -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null

    val seekLabel = TextView(context)
    val effectLabel = TextView(context)
    private val guide = TextView(context)

    // The gesture under way
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var fingers = 0
    private var scrubDirection = 0
    private var scrubbing = false
    private val slop = context.dp(24).toFloat()
    private val swipeDistance = context.dp(40).toFloat()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        background = GradientDrawable().apply {
            cornerRadius = context.dp(16).toFloat()
            setColor(context.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            setStroke(context.dp(1), context.themeColor(com.google.android.material.R.attr.colorOutlineVariant))
        }
        for (label in listOf(seekLabel, effectLabel)) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            label.gravity = Gravity.CENTER
            label.maxLines = 2
        }
        guide.text = guideText
        guide.gravity = Gravity.CENTER
        guide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        guide.setTextColor(context.secondaryText())
        addView(seekLabel)
        addView(effectLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(16) })
        addView(guide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(16) })

        // One element to a screen reader, with the commands as its actions
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        contentDescription = "Touch controls"
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.hintText = hintText
                // For BackTalk: this area takes touches directly (its direct touch regions)
                info.extras.putBoolean(DirectTouchGate.REGION_EXTRA, true)
            }
        })
        val commands = listOf(
            "Seek back" to { onSeek?.invoke(-1) },
            "Seek forward" to { onSeek?.invoke(1) },
            "Previous seek unit" to { onSeekUnit?.invoke(-1) },
            "Next seek unit" to { onSeekUnit?.invoke(1) },
            "Previous effect" to { onParam?.invoke(-1) },
            "Next effect" to { onParam?.invoke(1) },
            "Adjust down" to { onAdjust?.invoke(-1) },
            "Adjust up" to { onAdjust?.invoke(1) },
            "Play or pause" to { onPlayPause?.invoke() },
        )
        for ((label, run) in commands) {
            ViewCompat.addAccessibilityAction(this, label) { _, _ ->
                run()
                true
            }
        }
    }

    private val guideText: String
        get() = (if (scrubMode) "Hold and drag left or right to scrub, swipe up or down for the speed.\n"
        else "Swipe left or right to seek, up or down for the seek unit.\n") +
            "With two fingers: left or right for the effect, up or down to adjust it. Two finger tap: play or pause."

    private val hintText: String
        get() = (if (scrubMode) "Hold and drag left or right to scrub back or forward, and swipe up or down to change the speed. "
        else "Swipe left or right to seek, up or down to change the seek unit. ") +
            "With two fingers, swipe left or right to choose an effect, up or down to adjust it, and tap to play or pause. " +
            "With BackTalk's direct touch, or from the actions menu."

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                fingers = 1
                scrubbing = false
                scrubDirection = 0
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                fingers = maxOf(fingers, event.pointerCount)
                endScrub()  // a second finger is a two finger gesture, not a scrub
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = event.getX(0)
                lastY = event.getY(0)
                if (scrubMode && fingers == 1) drag(lastX - startX, lastY - startY)
            }
            MotionEvent.ACTION_UP -> {
                lastX = event.x
                lastY = event.y
                if (scrubbing) endScrub() else finish(SystemClock.uptimeMillis() - downTime)
                fingers = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                endScrub()
                fingers = 0
            }
        }
        return true
    }

    /**
     * The drag is for scrubbing, and only when it sets off sideways. Which side of
     * where it started the finger is on decides the direction; a little way past
     * the start before it turns round, so a wobble does not.
     */
    private fun drag(dx: Float, dy: Float) {
        if (!scrubbing) {
            if (hypot(dx, dy) < slop || abs(dx) <= abs(dy)) return
            scrubbing = true
        }
        var direction = scrubDirection
        if (direction == 0) direction = if (dx < 0) -1 else 1
        if (dx > slop) direction = 1 else if (dx < -slop) direction = -1
        if (direction != scrubDirection) {
            scrubDirection = direction
            onScrubStart?.invoke(direction)
        }
    }

    private fun endScrub() {
        if (scrubDirection != 0) {
            scrubDirection = 0
            onScrubStop?.invoke()
        }
        scrubbing = false
    }

    /** A gesture over: a swipe one way or another, or a two finger tap. */
    private fun finish(duration: Long) {
        val dx = lastX - startX
        val dy = lastY - startY
        val distance = hypot(dx, dy)
        if (distance < swipeDistance) {
            if (fingers >= 2 && duration < 400) onPlayPause?.invoke()
            return
        }
        if (duration > 1000) return  // a slow drag is not a swipe
        val horizontal = abs(dx) > abs(dy)
        val step = if (horizontal) (if (dx > 0) 1 else -1) else (if (dy < 0) 1 else -1)
        when {
            fingers == 1 && horizontal -> if (!scrubMode) onSeek?.invoke(step)
            fingers == 1 -> onSeekUnit?.invoke(step)
            horizontal -> onParam?.invoke(step)
            else -> onAdjust?.invoke(step)
        }
    }
}

fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return value.data
}
