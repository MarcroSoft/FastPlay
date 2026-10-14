package me.masonasons.fastplay.remote

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.R
import me.masonasons.fastplay.files.BrowserScreen
import me.masonasons.fastplay.ui.Dialogs
import me.masonasons.fastplay.ui.ListRow
import me.masonasons.fastplay.ui.ListScreen
import me.masonasons.fastplay.ui.RowAction
import me.masonasons.fastplay.ui.Screen
import me.masonasons.fastplay.ui.ToolbarItem
import me.masonasons.fastplay.ui.dp
import me.masonasons.fastplay.ui.secondaryText

/**
 * The FTP, SFTP and SMB servers added: choose one to browse it. Edit and Delete are
 * its actions (a long press, and TalkBack's actions).
 */
class ServersScreen : ListScreen() {
    override val title = "Servers"
    private var servers: List<RemoteServer> = emptyList()

    override fun toolbarItems() = listOf(
        ToolbarItem("Add server", R.drawable.ic_add) { edit(RemoteServer(), isNew = true) },
    )

    override fun onResume() {
        servers = ServerStore.all
        super.onResume()
    }

    private fun edit(server: RemoteServer, isNew: Boolean) = push(ServerEditScreen(server, isNew))

    private fun delete(server: RemoteServer) {
        Dialogs.confirm(requireContext(), "Delete ${server.displayName}",
            "FastPlay will forget this server and its password.", "Delete") {
            ServerStore.remove(server)
            servers = ServerStore.all
            reload()
            announce("Deleted ${server.displayName}")
        }
    }

    override fun rows(): List<ListRow> {
        if (servers.isEmpty()) {
            return listOf(ListRow.Footer("No servers yet.\n\nAdd an FTP, SFTP or SMB server with the Add button."))
        }
        return servers.map { server ->
            ListRow.Item(
                title = server.displayName,
                subtitle = "${server.kind.title}, ${server.host}",
                icon = R.drawable.ic_server,
                disclosure = true,
                id = server.id,
                actions = listOf(
                    RowAction("Edit") { edit(server, isNew = false) },
                    RowAction("Delete", destructive = true) { delete(server) },
                ),
                onClick = { push(BrowserScreen(server.makeSource(), server.startPath, server.displayName)) },
            )
        }
    }
}

/**
 * Adding or changing a server: its kind, where it is, and how to sign in. Save
 * keeps it; Back leaves it as it was (the iPhone's Cancel).
 */
class ServerEditScreen() : Screen() {
    private var server = RemoteServer()
    private var isNew = true

    constructor(server: RemoteServer, isNew: Boolean) : this() {
        this.server = server
        this.isNew = isNew
    }

    private enum class Field(val title: String) {
        NAME("Name"),
        HOST("Address"),
        PORT("Port"),
        SHARE("Share"),
        USER("User Name"),
        PASSWORD("Password"),
        FOLDER("Folder");

        fun placeholder(kind: RemoteServer.Kind): String = when (this) {
            NAME -> "What to call it"
            HOST -> "nas.local or 192.168.1.10"
            PORT -> when (kind) {
                RemoteServer.Kind.FTP -> "21"
                RemoteServer.Kind.SFTP -> "22"
                RemoteServer.Kind.SMB -> "445"
            }
            SHARE -> "Leave empty to choose from a list"
            USER -> when (kind) {
                RemoteServer.Kind.FTP -> "Leave empty for anonymous"
                RemoteServer.Kind.SFTP -> "Your user name on it"
                RemoteServer.Kind.SMB -> "Leave empty for guest"
            }
            PASSWORD -> ""
            FOLDER -> "A folder to start in (optional)"
        }
    }

    override val title get() = if (isNew) "Add Server" else "Edit Server"
    override val showsMiniPlayer = false

    private var kind = RemoteServer.Kind.FTP
    private val layouts = mutableMapOf<Field, TextInputLayout>()
    private val fields = mutableMapOf<Field, TextInputEditText>()
    private lateinit var footer: TextView
    private var saving = false

