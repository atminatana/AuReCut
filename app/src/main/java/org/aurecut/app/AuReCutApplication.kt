package org.aurecut.app

import android.app.Application
import org.aurecut.app.audio.WorkDir

class AuReCutApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A fresh process has no recording or editing session, so any cached work files are leftovers.
        WorkDir.clear(this)
    }
}
