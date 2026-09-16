package io.github.kuddev.pebrel.mobile.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.LocalPtyTransport
import com.termux.terminal.SessionTransport
import com.termux.terminal.TerminalSession
import io.github.kuddev.pebrel.mobile.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class LocalSession(val id: String, val title: String, val source: String, val terminal: TerminalSession, val status: String = "connecting")
data class DesktopWorkspace(val id: String, val host: HostProfile, val panes: List<DesktopPane> = emptyList(), val status: String = "connecting", val allowInput: Boolean = false)
data class TrustRequest(val host: HostProfile, val fingerprint: String, val answer: CompletableFuture<Boolean>)
data class DesktopOutput(val target: String = "", val text: String = "", val loading: Boolean = false)

/** Application owns sessions; activities only attach views. Metadata never updates per cell. */
class SessionRepository(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences("pebrel_mobile", Context.MODE_PRIVATE)
    private val live = MutableStateFlow<List<LocalSession>>(emptyList())
    val sessions = live.asStateFlow()
    private val computers = MutableStateFlow<List<DesktopWorkspace>>(emptyList())
    val desktops = computers.asStateFlow()
    private val savedHosts = MutableStateFlow(loadHosts())
    val hosts = savedHosts.asStateFlow()
    val trust = MutableStateFlow<TrustRequest?>(null)
    val error = MutableStateFlow<String?>(null)
    val backgroundActive = MutableStateFlow(false)
    val theme = MutableStateFlow(preferences.getString("theme", "system") ?: "system")
    fun selectTheme(value: String) {
        theme.value = value
        scope.launch { preferences.edit().putString("theme", theme.value).apply() }
    }
    val output = MutableStateFlow(DesktopOutput())
    private val hostWrites = Mutex()
    private var readJob: Job? = null
    private var readGeneration = 0L
    private val desktopClients = java.util.concurrent.ConcurrentHashMap<String, DesktopRuntimeClient>()
    private var renderOwner: String? = null
    private var redraw: (() -> Unit)? = null
    val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    fun setDraft(id: String, text: String) { drafts.value = drafts.value + (id to text) }
    fun acknowledgeDraft(id: String, sent: String) {
        if (drafts.value[id] == sent) drafts.value = drafts.value - id
    }

    private fun loadHosts(): List<HostProfile> = runCatching {
        val rows = JSONArray(preferences.getString("hosts", "[]"))
        (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            HostProfile(it.getString("id"), it.getString("name"), it.getString("address"), it.getInt("port"), it.getString("user"), it.optString("fingerprint"))
        } }
    }.getOrDefault(emptyList())

    fun saveHost(host: HostProfile) {
        val previous = savedHosts.value.find { it.id == host.id }
        val fingerprint = if (previous?.address == host.address && previous.port == host.port) previous.fingerprint else ""
        savedHosts.value = savedHosts.value.filterNot { it.id == host.id } + host.copy(fingerprint = fingerprint)
        persistHosts()
    }
    private fun persistHosts() {
        scope.launch {
            hostWrites.withLock {
                // Read the current authority inside the serialized writer. A late
                // launch must never persist an older host list over a newer one.
                val rows = JSONArray()
                savedHosts.value.forEach { h -> rows.put(JSONObject().put("id", h.id).put("name", h.name).put("address", h.address).put("port", h.port).put("user", h.user).put("fingerprint", h.fingerprint)) }
                if (!preferences.edit().putString("hosts", rows.toString()).commit()) error.value = "host_save_failed"
            }
        }
    }
    private fun verify(host: HostProfile, fingerprint: String): Boolean {
        val answer = CompletableFuture<Boolean>()
        main.post {
            if (trust.value != null) answer.complete(false)
            else trust.value = TrustRequest(host, fingerprint, answer)
        }
        val accepted = runCatching { answer.get(60, TimeUnit.SECONDS) }.getOrDefault(false)
        main.post {
            if (trust.value?.answer === answer) trust.value = null
            if (accepted) {
                savedHosts.value = savedHosts.value.map { if (it.id == host.id && it.address == host.address && it.port == host.port) it.copy(fingerprint = fingerprint) else it }
                persistHosts()
            }
        }
        return accepted
    }
    fun answerTrust(accept: Boolean) { trust.value?.answer?.complete(accept); trust.value = null }
    fun attachRenderer(id: String?, callback: (() -> Unit)?) { renderOwner = id; redraw = callback }
    fun local(): String = addTerminal("Term", "Local", LocalPtyTransport(context.filesDir.absolutePath))
    fun ssh(host: HostProfile, password: CharArray): String = addTerminal(host.name, "SSH", SshTerminalTransport(SshConnection(host, password, ::verify)))
    private fun addTerminal(title: String, source: String, transport: SessionTransport): String {
        val id = UUID.randomUUID().toString()
        val callbacks = object : TerminalCallbacks() {
            override fun onTextChanged(session: TerminalSession) { if (renderOwner == id) redraw?.invoke() }
            override fun onTitleChanged(session: TerminalSession) { update(id) { it.copy(title = session.title?.take(80) ?: it.title) } }
            override fun onTransportReady(session: TerminalSession) { update(id) { it.copy(status = "ready") } }
            override fun onSessionFinished(session: TerminalSession) {
                update(id) { it.copy(status = if (session.failure == null) "ended" else "failed") }
                stopIdleService()
            }
            override fun onInputRejected(session: TerminalSession) { error.value = "input_rejected" }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                if (renderOwner != id) return
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Pebrel", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                if (renderOwner != id) return
                val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                if (clip != null && clip.itemCount > 0) {
                    session?.emulator?.paste(clip.getItemAt(0).coerceToText(context).toString())
                }
            }
            override fun onColorsChanged(session: TerminalSession) { if (renderOwner == id) redraw?.invoke() }
        }
        val terminal = TerminalSession(transport, 2000, callbacks)
        live.value = live.value + LocalSession(id, title, source, terminal)
        return id
    }
    private fun update(id: String, change: (LocalSession) -> LocalSession) { live.value = live.value.map { if (it.id == id) change(it) else it } }
    fun closeTerminal(id: String) {
        live.value.find { it.id == id }?.terminal?.finishIfRunning()
        live.value = live.value.filterNot { it.id == id }
        drafts.value = drafts.value - id
        stopIdleService()
    }
    fun connectDesktop(host: HostProfile, password: CharArray, allowInput: Boolean): String {
        val id = UUID.randomUUID().toString()
        computers.value = computers.value + DesktopWorkspace(id, host, allowInput = allowInput)
        val transitions = DesktopTransitions()
        val client = DesktopRuntimeClient(SshConnection(host, password, ::verify), { snapshot ->
            val panes = parseDesktopPanes(snapshot)
            val events = transitions.observe(snapshot)
            main.post {
                computers.value = computers.value.map { if (it.id == id) it.copy(panes = panes, status = "ready") else it }
                if (computers.value.any { it.id == id }) events.forEach { SessionNotices.task(context, id, host.name, it) }
            }
        }, { main.post {
            desktopClients.remove(id)
            computers.value = computers.value.map { if (it.id == id) it.copy(status = "disconnected") else it }
            stopIdleService()
        } })
        desktopClients[id] = client
        scope.launch {
            try { client.connect(allowInput) }
            catch (_: Exception) {
                desktopClients.remove(id, client)
                client.close()
                main.post {
                    if (computers.value.any { it.id == id }) {
                        computers.value = computers.value.map { if (it.id == id) it.copy(status = "failed") else it }
                        error.value = "desktop_connection_failed"
                    }
                    stopIdleService()
                }
            }
        }
        return id
    }
    fun readDesktop(id: String, pane: DesktopPane) {
        readJob?.cancel()
        val generation = ++readGeneration
        val identity = "$id:${pane.window}:${pane.id}"
        output.value = DesktopOutput(identity, loading = true)
        readJob = scope.launch {
            val result = runCatching { checkNotNull(desktopClients[id]).request("pane.read", target(pane).put("lines", 120)) }
            if (!isActive) return@launch
            main.post {
                if (generation == readGeneration && output.value.target == identity) {
                    output.value = DesktopOutput(identity, result.getOrNull()?.optString("text") ?: "")
                    if (result.isFailure) error.value = "desktop_read_failed"
                }
            }
        }
    }
    fun leaveDesktopPane() {
        readGeneration++
        readJob?.cancel()
        output.value = DesktopOutput()
    }
    suspend fun sendDesktop(id: String, pane: DesktopPane, text: String): Boolean {
        if (computers.value.none { it.id == id && it.allowInput && it.status == "ready" }) return false
        return try {
            checkNotNull(desktopClients[id]).request("pane.prompt", target(pane).put("text", text).put("submit", true))
            if (output.value.target == "$id:${pane.window}:${pane.id}") readDesktop(id, pane)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error.value = "delivery_unknown"
            false
        }
    }
    private fun target(pane: DesktopPane) = JSONObject().put("window_id", pane.window).put("pane_id", pane.id)
    fun closeAll() {
        leaveDesktopPane()
        trust.value?.answer?.complete(false); trust.value = null
        live.value.forEach { it.terminal.finishIfRunning() }; live.value = emptyList()
        val clients = desktopClients.values.toList(); desktopClients.clear(); computers.value = emptyList()
        drafts.value = emptyMap()
        scope.launch { clients.forEach { it.close() } }
        stopIdleService()
    }
    fun closeDesktop(id: String) {
        leaveDesktopPane()
        val client = desktopClients.remove(id)
        computers.value = computers.value.filterNot { it.id == id }
        drafts.value = drafts.value.filterKeys { !it.startsWith("$id:") }
        scope.launch { client?.close() }
        stopIdleService()
    }
    private fun stopIdleService() {
        if (live.value.none { it.status in setOf("ready", "connecting") } &&
            computers.value.none { it.status in setOf("ready", "connecting") }) {
            context.stopService(android.content.Intent(context, SessionService::class.java))
        }
    }
}
