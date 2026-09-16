package io.github.kuddev.pebrel.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun PebrelTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { JSONObject(context.assets.open("themes.json").bufferedReader().use { it.readText() }) }
    val dark = isSystemInDarkTheme()
    val palette = catalog.getJSONObject(if (dark) "Nord" else "Paper")
    fun color(key: String): Color {
        val value = palette.getJSONArray(key)
        return Color(value.getInt(0), value.getInt(1), value.getInt(2))
    }
    val scheme = if (dark) darkColorScheme(primary = color("accent"), background = color("background"), surface = color("shell"), onBackground = color("foreground"), onSurface = color("foreground"))
        else lightColorScheme(primary = color("accent"), background = color("background"), surface = color("shell"), onBackground = color("foreground"), onSurface = color("foreground"))
    MaterialTheme(colorScheme = scheme, shapes = Shapes(small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(5.dp), large = RoundedCornerShape(6.dp)), content = content)
}
