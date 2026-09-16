package io.github.kuddev.pebrel.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalColors
import io.github.kuddev.pebrel.mobile.PebrelApplication
import io.github.kuddev.pebrel.mobile.R
import io.github.kuddev.pebrel.mobile.session.SessionRepository
import org.json.JSONObject
import java.util.Properties

private fun JSONObject.color(key: String): Color {
    val value = getJSONArray(key)
    return Color(value.getInt(0), value.getInt(1), value.getInt(2), if (value.length() == 4) value.getInt(3) else 255)
}

@Composable
fun PebrelTheme(content: @Composable () -> Unit) {
    val application = LocalContext.current.applicationContext as PebrelApplication
    val catalog = application.themes
    val choice by application.sessions.theme.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val name = if (catalog.has(choice)) choice else if (systemDark) "Nord" else "Paper"
    val palette = remember(name) { catalog.getJSONObject(name) }
    val background = palette.color("background")
    val dark = background.luminance() < 0.5f
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = palette.color("accent"), onPrimary = background,
        background = background, surface = palette.color("shell"),
        onBackground = palette.color("foreground"), onSurface = palette.color("foreground"),
        onSurfaceVariant = palette.color("muted"), outline = palette.color("frame"),
        outlineVariant = palette.color("line"), secondaryContainer = palette.color("selected"),
        onSecondaryContainer = palette.color("foreground"), error = palette.color("red"),
        surfaceContainer = background, surfaceContainerLow = background,
        surfaceContainerHigh = palette.color("shell"), surfaceTint = Color.Transparent,
    )
    LaunchedEffect(name) {
        val properties = Properties()
        fun hex(role: String): String {
            val rgb = palette.getJSONArray(role)
            return "#%02x%02x%02x".format(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2))
        }
        properties["background"] = hex("background")
        properties["foreground"] = hex("foreground")
        properties["cursor"] = hex("accent")
        val roles = listOf("background", "red", "green", "yellow", "blue", "purple", "cyan", "foreground")
        roles.forEachIndexed { index, role -> properties["color$index"] = hex(role) }
        TerminalColors.COLOR_SCHEME.updateWith(properties)
        application.sessions.sessions.value.forEach { session -> session.terminal.emulator?.mColors?.reset() }
    }
    MaterialTheme(colorScheme = scheme, shapes = Shapes(
        extraSmall = RoundedCornerShape(3.dp), small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(5.dp), large = RoundedCornerShape(6.dp),
        extraLarge = RoundedCornerShape(8.dp),
    ), content = content)
}

@Composable
fun ThemePicker(repository: SessionRepository) {
    val application = LocalContext.current.applicationContext as PebrelApplication
    val choice by repository.theme.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium)
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(if (choice == "system") stringResource(R.string.follow_system) else choice)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            (listOf("system") + application.themes.keys().asSequence().toList()).forEach { name ->
                DropdownMenuItem(text = { Text(if (name == "system") stringResource(R.string.follow_system) else name) },
                    onClick = { repository.selectTheme(name); expanded = false })
            }
        }
    }
}
