package io.github.kuddev.pebrel.mobile.connection

import com.termux.terminal.SessionTransport
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

data class HostProfile(
    val id: String, val name: String, val address: String, val port: Int = 22,
    val user: String, val fingerprint: String = "",
)

/** First trust is an explicit UI decision; an existing identity never changes silently. */
fun keyFingerprint(key: PublicKey): String {
    val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(encoded),
    )
}

class SshConnection(
    private val host: HostProfile,
    private val password: CharArray,
    private val verify: (HostProfile, String) -> Boolean,
) : Closeable {
    val client = SSHClient()
    @Volatile private var closed = false

    fun connect() {
        check(!closed)
        client.connectTimeout = 15_000
        client.timeout = 90_000
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fingerprint = keyFingerprint(key)
                return if (host.fingerprint.isNotEmpty()) host.fingerprint == fingerprint
                    else this@SshConnection.verify(host, fingerprint)
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            client.connect(host.address, host.port)
            check(!closed)
            client.authPassword(host.user, password)
            check(!closed)
            client.connection.keepAlive.keepAliveInterval = 30
        } finally { password.fill('\u0000') }
    }

    override fun close() {
        closed = true
        password.fill('\u0000')
        runCatching { client.close() }
    }
}

/** Termux owns VT/IME/selection. SSHJ only owns the encrypted byte transport. */
class SshTerminalTransport(private val connection: SshConnection) : SessionTransport {
    @Volatile private var shell: Session.Shell? = null
    @Volatile private var session: Session? = null
    @Volatile private var closed = false

    override fun open(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        check(!closed)
        connection.connect()
        val channel = connection.client.startSession()
        session = channel
        if (closed) { channel.close(); return }
        channel.allocatePTY("xterm-256color", columns, rows, columns * cellWidth, rows * cellHeight, emptyMap())
        shell = channel.startShell()
        if (closed) close()
    }
    override fun input(): InputStream = checkNotNull(shell).inputStream
    override fun output(): OutputStream = checkNotNull(shell).outputStream
    override fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        shell?.changeWindowDimensions(columns, rows, columns * cellWidth, rows * cellHeight)
    }
    override fun awaitExit(): Int {
        val running = checkNotNull(shell)
        running.join()
        // SSHJ's interactive Shell interface has no exit-status accessor.
        return -1
    }
    override fun close() {
        closed = true
        runCatching { shell?.close() }
        runCatching { session?.close() }
        connection.close()
    }
}
