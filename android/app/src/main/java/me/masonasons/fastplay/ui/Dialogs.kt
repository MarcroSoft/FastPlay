package me.masonasons.fastplay.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The iPhone app's alerts and action sheets, as Android dialogs. */
object Dialogs {
    /** A message with OK. */
    fun tell(context: Context, title: String, message: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /** A question with [action] and Cancel; [run] if the action is chosen. */
    fun confirm(context: Context, title: String, message: String?, action: String, run: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setPositiveButton(action) { _, _ -> run() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One of [options], the one at [selected] checked; [run] with the index chosen. */
    fun choose(context: Context, title: String, options: List<String>, selected: Int, run: (Int) -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                run(which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A list of actions (an action sheet): [run] with the index of the one chosen. */
    fun actions(context: Context, title: String?, message: String? = null, actions: List<String>, run: (Int) -> Unit) {
        MaterialAlertDialogBuilder(context)
            .apply {
                if (title != null) setTitle(title)
                // A message and a list do not show together; the message goes in the title
                if (message != null && title == null) setTitle(message)
            }
            .setItems(actions.toTypedArray()) { _, which -> run(which) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * A box to type into. [run] gets the text when [action] is chosen (and the
     * keyboard's done key does the same).
     */
    fun prompt(
        context: Context, title: String, message: String? = null, text: String = "", hint: String = "",
        action: String = "OK", inputType: Int = InputType.TYPE_CLASS_TEXT, run: (String) -> Unit,
    ) {
        val field = EditText(context).apply {
            setText(text)
            setSelection(text.length)
            this.hint = hint
            this.inputType = inputType
            contentDescription = title
            isSingleLine = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
        }
        val frame = FrameLayout(context).apply {
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(frame)
            .setPositiveButton(action) { _, _ -> run(field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        field.setOnEditorActionListener { _, _, _ ->
            dialog.dismiss()
            run(field.text.toString())
            true
        }
        dialog.setOnShowListener { field.requestFocus() }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }
}
