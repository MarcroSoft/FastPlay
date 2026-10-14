package me.masonasons.fastplay.ui

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import me.masonasons.fastplay.R

/**
 * One of the player's sliders: a name, the current value, and a step either way.
 *
 * To TalkBack it is a single slider: its adjust gestures (the volume keys, or the
 * reading control set to adjust) step it, and the new value is read out. On
 * screen it is the name and value between a minus and a plus button.
 */
class AdjustableRow(
    context: Context,
    private val name: String,
    private val hint: String,
    @DrawableRes minusIcon: Int = R.drawable.ic_remove,
    @DrawableRes plusIcon: Int = R.drawable.ic_add,
) : LinearLayout(context) {
    /** Called with -1 or 1. */
    var onStep: ((Int) -> Unit)? = null

    /** What it is at, shown and read. Changing it says nothing by itself (a position ticks on). */
    var value: String = ""
        set(new) {
            field = new
            valueLabel.text = new
        }

    private val nameLabel = TextView(context)
    private val valueLabel = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(6), 0, context.dp(6))

        nameLabel.text = name
        nameLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        nameLabel.setTextColor(context.secondaryText())
        nameLabel.gravity = Gravity.CENTER
        valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        valueLabel.gravity = Gravity.CENTER
        valueLabel.maxLines = 2

        addView(button(minusIcon, -1), LayoutParams(context.dp(56), context.dp(48)))
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(nameLabel)
            addView(valueLabel)
        }
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(button(plusIcon, 1), LayoutParams(context.dp(56), context.dp(48)))

        // One control to TalkBack: what is inside is not read apart
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.SeekBar::class.java.name
                info.contentDescription = name
                info.stateDescription = value
                info.hintText = hint
                // A range with room either way, so TalkBack offers to adjust it
                info.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                    AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT, 0f, 2f, 1f)
                info.addAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                info.addAction(AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> step(1, spoken = true)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> step(-1, spoken = true)
                else -> super.performAccessibilityAction(host, action, args)
            }
        })
    }

    private fun button(@DrawableRes icon: Int, direction: Int) = ImageButton(context).apply {
        setImageResource(icon)
        background = context.selectableBackground()
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setOnClickListener { step(direction, spoken = false) }
    }

    /**
     * Steps, then (from TalkBack) reads the new value, as VoiceOver does after an
     * adjustable control's swipe. The engine's own speech of the change is muted
     * by the player around the step, so it is said once.
     */
    private fun step(direction: Int, spoken: Boolean): Boolean {
        onStep?.invoke(direction)
        if (spoken) announceForAccessibility(value)
        return true
    }
}
