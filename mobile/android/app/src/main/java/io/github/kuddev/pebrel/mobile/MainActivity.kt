package io.github.kuddev.pebrel.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kuddev.pebrel.mobile.connection.*
import io.github.kuddev.pebrel.mobile.session.*
import io.github.kuddev.pebrel.mobile.ui.*
import java.util.UUID
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

class MainActivity : ComponentActivity() {
    private val launchTarget = mutableStateOf<Intent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchTarget.value = intent
        setContent { PebrelTheme { Workspace((application as PebrelApplication).sessions, launchTarget.value) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); launchTarget.value = intent }

    @Composable
    private fun Workspace(repository: SessionRepository, target: Intent?) {
        val sessions by repository.sessions.collectAsStateWithLifecycle()
        val desktops by repository.desktops.collectAsStateWithLifecycle()
        val hosts by repository.hosts.collectAsStateWithLifecycle()
        val trust by repository.trust.collectAsStateWithLifecycle()
        val error by repository.error.collectAsStateWithLifecycle()
        var selected by rememberSaveable { mutableStateOf("") }
        var desktopId by rememberSaveable { mutableStateOf("") }
        var pane by remember { mutableStateOf<DesktopPane?>(null) }
        var addHost by remember { mutableStateOf(false) }
        var login by remember { mutableStateOf<HostProfile?>(null) }
        var settings by remember { mutableStateOf(false) }
        fun back() {
            repository.leaveDesktopPane()
            when {
                settings -> settings = false
                pane != null -> pane = null
                desktopId.isNotEmpty() -> desktopId = ""
                else -> selected = ""
            }
        }
        BackHandler(selected.isNotEmpty() || desktopId.isNotEmpty() || settings) { back() }
        LaunchedEffect(target) {
            if (target?.action == "OPEN_TASK") {
                desktopId = target.getStringExtra("desktop") ?: ""
                pane = desktops.find { it.id == desktopId }?.panes?.find { it.window == target.getLongExtra("window", -1) && it.id == target.getLongExtra("pane", -1) }
                pane?.let { repository.readDesktop(desktopId, it) }
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selected.isNotEmpty() || desktopId.isNotEmpty() || settings) TextButton(onClick = { back() }) { Text(stringResource(R.string.back)) }
                    Text("Pebrel", fontSize = 21.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { settings = true }) { Text(stringResource(R.string.settings)) }
                }
                when {
                    settings -> Settings(repository)
                    selected.isNotEmpty() -> sessions.find { it.id == selected }?.let { session ->
                        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(session.title, modifier = Modifier.weight(1f), maxLines = 1)
                            TextButton(onClick = { repository.closeTerminal(session.id); selected = "" }) { Text(stringResource(R.string.close_session)) }
                        }
                        TerminalSurface(session, repository, Modifier.weight(1f).fillMaxWidth())
                        InputBar(session.id, repository, session.status == "ready") { command ->
                            val bytes = (command + "\r").toByteArray()
                            session.terminal.tryWrite(bytes, 0, bytes.size)
                        }
                    }
                    desktopId.isNotEmpty() -> {
                        val desktop = desktops.find { it.id == desktopId }
                        if (pane == null) {
                            Text(desktop?.host?.name ?: "", modifier = Modifier.padding(20.dp))
                            LazyColumn { items(desktop?.panes ?: emptyList(), key = { "${it.window}:${it.id}" }) { entry ->
                                ListItem(headlineContent = { Text(entry.title) }, supportingContent = { Text(entry.task.ifBlank { entry.cwd }) }, trailingContent = { Text(entry.state, fontSize = 11.sp) }, modifier = Modifier.clickable { pane = entry; repository.readDesktop(desktopId, entry) })
                                HorizontalDivider()
                            } }
                        } else {
                            val active = checkNotNull(pane)
                            val output by repository.output.collectAsStateWithLifecycle()
                            Text(active.title, modifier = Modifier.padding(horizontal = 20.dp))
                            TextButton(onClick = { repository.readDesktop(desktopId, active) }) { Text(stringResource(R.string.refresh_output)) }
                            Text(if (output.target == "$desktopId:${active.window}:${active.id}") output.text else "", fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp))
                            Text(stringResource(R.string.desktop_read_boundary), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                            InputBar("$desktopId:${active.window}:${active.id}", repository, desktop?.allowInput == true && desktop.status == "ready") { command -> repository.sendDesktop(desktopId, active, command) }
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                        item { SectionLabel(R.string.sessions) }
                        items(sessions, key = { it.id }) { session ->
                            ListItem(headlineContent = { Text(session.title) }, supportingContent = { Text(session.status) }, leadingContent = { Text(">_", fontFamily = FontFamily.Monospace) }, trailingContent = { Text(session.source) }, modifier = Modifier.clickable { selected = session.id })
                            HorizontalDivider()
                        }
                        item { SectionLabel(R.string.ssh_hosts) }
                        items(hosts, key = { it.id }) { host ->
                            ListItem(headlineContent = { Text(host.name) }, supportingContent = { Text("${host.user}@${host.address}:${host.port}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }, modifier = Modifier.clickable { login = host })
                            HorizontalDivider()
                        }
                        item { TextButton(onClick = { addHost = true }) { Text(stringResource(R.string.add_ssh)) } }
                        if (desktops.isNotEmpty()) item { SectionLabel(R.string.computers) }
                        items(desktops, key = { it.id }) { desktop ->
                            ListItem(headlineContent = { Text(desktop.host.name) }, supportingContent = { Text(desktop.status) }, trailingContent = { Text("PC") }, modifier = Modifier.clickable { desktopId = desktop.id; pane = null })
                        }
                        item { TextButton(onClick = { selected = repository.local() }) { Text(stringResource(R.string.local_terminal)) } }
                    }
                }
            }
        }
        if (addHost) HostForm(onCancel = { addHost = false }) { repository.saveHost(it); addHost = false }
        login?.let { host -> LoginForm(host, onCancel = { login = null }) { password, desktop, input ->
            if (desktop) { desktopId = repository.connectDesktop(host, password, input); pane = null; selected = "" }
            else { selected = repository.ssh(host, password); desktopId = "" }
            login = null
        } }
        trust?.let { request -> AlertDialog(onDismissRequest = { repository.answerTrust(false) }, title = { Text(stringResource(R.string.verify_host)) }, text = { Text("${request.host.address}\n\n${request.fingerprint}\n\n${stringResource(R.string.verify_hint)}") }, confirmButton = { TextButton(onClick = { repository.answerTrust(true) }) { Text(stringResource(R.string.trust_connect)) } }, dismissButton = { TextButton(onClick = { repository.answerTrust(false) }) { Text(stringResource(R.string.cancel)) } }) }
        error?.let { AlertDialog(onDismissRequest = { repository.error.value = null }, title = { Text(stringResource(R.string.operation_failed)) }, text = { Text(it) }, confirmButton = { TextButton(onClick = { repository.error.value = null }) { Text(stringResource(R.string.close)) } }) }
    }

    @Composable
    private fun Settings(repository: SessionRepository) {
        var background by rememberSaveable { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(stringResource(R.string.background_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.keep_background), modifier = Modifier.weight(1f))
                Switch(checked = background, onCheckedChange = { enabled ->
                    background = enabled
                    if (enabled) startForegroundService(Intent(this@MainActivity, SessionService::class.java))
                    else stopService(Intent(this@MainActivity, SessionService::class.java))
                })
            }
            Text(stringResource(R.string.background_boundary), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.enable_notifications)) }
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text(stringResource(R.string.desktop_setup), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.desktop_setup_hint), modifier = Modifier.padding(vertical = 12.dp))
            Text("pebrel mobile-bridge", fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.native_preview_boundary), modifier = Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun SectionLabel(id: Int) { Text(stringResource(id), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 28.dp, bottom = 12.dp)) }

