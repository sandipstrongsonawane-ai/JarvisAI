package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.diagnostics.CrashHandler
import com.jarvis.assistant.ui.PuppyOverlay

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        // Only displays if the user has already granted overlay access.
        PuppyOverlay.show(this)
    }
}
