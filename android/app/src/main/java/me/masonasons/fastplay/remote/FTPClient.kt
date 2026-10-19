package me.masonasons.fastplay.remote

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.masonasons.fastplay.files.RemoteError
import me.masonasons.fastplay.files.TransferMeter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A plain FTP control connection, for what FFmpeg cannot do on a server: make
 * folders, rename, delete and upload. (Listing, playing and downloading stay with
 * FFmpeg, in FTPSource.) Opened for a piece of work and closed after it. Its calls
 * block on the network: FTPSource makes them on Dispatchers.IO.
 */
class FTPClient(private val host: String, port: Int?) : Closeable {
    private val port = port ?: 21
    private var control: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    class Reply(val code: Int, val text: String)

    // ---- Signing in ----

    fun open(user: String, password: String) {
        val socket = connect(port)
        control = socket
        input = BufferedInputStream(socket.getInputStream())
        output = socket.getOutputStream()
        check(reply(), 200..299, "The server did not greet FastPlay.")
        val name = user.ifEmpty { "anonymous" }
        var answer = command("USER $name")
        if (answer.code == 331) {
            answer = command("PASS ${if (user.isEmpty()) "fastplay@" else password}")
        }
        check(answer, 200..299, "The server did not accept the user name and password.")
        check(command("TYPE I"), 200..299, "The server would not send files as they are.")
    }

    override fun close() {
        val socket = control ?: return
        try {
            output?.write("QUIT\r\n".toByteArray())
            output?.flush()
        } catch (e: IOException) {
            // Going anyway
        }
        try {
            socket.close()
        } catch (e: IOException) {
            // Gone already
        }
        control = null
    }

    // ---- Commands ----

    fun makeFolder(path: String) = check(command("MKD $path"), 200..299, "The folder could not be made.")

    fun rename(path: String, newPath: String) {
        check(command("RNFR $path"), 300..399, "It is not there to rename.")
        check(command("RNTO $newPath"), 200..299, "It could not be renamed.")
    }

    fun deleteFile(path: String) = check(command("DELE $path"), 200..299, "It could not be deleted.")

    fun deleteEmptyFolder(path: String) = check(command("RMD $path"), 200..299, "The folder could not be deleted.")

    /** Sends a file on this device to `path`, replacing one there. */
    suspend fun upload(file: File, path: String) {
        val data = passiveConnection()
        try {
            val started = command("STOR $path")
            check(started, 100..199, "The server would not take the file.")
            val meter = TransferMeter.current()
            file.inputStream().use { source ->
                val out = data.getOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(chunk)
                    if (count < 0) break
                    out.write(chunk, 0, count)
                    meter?.add(count)
                }
                out.flush()
            }
            // The end of the file is the end of the connection
            data.shutdownOutput()
            data.close()
            check(reply(), 200..299, "The file did not arrive whole.")
        } finally {
            try {
                data.close()
            } catch (e: IOException) {
                // Closed already
            }
        }
    }

    /**
     * The server's data connection for one transfer: EPSV, else PASV. Either way to
     * the address the control connection is to, which is the one known to work (a
     * server behind a router gives its inside address in PASV).
     */
    private fun passiveConnection(): Socket {
        var dataPort: Int? = null
        val extended = command("EPSV")
        val open = extended.text.indexOf('(')
        val shut = extended.text.lastIndexOf(')')
        if (extended.code == 229 && open >= 0 && shut > open) {
            // "(|||6446|)"
            dataPort = extended.text.substring(open + 1, shut).split('|').lastOrNull { it.isNotEmpty() }?.toIntOrNull()
        }
        if (dataPort == null) {
            val passive = command("PASV")
            check(passive, 227..227, "The server would not open a connection for the file.")
            // "(h1,h2,h3,h4,p1,p2)"
            val start = passive.text.indexOf('(')
            val end = passive.text.lastIndexOf(')')
            if (start >= 0 && end > start) {
                val numbers = passive.text.substring(start + 1, end).split(',').mapNotNull { it.trim().toIntOrNull() }
                if (numbers.size == 6) dataPort = numbers[4] * 256 + numbers[5]
            }
        }
        if (dataPort == null || dataPort !in 1..65535) {
            throw RemoteError("The server did not say where to send the file.")
        }
        return connect(dataPort)
    }

    private fun connect(port: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), TIMEOUT)
            socket.soTimeout = TIMEOUT
        } catch (e: IOException) {
            socket.close()
            throw RemoteError("Could not reach the server. ${e.localizedMessage ?: ""}".trim())
        }
        return socket
    }

    // ---- The conversation ----

    private fun check(reply: Reply, wanted: IntRange, problem: String) {
        if (reply.code !in wanted) throw RemoteError("$problem (${reply.code} ${reply.text})")
    }

    private fun command(line: String): Reply {
        val out = output ?: throw RemoteError("Not connected to the server.")
        out.write("$line\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
        return reply()
    }

    /** The next reply, all its lines ("250-..." up to "250 ..."). */
    private fun reply(): Reply {
        var code: Int? = null
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine()
            val digits = line.take(3).toIntOrNull()
            val separator = if (line.length > 3) line[3] else ' '
            if (code == null) {
                if (digits == null) continue
                code = digits
            }
            lines.add(if (line.length > 4) line.substring(4) else "")
            if (digits == code && separator == ' ') break
        }
        return Reply(code ?: 0, lines.lastOrNull() ?: "")
    }

    private fun readLine(): String {
        val stream = input ?: throw RemoteError("Not connected to the server.")
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = stream.read()
            if (byte < 0) throw RemoteError("The server closed the connection.")
            if (byte == '\n'.code) break
            line.write(byte)
        }
        val text = line.toString(Charsets.UTF_8.name())
        return text.removeSuffix("\r")
    }

    private companion object {
        /** How long a server may say nothing, in milliseconds (as FFmpeg is told for FTP). */
        const val TIMEOUT = 20_000
    }
}
