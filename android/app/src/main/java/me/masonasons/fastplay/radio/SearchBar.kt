package me.masonasons.fastplay.radio

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import me.masonasons.fastplay.ui.dp

/**
 * A search field at the top of a list, as the iPhone's search bar: the keyboard's
 * search key searches ([onSearch]), and Cancel ([onCancel]) clears it and goes back
 * to what the list had. Once the field is used the search is active until Cancel:
 * Cancel shows, and so do the [scopes] (if any), which choose where to search.
 */
internal class SearchBar(context: Context, placeholder: String, scopes: List<String> = emptyList()) : LinearLayout(context) {
    /** What to keep while the screen's view is gone (with the player in front). */
    data class State(val text: String, val scope: Int, val active: Boolean)

    var onSearch: () -> Unit = {}
    var onCancel: () -> Unit = {}
    var onScopeChanged: () -> Unit = {}

    private val box = textField(context, placeholder, imeAction = EditorInfo.IME_ACTION_SEARCH, clearButton = true)
    private val edit = box.editText!!
    private val cancel = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle)
    private val scopeGroup = RadioGroup(context)
    private val scopeIds = scopes.map { View.generateViewId() }
    private val scopeBar = HorizontalScrollView(context)
    private var quiet = false

    /** Searching, or about to: from the field's first use until Cancel. */
    var active = false
        private set

    val text: String get() = edit.text.toString()
    val selectedScope: Int get() = scopeIds.indexOf(scopeGroup.checkedRadioButtonId).coerceAtLeast(0)
    val state: State get() = State(text, selectedScope, active)

    init {
        orientation = VERTICAL
        setPadding(context.dp(16), context.dp(8), context.dp(8), 0)

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(box, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        cancel.text = "Cancel"
        cancel.setOnClickListener { cancel() }
        row.addView(cancel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(4)
        })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // The scope buttons: one of them chosen, as the iPhone's segments are
        scopeGroup.orientation = HORIZONTAL
        scopes.forEachIndexed { index, scope ->
            scopeGroup.addView(MaterialRadioButton(context).apply {
                id = scopeIds[index]
                text = scope
            }, RadioGroup.LayoutParams(RadioGroup.LayoutParams.WRAP_CONTENT, RadioGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(12)
            })
        }
        if (scopeIds.isNotEmpty()) scopeGroup.check(scopeIds[0])
        scopeGroup.setOnCheckedChangeListener { _, id -> if (id != View.NO_ID && !quiet) onScopeChanged() }
        scopeBar.isHorizontalScrollBarEnabled = false
        scopeBar.addView(scopeGroup)
        addView(scopeBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        edit.setOnFocusChangeListener { _, focused -> if (focused && !quiet) setActive(true) }
        edit.onKeyboardAction {
            if (text.isNotBlank()) edit.hideKeyboard()
            onSearch()
        }
        setActive(false)
    }

    /** As it was before the view went. */
    fun restore(state: State) {
        quiet = true
        edit.setText(state.text)
        scopeIds.getOrNull(state.scope)?.let { scopeGroup.check(it) }
        quiet = false
        setActive(state.active)
    }

    private fun setActive(on: Boolean) {
        active = on
        cancel.visibility = if (on) VISIBLE else GONE
        scopeBar.visibility = if (on && scopeIds.isNotEmpty()) VISIBLE else GONE
    }

    private fun cancel() {
        quiet = true
        edit.setText("")
        edit.hideKeyboard()
        edit.clearFocus()
        quiet = false
        setActive(false)
        onCancel()
        // Cancel has gone: TalkBack to the field, where the search was
        edit.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }
}