@Composable
private fun InputBar(id: String, repository: SessionRepository, enabled: Boolean, send: suspend (String) -> Boolean) {
    val drafts by repository.drafts.collectAsStateWithLifecycle()
    val draft = drafts[id] ?: ""
    val scope = rememberCoroutineScope()
    var sending by remember(id) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        val suggestions = listOf("git status", "git diff", "git log --oneline -5", "pwd", "ls").filter { draft.isNotBlank() && it.startsWith(draft) && it != draft }
        Row(Modifier.horizontalScroll(rememberScrollState())) { suggestions.forEach { value -> TextButton(onClick = { repository.setDraft(id, value) }) { Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { if (it.length <= 8192) repository.setDraft(id, it) }, placeholder = { Text(stringResource(R.string.local_edit)) }, maxLines = 4, modifier = Modifier.weight(1f))
            TextButton(enabled = enabled && !sending && draft.isNotBlank(), onClick = {
                val submitted = draft
                sending = true
                scope.launch {
                    try {
                        if (send(submitted)) repository.acknowledgeDraft(id, submitted)
                        else repository.error.value = "input_rejected"
                    } finally { sending = false }
                }
            }) { Text(stringResource(R.string.send)) }
        }
    }
}

@Composable
private fun HostForm(onCancel: () -> Unit, onSave: (HostProfile) -> Unit) {
    var name by remember { mutableStateOf("") }; var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }; var port by remember { mutableStateOf("22") }
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.add_ssh)) }, text = {
        Column { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.host_name)) })
            OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.host_address)) })
            OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.username)) })
            OutlinedTextField(port, { port = it }, label = { Text(stringResource(R.string.port)) }) }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && address.isNotBlank() && !address.any { it.isWhitespace() } && user.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535, onClick = { onSave(HostProfile(UUID.randomUUID().toString(), name.trim(), address.trim(), port.toInt(), user.trim())) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun LoginForm(host: HostProfile, onCancel: () -> Unit, onConnect: (CharArray, Boolean, Boolean) -> Unit) {
    var password by remember { mutableStateOf("") }; var desktop by remember { mutableStateOf(false) }; var input by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(host.name) }, text = {
        Column { OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(desktop, { desktop = it }); Text(stringResource(R.string.connect_pebrel)) }
            if (desktop) { Text(stringResource(R.string.desktop_setup_hint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(input, { input = it }); Text(stringResource(R.string.allow_input)) } }
        }
    }, confirmButton = { TextButton(onClick = { val secret = password.toCharArray(); password = ""; onConnect(secret, desktop, input) }) { Text(stringResource(R.string.connect)) } }, dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}
