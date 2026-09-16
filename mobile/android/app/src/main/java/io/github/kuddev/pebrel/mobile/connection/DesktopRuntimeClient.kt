package io.github.kuddev.pebrel.mobile.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.connection.channel.direct.Session
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One exec channel, one reader, bounded pending requests. No command replay on reconnect. */
class DesktopRuntimeClient(
    private val connection: SshConnection,
    private val onSnapshot: (JSONObject) -> Unit,
    private val onDisconnected: () -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val writer = Mutex()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val ready = CompletableDeferred<JSONObject>()
    private var session: Session? = null
    private var command: Session.Command? = null
    private var reader: Job? = null
    @Volatile private var closed = false

    suspend fun connect(allowInput: Boolean): JSONObject = withContext(Dispatchers.IO) {
        connection.connect()
        check(!closed)
        val exec = connection.client.startSession()
        session = exec
        // Fixed command only. Paths, prompts and host metadata are never shell-interpolated.
        command = exec.exec(if (allowInput) "pebrel mobile-bridge --allow-input" else "pebrel mobile-bridge")
        if (closed) { close(); error("connection_closed") }
        scope.launch {
            // Drain diagnostics without retaining terminal text or credentials.
            // An unread SSH stderr window must not stall the protocol channel.
            runCatching {
                val buffer = ByteArray(4096)
                val diagnostics = checkNotNull(command).errorStream
                while (!closed && diagnostics.read(buffer) >= 0) { }
            }
        }
        reader = scope.launch {
            try {
                BufferedInputStream(checkNotNull(command).inputStream, 8192).use { input ->
                    while (!closed) {
                        val frame = readBoundedFrame(input, 2 * 1024 * 1024) ?: break
                        val message = JSONObject(frame.toString(Charsets.UTF_8))
                        when {
                            message.optString("type") == "mobile.ready" -> ready.complete(message)
                            message.optString("type") == "mobile.disconnected" -> break
                            message.optString("event") == "runtime.snapshot" -> onSnapshot(message.getJSONObject("data"))
                            message.has("id") -> pending.remove(message.getString("id"))?.complete(message)
                        }
                    }
                }
            } finally {
                ready.completeExceptionally(IllegalStateException("desktop_disconnected"))
                pending.values.forEach { it.completeExceptionally(IllegalStateException("delivery_unknown")) }
                pending.clear()
                if (!closed) onDisconnected()
                close()
            }
        }
        val hello = withTimeout(20_000) { ready.await() }
        check(hello.optString("protocol") == "pebrel.mobile.ssh" && hello.optInt("version") == 1)
        request("events.subscribe")
        hello
    }

    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = sequence.incrementAndGet().toString()
        val completion = CompletableDeferred<JSONObject>()
        val bytes = (JSONObject().put("id", id).put("method", method).put("params", params).toString() + "\n").toByteArray()
        require(bytes.size <= 40 * 1024)
        try {
            withContext(Dispatchers.IO) {
                writer.withLock {
                    check(!closed && pending.size < 16)
                    pending[id] = completion
                    val output = checkNotNull(command).outputStream
                    output.write(bytes)
                    output.flush()
                }
            }
            val response = withTimeout(35_000) { completion.await() }
            if (!response.optBoolean("ok")) error(response.optJSONObject("error")?.optString("code") ?: "runtime_error")
            return response.optJSONObject("result") ?: JSONObject()
        } finally { pending.remove(id) }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        // Closing SSH channels can block; callers must invoke this on IO.
        runCatching { command?.close() }
        runCatching { session?.close() }
        connection.close()
    }
}

internal fun readBoundedFrame(input: java.io.InputStream, limit: Int): ByteArray? {
    val bytes = java.io.ByteArrayOutputStream(minOf(4096, limit))
    while (true) {
        val value = input.read()
        if (value < 0) {
            if (bytes.size() == 0) return null
            throw java.io.EOFException("incomplete_frame")
        }
        if (bytes.size() >= limit) throw java.io.IOException("frame_too_large")
        bytes.write(value)
        if (value == 10) return bytes.toByteArray()
    }
}