    override fun toolbarItems() = listOf(ToolbarItem("Save") { save() })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        kind = server.kind
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(24))
        }

        // The kind: the iPhone's segmented control, as three choices in a row
        val kindLabel = TextView(context).apply {
            text = "Kind of server"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.secondaryText())
            ViewCompat.setAccessibilityHeading(this, true)
        }
        column.addView(kindLabel)
        val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        for (each in RemoteServer.Kind.entries) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                text = each.title
                tag = each
                minHeight = context.dp(48)
                setPadding(paddingLeft, paddingTop, context.dp(16), paddingBottom)
            }
            group.addView(button)
            if (each == kind) button.isChecked = true
        }
        group.setOnCheckedChangeListener { radios, checkedId ->
            val chosen = radios.findViewById<RadioButton>(checkedId)?.tag as? RemoteServer.Kind ?: return@setOnCheckedChangeListener
            kind = chosen
            update()
        }
        column.addView(group)

        val values = mapOf(
            Field.NAME to server.name, Field.HOST to server.host, Field.PORT to (server.port?.toString() ?: ""),
            Field.SHARE to server.share, Field.USER to server.user,
            Field.PASSWORD to if (isNew) "" else server.password, Field.FOLDER to server.folder,
        )
        for (field in Field.entries) {
            val layout = TextInputLayout(context).apply {
                hint = field.title
                if (field == Field.PASSWORD) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            val text = TextInputEditText(layout.context).apply {
                setText(values[field])
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                inputType = when (field) {
                    Field.PORT -> InputType.TYPE_CLASS_NUMBER
                    Field.HOST -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    Field.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    Field.NAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
            }
            layout.addView(text)
            column.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12) })
            layouts[field] = layout
            fields[field] = text
        }

        footer = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.secondaryText())
            setPadding(0, context.dp(16), 0, 0)
        }
        column.addView(footer)
        update()
        return ScrollView(context).apply {
            isFillViewport = true
            addView(column)
        }
    }

    /** The fields that apply to the kind chosen (a share is an SMB thing), and what they say. */
    private fun update() {
        for ((field, layout) in layouts) {
            layout.visibility = if (field != Field.SHARE || kind == RemoteServer.Kind.SMB) View.VISIBLE else View.GONE
            layout.placeholderText = field.placeholder(kind).ifEmpty { null }
        }
        footer.text = when (kind) {
            RemoteServer.Kind.FTP -> "Plain FTP. The password is kept encrypted on this device."
            RemoteServer.Kind.SFTP -> "Files over SSH. The server's key is remembered the first time FastPlay connects, " +
                "and a server showing another one later is refused. The password is kept encrypted on this device."
            RemoteServer.Kind.SMB -> "A Windows share, a NAS or a Mac's file sharing. The password is kept encrypted " +
                "on this device."
        }
    }

    private fun value(field: Field) = fields[field]?.text?.toString()?.trim() ?: ""

    private fun save() {
        if (saving) return
        var host = value(Field.HOST)
        // An address pasted with its scheme is still an address
        for (scheme in listOf("ftp://", "sftp://", "smb://")) {
            if (host.lowercase().startsWith(scheme)) host = host.substring(scheme.length)
        }
        host = host.trim('/')
        if (host.isEmpty()) return tell("Address Needed", "Enter the server's name or address.")
        val saved = server.copy(
            kind = kind,
            name = value(Field.NAME),
            host = host,
            port = value(Field.PORT).toIntOrNull(),
            share = if (kind == RemoteServer.Kind.SMB) value(Field.SHARE) else "",
            user = value(Field.USER),
            folder = value(Field.FOLDER),
        )
        val password = fields[Field.PASSWORD]?.text?.toString() ?: ""
        saving = true
        viewLifecycleOwner.lifecycleScope.launch {
            // The password goes through the Keystore: not on the main thread
            withContext(Dispatchers.IO) { ServerStore.save(saved, password) }
            pop()
        }
    }
}
