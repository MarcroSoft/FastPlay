package me.masonasons.fastplay.files

import android.content.Context
import android.graphics.Typeface
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.masonasons.fastplay.ui.dp
import me.masonasons.fastplay.ui.secondaryText
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume

/**
 * Asked while pasting when a name is already taken: what is being pasted against
 * what is there, the choices, and whether to do the same for the rest. (The
 * iPhone's is a screen of its own over the browser; here it is a dialog, so the
 * paste behind it goes on.)
 */
object ConflictDialog {
    /** Asks. Null: stop pasting. */
    suspend fun ask(context: Context, pasting: FileEntry, existing: FileEntry): FileOperations.Choice? =
        suspendCancellableCoroutine { continuation ->
            val choices = if (pasting.isFolder) FileOperations.Resolution.forFolders
            else FileOperations.Resolution.forFiles
            var answered = false
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
            }
            fun heading(text: String) = TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.secondaryText())
                setPadding(0, context.dp(12), 0, context.dp(4))
                ViewCompat.setAccessibilityHeading(this, true)
            }
            fun line(text: String) = TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, context.dp(6), 0, context.dp(6))
            }

            content.addView(heading(pasting.name))
            if (pasting.isFolder) {
                content.addView(line("A folder of that name is already there"))
            } else {
                content.addView(line(listOf("Being pasted", describe(context, pasting)).filter { it.isNotEmpty() }
                    .joinToString(": ")))
                content.addView(line(listOf("Already there", describe(context, existing)).filter { it.isNotEmpty() }
                    .joinToString(": ")))
            }
            val applySwitch = MaterialSwitch(context).apply {
                text = "Do the Same for the Rest"
                contentDescription = "Do the same for the rest"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minHeight = context.dp(48)
            }
            content.addView(applySwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(heading("What to Do"))

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(if (pasting.isFolder) "Folder Already There" else "File Already There")
                .setView(ScrollView(context).apply { addView(content) })
                .setNegativeButton("Stop Pasting", null)
                .setCancelable(false)  // answered, not dismissed by accident
                .create()
            fun finish(choice: FileOperations.Choice?) {
                if (answered) return
                answered = true
                dialog.dismiss()
                if (continuation.isActive) continuation.resume(choice)
            }
            for (resolution in choices) {
                content.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = resolution.title
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { finish(FileOperations.Choice(resolution, applySwitch.isChecked)) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish(null) }
                content.announceForAccessibility("${pasting.name} is already there")
            }
            continuation.invokeOnCancellation {
                answered = true
                dialog.dismiss()
            }
            dialog.show()
        }

    private fun describe(context: Context, entry: FileEntry): String {
        val parts = mutableListOf<String>()
        if (!entry.isFolder) parts.add(Formatter.formatFileSize(context, entry.size))
        entry.modified?.let {
            parts.add(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)))
        }
        return parts.joinToString(", ")
    }
}
