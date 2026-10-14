package me.masonasons.fastplay.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import me.masonasons.fastplay.R

/** Something a row offers besides being chosen: on a long press, and in TalkBack's actions. */
data class RowAction(val title: String, val destructive: Boolean = false, val run: () -> Unit)

/**
 * A row of a list screen, as the iPhone's table cells are: a section [Header] or
 * [Footer], or an [Item].
 */
sealed class ListRow {
    abstract val key: String

    data class Header(val title: String) : ListRow() {
        override val key get() = "header:$title"
    }

    data class Footer(val text: String) : ListRow() {
        override val key get() = "footer:$text"
    }

    /**
     * A row: [title], with a [subtitle] under it or a [value] beside it, an
     * [icon], a checkmark ([checked]), an arrow to another screen ([disclosure])
     * or a switch ([toggle], which [onToggle] changes). [description] replaces
     * what TalkBack reads; [selected] marks it chosen in a selection.
     */
    class Item(
        val title: String,
        val subtitle: String? = null,
        val value: String? = null,
        @DrawableRes val icon: Int? = null,
        val checked: Boolean = false,
        val disclosure: Boolean = false,
        val toggle: Boolean? = null,
        val onToggle: ((Boolean) -> Unit)? = null,
        val enabled: Boolean = true,
        val selected: Boolean = false,
        val description: String? = null,
        val hint: String? = null,
        val actions: List<RowAction> = emptyList(),
        val id: String? = null,
        val onClick: (() -> Unit)? = null,
    ) : ListRow() {
        override val key get() = "item:" + (id ?: "$title|$subtitle")
        /** What the screen shows and says, to tell whether a row changed. */
        val contents get() = listOf(title, subtitle, value, icon, checked, disclosure, toggle, enabled, selected,
            description, hint, actions.map { it.title }).toString()
    }
}

/**
 * A screen that is a list: [rows] says what is in it, and [reload] shows it again.
 * Rows keep their place (and TalkBack's focus) across a reload when their keys do.
 */
abstract class ListScreen : Screen() {
    lateinit var list: RecyclerView
        private set
    private val adapter = RowAdapter()

    abstract fun rows(): List<ListRow>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        list = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ListScreen.adapter
            itemAnimator = null  // nothing slides about on a reload
            clipToPadding = false
            setPadding(0, 0, 0, context.dp(8))
        }
        return list
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Shows [rows] again. */
    fun reload() {
        if (view != null) adapter.submitList(rows())
    }

    /** The row with [key] brought into view. */
    fun scrollTo(key: String) {
        val index = adapter.currentList.indexOfFirst { it.key == key }
        if (index >= 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, list.height / 3)
    }
}

private class RowAdapter : ListAdapter<ListRow, RowAdapter.Holder>(object : DiffUtil.ItemCallback<ListRow>() {
    override fun areItemsTheSame(a: ListRow, b: ListRow) = a.key == b.key
    override fun areContentsTheSame(a: ListRow, b: ListRow) = when {
        a is ListRow.Item && b is ListRow.Item -> a.contents == b.contents
        else -> a == b
    }
}) {
    class Holder(val view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ListRow.Header -> 0
        is ListRow.Footer -> 1
        is ListRow.Item -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val view = when (viewType) {
            0 -> TextView(context).apply {
                setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(6))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.secondaryText())
                ViewCompat.setAccessibilityHeading(this, true)
            }
            1 -> TextView(context).apply {
                setPadding(context.dp(20), context.dp(6), context.dp(20), context.dp(12))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(context.secondaryText())
            }
            else -> ItemView(context)
        }
        view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val row = getItem(position)) {
            is ListRow.Header -> (holder.view as TextView).apply {
                // An empty header is a gap between sections, nothing to read
                text = row.title
                ViewCompat.setAccessibilityHeading(this, row.title.isNotEmpty())
                importantForAccessibility = if (row.title.isEmpty()) View.IMPORTANT_FOR_ACCESSIBILITY_NO
                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
            is ListRow.Footer -> (holder.view as TextView).text = row.text
            is ListRow.Item -> (holder.view as ItemView).bind(row)
        }
    }
}

