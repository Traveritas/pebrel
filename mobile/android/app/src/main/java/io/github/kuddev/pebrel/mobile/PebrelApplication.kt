package io.github.kuddev.pebrel.mobile

import android.app.Application
import io.github.kuddev.pebrel.mobile.session.SessionRepository

class PebrelApplication : Application() {
    val sessions by lazy { SessionRepository(this) }
    val terminalTypeface by lazy {
        runCatching { android.graphics.Typeface.createFromAsset(assets, "terminal.ttf") }
            .getOrDefault(android.graphics.Typeface.MONOSPACE)
    }
    val themes by lazy {
        org.json.JSONObject(assets.open("themes.json").bufferedReader().use { it.readText() })
    }
}
