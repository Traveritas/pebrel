package io.github.kuddev.pebrel.mobile

import android.app.Application
import io.github.kuddev.pebrel.mobile.session.SessionRepository

class PebrelApplication : Application() {
    val sessions by lazy { SessionRepository(this) }
}
