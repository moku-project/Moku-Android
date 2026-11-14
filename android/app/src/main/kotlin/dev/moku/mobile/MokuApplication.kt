package dev.moku.mobile

import android.app.Application
import dev.moku.mobile.core.CrashReports

class MokuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything else, so crashes in background downloads are caught too, not just the UI's.
        CrashReports.install(this)
    }
}
