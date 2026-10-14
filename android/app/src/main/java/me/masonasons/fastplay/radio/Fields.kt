package me.masonasons.fastplay.radio

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.masonasons.fastplay.ui.dp
import me.masonasons.fastplay.ui.secondaryText

// What the radio, podcast and Open Address screens have in common: their text
// fields, a dialog of fields, and the line that says why a list is empty.

/** An address typed in: no capitals, no corrections, the keyboard's address keys. */
internal const val ADDRESS_INPUT =
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

/**
 * A text field labelled [label] (what TalkBack calls it, as the iPhone's
 * accessibilityLabel), showing [placeholder] while empty, with a button to clear
 * it if [clearButton].
 */
internal fun textField(
    context: Context, label: String, placeholder: String? = null, inputType: Int = InputType.TYPE_CLASS_TEXT,
    imeAction: Int = EditorInfo.IME_ACTION_DONE, clearButton: Boolean = false,
): TextInputLayout {
    val layout = TextInputLayout(context)
    val field = TextInputEditText(layout.context)
    field.inputType = inputType
    field.imeOptions = imeAction
    layout.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    layout.hint = label
    if (placeholder != null) layout.placeholderText = placeholder
    if (clearButton) layout.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
    return layout
}

/** What is typed in a field, as it is. */
internal val TextInputLayout.value: String get() = editText?.text?.toString().orEmpty()

/**
 * Calls [run] when the keyboard's action key is pressed in the field (once: a
 * hardware Enter sends a key down and a key up).
 */
internal fun TextView.onKeyboardAction(run: () -> Unit) = setOnEditorActionListener { _, _, event ->
    if (event == null || event.action == KeyEvent.ACTION_DOWN) run()
    true
}

/**
 * An alert with text fields, as the iPhone's alerts with text fields: Cancel and
 * [action], which [run] does (and the last field's action key does the same).
 */
internal fun fieldsDialog(
    context: Context, title: String, message: String?, fields: List<TextInputLayout>, action: String, run: () -> Unit,
) {
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20), context.dp(4), context.dp(20), 0)
    }
    for (field in fields) {
        column.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = context.dp(8) })
    }
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .apply { if (message != null) setMessage(message) }
        .setView(column)
        .setPositiveButton(action) { _, _ -> run() }
        .setNegativeButton("Cancel", null)
        .create()
    fields.last().editText?.onKeyboardAction {
        if (dialog.isShowing) {
            dialog.dismiss()
            run()
        }
    }
    dialog.setOnShowListener { fields.first().editText?.requestFocus() }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
}

/** The line in the middle of an empty list that says why it is empty ("No stations found."). */
internal fun statusLabel(context: Context) = TextView(context).apply {
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    setTextColor(context.secondaryText())
    setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
    visibility = View.GONE
}

/** A screen's view: [top] (a search field), then the list with [status] over it while it is empty. */
internal fun screenLayout(context: Context, top: View, list: View, status: TextView): View =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val frame = FrameLayout(context)
        frame.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

/** Shows [text] in the label, or hides it with none. */
internal fun TextView.showStatus(text: String?) {
    this.text = text
    visibility = if (text == null) View.GONE else View.VISIBLE
}

internal fun View.hideKeyboard() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
}

internal fun View.showKeyboard() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}