/** An item row: icon, title and subtitle, then a value, a switch, a checkmark or an arrow. */
private class ItemView(context: Context) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private val valueView = TextView(context)
    private val switch = MaterialSwitch(context)
    private val accessory = ImageView(context)
    private var row: ListRow.Item? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(56)
        setPadding(context.dp(20), context.dp(8), context.dp(12), context.dp(8))
        isClickable = true
        isFocusable = true
        background = context.selectableBackground()

        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon, LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(16) })

        val text = LinearLayout(context).apply { orientation = VERTICAL }
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        subtitleView.setTextColor(context.secondaryText())
        text.addView(titleView)
        text.addView(subtitleView)
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        valueView.setTextColor(context.secondaryText())
        valueView.maxLines = 2
        valueView.ellipsize = TextUtils.TruncateAt.END
        valueView.gravity = Gravity.END
        addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(12)
        })
        // The row is the switch to TalkBack; the switch itself is not focused apart
        switch.isClickable = false
        switch.isFocusable = false
        switch.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(switch)
        accessory.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(accessory, LayoutParams(context.dp(24), context.dp(24)).apply { marginStart = context.dp(8) })

        setOnClickListener {
            val row = row ?: return@setOnClickListener
            if (row.toggle != null) {
                val now = !switch.isChecked
                switch.isChecked = now
                row.onToggle?.invoke(now)
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            } else {
                row.onClick?.invoke()
            }
        }
        setOnLongClickListener {
            val row = row ?: return@setOnLongClickListener false
            if (row.actions.isEmpty()) return@setOnLongClickListener false
            val menu = PopupMenu(context, this, Gravity.END)
            row.actions.forEachIndexed { index, action -> menu.menu.add(0, index, index, action.title) }
            menu.setOnMenuItemClickListener { item ->
                row.actions[item.itemId].run()
                true
            }
            menu.show()
            true
        }
    }

    fun bind(row: ListRow.Item) {
        this.row = row
        isEnabled = row.enabled
        alpha = if (row.enabled) 1f else 0.5f
        isActivated = row.selected
        setBackgroundColor(0)
        background = if (row.selected) context.selectedBackground() else context.selectableBackground()

        icon.visibility = if (row.icon != null) VISIBLE else GONE
        row.icon?.let { icon.setImageResource(it) }
        titleView.text = row.title
        subtitleView.text = row.subtitle
        subtitleView.visibility = if (row.subtitle.isNullOrEmpty()) GONE else VISIBLE
        valueView.text = row.value
        valueView.visibility = if (row.value.isNullOrEmpty()) GONE else VISIBLE
        switch.visibility = if (row.toggle != null) VISIBLE else GONE
        switch.isChecked = row.toggle == true
        val accessoryIcon = when {
            row.checked -> R.drawable.ic_check
            row.disclosure -> R.drawable.ic_chevron_right
            else -> null
        }
        accessory.visibility = if (accessoryIcon != null) VISIBLE else GONE
        accessoryIcon?.let { accessory.setImageResource(it) }

        // What TalkBack says: the title, then the subtitle or value; a switch says
        // its state itself, as do selection and the checkmark
        contentDescription = row.description ?: listOfNotNull(row.title, row.subtitle?.ifEmpty { null },
            row.value?.ifEmpty { null }).joinToString(", ")
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val current = this@ItemView.row ?: return
                if (current.toggle != null) {
                    info.className = android.widget.Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = switch.isChecked
                } else {
                    info.className = android.widget.Button::class.java.name
                    // (A row that says its own state, "playing", needs no "checked")
                    if (current.checked && current.description == null) info.stateDescription = "checked"
                }
                if (current.selected) info.isSelected = true
                if (current.hint != null) info.hintText = current.hint
                // Long press offers the actions; TalkBack lists them by name
                if (current.actions.isNotEmpty()) {
                    info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK)
                }
            }
        })
        // The actions by name in TalkBack's actions menu
        for (id in actionIds) ViewCompat.removeAccessibilityAction(this, id)
        actionIds = row.actions.map { action ->
            ViewCompat.addAccessibilityAction(this, action.title) { _, _ ->
                action.run()
                true
            }
        }
        isLongClickable = row.actions.isNotEmpty()
    }

    private var actionIds: List<Int> = emptyList()
}

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

fun Context.secondaryText(): Int {
    val value = TypedValue()
    theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)
    return if (value.resourceId != 0) getColor(value.resourceId) else value.data
}

fun Context.selectableBackground(): android.graphics.drawable.Drawable? {
    val value = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
    return getDrawable(value.resourceId)
}

fun Context.selectedBackground(): android.graphics.drawable.Drawable {
    val value = TypedValue()
    theme.resolveAttribute(com.google.android.material.R.attr.colorSecondaryContainer, value, true)
    return android.graphics.drawable.ColorDrawable(value.data)
}
