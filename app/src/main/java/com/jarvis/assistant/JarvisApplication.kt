package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.diagnostics.CrashHandler

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        // Overlay is intentionally not attached during process startup.
        // MainActivity starts it after the activity has entered the foreground.
    }
}
