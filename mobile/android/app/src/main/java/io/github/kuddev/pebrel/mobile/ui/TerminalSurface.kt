package io.github.kuddev.pebrel.mobile.ui

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.kuddev.pebrel.mobile.session.LocalSession
import io.github.kuddev.pebrel.mobile.session.SessionRepository

@Composable
fun TerminalSurface(session: LocalSession, repository: SessionRepository, modifier: Modifier) {
    DisposableEffect(session.id) { onDispose { repository.attachRenderer(null, null) } }
    AndroidView(modifier = modifier, factory = { context ->
        TerminalView(context, null).apply {
            setTextSize((14 * resources.displayMetrics.scaledDensity).toInt())
            setTypeface(runCatching { Typeface.createFromAsset(context.assets, "terminal.ttf") }.getOrDefault(Typeface.MONOSPACE))
            setTerminalViewClient(object : TerminalViewClient {
                override fun onScale(scale: Float): Float = scale
                override fun onSingleTapUp(event: MotionEvent) { requestFocus(); context.getSystemService(InputMethodManager::class.java).showSoftInput(this@apply, 0) }
                override fun shouldBackButtonBeMappedToEscape() = false
                override fun shouldEnforceCharBasedInput() = false
                override fun shouldUseCtrlSpaceWorkaround() = false
                override fun isTerminalViewSelected() = true
                override fun copyModeChanged(copyMode: Boolean) {}
                override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession) = false
                override fun onKeyUp(keyCode: Int, event: KeyEvent) = false
                override fun onLongPress(event: MotionEvent) = false
                override fun readControlKey() = false
                override fun readAltKey() = false
                override fun readShiftKey() = false
                override fun readFnKey() = false
                override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
                override fun onEmulatorSet() {}
                override fun logError(tag: String, message: String) {}
                override fun logWarn(tag: String, message: String) {}
                override fun logInfo(tag: String, message: String) {}
                override fun logDebug(tag: String, message: String) {}
                override fun logVerbose(tag: String, message: String) {}
                override fun logStackTraceWithMessage(tag: String, message: String, exception: Exception) {}
                override fun logStackTrace(tag: String, exception: Exception) {}
            })
        }
    }, update = { view ->
        if (view.currentSession !== session.terminal) view.attachSession(session.terminal)
        repository.attachRenderer(session.id) { view.onScreenUpdated() }
    })
}
