package me.masonasons.fastplay.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow

/**
 * The player's touch area with BackTalk's direct touch, which passes a screen
 * reader's touches straight to an app.
 *
 * FastPlay declares two things in its manifest:
 *
 * - dev.nvgt.capability.DIRECT_TOUCH_REGIONS: a BackTalk that knows regions passes
 *   through only the views marked with [REGION_EXTRA] (DirectTouchView), while they
 *   are on screen. The rest of the app stays with explore by touch.
 * - dev.nvgt.capability.DIRECT_TOUCH: a BackTalk without regions passes the whole
 *   screen through whenever FastPlay is in front with no dialog showing. So
 *   everywhere but touch mode FastPlay keeps a tiny window of its own open, which
 *   BackTalk takes as a dialog and so keeps touch for itself; in touch mode the
 *   window goes, and touch comes to FastPlay (the navigation bar's Back still
 *   leaves).
 */
object DirectTouchGate {
    /** The accessibility extra that marks a view as taking touches directly. */
    const val REGION_EXTRA = "dev.nvgt.DIRECT_TOUCH_REGION"

    private var activity: Activity? = null
    private var blocker: PopupWindow? = null

    /** The player is in front in touch mode. */
    var touchMode = false
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    fun attach(activity: Activity) {
        this.activity = activity
        activity.window.decorView.post { update() }
    }

    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        blocker?.dismiss()
        blocker = null
        this.activity = null
    }

    private fun update() {
        val activity = activity ?: return
        val decor = activity.window.decorView
        if (touchMode) {
            blocker?.dismiss()
            blocker = null
            return
        }
        if (blocker?.isShowing == true) return
        if (decor.windowToken == null) {
            decor.post { update() }
            return
        }
        // One pixel in the corner, under the status bar: nothing to see, read or press
        val content = View(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        blocker = PopupWindow(content, 1, 1, false).apply {
            isTouchable = true
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(0))
            try {
                showAtLocation(decor, Gravity.TOP or Gravity.START, 0, 0)
            } catch (e: Exception) {
                // The window is going away; nothing to keep touch for
            }
        }
    }
}
